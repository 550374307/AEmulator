package app.aemu

import app.aemu.core.EndpointRegistry
import app.aemu.core.EndpointRegistry.Endpoint
import app.aemu.core.EndpointRegistry.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle and ordering of the host endpoints.
 *
 * `startAll` replaced a dozen hand-written `props.serve()` / `vold.serve()` calls in
 * `GuestVm.doBoot()`, and `stopAll` replaced the matching wall of `stop()`s. The two
 * things that can go wrong there are silent: an endpoint that never starts because the
 * API gate is off by one, and an endpoint that is stopped twice because it was
 * removed from the hand-written list but the registry still knows about it. Both are
 * cheaper to assert here than in a boot log.
 */
class EndpointRegistryTest {

    private val log = ArrayList<String>()
    private fun reg() = EndpointRegistry { log += it }

    /** An endpoint that records what happened to it. */
    private class Probe(
        val name: String,
        val fromApi: Int = 0,
        val protocol: Protocol = Protocol.LEGACY,
        val seq: Boolean = false,
        val starts: Boolean = true,
        val throws: Boolean = false,
        var clients: Int = 0,
    ) {
        var started = 0
        var stopped = 0
        fun endpoint() = Endpoint(
            name = name,
            path = "/dev/socket/$name",
            protocol = protocol,
            fromApi = fromApi,
            seq = seq,
            start = { started++; if (throws) error("bind failed") else starts },
            stop = { stopped++ },
            clients = { clients },
        )
    }

    @Test
    fun registerIsIdempotentAndKeepsTheFirstDeclaration() {
        val r = reg()
        val a = Probe("rild")
        assertTrue(r.register(a.endpoint()))
        // a second declaration of the same name must not replace the one that is
        // already wired — otherwise the start/stop pair would point at different objects
        assertFalse(r.register(Probe("rild").endpoint()))
        assertEquals(listOf("rild"), r.declared())
    }

    @Test
    fun startAllStartsInDeclarationOrder() {
        val r = reg()
        val order = ArrayList<String>()
        for (n in listOf("property_service", "input", "frame", "rild", "vold")) {
            r.register(Endpoint(n, "/dev/socket/$n", start = { order += n; true }, stop = {}))
        }
        assertEquals(5, r.startAll(28))
        assertEquals(listOf("property_service", "input", "frame", "rild", "vold"), order)
        assertEquals(5, r.up().size)
    }

    @Test
    fun fromApiSkipsOldImagesAndSaysSo() {
        val r = reg()
        val dns = Probe("dnsproxyd", fromApi = 14)
        val bridge = Probe("aemu_bridge", fromApi = 28, protocol = Protocol.BRIDGE, seq = true)
        r.register(dns.endpoint())
        r.register(bridge.endpoint())

        assertEquals(0, r.startAll(10))
        assertEquals(0, dns.started)
        assertEquals(0, bridge.started)
        assertTrue(log.any { it.contains("dnsproxyd skipped") && it.contains("api 10") })

        log.clear()
        assertEquals(0, r.startAll(13))           // one short of dns's gate
        assertEquals(0, dns.started)

        assertEquals(1, r.startAll(14))           // exactly on the gate
        assertEquals(1, dns.started)
        assertEquals(0, bridge.started)

        assertEquals(2, r.startAll(28))
        assertEquals(1, bridge.started)
        assertTrue(log.any { it.contains("aemu_bridge up (BRIDGE,seq)") })
    }

    @Test
    fun startAllIsIdempotent() {
        val r = reg()
        val p = Probe("rild")
        r.register(p.endpoint())
        r.startAll(28)
        r.startAll(28)
        // a second boot pass must not rebind a socket that is already listening
        assertEquals(1, p.started)
    }

    @Test
    fun aRefusedStartIsReportedAndCanBeRetried() {
        val r = reg()
        val p = Probe("vold", starts = false)
        r.register(p.endpoint())
        assertEquals(0, r.startAll(28))
        assertFalse(r.isUp("vold"))
        // "down" covers a bind the instance refused as well as one the kernel did; the
        // endpoint itself logs why, so the registry only has to stay truthful
        assertTrue(log.any { it.contains("vold down (LEGACY)") })

        val q = Probe("vold")
        val r2 = reg()
        r2.register(q.endpoint())
        assertEquals(1, r2.startAll(28))
        assertTrue(r2.isUp("vold"))
    }

    @Test
    fun aThrowingStartPropagatesBecauseThatIsABootFailure() {
        val r = reg()
        r.register(Probe("property_service", throws = true).endpoint())
        var threw = false
        try {
            r.startAll(28)
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue("a stub that cannot bind must fail the boot, not be swallowed", threw)
    }

    @Test
    fun stopAllRunsInReverseAndOnlyForWhatStarted() {
        val r = reg()
        val stopped = ArrayList<String>()
        for (n in listOf("property_service", "input", "frame")) {
            r.register(Endpoint(n, "/dev/socket/$n", start = { true }, stop = { stopped += n }))
        }
        val skipped = Probe("dnsproxyd", fromApi = 30)
        r.register(skipped.endpoint())

        r.startAll(28)
        r.stopAll()

        // the last one started is the most likely to depend on the rest
        assertEquals(listOf("frame", "input", "property_service"), stopped)
        assertEquals(0, skipped.stopped)
        assertEquals(0, r.up().size)
    }

    @Test
    fun stopAllClearsStateSoASecondBootRestarts() {
        val r = reg()
        val p = Probe("rild")
        r.register(p.endpoint())
        r.startAll(28); r.stopAll()
        r.startAll(28)
        assertEquals(2, p.started)
        assertEquals(1, p.stopped)
    }

    @Test
    fun stopAllSurvivesAnEndpointThatThrowsOnStop() {
        val r = reg()
        r.register(Endpoint("bad", "/dev/socket/bad", start = { true }, stop = { error("boom") }))
        r.register(Probe("rild").endpoint())
        r.startAll(28)
        r.stopAll()                                // must not propagate: shutdown is best-effort
        assertEquals(0, r.up().size)
    }

    @Test
    fun statsReportsClientsOnlyForEndpointsThatAreUp() {
        val r = reg()
        val p = Probe("input", clients = 3)
        val off = Probe("aemu_bridge", fromApi = 30, clients = 9)
        r.register(p.endpoint())
        r.register(off.endpoint())
        r.startAll(28)

        val s = r.stats()
        assertEquals(3, s["input"])
        // an endpoint that never started has no meaningful client count, even if its
        // lambda would happily return a stale number
        assertEquals(0, s["aemu_bridge"])
    }

    @Test
    fun bridgeEndpointsAreTheOnlyOnesMarkedSeq() {
        val r = reg()
        r.register(Probe("rild").endpoint())
        r.register(Probe("aemu_bridge", protocol = Protocol.BRIDGE, seq = true).endpoint())
        r.startAll(28)
        // the legacy sockets must stay SOCK_STREAM: their wire format is the firmware's,
        // and changing the socket type would break it just as surely as changing the bytes
        assertTrue(log.any { it.contains("rild up (LEGACY)") })
        assertTrue(log.any { it.contains("aemu_bridge up (BRIDGE,seq)") })
    }

    @Test
    fun resetLetsARebootDeclareTheSameNamesAgain() {
        // VmActivity's retry button is `vm.stop(); vm.boot()` on the same instance, so the
        // second boot declares the same endpoint names. register() keeps the first
        // declaration, and that one closed over the settings of the earlier boot — a stale
        // "rild" would keep last boot's radio flag forever. reset() is what makes the
        // re-declaration take effect.
        val r = reg()
        val first = Probe("rild")
        r.register(first.endpoint())
        r.startAll(28)
        r.reset()

        val second = Probe("rild")
        assertTrue("after reset the name is free again", r.register(second.endpoint()))
        assertEquals("reset stopped what was up", 1, first.stopped)
        assertEquals(0, r.up().size)

        r.startAll(28)
        assertEquals(1, second.started)
        assertEquals("the stale declaration was not started again", 1, first.started)
    }

    @Test
    fun resetStopsBeforeItForgets() {
        // Forgetting first would leave bound sockets behind with no stop lambda left to
        // close them — the exact leak the registry exists to prevent.
        val r = reg()
        val p = Probe("input")
        r.register(p.endpoint())
        r.startAll(28)
        r.reset()
        assertEquals(1, p.stopped)
        assertTrue(r.declared().isEmpty())
    }
}
