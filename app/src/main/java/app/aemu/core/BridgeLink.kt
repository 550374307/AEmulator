package app.aemu.core

import android.net.LocalSocket
import android.system.Os
import java.io.File

/**
 * The `aemu_bridge` endpoint: one `SOCK_SEQPACKET` socket in the image's
 * `dev/socket`, one datagram in, one datagram out (see [BridgeProto]).
 *
 * Kept separate from [Bridge] because this is the only piece that needs `android.*`,
 * and the framing is the piece worth testing without a device.
 */
class BridgeLink(
    private val path: File,
    private val bridge: Bridge,
    private val log: (String) -> Unit,
) {
    private val server = UnixServer(path, "bridge", seq = true) { c -> serve(c) }

    fun start(): Boolean = server.start(log)
    fun stop() = server.stop()
    fun clients(): Int = server.clients

    private fun serve(c: LocalSocket) {
        val buf = ByteArray(BridgeProto.MAX)
        val fd = c.fileDescriptor
        while (true) {
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: Throwable) {
                break                     // closed under us: the VM is going down
            }
            if (n <= 0) break
            val rep = bridge.handle(buf, n) ?: continue
            try {
                Os.write(fd, rep, 0, rep.size)
            } catch (e: Throwable) {
                log("bridge: write failed: $e")
                break
            }
        }
    }

    companion object {
        /** Where the guest looks for us: `run64.c`'s `xl()` rewrites `/dev/socket/*` into the image root. */
        const val GUEST_PATH = "/dev/socket/aemu_bridge"
        fun hostPath(paths: VmPaths): File = paths.socket("aemu_bridge")
    }
}
