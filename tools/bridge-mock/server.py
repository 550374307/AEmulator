# server.py [--path P] [--caps props,power,clipboard,lmkd] [--verbose]:
# a stand-in for the host half of bproto2, so the guest client (native/guestshim/bridge.c) and any
# hand-rolled payload can be exercised without booting an image. It answers exactly like
# app/src/main/java/app/aemu/core/Bridge.kt + HostServices.kt do -- same magic, same status codes,
# same per-service bodies -- and logs every datagram in both directions.
#
#   python tools/bridge-mock/server.py --path /tmp/aemu_bridge --verbose
#   AEMU64_BRIDGE=/tmp/aemu_bridge <anything that calls ab_call()>
#
# The dispatch half is pure bytes and is covered by selftest.py, which runs anywhere. binding the
# socket needs AF_UNIX, which CPython only exposes on hosts whose kernel has it (Linux, macOS,
# Windows 10 1803+ with a matching build) -- on a stock Windows CPython the import-time check in
# main() gives up with a sentence instead of a traceback.
import argparse, os, socket, struct, sys, threading, time

MAGIC = 0x41454252          # "AEBR", little-endian on the wire
VER = 1
MAX = 65536
HDR = 12                    # magic(4) ver(1) type(1) seq(2) len(4)
HELLO_SIZE = 76             # sizeof(struct ab_hello_rep); pid is 4-byte aligned, so name lands at 12

OK, EPERM, ENOENT, EINVAL, EIO, ETOOBIG = 0, 1, 2, 3, 4, 5

SVC_HELLO, SVC_PROPS, SVC_CLIPBOARD, SVC_POWER, SVC_LMKD, SVC_HOST = 0x00, 0x01, 0x02, 0x03, 0x04, 0x10
PROP_GET, PROP_SET, PROP_LIST = 0, 1, 2
CLIP_GET, CLIP_SET, CLIP_WATCH = 0, 1, 2
POWER_REBOOT, POWER_SHUTDOWN, POWER_WAKE = 0, 1, 2
LMK_PRIO, LMK_REMOVE = 0, 1
HOST_MAX_NAME = 32
CLIP_MAX = 1 << 20

DEFAULT_CAPS = ("props", "power", "clipboard", "lmkd")


def log(msg, on=True):
    if on:
        print(msg, flush=True)


# ---------------------------------------------------------------- host state
# A deliberately small, in-memory imitation of VmPaths + PropArea + the clipboard.
class Host:
    def __init__(self, caps, name, verbose):
        self.caps = set(caps)
        self.name = name
        self.verbose = verbose
        self.props = {
            "ro.aemu.mock": "1",
            "ro.build.version.sdk": "28",
            "ro.product.model": "aemu-bridge-mock",
        }
        self.ro = set(self.props)          # ro.* never takes a write, same as PropService
        self.clip = ""
        self.adj = {}
        self.power = []

    # -- PROPS
    def prop_get(self, key):
        return self.props.get(key)

    def prop_set(self, key, value):
        if key in self.ro:
            return False                   # read-only: EPERM, and the guest does not need to know why
        self.props[key] = value
        return True

    def prop_list(self):
        return b"".join(k.encode() + b"\0" + v.encode() + b"\0" for k, v in self.props.items())

    # -- CLIPBOARD
    def clip_get(self):
        return self.clip

    def clip_set(self, text):
        self.clip = text
        return True

    # -- POWER
    def power_op(self, op):
        self.power.append(op)
        return True                        # "already asked" is not an error

    # -- LMKD
    def lmk(self, body):
        # A typed service like the rest: op in body[0], payload after it.
        if len(body) < 1:
            return bytes([EINVAL])
        op = body[0]
        if op == LMK_PRIO:
            if len(body) < 13:
                return bytes([EINVAL])
            pid, uid, adj = struct.unpack_from("<iii", body, 1)
            self.adj[pid] = adj
            log("lmk prio pid=%d uid=%d adj=%d (tracked=%d)" % (pid, uid, adj, len(self.adj)), self.verbose)
            return bytes([OK])
        if op == LMK_REMOVE:
            if len(body) < 5:
                return bytes([EINVAL])
            (pid,) = struct.unpack_from("<i", body, 1)
            self.adj.pop(pid, None)
            log("lmk remove pid=%d (tracked=%d)" % (pid, len(self.adj)), self.verbose)
            return bytes([OK])
        return bytes([ENOENT])


# ---------------------------------------------------------------- dispatch
def hello_reply(host):
    out = bytearray(HELLO_SIZE)            # zero-filled: the padding bytes must stay 0
    out[0] = OK
    struct.pack_into("<H", out, 2, VER)
    struct.pack_into("<H", out, 4, min(MAX, 0xFFFF))
    struct.pack_into("<I", out, 8, os.getpid())          # offset 8, not 6: see HELLO_SIZE
    nm = host.name.encode()[:63]
    out[12:12 + len(nm)] = nm
    return bytes(out)


def typed(host, name, op, body):
    """The {u8 op, payload...} services. Same order of checks as HostServices.invoke()."""
    if name not in host.caps:
        return bytes([EPERM])
    if name == "props":
        if op == PROP_GET:
            key = body.split(b"\0", 1)[0].decode("utf-8", "replace")
            v = host.prop_get(key)
            return bytes([ENOENT]) if v is None else bytes([OK]) + v.encode()
        if op == PROP_SET:
            parts = body.split(b"\0", 2)
            if len(parts) < 2 or not parts[0]:
                return bytes([EINVAL])
            return bytes([OK if host.prop_set(parts[0].decode(), parts[1].decode()) else EPERM])
        if op == PROP_LIST:
            blob = host.prop_list()
            return bytes([ETOOBIG]) if len(blob) > MAX else bytes([OK]) + blob
        return bytes([ENOENT])
    if name == "clipboard":
        if op == CLIP_GET:
            t = host.clip_get()
            return bytes([ENOENT]) if not t else bytes([OK]) + t.encode()
        if op == CLIP_SET:
            if len(body) > CLIP_MAX:
                return bytes([ETOOBIG])
            return bytes([OK if host.clip_set(body.decode("utf-8", "replace")) else EIO])
        if op == CLIP_WATCH:
            return bytes([ENOENT])         # no push channel yet
        return bytes([ENOENT])
    if name == "power":
        if op in (POWER_REBOOT, POWER_SHUTDOWN, POWER_WAKE):
            host.power_op(op)
            return bytes([OK])
        return bytes([ENOENT])
    return bytes([ENOENT])


def host_envelope(host, body):
    """{u8 nameLen, char name[], u16 op, bytes} -> the generic B1 dispatcher."""
    if not body:
        return bytes([EINVAL])
    nl = body[0]
    if nl == 0 or nl > HOST_MAX_NAME or len(body) < 1 + nl + 2:
        return bytes([EINVAL])
    name = body[1:1 + nl].decode("utf-8", "replace")
    (op,) = struct.unpack_from("<H", body, 1 + nl)
    return typed(host, name, op, body[1 + nl + 2:])


def dispatch(host, typ, body):
    if typ == SVC_HELLO:
        return hello_reply(host)
    if typ in (SVC_PROPS, SVC_CLIPBOARD, SVC_POWER):
        op = body[0] if body else -1
        name = {SVC_PROPS: "props", SVC_CLIPBOARD: "clipboard", SVC_POWER: "power"}[typ]
        return typed(host, name, op, body[1:])
    if typ == SVC_LMKD:
        if "lmkd" not in host.caps:
            return bytes([EPERM])          # the whitelist gates this one too
        return host.lmk(body)
    if typ == SVC_HOST:
        return host_envelope(host, body)
    return bytes([ENOENT])


# ---------------------------------------------------------------- the socket
def serve_client(conn, host):
    peer = None
    try:
        while True:
            buf = conn.recv(MAX)
            if not buf:
                break
            if len(buf) < HDR:
                log("short datagram (%d bytes) dropped" % len(buf), host.verbose)
                continue
            magic, ver, typ, seq, ln = struct.unpack_from("<IBBHI", buf, 0)
            if magic != MAGIC:
                log("bad magic 0x%08x dropped" % magic, host.verbose)
                continue
            if ln > len(buf) - HDR:
                log("declared body %d > arrived %d dropped" % (ln, len(buf) - HDR), host.verbose)
                continue
            body = buf[HDR:HDR + ln]
            log("<-- type=0x%02x seq=%d len=%d %s" % (typ, seq, ln, body[:48].hex()), host.verbose)
            if ver > VER:
                rep = bytes([EINVAL])      # newer guest: refuse rather than guess at the body
                log("    ver %d > %d -> EINVAL" % (ver, VER), host.verbose)
            else:
                rep = dispatch(host, typ, body)
            out = struct.pack("<IBBHI", MAGIC, VER, typ, seq, len(rep)) + rep
            log("--> status=%d len=%d %s" % (rep[0] if rep else -1, len(rep), rep[:48].hex()), host.verbose)
            conn.send(out)
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        conn.close()


def main(argv):
    ap = argparse.ArgumentParser(description="mock host half of the AEMU bridge protocol")
    ap.add_argument("--path", default=os.environ.get("AEMU64_BRIDGE", "/dev/socket/aemu_bridge"),
                    help="socket path to bind (default: $AEMU64_BRIDGE or /dev/socket/aemu_bridge)")
    ap.add_argument("--caps", default=",".join(DEFAULT_CAPS),
                    help="comma-separated capabilities to grant (default: %s)" % ",".join(DEFAULT_CAPS))
    ap.add_argument("--name", default="bridge-mock", help="name reported in the HELLO reply")
    ap.add_argument("--verbose", "-v", action="store_true", help="log every datagram")
    args = ap.parse_args(argv)

    caps = [c.strip() for c in args.caps.split(",") if c.strip()]
    unknown = [c for c in caps if c not in DEFAULT_CAPS + ("telephony", "storage", "adb")]
    if unknown:
        print("warning: unknown capability %r (the real host logs and ignores it)" % unknown, file=sys.stderr)

    host = Host(caps, args.name, args.verbose)
    path = args.path
    if not hasattr(socket, "AF_UNIX"):
        print("this python has no AF_UNIX (CPython on Windows omits it); run selftest.py "
              "instead, or run the server under WSL/Linux/msys", file=sys.stderr)
        return 2
    if os.path.exists(path):
        os.unlink(path)                    # a leftover socket file from a crash would block bind
    parent = os.path.dirname(path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)

    srv = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    try:
        srv.bind(path)
    except OSError:
        # SOCK_STREAM if SEQPACKET is unavailable (mingw builds of CPython, some BSDs).
        srv.close()
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(path)
    srv.listen(8)
    os.chmod(path, 0o666)
    print("listening on %s (caps: %s)" % (path, ",".join(sorted(host.caps))), flush=True)

    try:
        while True:
            conn, _ = srv.accept()
            threading.Thread(target=serve_client, args=(conn, host), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        srv.close()
        try:
            os.unlink(path)
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
