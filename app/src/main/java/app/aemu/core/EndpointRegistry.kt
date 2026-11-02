package app.aemu.core

/**
 * Lifecycle registry for every guest-visible host endpoint of one VM.
 *
 * Before this, each stub (`Stubs.kt`, `GuestLmk.kt`, `DnsProxy.kt`, `CameraFeed.kt`,
 * `GlHost`) started its own `UnixServer` from a scattered place in `GuestVm`, and
 * there was no single answer to "what does this API/engine combination actually
 * expose, and which of them are up right now". Now there is: one list, one start,
 * one stop, one place to look.
 *
 * Deliberately free of `android.*`: endpoints are declared as start/stop lambdas, so
 * the ordering and `fromApi` gating are covered by `EndpointRegistryTest` on a plain
 * JVM. The wire protocols are *not* unified here — `rild`, `vold`, `dnsproxyd` and
 * `lmkd` speak whatever the guest firmware's own binaries expect, and only
 * [Protocol.BRIDGE] endpoints use bproto2 (see [BridgeProto]).
 */
class EndpointRegistry(private val log: (String) -> Unit) {

    enum class Protocol { LEGACY, BRIDGE }

    /**
     * One host endpoint. [path] is for logs and the info screen only; the socket
     * itself belongs to whoever passed [start].
     */
    class Endpoint(
        val name: String,
        val path: String,
        val protocol: Protocol = Protocol.LEGACY,
        /** start only for images at least this new (0 = always) */
        val fromApi: Int = 0,
        /** SOCK_SEQPACKET instead of SOCK_STREAM (BRIDGE endpoints only) */
        val seq: Boolean = false,
        val start: () -> Boolean,
        val stop: () -> Unit,
        val clients: () -> Int = { 0 },
    )

    private val entries = LinkedHashMap<String, Endpoint>()
    private val up = LinkedHashSet<String>()

    /** Declare an endpoint. Idempotent: the first declaration of a name wins. */
    @Synchronized
    fun register(ep: Endpoint): Boolean {
        if (entries.containsKey(ep.name)) return false
        entries[ep.name] = ep
        return true
    }

    /**
     * Start everything this image qualifies for, in declaration order.
     * Returns how many endpoints are up afterwards.
     *
     * A throwing [Endpoint.start] propagates: a stub that cannot bind its socket is
     * a boot failure, exactly as it was when the calls were spelled out by hand.
     */
    @Synchronized
    fun startAll(api: Int): Int {
        for (e in entries.values) {
            if (api < e.fromApi) {
                log("host: ${e.name} skipped (api $api < ${e.fromApi})")
                continue
            }
            if (up.contains(e.name)) continue
            val ok = e.start()
            if (ok) up += e.name
            // "down" covers both a refused bind and a service this instance turned off; the
            // endpoint itself logs the reason (UnixServer says why a bind failed).
            log("host: ${e.name} ${if (ok) "up" else "down"} (${e.protocol}${if (e.seq) ",seq" else ""})")
        }
        return up.size
    }

    @Synchronized
    fun stopAll() {
        // reverse order: what was started last is the most likely to depend on the rest
        for (e in entries.values.reversed()) {
            if (!up.contains(e.name)) continue
            runCatching { e.stop() }
            up -= e.name
        }
        up.clear()
    }

    /**
     * Stop everything and forget the declarations, so the next boot can declare the
     * same names again. Needed because a `GuestVm` is rebooted in place
     * (`VmActivity` does `vm.stop(); vm.boot()`): `register` keeps the first
     * declaration of a name, and that declaration closed over the settings of the
     * boot it was written in — a lambda from last boot would start `rild` with last
     * boot's `radio` flag forever.
     */
    @Synchronized
    fun reset() {
        stopAll()
        entries.clear()
    }

    @Synchronized
    fun isUp(name: String) = up.contains(name)

    @Synchronized
    fun declared(): List<String> = entries.keys.toList()

    @Synchronized
    fun up(): List<String> = up.toList()

    /** Counters for the VM info screen and bug reports. */
    @Synchronized
    fun stats(): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (e in entries.values) m[e.name] = if (up.contains(e.name)) runCatching { e.clients() }.getOrDefault(0) else 0
        return m
    }
}
