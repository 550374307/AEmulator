package app.aemu.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * Unix-сокет в файловой системе, который слушает приложение. Гость (через qemu) подключается
 * к нему по гостевому пути — qemu переписывает путь в дерево прошивки.
 * Все дескрипторы помечаются CLOEXEC, иначе они утекают в процессы гостя.
 *
 * [seq] selects `SOCK_SEQPACKET`, where one write is one read — the bridge protocol
 * (see [BridgeProto]) frames one message per datagram and relies on that.
 */
class UnixServer(
    val file: File,
    private val name: String,
    private val seq: Boolean = false,
    private val onClient: (LocalSocket) -> Unit,
) {
    @Volatile private var server: LocalServerSocket? = null
    /**
     * Strong reference to the socket the server was built from. `LocalServerSocket` takes a
     * `FileDescriptor`, not the socket, so without this the `LocalSocket` would become
     * unreachable the moment [start] returns — and its finalizer would close the very
     * descriptor the server is listening on.
     */
    @Volatile private var bound: LocalSocket? = null
    @Volatile var clients = 0
        private set

    val running: Boolean get() = server != null && file.exists()

    @Synchronized
    fun start(log: (String) -> Unit): Boolean {
        if (running) return true
        stop()
        file.parentFile?.mkdirs()
        file.delete()
        return try {
            // The socket type has to be chosen at construction, so the socket is created here
            // and only then handed to LocalServerSocket. LocalSocket.SOCKET_SEQPACKET is public
            // API (GuestLmk already relies on it), unlike android.system.UnixSocketAddress,
            // which the SDK stub jar does not export.
            val ls = LocalSocket(if (seq) LocalSocket.SOCKET_SEQPACKET else LocalSocket.SOCKET_STREAM)
            ls.bind(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            cloexec(ls.fileDescriptor)
            runCatching { Os.chmod(file.absolutePath, "666".toInt(8)) }
            bound = ls
            val s = LocalServerSocket(ls.fileDescriptor)
            server = s
            Thread({
                while (true) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    cloexec(c.fileDescriptor)
                    clients++
                    Thread({
                        try { onClient(c) } catch (_: Throwable) {
                        } finally {
                            clients--
                            runCatching { c.close() }
                        }
                    }, "$name-client").apply { isDaemon = true; start() }
                }
            }, "$name-accept").apply { isDaemon = true; start() }
            true
        } catch (e: Throwable) {
            log("$name: socket failed: $e")
            false
        }
    }

    @Synchronized
    fun stop() {
        // Both wrap the same descriptor, so the second close is an EBADF after the first —
        // swallowed on purpose. Closing them in this order keeps the listener gone before the
        // socket that owns it, and `bound` is still non-null here so no finalizer can race us.
        runCatching { server?.close() }
        runCatching { bound?.close() }
        server = null
        bound = null
        clients = 0
    }

    companion object {
        fun cloexec(fd: FileDescriptor?) {
            if (fd == null) return
            runCatching { Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC) }
        }
    }
}
