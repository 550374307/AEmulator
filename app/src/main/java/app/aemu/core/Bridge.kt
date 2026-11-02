package app.aemu.core

/**
 * The bproto2 side of the host: decode one datagram, dispatch it, frame the reply.
 *
 * Everything here is pure — no `android.*`, no socket. The socket lives in
 * [BridgeLink], so [handle] can be exercised byte-for-byte in `BridgeTest`
 * on a plain JVM. That split is the point: framing and the capability check are
 * the two things that are miserable to debug on a device.
 *
 * The service set is closed. [HostServices] looks names up in a map of hand-written
 * handlers; there is no `Class.forName`, no `Method.invoke` on this path at all.
 */
class Bridge(
    private val tag: String,
    private val services: HostServices,
    private val pid: () -> Int,
    /** LMKD bodies go to the real `GuestLmk`; a lambda keeps that out of the protocol layer. */
    private val lmk: (ByteArray) -> ByteArray,
    private val log: (String) -> Unit,
) {

    /**
     * One request in, one framed reply out — or null when the datagram is not a
     * bridge message and should simply be dropped. `SOCK_SEQPACKET` guarantees the
     * next read starts at the next message, so a dropped datagram costs nothing.
     */
    fun handle(datagram: ByteArray, n: Int = datagram.size): ByteArray? {
        val msg = BridgeProto.decode(datagram, 0, n) ?: return null
        if (msg.ver > BridgeProto.VER) {
            // A newer guest than this host: say so instead of guessing at the body.
            log("bridge: ver ${msg.ver} > ${BridgeProto.VER}, refusing")
            return BridgeProto.frame(msg.type, msg.seq, BridgeProto.err(BridgeProto.EINVAL))
        }
        val reply = try {
            dispatch(msg.type, msg.body)
        } catch (d: HostServices.Denied) {
            log("bridge: ${d.message}")
            BridgeProto.err(BridgeProto.EPERM)
        } catch (t: Throwable) {
            log("bridge: type ${msg.type} failed: $t")
            BridgeProto.err(BridgeProto.EIO)
        }
        return BridgeProto.frame(msg.type, msg.seq, reply)
    }

    /** `AB_SVC_*` → handler. Unknown types are `AB_ENOENT`, not an exception. */
    fun dispatch(type: Int, body: ByteArray): ByteArray = when (type) {
        BridgeProto.SVC_HELLO -> BridgeProto.hello(pid(), tag)
        BridgeProto.SVC_PROPS -> services.typed(HostServices.PROPS, BridgeProto.op(body), BridgeProto.payload(body))
        BridgeProto.SVC_CLIPBOARD -> services.typed(HostServices.CLIPBOARD, BridgeProto.op(body), BridgeProto.payload(body))
        BridgeProto.SVC_POWER -> services.typed(HostServices.POWER, BridgeProto.op(body), BridgeProto.payload(body))
        // LMKD is a typed service like the rest (op in body[0]), but its handler is the live
        // `GuestLmk` rather than an entry in `HostServices.table`, so it is dispatched here.
        // The capability gate still applies — without it `lmkd` would be reachable by anyone.
        BridgeProto.SVC_LMKD ->
            if (services.granted(HostServices.LMKD)) lmk(body)
            else throw HostServices.Denied("host service '${HostServices.LMKD}' not granted")
        BridgeProto.SVC_HOST -> services.host(body)
        else -> BridgeProto.err(BridgeProto.ENOENT)
    }

    /** What the info screen and `aemu.log` show: declared vs granted, so a gap is visible. */
    fun describe(): String =
        "bridge: declared=${services.serviceNames().joinToString(",")} granted=${services.grantedNames().joinToString(",")}"
}
