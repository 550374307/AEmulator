package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * bproto2 wire format (see `native/guest64/common/bproto2.h`).
 *
 * Deliberately free of any `android.*` import: this is the part of the bridge that
 * has to be testable on a plain JVM (`app/src/test/.../BridgeProtoTest.kt`), because
 * the codec is where a framing bug would cost a day of device debugging.
 *
 * One message = one `SOCK_SEQPACKET` datagram: a 12-byte header followed by `len`
 * body bytes. The reply echoes `type` and `seq`; its first body byte is the status
 * (`AB_OK` / `AB_E*`).
 */
object BridgeProto {

    const val MAGIC = 0x41454252          // "AEBR"
    const val VER = 1
    const val HDR = 12                    // 4 + 1 + 1 + 2 + 4
    const val MAX = 65536                 // one datagram, header included
    const val HOST_MAX_NAME = 32

    const val OK = 0
    const val EPERM = 1                   // capability not granted to this instance
    const val ENOENT = 2                  // unknown service / op
    const val EINVAL = 3                  // malformed body
    const val EIO = 4
    const val ETOOBIG = 5

    const val SVC_HELLO = 0x00
    const val SVC_PROPS = 0x01
    const val SVC_CLIPBOARD = 0x02
    const val SVC_POWER = 0x03
    const val SVC_LMKD = 0x04
    const val SVC_HOST = 0x10

    const val PROP_GET = 0
    const val PROP_SET = 1
    const val PROP_LIST = 2

    const val CLIP_GET = 0
    const val CLIP_SET = 1
    const val CLIP_WATCH = 2

    const val POWER_REBOOT = 0
    const val POWER_SHUTDOWN = 1
    const val POWER_WAKE = 2

    const val LMK_PRIO = 0                // {i32 pid, i32 uid, i32 adj}
    const val LMK_REMOVE = 1              // {i32 pid}

    /** One decoded request (or reply) from a datagram. */
    class Msg(val ver: Int, val type: Int, val seq: Int, val body: ByteArray) {
        override fun toString() = "ab{ver=$ver type=$type seq=$seq len=${body.size}}"
    }

    /** `AB_SVC_HELLO` reply, as far as the host cares about it. */
    class Hello(val status: Int, val ver: Int, val maxBody: Int, val pid: Int, val name: String)

    /** `AB_SVC_HOST` request: a service name from a closed set, an op, and opaque bytes. */
    class HostReq(val name: String, val op: Int, val body: ByteArray)

    fun err(code: Int): ByteArray = byteArrayOf(code.toByte())

    /** Header + body, ready for one `Os.write` / one `sendto`. */
    fun frame(type: Int, seq: Int, body: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(HDR + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).put(VER.toByte()).put(type.toByte()).putShort(seq.toShort())
        out.putInt(body.size).put(body)
        return out.array()
    }

    /**
     * Decode one datagram. Returns null when it is not a whole bridge message —
     * a foreign writer, a truncated buffer, or `len` larger than what arrived.
     * The caller drops the datagram; `SOCK_SEQPACKET` guarantees the next read
     * starts at the next message, so there is nothing to resynchronise.
     */
    fun decode(buf: ByteArray, off: Int = 0, n: Int = buf.size): Msg? {
        if (n < HDR || off < 0 || off + n > buf.size) return null
        val bb = ByteBuffer.wrap(buf, off, n).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != MAGIC) return null
        val ver = bb.get().toInt() and 0xff
        val type = bb.get().toInt() and 0xff
        val seq = bb.short.toInt() and 0xffff
        val len = bb.int
        if (len < 0 || len > n - HDR) return null
        val body = ByteArray(len).also { bb.get(it) }
        return Msg(ver, type, seq, body)
    }

    /**
     * `sizeof(struct ab_hello_rep)` on the C side — and that number is 76, not the 74 the
     * field sizes add up to: `pid` is a 4-byte field, so the compiler inserts two bytes of
     * padding after `max_body` to align it at offset 8, which pushes `name` to offset 12.
     *
     * [LAYOUT] is therefore spelled out field by field below instead of being written
     * sequentially: the guest memcpys a struct, and a Kotlin `putInt` chain would pack the
     * fields at 0/1/2/4/6/10 and silently disagree with every C reader.
     */
    const val HELLO_SIZE = 76

    /** Offsets of `struct ab_hello_rep`, so the padding is visible where it is written. */
    const val HELLO_STATUS = 0
    const val HELLO_VER = 2
    const val HELLO_MAX = 4
    const val HELLO_PID = 8
    const val HELLO_NAME = 12
    const val HELLO_NAME_CAP = 64

    /**
     * `ab_hello_rep`, always the full [HELLO_SIZE] — the C side memcpys a struct, so the
     * reply is a fixed-size record, not a variable-length one. The name is truncated to
     * 63 bytes to leave the last byte of `name[64]` as a terminator: the guest reads it
     * with `strlen`, and an unterminated field would run into whatever follows.
     */
    fun hello(pid: Int, name: String, status: Int = OK): ByteArray {
        val out = ByteArray(HELLO_SIZE)                // zero-filled, so pad and terminator are free
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.put(HELLO_STATUS, status.toByte())
        b.putShort(HELLO_VER, VER.toShort())
        b.putShort(HELLO_MAX, MAX.coerceAtMost(0xffff).toShort())
        b.putInt(HELLO_PID, pid)
        val nm = name.toByteArray(Charsets.UTF_8)
        val take = minOf(HELLO_NAME_CAP - 1, nm.size)
        System.arraycopy(nm, 0, out, HELLO_NAME, take)
        return out
    }

    fun parseHello(body: ByteArray): Hello? {
        if (body.size < HELLO_NAME) return null        // status..pid, name may be empty
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val status = body[HELLO_STATUS].toInt() and 0xff
        val ver = b.getShort(HELLO_VER).toInt() and 0xffff
        val maxBody = b.getShort(HELLO_MAX).toInt() and 0xffff
        val pid = b.getInt(HELLO_PID)
        val rest = body.copyOfRange(HELLO_NAME, body.size)
        val zero = rest.indexOfFirst { it == 0.toByte() }
        val name = String(if (zero < 0) rest else rest.copyOf(zero), Charsets.UTF_8)
        return Hello(status, ver, maxBody, pid, name)
    }

    /** `{u8 nameLen, char name[], u16 op, payload}` — the B1 dispatcher's envelope. */
    fun hostReq(name: String, op: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val nm = name.toByteArray(Charsets.UTF_8)
        require(nm.size in 1..HOST_MAX_NAME) { "name must be 1..$HOST_MAX_NAME bytes" }
        val out = ByteBuffer.allocate(1 + nm.size + 2 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(nm.size.toByte()).put(nm).putShort(op.toShort()).put(body)
        return out.array()
    }

    fun parseHostReq(body: ByteArray): HostReq? {
        if (body.isEmpty()) return null
        val nl = body[0].toInt() and 0xff
        if (nl == 0 || nl > HOST_MAX_NAME || body.size < 1 + nl + 2) return null
        val name = String(body, 1, nl, Charsets.UTF_8)
        val op = (body[1 + nl].toInt() and 0xff) or ((body[2 + nl].toInt() and 0xff) shl 8)
        return HostReq(name, op, body.copyOfRange(3 + nl, body.size))
    }

    /** Typed services put their op in the first body byte; the rest is payload. */
    fun op(body: ByteArray): Int = if (body.isEmpty()) -1 else body[0].toInt() and 0xff

    fun payload(body: ByteArray): ByteArray = if (body.size <= 1) ByteArray(0) else body.copyOfRange(1, body.size)

    /** A `key\0value\0` pair, as PROPS/GET and PROPS/SET carry them. */
    fun pair(body: ByteArray, from: Int = 0): Pair<String, String>? {
        if (from >= body.size) return null
        var i = from
        while (i < body.size && body[i] != 0.toByte()) i++
        if (i >= body.size) return null
        val key = String(body, from, i - from, Charsets.UTF_8)
        val vStart = i + 1
        var j = vStart
        while (j < body.size && body[j] != 0.toByte()) j++
        val value = String(body, vStart, j - vStart, Charsets.UTF_8)
        return key to value
    }

    /** Reply bodies are plain byte arrays; this reads the status byte safely. */
    fun status(rep: ByteArray?): Int = if (rep == null || rep.isEmpty()) EIO else rep[0].toInt() and 0xff
}
