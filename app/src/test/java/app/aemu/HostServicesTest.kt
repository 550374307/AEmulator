package app.aemu

import app.aemu.core.BridgeProto
import app.aemu.core.HostServices
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The capability whitelist, exercised through the real dispatch path.
 *
 * This is the security boundary that replaces VMOS' reflected system-service
 * surface, so the assertions are about what guest code *cannot* reach as much as
 * about what it can: a service that exists but was not granted must answer EPERM,
 * and there must be no way to address a handler by name that is not in the table.
 */
class HostServicesTest {

    private val log = ArrayList<String>()

    /** A stand-in for GuestVm's bridge to PropService / power / the clipboard. */
    private class FakeHost : HostServices.Host {
        val props = linkedMapOf("ro.aemu.test" to "yes", "ro.build.version.sdk" to "28")
        val revoke = HashSet<String>()          // keys whose write must be refused
        var clip: String? = null
        val powerCalls = ArrayList<Int>()

        override fun propGet(key: String): String? = props[key]

        override fun propSet(key: String, value: String): Boolean {
            if (key in revoke || key.startsWith("ro.")) return false
            props[key] = value
            return true
        }

        override fun propList(): List<Pair<String, String>> = props.map { it.key to it.value }

        override fun power(op: Int): Boolean {
            powerCalls += op
            return powerCalls.size == 1          // "false" = the guest already asked
        }

        override fun clipGet(): String? = clip

        override fun clipSet(text: String): Boolean {
            clip = text
            return true
        }
    }

    private lateinit var host: FakeHost
    private lateinit var svc: HostServices

    private fun newService(vararg caps: String): HostServices {
        val s = HostServices { log += it }
        host = FakeHost()
        s.builtins(host)
        s.grant(caps.toList())
        return s
    }

    @Before
    fun setUp() {
        svc = newService(HostServices.PROPS, HostServices.POWER, HostServices.CLIPBOARD, HostServices.LMKD)
    }

    private fun get(key: String) =
        svc.typed(HostServices.PROPS, BridgeProto.PROP_GET, "$key\u0000".toByteArray(Charsets.UTF_8))

    // ---- the whitelist itself -------------------------------------------

    @Test
    fun defaultCapsAreTheFourTheDesignPromised() {
        assertEquals(
            linkedSetOf(HostServices.PROPS, HostServices.POWER, HostServices.CLIPBOARD, HostServices.LMKD),
            HostServices.DEFAULT_CAPS,
        )
        // telephony/storage/adb exist as names but are off by default: the bridge is not
        // an excuse to hand a guest a modem or the user's files on first boot
        assertFalse(HostServices.TELEPHONY in HostServices.DEFAULT_CAPS)
        assertFalse(HostServices.STORAGE in HostServices.DEFAULT_CAPS)
        assertFalse(HostServices.ADB in HostServices.DEFAULT_CAPS)
    }

    @Test
    fun aServiceThatExistsButWasNotGrantedAnswersEperm() {
        val s = newService(HostServices.PROPS)          // clipboard/power not granted
        try {
            s.typed(HostServices.CLIPBOARD, BridgeProto.CLIP_GET, ByteArray(0))
            throw AssertionError("expected Denied")
        } catch (e: HostServices.Denied) {
            assertTrue(e.message!!.contains("clipboard"))
        }
    }

    @Test
    fun denyingIsNotTheSameAsNotExisting() {
        // an unknown name is ENOENT and must not throw: the guest learns "there is no such
        // service", not "you are not allowed" — those are different answers for a caller
        val body = BridgeProto.hostReq("telephony", 0)
        assertArrayEquals(BridgeProto.err(BridgeProto.ENOENT), svc.host(body))
    }

    @Test
    fun grantingAnUnknownCapabilityIsIgnoredRatherThanTrusted() {
        val s = newService()
        s.grant(listOf("props", "definitely-not-a-service"))
        assertEquals(listOf("props"), s.grantedNames())
        assertTrue(log.any { it.contains("no such service") })
    }

    @Test
    fun typedAndNamedEntryPointsShareOneCheck() {
        // swapping the type byte for AB_SVC_HOST must not be a way around the cap
        val s = newService()                             // nothing granted
        try {
            s.typed(HostServices.PROPS, BridgeProto.PROP_GET, "ro.aemu.test\u0000".toByteArray())
            throw AssertionError("typed path skipped the check")
        } catch (e: HostServices.Denied) {
            // expected
        }
        try {
            s.host(BridgeProto.hostReq("props", BridgeProto.PROP_GET, "ro.aemu.test\u0000".toByteArray()))
            throw AssertionError("named path skipped the check")
        } catch (e: HostServices.Denied) {
            // expected
        }
    }

    // ---- PROPS ----------------------------------------------------------

    @Test
    fun propGetReturnsTheValue() {
        val rep = get("ro.aemu.test")
        assertEquals(BridgeProto.OK, rep[0].toInt())
        assertEquals("yes", String(rep, 1, rep.size - 1))
    }

    @Test
    fun propGetMissIsEnoent() {
        assertArrayEquals(BridgeProto.err(BridgeProto.ENOENT), get("ro.nope"))
    }

    @Test
    fun propGetWithAMalformedBodyIsEinval() {
        // no NUL terminator: the body was cut short, so do not guess at a key
        assertArrayEquals(
            BridgeProto.err(BridgeProto.EINVAL),
            svc.typed(HostServices.PROPS, BridgeProto.PROP_GET, "ro.aemu.test".toByteArray()),
        )
    }

    @Test
    fun propSetWritesWhenTheHostAllowsIt() {
        val rep = svc.typed(
            HostServices.PROPS, BridgeProto.PROP_SET,
            "persist.aemu.x\u00007\u0000".toByteArray(Charsets.UTF_8),
        )
        assertArrayEquals(BridgeProto.err(BridgeProto.OK), rep)
        assertEquals("7", host.props["persist.aemu.x"])
    }

    @Test
    fun propSetRefusalIsEpermWhicheverWayItWasRefused() {
        // `ro.*` is read-only in PropService, and an area that is not up yet also says no;
        // the guest has nothing useful to do with the difference, so both are EPERM
        val ro = svc.typed(HostServices.PROPS, BridgeProto.PROP_SET, "ro.aemu.test\u0000nope\u0000".toByteArray())
        assertArrayEquals(BridgeProto.err(BridgeProto.EPERM), ro)
    }

    @Test
    fun propSetWithAnEmptyKeyIsRejected() {
        assertArrayEquals(
            BridgeProto.err(BridgeProto.EINVAL),
            svc.typed(HostServices.PROPS, BridgeProto.PROP_SET, "\u0000v\u0000".toByteArray()),
        )
    }

    @Test
    fun propListIsNulSeparatedPairs() {
        val rep = svc.typed(HostServices.PROPS, BridgeProto.PROP_LIST, ByteArray(0))
        assertEquals(BridgeProto.OK, rep[0].toInt())
        val text = String(rep, 1, rep.size - 1)
        val fields = text.split('\u0000')
        assertTrue(fields.contains("ro.aemu.test"))
        assertTrue(fields.contains("yes"))
        assertTrue(fields.contains("ro.build.version.sdk"))
    }

    @Test
    fun propListRefusesToExceedOneDatagram() {
        val big = HostServices { log += it }
        val h = object : HostServices.Host {
            override fun propGet(key: String) = null
            override fun propSet(key: String, value: String) = false
            override fun propList(): List<Pair<String, String>> =
                List(4000) { "prop.number.$it" to "v".repeat(40) }
            override fun power(op: Int) = true
            override fun clipGet(): String? = null
            override fun clipSet(text: String) = true
        }
        big.builtins(h)
        big.grant(listOf(HostServices.PROPS))
        // truncating would hand the guest a half-written pair, so say "too big" instead
        assertArrayEquals(
            BridgeProto.err(BridgeProto.ETOOBIG),
            big.typed(HostServices.PROPS, BridgeProto.PROP_LIST, ByteArray(0)),
        )
    }

    @Test
    fun unknownPropOpIsEnoent() {
        assertArrayEquals(
            BridgeProto.err(BridgeProto.ENOENT),
            svc.typed(HostServices.PROPS, 99, "x\u0000".toByteArray()),
        )
    }

    // ---- CLIPBOARD ------------------------------------------------------

    @Test
    fun clipboardRoundTrips() {
        assertArrayEquals(BridgeProto.err(BridgeProto.OK), svc.typed(HostServices.CLIPBOARD, BridgeProto.CLIP_SET, "hi".toByteArray()))
        assertEquals("hi", host.clip)
        val rep = svc.typed(HostServices.CLIPBOARD, BridgeProto.CLIP_GET, ByteArray(0))
        assertEquals(BridgeProto.OK, rep[0].toInt())
        assertEquals("hi", String(rep, 1, rep.size - 1))
    }

    @Test
    fun clipboardGetOnEmptyClipboardIsEnoent() {
        assertArrayEquals(BridgeProto.err(BridgeProto.ENOENT), svc.typed(HostServices.CLIPBOARD, BridgeProto.CLIP_GET, ByteArray(0)))
    }

    @Test
    fun clipboardSetRefusesAPasteBomb() {
        // over CLIP_MAX is a paste bomb, not a paste
        val huge = ByteArray(HostServices.CLIP_MAX + 1) { 'a'.code.toByte() }
        assertArrayEquals(BridgeProto.err(BridgeProto.ETOOBIG), svc.typed(HostServices.CLIPBOARD, BridgeProto.CLIP_SET, huge))
        assertEquals(null, host.clip)
    }

    @Test
    fun clipboardWatchIsHonestRatherThanSilent() {
        // there is no push channel yet; saying ENOENT is better than accepting a watch
        // that would never fire and leaving the guest waiting
        assertArrayEquals(BridgeProto.err(BridgeProto.ENOENT), svc.typed(HostServices.CLIPBOARD, BridgeProto.CLIP_WATCH, ByteArray(0)))
    }

    // ---- POWER ----------------------------------------------------------

    @Test
    fun everyPowerOpReachesTheHostAndStillAnswersOk() {
        for (op in listOf(BridgeProto.POWER_REBOOT, BridgeProto.POWER_SHUTDOWN, BridgeProto.POWER_WAKE)) {
            assertArrayEquals(BridgeProto.err(BridgeProto.OK), svc.typed(HostServices.POWER, op, ByteArray(0)))
        }
        assertEquals(listOf(BridgeProto.POWER_REBOOT, BridgeProto.POWER_SHUTDOWN, BridgeProto.POWER_WAKE), host.powerCalls)
    }

    @Test
    fun asecondPowerRequestIsStillOk() {
        // the host says "already asked" by returning false; a guest that reboots twice
        // during shutdown is not making an error, so it must not see one
        svc.typed(HostServices.POWER, BridgeProto.POWER_REBOOT, ByteArray(0))
        assertArrayEquals(BridgeProto.err(BridgeProto.OK), svc.typed(HostServices.POWER, BridgeProto.POWER_REBOOT, ByteArray(0)))
    }

    @Test
    fun unknownPowerOpIsEnoent() {
        assertArrayEquals(BridgeProto.err(BridgeProto.ENOENT), svc.typed(HostServices.POWER, 42, ByteArray(0)))
        assertTrue(host.powerCalls.isEmpty())
    }

    // ---- the envelope ---------------------------------------------------

    @Test
    fun hostEnvelopeReachesTheNamedService() {
        val rep = svc.host(BridgeProto.hostReq("props", BridgeProto.PROP_GET, "ro.aemu.test\u0000".toByteArray()))
        assertEquals(BridgeProto.OK, rep[0].toInt())
        assertEquals("yes", String(rep, 1, rep.size - 1))
    }

    @Test
    fun aThrowingServiceBecomesEioInsteadOfCrashingTheBridge() {
        val s = HostServices { log += it }
        s.register(object : HostServices.Service {
            override val name = "boom"
            override fun call(op: Int, body: ByteArray): ByteArray = error("handler exploded")
        })
        s.grant(listOf("boom"))
        // one broken handler must not take the socket down with it
        assertArrayEquals(BridgeProto.err(BridgeProto.EIO), s.host(BridgeProto.hostReq("boom", 0)))
        assertTrue(log.any { it.contains("boom/0 failed") })
    }

    @Test
    fun malformedEnvelopeIsEinval() {
        assertArrayEquals(BridgeProto.err(BridgeProto.EINVAL), svc.host(ByteArray(0)))
        assertArrayEquals(BridgeProto.err(BridgeProto.EINVAL), svc.host(byteArrayOf(0, 0, 0)))
    }
}
