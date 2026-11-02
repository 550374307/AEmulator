// Host-side test for native/guestshim/bridge.c: a mock bproto2 server in the same
// process, and a client that exercises the framing through the real ab_call().
//
// Why this exists: the bridge's wire format is the one thing that is miserable to
// debug on a device (a wrong length byte looks exactly like a wedged guest). Here
// both ends are in one process on a workstation, with libc in place of raw syscalls,
// so a framing regression fails a test instead of a boot.
//
//   sh build.sh hosttest && ./ab_host_test          (POSIX: SOCK_SEQPACKET)
//   sh build.sh hosttest && ab_host_test.exe        (Windows: SOCK_STREAM, still
//                                                    one write per message)
//
// Every case asserts on the status byte and on the reply body, so a swap of two
// fields in ab_hdr shows up as a name that decodes as garbage, not as a timeout.

#if defined(_WIN32)
#  include <winsock2.h>
#  include <windows.h>
#else
#  include <pthread.h>
#  include <sys/socket.h>
#  include <sys/un.h>
#  include <unistd.h>
#endif
#include <stdio.h>
#include <string.h>

#include "../guest64/common/bproto2.h"

/* built with -DAB_HOST_TEST, so the client connects to whatever path we set here
 * instead of reading AEMU64_BRIDGE (there is no guest environment on a workstation) */
void ab_test_path(const char *p);

/* convenience wrapper implemented in bridge.c on top of ab_call(AB_SVC_HELLO):
 * not in bproto2.h because only bring-up and this test use it. */
int ab_hello(unsigned short *ver, unsigned short *maxBody);
int ab_hello_full(unsigned short *ver, unsigned short *maxBody,
                  unsigned *pid, char *name, unsigned nameCap);

/* ---- the mock server ------------------------------------------------- */

static const char *MOCK_NAME = "hosttest-1";
static int mock_stop_after = 0;       /* >0: close the connection after N replies */

static int srv_fd = -1;

static int srv_open(const char *path, int seq_ok) {
    /* POSIX prefers SEQPACKET and falls back to STREAM; Windows only has STREAM,
     * so the flag is informational there. Both ends still write one message per call. */
    (void)seq_ok;
#if defined(_WIN32)
    WSADATA w;
    if (WSAStartup(0x202, &w) != 0) return -1;
    srv_fd = (int)socket(AF_UNIX, SOCK_STREAM, 0);
    if (srv_fd < 0) return -1;
    /* A socket file left by an interrupted run makes bind() fail with EADDRINUSE,
     * so clear the path first — the same thing the POSIX branch does with unlink(). */
    remove(path);
    struct { unsigned short family; char path[108]; } sa;
    int i = 0;
    sa.family = AF_UNIX;
    while (path[i] && i < 107) { sa.path[i] = path[i]; i++; }
    sa.path[i] = 0;
    if (bind(srv_fd, (const struct sockaddr *)&sa, (int)sizeof(sa)) != 0) return -1;
    if (listen(srv_fd, 4) != 0) return -1;
#else
    srv_fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (srv_fd < 0) srv_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (srv_fd < 0) return -1;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, path, sizeof(sa.sun_path) - 1);
    unlink(path);
    if (bind(srv_fd, (const struct sockaddr *)&sa, (socklen_t)sizeof(sa)) != 0) return -1;
    if (listen(srv_fd, 4) != 0) return -1;
#endif
    return 0;
}

static int srv_accept(void) {
    return (int)accept(srv_fd, 0, 0);
}

#if defined(_WIN32)
static int io_send(int fd, const char *b, int n) { return send(fd, b, n, 0); }
static int io_recv(int fd, char *b, int n) { return recv(fd, b, n, 0); }
static void io_close(int fd) { closesocket(fd); }
#else
static int io_send(int fd, const char *b, int n) { return (int)write(fd, b, (size_t)n); }
static int io_recv(int fd, char *b, int n) { return (int)read(fd, b, (size_t)n); }
static void io_close(int fd) { close(fd); }
#endif

/* One reply: header + status byte + payload. */
static int reply(int c, struct ab_hdr *req, int status, const void *pay, unsigned payLen) {
    static char buf[AB_MAX];
    struct ab_hdr *h = (struct ab_hdr *)buf;
    h->magic = AB_MAGIC;
    h->ver = AB_VER;
    h->type = req->type;
    h->seq = req->seq;
    h->len = 1 + payLen;
    buf[sizeof(struct ab_hdr)] = (char)status;
    if (payLen) memcpy(buf + sizeof(struct ab_hdr) + 1, pay, payLen);
    return io_send(c, buf, (int)(sizeof(struct ab_hdr) + 1 + payLen));
}

/* Answer one request the way Bridge.kt would. */
static int serve_one(int c) {
    static _Alignas(8) char buf[AB_MAX];
    int n = io_recv(c, buf, AB_MAX);
    if (n < (int)sizeof(struct ab_hdr)) return -1;
    struct ab_hdr *h = (struct ab_hdr *)buf;
    if (h->magic != AB_MAGIC) return -1;

    /* A newer guest than the host: refuse rather than guess at the body. */
    if (h->ver > AB_VER) return reply(c, h, AB_EINVAL, 0, 0);

    const char *body = buf + sizeof(struct ab_hdr);
    unsigned blen = h->len;

    switch (h->type) {
    case AB_SVC_HELLO: {
        struct ab_hello_rep r;
        memset(&r, 0, sizeof(r));
        r.status = AB_OK;
        r.ver = AB_VER;
        r.max_body = (unsigned short)(AB_MAX > 0xffff ? 0xffff : AB_MAX);
        r.pid = 4242;
        strncpy(r.name, MOCK_NAME, sizeof(r.name) - 1);
        return reply(c, h, AB_OK, ((char *)&r) + 1, (unsigned)sizeof(r) - 1);
    }
    case AB_SVC_PROPS: {
        unsigned char op = (unsigned char)body[0];
        if (op == AB_PROP_GET) {
            /* {key\0} -> value\0 */
            if (blen < 2) return reply(c, h, AB_EINVAL, 0, 0);
            if (strcmp(body + 1, "ro.aemu.test") == 0) {
                const char *v = "yes";
                return reply(c, h, AB_OK, v, 3);
            }
            return reply(c, h, AB_ENOENT, 0, 0);
        }
        if (op == AB_PROP_SET) return reply(c, h, AB_OK, 0, 0);
        return reply(c, h, AB_ENOENT, 0, 0);
    }
    case AB_SVC_CLIPBOARD: {
        unsigned char op = (unsigned char)body[0];
        if (op == AB_CLIP_GET) { const char *t = "clip"; return reply(c, h, AB_OK, t, 4); }
        if (op == AB_CLIP_SET) return reply(c, h, AB_OK, 0, 0);
        return reply(c, h, AB_ENOENT, 0, 0);
    }
    case AB_SVC_LMKD:
        /* typed like the rest: {u8 op, i32 pid, i32 uid, i32 adj} */
        if (blen < 1) return reply(c, h, AB_EINVAL, 0, 0);
        if (body[0] == AB_LMK_PRIO) {
            if (blen < 13) return reply(c, h, AB_EINVAL, 0, 0);
            return reply(c, h, AB_OK, 0, 0);
        }
        if (body[0] == AB_LMK_REMOVE) {
            if (blen < 5) return reply(c, h, AB_EINVAL, 0, 0);
            return reply(c, h, AB_OK, 0, 0);
        }
        return reply(c, h, AB_ENOENT, 0, 0);
    case AB_SVC_HOST: {
        /* {u8 nameLen, name, u16 op} */
        unsigned nl = (unsigned char)body[0];
        if (nl == 0 || blen < 1 + nl + 2) return reply(c, h, AB_EINVAL, 0, 0);
        if (nl == 5 && memcmp(body + 1, "props", 5) == 0) return reply(c, h, AB_OK, 0, 0);
        return reply(c, h, AB_ENOENT, 0, 0);
    }
    default:
        return reply(c, h, AB_ENOENT, 0, 0);
    }
}

#if defined(_WIN32)
static DWORD WINAPI server_main(LPVOID arg) {
#else
static void *server_main(void *arg) {
#endif
    (void)arg;
    int c = srv_accept();
    if (c < 0) return 0;
    int served = 0;
    while (mock_stop_after <= 0 || served < mock_stop_after) {
        if (serve_one(c) < 0) break;
        served++;
    }
    io_close(c);
    return 0;
}

/* ---- assertions ------------------------------------------------------ */

static int g_fail = 0;
static int g_run = 0;

static void ok(int cond, const char *what) {
    g_run++;
    if (!cond) { g_fail++; printf("FAIL %s\n", what); }
    else printf("ok   %s\n", what);
}

static void eq(int a, int b, const char *what) {
    g_run++;
    if (a != b) { g_fail++; printf("FAIL %s (%d != %d)\n", what, a, b); }
    else printf("ok   %s\n", what);
}

int main(void) {
    char path[128];
#if defined(_WIN32)
    const char *tmp = getenv("TEMP");
    if (!tmp) tmp = ".";
    snprintf(path, sizeof(path), "%s\\ab_host_test.sock", tmp);
#else
    snprintf(path, sizeof(path), "/tmp/ab_host_test.%d.sock", (int)getpid());
#endif
    ab_test_path(path);
    if (srv_open(path, 0) != 0) { printf("FAIL server bind %s\n", path); return 1; }

#if defined(_WIN32)
    HANDLE th = CreateThread(0, 0, server_main, 0, 0, 0);
    (void)th;
#else
    pthread_t th;
    pthread_create(&th, 0, server_main, 0);
#endif
    /* give the accept loop a moment to reach accept() — on Windows a connect to a
     * listening socket succeeds even before accept, so this is belt and braces */
#if defined(_WIN32)
    Sleep(50);
#else
    usleep(50 * 1000);
#endif

    /* 1. HELLO: version negotiation and the name, which is what bring-up logs show */
    {
        unsigned short ver = 0, maxb = 0;
        int st = ab_hello(&ver, &maxb);
        eq(st, 0, "hello returns 0");
        eq((int)ver, AB_VER, "hello advertises AB_VER");
        eq((int)maxb, AB_MAX > 0xffff ? 0xffff : AB_MAX, "hello advertises max body");
        eq(ab_status(), AB_OK, "hello leaves OK as the last status");
    }

    /* 1b. the same reply read in full: pid at struct offset 8 (out offset 7), name at
     * 12 (out offset 11). Getting these wrong is silent — the handshake still "works"
     * and only the log line shows the drift — so the offsets are asserted here too. */
    {
        unsigned short ver = 0, maxb = 0;
        unsigned pid = 0;
        char name[80];
        int i;
        for (i = 0; i < (int)sizeof(name); i++) name[i] = 'x';
        name[sizeof(name) - 1] = 0;
        int st = ab_hello_full(&ver, &maxb, &pid, name, sizeof(name));
        eq(st, 0, "hello_full returns 0");
        eq((int)pid, 4242, "hello_full reads pid at the struct's offset");
        eq(strcmp(name, MOCK_NAME) == 0, 1, "hello_full reads the name");
        eq((int)strlen(name), (int)strlen(MOCK_NAME), "hello_full stops at the terminator");
    }

    /* 2. PROPS/GET: a hit and a miss, with the body copied out of the reply */
    {
        char body[64];
        char out[64];
        int n = 0, i;
        body[n++] = AB_PROP_GET;
        for (i = 0; "ro.aemu.test"[i]; i++) body[n++] = "ro.aemu.test"[i];
        body[n++] = 0;

        memset(out, 0, sizeof(out));
        int st = ab_call(AB_SVC_PROPS, 7, body, (unsigned)n, out, sizeof(out));
        eq(st, AB_OK, "props/get hit is OK");
        ok(strcmp(out, "yes") == 0, "props/get delivers the value");
        eq((int)ab_last_len(), 3, "props/get reports the payload length");
        eq((int)out[3], 0, "props/get leaves a terminator past the value");

        n = 0;
        body[n++] = AB_PROP_GET;
        for (i = 0; "ro.missing"[i]; i++) body[n++] = "ro.missing"[i];
        body[n++] = 0;
        st = ab_call(AB_SVC_PROPS, 8, body, (unsigned)n, out, sizeof(out));
        eq(st, AB_ENOENT, "props/get miss is ENOENT");
        eq((int)ab_last_len(), 0, "a failed call reports no payload");
    }

    /* 2b. a value that exactly fills the caller's buffer is truncated, not terminated */
    {
        char body[64];
        char out[4];
        int n = 0, i;
        body[n++] = AB_PROP_GET;
        for (i = 0; "ro.aemu.test"[i]; i++) body[n++] = "ro.aemu.test"[i];
        body[n++] = 0;
        memset(out, 'z', sizeof(out));
        int st = ab_call(AB_SVC_PROPS, 20, body, (unsigned)n, out, 3);
        eq(st, AB_OK, "a short out buffer still succeeds");
        eq((int)ab_last_len(), 3, "ab_last_len reports what the host sent, not what fit");
        ok(memcmp(out, "yes", 3) == 0, "as much of the value as fits is copied");
    }

    /* 3. seq is per call, and the reply must echo it — ab_call rejects a mismatch */
    {
        char out[16];
        int st = ab_call(AB_SVC_CLIPBOARD, 0xbEEF, "\x00", 1, out, sizeof(out));
        eq(st, AB_OK, "clipboard/get with a high seq");
        ok(memcmp(out, "clip", 4) == 0, "clipboard/get delivers the text");
    }

    /* 4. LMKD: typed like the rest, so the op travels in body[0] */
    {
        char req[13];
        memset(req, 0, sizeof(req));
        req[0] = AB_LMK_PRIO;
        req[1] = 0x10; req[5] = 0x20; req[9] = 9;      /* pid 16, uid 32, adj 9 */
        int st = ab_call(AB_SVC_LMKD, 9, req, sizeof(req), 0, 0);
        eq(st, AB_OK, "lmkd prio accepted (no out buffer)");
    }
    {
        char req[5];
        memset(req, 0, sizeof(req));
        req[0] = AB_LMK_REMOVE;
        req[1] = 0x10;
        eq(ab_call(AB_SVC_LMKD, 10, req, sizeof(req), 0, 0), AB_OK, "lmkd remove accepted");
    }
    {
        /* a 12-byte body is what the no-op-byte encoding used to be: it must no longer pass */
        char req[12];
        memset(req, 0, sizeof(req));
        req[0] = AB_LMK_PRIO;
        eq(ab_call(AB_SVC_LMKD, 11, req, sizeof(req), 0, 0), AB_EINVAL, "lmkd prio without the payload is EINVAL");
    }
    {
        char req[5];
        memset(req, 0, sizeof(req));
        req[0] = 9;                                    /* unknown op */
        eq(ab_call(AB_SVC_LMKD, 12, req, sizeof(req), 0, 0), AB_ENOENT, "lmkd unknown op is ENOENT");
    }

    /* 5. AB_SVC_HOST: the name-addressed envelope */
    {
        char req[16];
        int n = 0;
        req[n++] = 5;
        memcpy(req + n, "props", 5); n += 5;
        req[n++] = AB_PROP_LIST; req[n++] = 0;          /* u16 op, LE */
        int st = ab_call(AB_SVC_HOST, 10, req, (unsigned)n, 0, 0);
        eq(st, AB_OK, "host/props accepted");
    }

    /* 6. unknown type is ENOENT, not a hang */
    {
        int st = ab_call(0x7f, 11, 0, 0, 0, 0);
        eq(st, AB_ENOENT, "unknown type is ENOENT");
    }

    /* 7. an oversize request is refused before it hits the wire */
    {
        static char big[AB_MAX];
        int st = ab_call(AB_SVC_PROPS, 12, big, sizeof(big), 0, 0);
        eq(st, -1, "oversize request refused locally");
    }

    if (g_fail) { printf("\n%d of %d checks FAILED\n", g_fail, g_run); return 1; }
    printf("\nall %d checks passed\n", g_run);
    return 0;
}
