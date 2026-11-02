package app.aemu

import app.aemu.core.Bridge
import app.aemu.core.BridgeProto
import app.aemu.core.HostServices
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dispatcher: one datagram in, one framed reply out.
 *
 * `Bridge` is the whole host half of bproto2 minus the socket, so these tests cover
 * what a device would otherwise tell us one boot log at a time — that a reply always
 * echoes the request's `seq` and `type`, that a newer peer is refused instead of
 * misparsed, and that a denied capability comes back as `AB_EPERM` while a broken
 * handler comes back as `AB_EIO`.
 */
class BridgeTest {

    private val log = ArrayList<String>()
    private val lmkCalls = ArrayList<ByteArray>()
    private var lmkAnswer: ByteArray = byteArrayOf(BridgeProto.OK.toByte())
    private lateinit var services: HostServices
    private lateinit var bridge: Bridge

    private class FakeHost : HostServices.Host {
        var clip: String? = "shared"
        val props = linkedMapOf("ro.aemu.test" to "yes")
        override fun propGet(key: String) = props[key]
        override fun propSet(key: String, value: String) = false
        override fun propList(): List<Pair<String, String>> = props.map { it.key to it.value }
        override fun power(op: Int) = true
        override fun clipGet(): String? = clip
        override fun clipSet(text: String): Boolean { clip = text; return true }
    }

    private fun build(vararg caps: String): Bridge {
        services = HostServices { log += it }
        services.builtins(FakeHost())
        services.grant(caps.toList())
        return Bridge("n64-28", services, { 4242 }, { body -> lmkCalls += body; lmkAnswer }, { log += it })
    }

    @Before
    fun setUp() {
        bridge = build(HostServices.PROPS, HostServices.POWER, HostServices.CLIPBOARD, HostServices.LMKD)
    }

    private fun call(type: Int, seq: Int, body: ByteArray = ByteArray(0)): BridgeProto.Msg? {
        val rep = bridge.handle(BridgeProto.frame(type, seq, body)) ?: return null
        return BridgeProto.decode(rep)
    }

    @Test
    fun replyEchoesTypeAndSeq() {
        val rep = call(BridgeProto.SVC_CLIPBOARD, 0xBEEF, byteArrayOf(BridgeProto.CLIP_GET.toByte()))!!
        assertEquals(BridgeProto.SVC_CLIPBOARD, rep.type)
        assertEquals(0xBEEF, rep.seq)
        assertEquals(BridgeProto.OK, rep.body[0].toInt())
        assertEquals("shared", String(rep.body, 1, rep.body.size - 1))
    }

    @Test
    fun helloTellsTheGuestWhoAnswered() {
        val rep = call(BridgeProto.SVC_HELLO, 1)!!
        val hello = BridgeProto.parseHello(rep.body)!!
        assertEquals(BridgeProto.OK, hello.status)
        assertEquals(BridgeProto.VER, hello.ver)
        assertEquals(BridgeProto.MAX.coerceAtMost(0xffff), hello.maxBody)
        assertEquals(4242, hello.pid)
        assertEquals("n64-28", hello.name)
    }

    @Test
    fun aForeignDatagramIsDroppedRatherThanAnswered() {
        // nothing to reply to: answering would be inventing a peer
        val junk = ByteArray(16) { 0x5a }
        assertNull(bridge.handle(junk))
        assertNull(bridge.handle(ByteArray(0)))
    }

    @Test
    fun aNewerPeerIsRefusedInsteadOfMisparsed() {
        val f = BridgeProto.frame(BridgeProto.SVC_PROPS, 7, byteArrayOf(BridgeProto.PROP_LIST.toByte()))
        f[4] = (BridgeProto.VER + 1).toByte()          // ver byte
        val rep = BridgeProto.decode(bridge.handle(f)!!)!!
        assertEquals(7, rep.seq)
        assertEquals(BridgeProto.EINVAL, rep.body[0].toInt())
        assertTrue(log.any { it.contains("refusing") })
    }

    @Test
    fun anUnknownServiceIsEnoent() {
        val rep = call(0x7f, 3)!!
        assertEquals(0x7f, rep.type)
        assertEquals(BridgeProto.ENOENT, rep.body[0].toInt())
    }

    @Test
    fun aDeniedServiceIsEpermNotEnoent() {
        val b = build(HostServices.PROPS)               // clipboard not granted
        val f = BridgeProto.frame(BridgeProto.SVC_CLIPBOARD, 1, byteArrayOf(BridgeProto.CLIP_GET.toByte()))
        val rep = BridgeProto.decode(b.handle(f)!!)!!
        // the service exists, the instance just did not grant it: that distinction is
        // exactly what makes an audit of `image.json` readable
        assertEquals(BridgeProto.EPERM, rep.body[0].toInt())
        assertTrue(log.any { it.contains("not granted") })
    }

    @Test
    fun lmkdIsGatedLikeEveryOtherService() {
        val b = build(HostServices.PROPS)               // lmkd not granted
        val req = lmkPrio()                             // {u8 op, i32 pid, i32 uid, i32 adj}
        val rep = BridgeProto.decode(b.handle(BridgeProto.frame(BridgeProto.SVC_LMKD, 1, req))!!)!!
        assertEquals(BridgeProto.EPERM, rep.body[0].toInt())
        assertTrue(lmkCalls.isEmpty())

        // and when it is granted, the body reaches GuestLmk untouched
        val rep2 = call(BridgeProto.SVC_LMKD, 2, req)!!
        assertEquals(BridgeProto.OK, rep2.body[0].toInt())
        assertEquals(req.size, lmkCalls.single().size)
        assertEquals(BridgeProto.LMK_PRIO, BridgeProto.op(lmkCalls.single()))
    }

    /** A well-formed LMKD PRIO body: op byte first, then the three little-endian ints. */
    private fun lmkPrio(pid: Int = 1234, uid: Int = 10000, adj: Int = 9): ByteArray =
        java.nio.ByteBuffer.allocate(13).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(BridgeProto.LMK_PRIO.toByte()).putInt(pid).putInt(uid).putInt(adj).array()

    @Test
    fun typedServicesReachTheirHandlers() {
        // a typed request carries its op in the FIRST byte of the body, then the payload
        val get = call(BridgeProto.SVC_PROPS, 5, byteArrayOf(BridgeProto.PROP_GET.toByte()) + "ro.aemu.test\u0000".toByteArray())!!
        assertEquals(BridgeProto.OK, get.body[0].toInt())
        assertEquals("yes", String(get.body, 1, get.body.size - 1))

        val pwr = call(BridgeProto.SVC_POWER, 6, byteArrayOf(BridgeProto.POWER_REBOOT.toByte()))!!
        assertEquals(BridgeProto.OK, pwr.body[0].toInt())
    }

    @Test
    fun hostEnvelopeReachesTheDispatcher() {
        val env = BridgeProto.hostReq("props", BridgeProto.PROP_LIST)
        val rep = call(BridgeProto.SVC_HOST, 8, env)!!
        assertEquals(BridgeProto.SVC_HOST, rep.type)
        assertEquals(BridgeProto.OK, rep.body[0].toInt())
    }

    @Test
    fun aBrokenHandlerBecomesEioAndTheSocketSurvives() {
        services.register(object : HostServices.Service {
            override val name = "boom"
            override fun call(op: Int, body: ByteArray): ByteArray = error("exploded")
        })
        services.grant(listOf("boom"))
        val rep = call(BridgeProto.SVC_HOST, 9, BridgeProto.hostReq("boom", 0))!!
        assertEquals(BridgeProto.EIO, rep.body[0].toInt())

        // the next message on the same bridge must still work — one bad handler is not
        // a reason to tear the channel down
        val after = call(BridgeProto.SVC_PROPS, 10, byteArrayOf(BridgeProto.PROP_GET.toByte()) + "ro.aemu.test\u0000".toByteArray())!!
        assertEquals(BridgeProto.OK, after.body[0].toInt())
    }

    @Test
    fun describeShowsDeclaredVersusGranted() {
        val d = bridge.describe()
        assertTrue(d.contains("declared="))
        assertTrue(d.contains("granted="))
        assertTrue(d.contains(HostServices.PROPS))
    }

    @Test
    fun handlePrefersTheFirstMessageInABufferOfSeveral() {
        // Os.read on a SEQPACKET socket returns exactly one datagram, so n is normally
        // the whole buffer; passing a larger buffer must not make the tail look like a body
        val one = BridgeProto.frame(BridgeProto.SVC_POWER, 11, byteArrayOf(BridgeProto.POWER_WAKE.toByte()))
        val buf = one + ByteArray(64) { 0x11 }
        val rep = BridgeProto.decode(bridge.handle(buf, one.size)!!)!!
        // the reply to POWER is a bare status byte, not an echo of the op, so the proof that
        // only the first message was consumed is that the reply is answered at all and says OK
        assertEquals(BridgeProto.SVC_POWER, rep.type)
        assertEquals(11, rep.seq)
        assertEquals(BridgeProto.OK, rep.body[0].toInt())
        assertEquals(1, rep.body.size)
    }
}
