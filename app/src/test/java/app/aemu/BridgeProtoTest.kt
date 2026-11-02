package app.aemu

import app.aemu.core.BridgeProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bproto2 framing, byte for byte.
 *
 * This is the one part of the bridge that a device cannot help with: a wrong length
 * field looks exactly like a wedged guest, and a swapped header field looks like a
 * service that silently never answers. Keeping the codec free of `android.*` is what
 * makes these assertions possible on a plain JVM (see `native/guestshim/ab_host_test.c`
 * for the other half — the same wire format driven through real sockets).
 */
class BridgeProtoTest {

    @Test
    fun frameWritesTheHeaderLayoutTheGuestExpects() {
        val f = BridgeProto.frame(BridgeProto.SVC_PROPS, 0x1234, byteArrayOf(2, 7, 9))
        assertEquals(BridgeProto.HDR + 3, f.size)

        // magic is a u32 on the wire, not a string: `AB_MAGIC == 0x41454252` is
        // 'A'<<24 | 'E'<<16 | 'B'<<8 | 'R', so little-endian bytes read R, B, E, A.
        // Both ends compare it as a u32, so what matters is that the four bytes are
        // exactly this order — a "nicer" spelling here would fail every guest.
        assertEquals(0x52.toByte(), f[0])          // 'R'
        assertEquals(0x42.toByte(), f[1])          // 'B'
        assertEquals(0x45.toByte(), f[2])          // 'E'
        assertEquals(0x41.toByte(), f[3])          // 'A'
        assertEquals(BridgeProto.VER.toByte(), f[4])
        assertEquals(BridgeProto.SVC_PROPS.toByte(), f[5])
        assertEquals(0x34.toByte(), f[6])          // seq low
        assertEquals(0x12.toByte(), f[7])          // seq high
        assertEquals(3, f[8].toInt())              // len low
        assertEquals(0, f[9].toInt())
        assertEquals(0, f[10].toInt())
        assertEquals(0, f[11].toInt())
        assertArrayEquals(byteArrayOf(2, 7, 9), f.copyOfRange(BridgeProto.HDR, f.size))
    }

    @Test
    fun decodeRoundTripsFrame() {
        val body = ByteArray(64) { it.toByte() }
        val msg = BridgeProto.decode(BridgeProto.frame(BridgeProto.SVC_HOST, 0xBEEF, body))!!
        assertEquals(BridgeProto.VER, msg.ver)
        assertEquals(BridgeProto.SVC_HOST, msg.type)
        assertEquals(0xBEEF, msg.seq)              // and 0xffff masking, not sign extension
        assertArrayEquals(body, msg.body)
    }

    @Test
    fun decodeRejectsForeignAndTruncatedDatagrams() {
        // a foreign writer on the same socket: drop it, do not answer it
        val alien = BridgeProto.frame(BridgeProto.SVC_PROPS, 1, ByteArray(0))
        alien[0] = 'X'.code.toByte()
        assertNull(BridgeProto.decode(alien))

        // shorter than a header
        assertNull(BridgeProto.decode(ByteArray(BridgeProto.HDR - 1)))

        // len claims more than arrived
        val lying = BridgeProto.frame(BridgeProto.SVC_PROPS, 1, ByteArray(4))
        lying[8] = 40
        assertNull(BridgeProto.decode(lying))

        // len negative (0xff.. as a signed int)
        val negative = BridgeProto.frame(BridgeProto.SVC_PROPS, 1, ByteArray(0))
        negative[8] = 0xff.toByte(); negative[9] = 0xff.toByte()
        negative[10] = 0xff.toByte(); negative[11] = 0xff.toByte()
        assertNull(BridgeProto.decode(negative))
    }

    @Test
    fun decodeHonoursOffsetAndLength() {
        val inner = BridgeProto.frame(BridgeProto.SVC_POWER, 5, byteArrayOf(1))
        val padded = ByteArray(7) + inner + ByteArray(3)
        val msg = BridgeProto.decode(padded, 7, inner.size)!!
        assertEquals(BridgeProto.SVC_POWER, msg.type)
        assertEquals(5, msg.seq)
    }

    @Test
    fun helloReplyIsParseableByTheGuest() {
        val rep = BridgeProto.hello(4242, "n64-28")
        val h = BridgeProto.parseHello(rep)!!
        assertEquals(BridgeProto.OK, h.status)
        assertEquals(BridgeProto.VER, h.ver)
        assertEquals(BridgeProto.MAX.coerceAtMost(0xffff), h.maxBody)
        assertEquals(4242, h.pid)
        assertEquals("n64-28", h.name)
    }

    @Test
    fun helloTruncatesALongTagInsteadOfOverrunning() {
        val rep = BridgeProto.hello(1, "x".repeat(200))
        // a fixed-size record, not a variable-length one: the C side memcpys a struct
        assertEquals(BridgeProto.HELLO_SIZE, rep.size)
        assertEquals(76, rep.size)                 // sizeof(struct ab_hello_rep), padding included
        val h = BridgeProto.parseHello(rep)!!
        assertEquals(63, h.name.length)            // 63 name bytes + the terminator
        assertEquals(0.toByte(), rep[rep.size - 1]) // last byte stays NUL for strlen
    }

    @Test
    fun helloWritesTheStructLayoutNotTheFieldSizes() {
        // The regression this guards: `pid` is 4-byte aligned, so C puts it at offset 8 and
        // `name` at 12. A sequential putInt chain would land them at 6 and 10, and every C
        // reader (`ab_hello()` reads ver at 2..3, the mock server reads pid) would drift.
        val rep = BridgeProto.hello(0x11223344, "abc")
        assertEquals(0x44.toByte(), rep[8])        // pid, little-endian, at offset 8
        assertEquals(0x33.toByte(), rep[9])
        assertEquals(0x22.toByte(), rep[10])
        assertEquals(0x11.toByte(), rep[11])
        assertEquals('a'.code.toByte(), rep[12])   // name starts at 12, not 10
        assertEquals(0.toByte(), rep[6])           // the two padding bytes stay zero
        assertEquals(0.toByte(), rep[7])
    }

    @Test
    fun parseHelloRejectsAShortBody() {
        assertNull(BridgeProto.parseHello(ByteArray(11)))   // status..pid is 12 bytes
        // a body with no name at all is still a valid hello
        assertTrue(BridgeProto.parseHello(BridgeProto.hello(1, "")) != null)
    }

    @Test
    fun hostReqCarriesTheNameAndOpTheDispatcherReads() {
        val body = BridgeProto.hostReq("clipboard", BridgeProto.CLIP_SET, "hi".toByteArray())
        assertEquals(1 + 9 + 2 + 2, body.size)
        val req = BridgeProto.parseHostReq(body)!!
        assertEquals("clipboard", req.name)
        assertEquals(BridgeProto.CLIP_SET, req.op)
        assertArrayEquals("hi".toByteArray(), req.body)
    }

    @Test
    fun hostReqRefusesNamesItCannotExpress() {
        // the length is one byte and the host caps names at 32: refuse at the source
        // rather than emit something the other end will read as garbage
        assertThrows { BridgeProto.hostReq("", 0) }
        assertThrows { BridgeProto.hostReq("x".repeat(BridgeProto.HOST_MAX_NAME + 1), 0) }
    }

    @Test
    fun parseHostReqRejectsMalformedBodies() {
        assertNull(BridgeProto.parseHostReq(ByteArray(0)))
        assertNull(BridgeProto.parseHostReq(byteArrayOf(0, 0, 0)))                  // empty name
        assertNull(BridgeProto.parseHostReq(byteArrayOf(9, 'a'.code.toByte(), 0, 0))) // name longer than body
        assertNull(BridgeProto.parseHostReq(byteArrayOf(40, 'a'.code.toByte(), 0, 0))) // name over the cap
    }

    @Test
    fun opAndPayloadSplitTypedBodies() {
        val body = byteArrayOf(BridgeProto.PROP_GET.toByte(), 'r'.code.toByte(), 0)
        assertEquals(BridgeProto.PROP_GET, BridgeProto.op(body))
        assertArrayEquals(byteArrayOf('r'.code.toByte(), 0), BridgeProto.payload(body))

        // an empty body has no op and no payload; it must not throw
        assertEquals(-1, BridgeProto.op(ByteArray(0)))
        assertEquals(0, BridgeProto.payload(ByteArray(0)).size)
        assertEquals(0, BridgeProto.payload(byteArrayOf(3)).size)
    }

    @Test
    fun pairReadsKeyValueAndToleratesAValueWithNulTerminator() {
        val body = "ro.aemu.test\u0000yes\u0000".toByteArray(Charsets.UTF_8)
        val (k, v) = BridgeProto.pair(body)!!
        assertEquals("ro.aemu.test", k)
        assertEquals("yes", v)

        // PROPS/GET carries only `key\0`: the value comes back empty, not null
        val get = "ro.aemu.test\u0000".toByteArray(Charsets.UTF_8)
        assertEquals("ro.aemu.test" to "", BridgeProto.pair(get))

        // no terminator means the body was cut short
        assertNull(BridgeProto.pair("ro.aemu.test".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun statusReadsTheFirstByteAndDefaultsToEio() {
        assertEquals(BridgeProto.OK, BridgeProto.status(byteArrayOf(BridgeProto.OK.toByte())))
        assertEquals(BridgeProto.EPERM, BridgeProto.status(byteArrayOf(BridgeProto.EPERM.toByte())))
        assertEquals(BridgeProto.EIO, BridgeProto.status(null))
        assertEquals(BridgeProto.EIO, BridgeProto.status(ByteArray(0)))
        // a high-bit status must stay unsigned
        assertEquals(0xff, BridgeProto.status(byteArrayOf(0xff.toByte())))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected an exception")
        } catch (expected: IllegalArgumentException) {
            // require() throws this
        }
    }
}
