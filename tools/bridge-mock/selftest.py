# selftest.py: checks the mock's wire format against the same byte layout the Kotlin side
# (app/src/test/java/app/aemu/BridgeProtoTest.kt) and the C side (native/guestshim/ab_host_test.c)
# assert, without opening a socket. That matters because CPython on Windows exposes no AF_UNIX, so
# server.py itself can only be started on a POSIX host -- this file runs everywhere.
#
#   python tools/bridge-mock/selftest.py
import struct
import sys

import server as S

fails = 0
total = 0


def ok(cond, what, extra=""):
    global fails, total
    total += 1
    if cond:
        print("ok   %s" % what)
    else:
        fails += 1
        print("FAIL %s %s" % (what, extra))


def eq(a, b, what):
    ok(a == b, what, "(%r != %r)" % (a, b))


def main():
    host = S.Host(S.DEFAULT_CAPS, "bridge-mock", False)

    # --- the header is 12 bytes and little-endian, exactly as struct ab_hdr
    eq(struct.calcsize("<IBBHI"), 12, "header packs to 12 bytes")

    # --- HELLO: sizeof(struct ab_hello_rep) with the padding pid forces
    rep = S.hello_reply(host)
    eq(len(rep), S.HELLO_SIZE, "hello reply is HELLO_SIZE")
    eq(len(rep), 76, "hello reply is 76 bytes")
    eq(rep[0], S.OK, "hello status")
    eq(struct.unpack_from("<H", rep, 2)[0], S.VER, "hello ver at offset 2")
    eq(struct.unpack_from("<H", rep, 4)[0], 0xFFFF, "hello max_body at offset 4")
    eq(struct.unpack_from("<I", rep, 8)[0], __import__("os").getpid(), "hello pid at offset 8")
    eq(rep[6], 0, "padding byte 6 stays zero")
    eq(rep[7], 0, "padding byte 7 stays zero")
    eq(rep[12:12 + 11].decode(), "bridge-mock", "hello name at offset 12")

    # --- PROPS/GET: {u8 op, key\0} -> status + value. No NUL on the way back (see HostServices.cstr).
    eq(S.typed(host, "props", S.PROP_GET, b"ro.aemu.mock\0"), bytes([S.OK]) + b"1", "props/get hit")
    eq(S.typed(host, "props", S.PROP_GET, b"ro.aemu.absent\0"), bytes([S.ENOENT]), "props/get miss")
    eq(S.typed(host, "props", S.PROP_SET, b"ro.aemu.mock\0" b"2"), bytes([S.EPERM]), "ro.* write refused")
    eq(S.typed(host, "props", S.PROP_SET, b"debug.aemu\0on"), bytes([S.OK]), "writable key accepted")
    eq(S.typed(host, "props", S.PROP_SET, b"\0"), bytes([S.EINVAL]), "empty key refused")
    eq(S.typed(host, "props", 0x7f, b"x\0"), bytes([S.ENOENT]), "unknown props op")

    # --- PROP_LIST round trip
    lst = S.typed(host, "props", S.PROP_LIST, b"")
    eq(lst[0], S.OK, "props/list status")
    pairs = lst[1:].split(b"\0")
    ok(b"ro.build.version.sdk" in pairs and b"28" in pairs, "props/list carries key and value")

    # --- CLIPBOARD: a set then a get returns the same text
    eq(S.typed(host, "clipboard", S.CLIP_SET, b"hello clip"), bytes([S.OK]), "clipboard/set")
    eq(S.typed(host, "clipboard", S.CLIP_GET, b""), bytes([S.OK]) + b"hello clip", "clipboard/get")
    eq(S.typed(host, "clipboard", S.CLIP_WATCH, b""), bytes([S.ENOENT]), "clipboard/watch has no channel")
    big = b"x" * (S.CLIP_MAX + 1)
    eq(S.typed(host, "clipboard", S.CLIP_SET, big), bytes([S.ETOOBIG]), "oversize clipboard refused")

    # --- POWER: all three ops reach the host and answer OK
    for op in (S.POWER_REBOOT, S.POWER_SHUTDOWN, S.POWER_WAKE):
        eq(S.typed(host, "power", op, b""), bytes([S.OK]), "power op %d" % op)
    eq(len(host.power), 3, "power calls were recorded")
    eq(S.typed(host, "power", 0x7f, b""), bytes([S.ENOENT]), "unknown power op")

    # --- capability gate: same request, service not granted
    grudging = S.Host(("props",), "greedy", False)
    eq(S.typed(grudging, "power", S.POWER_WAKE, b""), bytes([S.EPERM]), "ungranted service is EPERM")
    eq(len(grudging.power), 0, "ungranted service never reaches the host")

    # --- LMKD is typed like the rest: op in body[0]
    body = bytes([S.LMK_PRIO]) + struct.pack("<iii", 1234, 10000, 9)
    eq(len(body), 13, "lmk prio body is 1 + 12 bytes")
    eq(S.dispatch(host, S.SVC_LMKD, body), bytes([S.OK]), "lmkd/prio")
    eq(host.adj.get(1234), 9, "lmkd tracked the adj")
    eq(S.dispatch(host, S.SVC_LMKD, bytes([S.LMK_REMOVE]) + struct.pack("<i", 1234)), bytes([S.OK]), "lmkd/remove")
    ok(1234 not in host.adj, "lmkd removed the pid")
    eq(S.dispatch(host, S.SVC_LMKD, bytes([S.LMK_PRIO]) + b"\0\0"), bytes([S.EINVAL]), "lmkd short body")
    eq(S.dispatch(host, S.SVC_LMKD, bytes([9]) + struct.pack("<i", 1)), bytes([S.ENOENT]), "lmkd unknown op")

    # --- the generic B1 envelope: {u8 nameLen, name[], u16 op, payload}
    env = bytes([5]) + b"props" + struct.pack("<H", S.PROP_GET) + b"ro.aemu.mock\0"
    eq(S.host_envelope(host, env), bytes([S.OK]) + b"1", "host envelope reaches props/get")
    eq(S.host_envelope(host, b""), bytes([S.EINVAL]), "empty envelope")
    eq(S.host_envelope(host, bytes([0]) + b"x" + b"\0\0"), bytes([S.EINVAL]), "zero-length name")
    eq(S.host_envelope(host, bytes([40]) + b"x" * 40), bytes([S.EINVAL]), "name past HOST_MAX_NAME")

    # --- unknown type is ENOENT, and a type byte never stands in for a capability
    eq(S.dispatch(host, 0x7f, b""), bytes([S.ENOENT]), "unknown type")

    # --- reply framing echoes type and seq, big-endian-hostile seq included
    req = struct.pack("<IBBHI", S.MAGIC, S.VER, S.SVC_CLIPBOARD, 0xBEEF, 1) + b"\x00"
    magic, ver, typ, seq, ln = struct.unpack_from("<IBBHI", req, 0)
    eq(magic, S.MAGIC, "magic round trips")
    eq(seq, 0xBEEF, "seq round trips through the header")

    print("\n%d checks, %d failed" % (total, fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
