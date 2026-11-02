package app.aemu.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
import android.system.UnixSocketAddress
import java.io.File
import java.io.FileDescriptor

/**
 * Unix-сокет в файловой системе, который слушает приложение. Гость (через qemu) подключается
 * к нему по гостевому пути — qemu переписывает путь в дерево прошивки.
 * Все дескрипторы помечаются CLOEXEC, иначе они утекают в процессы гостя.
 *
 * [seq] selects `SOCK_SEQPACKET`, where one write is one read — the bridge protocol
 * (see [BridgeProto]) is framed that way. It goes through the public `Os` API rather
 * than `LocalSocket.SOCKET_SEQPACKET`, which is `@hide` and may be refused on 9+.
 */
class UnixServer(
    val file: File,
    private val name: String,
    private val seq: Boolean = false,
    private val onClient: (LocalSocket) -> Unit,
) {
    @Volatile private var server: LocalServerSocket? = null
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
            val fd = Os.socket(
                OsConstants.AF_UNIX,
                if (seq) OsConstants.SOCK_SEQPACKET else OsConstants.SOCK_STREAM,
                0,
            )
            // Os.socket hands back a raw descriptor with no finalizer behind it: if bind or the
            // wrapper below throws, nothing else will ever close it, so it is closed here.
            val s = try {
                Os.bind(fd, UnixSocketAddress(file.absolutePath))
                cloexec(fd)
                runCatching { Os.chmod(file.absolutePath, "666".toInt(8)) }
                LocalServerSocket(fd)
            } catch (e: Throwable) {
                runCatching { Os.close(fd) }
                throw e
            }
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
        // LocalServerSocket owns the fd it was built from, so closing it is enough.
        runCatching { server?.close() }
        server = null
        clients = 0
    }

    companion object {
        fun cloexec(fd: FileDescriptor?) {
            if (fd == null) return
            runCatching { Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC) }
        }
    }
}
