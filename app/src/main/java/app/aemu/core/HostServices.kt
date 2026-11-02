package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The host capabilities guest code may reach through the bridge.
 *
 * This is the deliberate inverse of VMOS' `SampleRomService`: there, a guest
 * component could reflect its way into any host system service (the `response 53`
 * surface in the 3.10 analysis). Here the table is a closed set of hand-written
 * handlers looked up by name in a map — no `Class.forName`, no `Method.invoke`,
 * anywhere on this path — and an instance only sees what its settings granted.
 *
 * No `android.*` import on purpose: the whitelist decision is pure logic and is
 * covered by `HostServicesTest` on a plain JVM. Everything that needs a Context
 * (clipboard, power) arrives through [Host], implemented in `GuestVm`.
 */
class HostServices(private val log: (String) -> Unit) {

    /** Raised when a service exists but this instance was not granted it. */
    class Denied(message: String) : Exception(message)

    /** One whitelisted service. The first reply byte is [BridgeProto.OK] or an `AB_E*` code. */
    interface Service {
        val name: String
        fun call(op: Int, body: ByteArray): ByteArray
    }

    /**
     * The host side of the built-in services, so [HostServices] stays free of
     * Android types. `GuestVm` wires this to `PropService`, `powerRequest()` and
     * the clipboard.
     */
    interface Host {
        fun propGet(key: String): String?
        fun propSet(key: String, value: String): Boolean
        fun propList(): List<Pair<String, String>>
        /** [BridgeProto.POWER_REBOOT] / [_SHUTDOWN] / [_WAKE]; false when the guest already asked. */
        fun power(op: Int): Boolean
        fun clipGet(): String?
        fun clipSet(text: String): Boolean
    }

    private val table = LinkedHashMap<String, Service>()

    /** Capabilities this instance was granted (persisted in `image.json` as `hostCaps`). */
    private val caps = LinkedHashSet<String>()

    fun register(s: Service) {
        table[s.name] = s
    }

    fun granted(cap: String) = cap in caps

    /**
     * Only names that exist can be granted; anything else is a typo worth logging.
     *
     * "Exist" is wider than [table]: `lmkd` gates a path of its own (its bodies carry no
     * leading op byte, so it cannot be a [Service]) and `telephony`/`storage`/`adb` are
     * reserved for paths that do not exist yet. Granting one of those must not be dropped
     * on the floor — a grant that silently disappears is how a whitelist rots.
     */
    fun grant(caps: Collection<String>) {
        for (c in caps) {
            if (table.containsKey(c) || c in reserved) this.caps += c
            else log("hostsvc: no such service '$c'")
        }
    }

    fun serviceNames(): List<String> = table.keys.toList()
    fun grantedNames(): List<String> = caps.toList()

    /**
     * Register the three always-present services against [host]. Split out so a
     * test can pass a stand-in [Host] and exercise the real dispatch path.
     */
    fun builtins(host: Host) {
        register(object : Service {
            override val name = PROPS
            override fun call(op: Int, body: ByteArray) = props(host, op, body)
        })
        register(object : Service {
            override val name = CLIPBOARD
            override fun call(op: Int, body: ByteArray) = clipboard(host, op, body)
        })
        register(object : Service {
            override val name = POWER
            override fun call(op: Int, body: ByteArray) = power(host, op, body)
        })
    }

    // ---- AB_SVC_PROPS: {u8 op, payload} ---------------------------------

    private fun props(host: Host, op: Int, body: ByteArray): ByteArray {
        // A block body, not `= when (...)`: the GET/SET branches bail out on a malformed
        // body, and `return` is not allowed inside an expression body.
        return when (op) {
            BridgeProto.PROP_GET -> {
                val (k, _) = BridgeProto.pair(body) ?: return BridgeProto.err(BridgeProto.EINVAL)
                val v = host.propGet(k) ?: return BridgeProto.err(BridgeProto.ENOENT)
                cstr(v)
            }
            BridgeProto.PROP_SET -> {
                val (k, v) = BridgeProto.pair(body) ?: return BridgeProto.err(BridgeProto.EINVAL)
                if (k.isEmpty()) return BridgeProto.err(BridgeProto.EINVAL)
                // A refused write is EPERM whichever way it was refused: `ro.*` is read-only, the
                // area is not up yet, or the name is outside what this instance lets a guest touch.
                // The guest has nothing useful to do with the difference.
                if (host.propSet(k, v)) BridgeProto.err(BridgeProto.OK) else BridgeProto.err(BridgeProto.EPERM)
            }
            BridgeProto.PROP_LIST -> {
                val out = StringBuilder()
                for ((k, v) in host.propList()) out.append(k).append('\u0000').append(v).append('\u0000')
                val bytes = out.toString().toByteArray(Charsets.UTF_8)
                // Truncating would hand the guest a half-written pair, so say "too big" instead.
                if (bytes.size + 1 > BridgeProto.MAX) BridgeProto.err(BridgeProto.ETOOBIG)
                else byteArrayOf(BridgeProto.OK.toByte()) + bytes
            }
            else -> BridgeProto.err(BridgeProto.ENOENT)
        }
    }

    // ---- AB_SVC_CLIPBOARD -----------------------------------------------

    private fun clipboard(host: Host, op: Int, body: ByteArray): ByteArray = when (op) {
        BridgeProto.CLIP_GET -> {
            val t = host.clipGet() ?: return BridgeProto.err(BridgeProto.ENOENT)
            cstr(t)
        }
        BridgeProto.CLIP_SET -> {
            val t = String(body, Charsets.UTF_8)
            if (t.length > CLIP_MAX) BridgeProto.err(BridgeProto.ETOOBIG)
            else if (host.clipSet(t)) BridgeProto.err(BridgeProto.OK) else BridgeProto.err(BridgeProto.EIO)
        }
        // watching means "push me changes"; there is no push channel yet, so say so plainly
        BridgeProto.CLIP_WATCH -> BridgeProto.err(BridgeProto.ENOENT)
        else -> BridgeProto.err(BridgeProto.ENOENT)
    }

    // ---- AB_SVC_POWER ---------------------------------------------------

    private fun power(host: Host, op: Int, body: ByteArray): ByteArray = when (op) {
        // "already asked" is not an error for the guest: it only means the VM is on its way out,
        // so a second REBOOT while the first one is running still answers OK.
        BridgeProto.POWER_REBOOT, BridgeProto.POWER_SHUTDOWN, BridgeProto.POWER_WAKE -> {
            host.power(op)
            BridgeProto.err(BridgeProto.OK)
        }
        else -> BridgeProto.err(BridgeProto.ENOENT)
    }

    // ---- AB_SVC_HOST: the generic dispatcher ----------------------------

    /**
     * Body: `{u8 nameLen, char name[nameLen], u16 op, payload}`.
     * A map hit or `AB_ENOENT`; a hit that was not granted throws [Denied], which
     * [Bridge] turns into `AB_EPERM`. Never reflection.
     */
    fun host(body: ByteArray): ByteArray {
        val req = BridgeProto.parseHostReq(body) ?: return BridgeProto.err(BridgeProto.EINVAL)
        return invoke(req.name, req.op, req.body)
    }

    /**
     * The typed entry point: `AB_SVC_PROPS` and friends name their service by type
     * instead of carrying the name in the body, but the capability check is the same
     * one, so a guest cannot reach `props` by swapping the type byte for `AB_SVC_HOST`.
     */
    fun typed(name: String, op: Int, body: ByteArray): ByteArray = invoke(name, op, body)

    private fun invoke(name: String, op: Int, body: ByteArray): ByteArray {
        val svc = table[name] ?: return BridgeProto.err(BridgeProto.ENOENT)   // closed set
        if (name !in caps) throw Denied("host service '$name' not granted")
        return try {
            svc.call(op, body)
        } catch (t: Throwable) {
            log("hostsvc: $name/$op failed: $t")
            BridgeProto.err(BridgeProto.EIO)
        }
    }

    companion object {
        const val PROPS = "props"
        const val CLIPBOARD = "clipboard"
        const val POWER = "power"
        const val LMKD = "lmkd"
        const val TELEPHONY = "telephony"
        const val STORAGE = "storage"
        const val ADB = "adb"

        /** What a new instance gets unless its `image.json` says otherwise. */
        val DEFAULT_CAPS: Set<String> = linkedSetOf(PROPS, POWER, CLIPBOARD, LMKD)

        /**
         * Capability names that carry no [Service] of their own: `lmkd` is dispatched by
         * [Bridge] straight to `GuestLmk`, and the rest are names a future package may
         * want to gate before the path exists. They are still grantable, so the stored
         * `hostCaps` round-trips intact.
         */
        private val reserved: Set<String> = linkedSetOf(LMKD, TELEPHONY, STORAGE, ADB)

        /** 1 MB: a clipboard entry larger than that is a paste bomb, not a paste. */
        const val CLIP_MAX = 1 shl 20

        private fun cstr(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(1 + b.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(BridgeProto.OK.toByte()).put(b).array()
        }
    }
}
