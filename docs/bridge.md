# The bridge: one registry, one protocol

This document describes the two pieces a VM uses to talk to its host:

- **`EndpointRegistry`** — one list of every guest-visible socket, started in declaration
  order and stopped in reverse. It answers "what does this image expose, and what is up
  right now" without reading five files.
- **bproto2** — the wire format of the one socket whose *both* ends are ours
  (`aemu_bridge`). Everything else keeps speaking whatever the guest firmware expects.

The rule that decided the split: **only change the protocol where we own both sides.**

`rild`, `vold`, `dnsproxyd` and `lmkd` are spoken to by the firmware's own Java and libc.
Their wire format is not ours to change, so the registry tracks their *lifetime* and
leaves their *bytes* alone. `aemu_bridge` is new on both ends, so it gets a format we can
version.

---

## 1. Endpoints

Declared in `GuestVm.doBoot()` (see `app/src/main/java/app/aemu/core/GuestVm.kt`), in this
order. `startAll(api)` walks the list once; an endpoint whose `fromApi` is newer than the
image's `api` is skipped with a log line instead of being started.

| # | name | socket | protocol | fromApi | seq | wires to |
|---|------|--------|----------|---------|-----|----------|
| 1 | `property_service` | `<root>/dev/socket/property_service` | LEGACY | 0 | no | `PropService` |
| 2 | `input` | `<bin>/input.sock` | LEGACY | 0 | no | `InputService` |
| 3 | `frame` | `<bin>/frame.sock` | LEGACY | 0 | no | `FrameBell` |
| 4 | `rild` | `<root>/dev/socket/rild` | LEGACY | 0 | no | `RilStub` (skipped when `radio` is off) |
| 5 | `vold` | `<root>/dev/socket/vold` | LEGACY | 0 | no | `VoldStub` |
| 6 | `dnsproxyd` | `<root>/dev/socket/dnsproxyd` | LEGACY | 14 | no | `DnsProxy` |
| 7 | `aemu_cam` | `<root>/dev/socket/aemu_cam` | LEGACY | 0 | no | `CameraFeed` |
| 8 | `aemu_bridge` | `<root>/dev/socket/aemu_bridge` | **BRIDGE** | 28 | **yes** | `BridgeLink` → `Bridge` |

`<root>` is the image root, `<bin>` is `<image>/run`. Paths come from `VmPaths` in
`app/src/main/java/app/aemu/core/ImageStore.kt`.

Vendor `vold` sockets (Samsung's `usbstorage`, `enc_report`, …) are discovered from
`init.rc` at boot time, so they cannot be declared here. They live in `extraStubs` and are
torn down by `GuestVm.stop()`.

### Lifecycle rules

- `register()` is **first declaration wins**. That is why `reset()` exists: `VmActivity`'s
  retry button is `vm.stop(); vm.boot()` on the *same* `GuestVm`, and a stale `rild`
  declaration would keep last boot's `radio` flag forever.
- `reset()` stops before it forgets. Clearing the map first would leave bound sockets
  behind with no `stop` lambda to close them.
- A throwing `start()` propagates as a boot failure — a stub that cannot bind its socket
  is not something to log and continue past.
- `stopAll()` runs in **reverse** declaration order: what started last is the most likely
  to depend on the rest.

---

## 2. bproto2

Defined once in `native/guest64/common/bproto2.h`, mirrored in
`app/src/main/java/app/aemu/core/BridgeProto.kt` for the host side. One message is one
`SOCK_SEQPACKET` datagram: a 12-byte header plus `len` body bytes.

```
struct ab_hdr {          // 12 bytes, no padding
    unsigned      magic; // 0x41454252  "AEBR"
    unsigned char ver;   // 1
    unsigned char type;  // AB_SVC_*
    unsigned short seq;  // echoed by the reply
    unsigned      len;   // body length following the header
};
```

Byte order is little-endian throughout. The reply echoes `type` and `seq` and its **first
body byte is the status**:

| code | name | meaning |
|------|------|---------|
| 0 | `AB_OK` | done |
| 1 | `AB_EPERM` | the service exists but this instance was not granted it |
| 2 | `AB_ENOENT` | unknown service or op |
| 3 | `AB_EINVAL` | malformed body, or a protocol version we do not speak |
| 4 | `AB_EIO` | the handler threw |
| 5 | `AB_ETOOBIG` | the reply would not fit in one datagram |

A datagram that is not a whole bridge message is **dropped**, not answered: the next read
starts at the next message, so there is nothing to resynchronise.

### Services

Every typed service carries its op as the **first body byte**: `body = {u8 op, payload…}`.
There is no exception to that, so a dispatcher can always read `body[0]` without knowing
which service it is looking at.

| type | service | op | body |
|------|---------|----|------|
| `0x00` | HELLO | — | empty → `ab_hello_rep` |
| `0x01` | PROPS | 0 GET / 1 SET / 2 LIST | `key\0` / `key\0value\0` / empty |
| `0x02` | CLIPBOARD | 0 GET / 1 SET / 2 WATCH | empty / text / empty |
| `0x03` | POWER | 0 REBOOT / 1 SHUTDOWN / 2 WAKE | empty |
| `0x04` | LMKD | 0 PRIO / 1 REMOVE | `{i32 pid, i32 uid, i32 adj}` / `{i32 pid}` |
| `0x10` | HOST | any | `{u8 nameLen, char name[nameLen], u16 op, payload}` |

`AB_SVC_HOST` is the generic door: the service is named in the body instead of by the type
byte. **It goes through the same capability check as the typed path**, so a guest cannot
reach `props` by swapping the type byte. That equality is covered by
`HostServicesTest` and `BridgeTest`.

PROPS/GET answers `value\0`; SET answers empty; LIST answers `key\0value\0key\0value\0…`
and `AB_ETOOBIG` rather than a half-written pair. CLIPBOARD/WATCH answers `AB_ENOENT` —
there is no push channel yet, and saying so is better than pretending.

### HELLO and the layout trap

The reply is a **fixed 76-byte record**, not a variable-length one — the C side memcpys a
struct:

```
offset  0  status      u8
offset  1  pad         u8
offset  2  ver         u16
offset  4  max_body    u16
offset  6  padding     2 bytes, inserted by the compiler
offset  8  pid         u32
offset 12  name[64]    NUL-terminated (we truncate to 63 bytes and leave the last as 0)
```

Adding up the field sizes gives 74. The real size is 76, because `pid` is a 4-byte field
and the compiler inserts two bytes of padding after `max_body` to align it. A Kotlin
`putInt` chain would pack the fields sequentially at 0/1/2/4/6/10 and **silently disagree
with every C reader** — that bug shipped once and was caught only by the host test. Both
sides now spell the offsets out and both sides assert them:
`_Static_assert(sizeof(struct ab_hello_rep) == 76, …)` in the header, and
`helloWritesTheStructLayoutNotTheFieldSizes` in `BridgeProtoTest`.

Version negotiation: a guest sends HELLO first. If the guest's `ver` is newer than the
host's, the host answers `AB_EINVAL` and the guest falls back to the legacy channel rather
than guessing at the body.

---

## 3. Capabilities

`HostServices` is a **closed map of hand-written handlers looked up by name**. There is no
`Class.forName`, no `Method.invoke` anywhere on this path: the guest asks for a *name*, not
for a *class*. This is the deliberate inverse of VMOS' `SampleRomService`, where a guest
component could reflect its way into any host system service.

An instance only sees what its settings granted. Grants are stored in `image.json` as
`hostCaps` and round-trip through `VmSettings`:

| capability | default | what it opens |
|------------|---------|---------------|
| `props` | **on** | property get/set/list |
| `power` | **on** | reboot / shutdown / wake |
| `clipboard` | **on** | clipboard get/set |
| `lmkd` | **on** | low-memory-killer adj updates |
| `telephony` | off | reserved, no handler yet |
| `storage` | off | reserved, no handler yet |
| `adb` | off | reserved, no handler yet |

A service that exists but was not granted answers `AB_EPERM`. A name that matches nothing
is a typo worth logging — but `lmkd` is grantable even though it has no `HostServices`
entry (its handler is the live `GuestLmk`), because *a grant that silently disappears is
how a whitelist rots*.

---

## 4. Guest side

`native/guestshim/bridge.c` is compiled into `libaemushim.so`, which is already
`LD_PRELOAD`ed into every guest process, so nothing new has to be installed. It exports
three functions (`AB_EXPORT` — the shim is built with `-fvisibility=hidden`):

```c
int      ab_call(unsigned char type, unsigned short seq,
                 const void *in, unsigned inLen, void *out, unsigned outCap);
int      ab_status(void);      // last status byte, -1 when never connected
unsigned ab_last_len(void);    // payload bytes the host actually sent
```

`ab_call` returns the status byte, or `-1` when the bridge is missing (an older kernel) —
callers then fall back to the legacy channel, so old and new kernels share the same shim.

Three rules that are easy to get wrong:

- **Connect lazily.** `ab_open()` is called on first use, never from a constructor. The
  shim is preloaded into *every* guest process; a connection made at load time holds an
  extra fd in zygote, and Android 7+ aborts at fork when it inspects the descriptor table.
  The fd is `O_CLOEXEC` for the same reason.
- **Never call it from a SIGSYS handler.** It does a round trip and may block; that stack
  is tiny (see `native/guest64/*/bclient.c`).
- **Reply bodies have no terminator.** A reply is `status + raw bytes`. A caller that wants
  a value as a string uses `ab_last_len()` instead of `strlen()`. `ab_call` NUL-terminates
  when there is room and reports the host's real payload length either way — truncation is
  an honest answer, not something to hide by overwriting the last byte.

The rendezvous path comes from the environment: `AEMU64_BRIDGE`, exported by
`GuestRunner` only when the kernel tag declares `bridgeV1`. The guest's own absolute
`/dev/socket/aemu_bridge` is rewritten into the image root by the engine kernel's
`unix_addr()` before `connect()`, so the host listens at `VmPaths.socket("aemu_bridge")`.

`bridgeV1` is set on kernel tags A9…A17 (API 28…37). Older tags build the same shim, get
`ab_call() == -1`, and fall back — `native/guest64/*/run64.c` is untouched by this work.

---

## 5. Verification

Anything that can be checked without a device is checked on every push
(`.github/workflows/build.yml`):

| layer | harness | covers |
|-------|---------|--------|
| C client ↔ C mock host | `native/guestshim/ab_host_test.c`, 26 checks | framing, seq echo, HELLO record layout, NUL handling, short buffers, LMKD op byte, oversize rejection |
| host byte layout | `tools/bridge-mock/selftest.py`, 43 checks | dispatch order, capability gate, reply framing, malformed envelopes |
| Kotlin codec + dispatch + registry | `app/src/test/java/app/aemu/{BridgeProto,Bridge,HostServices,EndpointRegistry}Test.kt`, 63 tests | offset-level framing, `EPERM` before the handler runs, reverse-order stop, `fromApi` gating |

What still needs a device:

1. the guest actually reaching the socket through the engine kernel's `unix_addr()`
   rewrite;
2. Android 7+ zygote's socket-name whitelist not rejecting `aemu_bridge` at fork — watch
   for `Socket name not whitelisted` in the log;
3. clipboard and power against a real `Context`;
4. a GL regression pass (the bridge must not disturb the GL socket);
5. a latency baseline (a 1 KB clipboard round trip should stay under 2 ms).

The device must be arm64: the build ships `abiFilters arm64-v8a` with `minSdk 26`, so an
x86_64 emulator cannot install it.
