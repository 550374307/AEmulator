// libaemushim's bridge client: the only place in the guest that knows bproto2.
//
// Both ends of this channel are ours, so unlike rild / vold / dnsproxyd / lmkd the
// wire format here is free to change. Everything else in the shim keeps its
// firmware-defined format.
//
// Freestanding by design: it is compiled into libaemushim.so together with
// aemushim.c (no libc there), and into a host test binary with -DAB_HOST_TEST.
//
// H1 — ab_open() is lazy. This library is LD_PRELOADed into every guest process,
// and from 7.0 the zygote validates every open fd against the guest path whitelist
// before forking (aemushim.c:strip_root exists for that check). Connecting during
// the constructor would leave one extra socket there and abort the boot, so the
// connection is made on the first ab_call() instead, with CLOEXEC set.

// The header lives next to the kernels, which are the other half of this protocol;
// quoted relative to this file so it resolves the same from build.sh and build_all.py.
#include "../guest64/common/bproto2.h"

#if defined(AB_HOST_TEST)
#  if defined(_WIN32)
#    include <winsock2.h>
#    include <windows.h>
#  else
#    include <errno.h>
#    include <sys/socket.h>
#    include <sys/un.h>
#    include <unistd.h>
#  endif
#  include <stdlib.h>
#  include <string.h>
#endif

#define AB_AF_UNIX        1
#define AB_SOCK_SEQPACKET 5
#define AB_SOCK_CLOEXEC   0x80000     /* the socket() flavour of O_CLOEXEC */

#if !defined(AB_HOST_TEST)
#  if defined(__aarch64__)
#    define AB_NR_socket   198
#    define AB_NR_connect  203
#    define AB_NR_read      63
#    define AB_NR_write     64
#    define AB_NR_close     57
#  else
#    define AB_NR_socket   281       /* armv7 EABI */
#    define AB_NR_connect  283
#    define AB_NR_read       3
#    define AB_NR_write      4
#    define AB_NR_close      6
#  endif

/*
 * Three-argument syscall. read/write are used rather than recvfrom/sendto: on a
 * connected SOCK_SEQPACKET socket one write is one datagram and one read returns
 * exactly one datagram, which is the whole assumption of bproto2 — and it keeps
 * this file down to a single 3-arg stub instead of a 6-arg one.
 */
static long ab_sc3(long n, long a, long b, long c) __attribute__((naked));
static long ab_sc3(long n, long a, long b, long c) {
#if defined(__aarch64__)
    __asm__ volatile(
        "mov x8, x0\n"
        "mov x0, x1\n"
        "mov x1, x2\n"
        "mov x2, x3\n"
        "svc #0\n"
        "ret\n");
#else
    __asm__ volatile(
        "push {r7}\n"
        "mov r7, r0\n"
        "mov r0, r1\n"
        "mov r1, r2\n"
        "mov r2, r3\n"
        "svc #0\n"
        "pop {r7}\n"
        "bx lr\n");
#endif
}
#endif /* !AB_HOST_TEST */

struct ab_sun {                 /* sockaddr_un, written out: mingw has no <afunix.h> */
    unsigned short family;
    char path[108];
};

/* libaemushim.so is built with -fvisibility=hidden, so the entry points that other
 * guest code may call have to say so out loud (aemushim.c does the same thing). */
#if !defined(AB_HOST_TEST)
#  define AB_EXPORT __attribute__((visibility("default")))
#else
#  define AB_EXPORT
#endif

/*
 * The request buffer doubles as the reply buffer: the reply cannot arrive before
 * the request is on the wire, and one datagram is at most AB_MAX bytes. It costs
 * 64 KB of .bss per guest process, touched only by callers that use the bridge.
 * _Alignas matters: a char array is only byte-aligned, and this is cast to
 * struct ab_hdr below.
 */
static _Alignas(8) char ab_buf[AB_MAX];

static int ab_fd = -1;
static int ab_last = -1;        /* last status the host answered with, -1 = never */
static unsigned ab_last_pay;    /* payload bytes that came with it, status byte excluded */

#if !defined(AB_HOST_TEST)
extern char **environ;
static const char *ab_env(const char *key) {
    if (!environ) return 0;
    for (char **e = environ; *e; e++) {
        const char *p = *e, *k = key;
        while (*k && *p == *k) { p++; k++; }
        if (*k == 0 && *p == '=') return p + 1;
    }
    return 0;
}
#endif

#if defined(AB_HOST_TEST)
/* The path the test connects to; set by ab_test_path() before the first call. */
static char ab_test_path_buf[108];
void ab_test_path(const char *p) {
    int i = 0;
    while (p[i] && i < 107) { ab_test_path_buf[i] = p[i]; i++; }
    ab_test_path_buf[i] = 0;
}
static const char *ab_path(void) {
    return ab_test_path_buf[0] ? ab_test_path_buf : "/dev/socket/aemu_bridge";
}
#else
static const char *ab_path(void) {
    const char *p = ab_env("AEMU64_BRIDGE");
    return (p && p[0]) ? p : "/dev/socket/aemu_bridge";
}
#endif

static void ab_copy_path(struct ab_sun *sa, const char *p) {
    int i = 0;
    sa->family = AB_AF_UNIX;
    while (p[i] && i < 107) { sa->path[i] = p[i]; i++; }
    sa->path[i] = 0;
    for (i = i + 1; i < 108; i++) sa->path[i] = 0;     /* keep the tail zeroed */
}

#if defined(AB_HOST_TEST)

#  if defined(_WIN32)
static int ab_sock_open(void) {
    WSADATA w;
    if (WSAStartup(0x202, &w) != 0) return -1;
    return (int)socket(AF_UNIX, SOCK_STREAM, 0); /* Windows AF_UNIX is stream-only */
}
static int ab_sock_send(int fd, const char *b, int n) { return send(fd, b, n, 0); }
static int ab_sock_recv(int fd, char *b, int n) { return recv(fd, b, n, 0); }
/* No WSACleanup: the host test runs a mock server in the same process, and tearing
 * winsock down under it would be a lie about what closed. WSAStartup is refcounted. */
static void ab_sock_close(int fd) { closesocket(fd); }
#  else
static int ab_sock_open(void) {
    int fd = (int)socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) fd = (int)socket(AF_UNIX, SOCK_STREAM, 0);
    return fd;
}
static int ab_sock_send(int fd, const char *b, int n) {
    long r;
    do { r = (long)write(fd, b, (size_t)n); } while (r < 0 && errno == EINTR);
    return (int)r;
}
static int ab_sock_recv(int fd, char *b, int n) {
    long r;
    do { r = (long)read(fd, b, (size_t)n); } while (r < 0 && errno == EINTR);
    return (int)r;
}
static void ab_sock_close(int fd) { close(fd); }
#  endif

#else /* guest: raw syscalls */

static int ab_sock_open(void) {
    return (int)ab_sc3(AB_NR_socket, AB_AF_UNIX, AB_SOCK_SEQPACKET | AB_SOCK_CLOEXEC, 0);
}
static int ab_sock_send(int fd, const char *b, int n) {
    return (int)ab_sc3(AB_NR_write, fd, (long)b, n);
}
static int ab_sock_recv(int fd, char *b, int n) {
    return (int)ab_sc3(AB_NR_read, fd, (long)b, n);
}
static void ab_sock_close(int fd) { ab_sc3(AB_NR_close, fd, 0, 0); }

#endif

/*
 * Lazy connect. Returns the fd, or -1 when there is no bridge to talk to — an
 * older kernel, or a guest process that lost the race with the host going down.
 * Callers fall back to the legacy channel, so one shim serves both.
 */
static int ab_open(void) {
    if (ab_fd >= 0) return ab_fd;

    int fd = ab_sock_open();
    if (fd < 0) return -1;

    struct ab_sun sa;
    ab_copy_path(&sa, ab_path());
    long r;
#if defined(AB_HOST_TEST)
    r = connect(fd, (const struct sockaddr *)&sa, (int)sizeof(sa));
#else
    r = ab_sc3(AB_NR_connect, fd, (long)&sa, (long)sizeof(sa));
#endif
    if (r < 0) {
        ab_sock_close(fd);
        return -1;
    }
    ab_fd = fd;
    return fd;
}

AB_EXPORT int ab_status(void) { return ab_last; }

/* How many payload bytes the last ab_call() copied (status byte excluded). 0 when the
 * host sent none or the call failed. Needed because a value like a property or a
 * clipboard entry has no terminator on the wire: reply bodies are status + raw bytes. */
AB_EXPORT unsigned ab_last_len(void) { return ab_last_pay; }

/*
 * One request, one reply. Returns the status byte (AB_OK / AB_E*), or -1 when the
 * bridge is missing. `out` receives the reply body *without* its status byte, at
 * most `outCap` bytes; pass out = 0 to drop the body.
 *
 * Never call this from a SIGSYS handler: it does a round trip and may block, and
 * that stack is tiny (bclient.c does talk to binderd from there).
 */
AB_EXPORT int ab_call(unsigned char type, unsigned short seq,
                      const void *in, unsigned inLen, void *out, unsigned outCap) {
    ab_last_pay = 0;            /* reset first: a failure must not leak the previous reply's length */
    if (inLen > (unsigned)(AB_MAX - sizeof(struct ab_hdr))) return -1;

    int fd = ab_open();
    if (fd < 0) { ab_last = -1; return -1; }

    struct ab_hdr *h = (struct ab_hdr *)ab_buf;
    h->magic = AB_MAGIC;
    h->ver = AB_VER;
    h->type = type;
    h->seq = seq;
    h->len = inLen;
    if (inLen) {
        const char *s = (const char *)in;
        char *d = ab_buf + sizeof(struct ab_hdr);
        unsigned i;
        for (i = 0; i < inLen; i++) d[i] = s[i];
    }

    int want = (int)(sizeof(struct ab_hdr) + inLen);
    int sent = ab_sock_send(fd, ab_buf, want);
    if (sent != want) {
        /* A half-written datagram means the socket is gone for good; reconnect next time. */
        ab_sock_close(fd);
        ab_fd = -1;
        ab_last = -1;
        return -1;
    }

    int n = ab_sock_recv(fd, ab_buf, AB_MAX);
    if (n < 0) {
        ab_sock_close(fd);
        ab_fd = -1;
        ab_last = -1;
        return -1;
    }
    if (n < (int)sizeof(struct ab_hdr)) { ab_last = -1; return -1; }

    struct ab_hdr *rh = (struct ab_hdr *)ab_buf;
    if (rh->magic != AB_MAGIC || rh->seq != seq) { ab_last = -1; return -1; }
    unsigned blen = rh->len;
    if (blen > (unsigned)(n - (int)sizeof(struct ab_hdr))) { ab_last = -1; return -1; }
    if (blen == 0) { ab_last = -1; return -1; }

    const char *body = ab_buf + sizeof(struct ab_hdr);
    int status = body[0] & 0xff;
    unsigned pay = blen - 1;
    ab_last_pay = pay;
    if (out && outCap) {
        unsigned cp = pay < outCap ? pay : outCap;
        char *d = (char *)out;
        unsigned i;
        for (i = 0; i < cp; i++) d[i] = body[1 + i];
        /* Terminate when there is room. The reply body itself carries no terminator —
         * HostServices.cstr() writes status + UTF-8 and stops — so without this every
         * caller doing strcmp()/strlen() on a copied value reads past its end. A payload
         * that filled outCap exactly is left alone: the caller asked for a bound and
         * truncation is the honest answer, not a silent NUL in the last byte. */
        if (cp < outCap) d[cp] = 0;
    }
    ab_last = status;
    return status;
}

/*
 * Bring-up / diagnostics: HELLO and parse the answer. Returns 0 on success and
 * fills ver/maxBody when the pointers are non-null, negative when there is no
 * bridge (the caller then keeps using the FIFO).
 */
AB_EXPORT int ab_hello(unsigned short *ver, unsigned short *maxBody) {
    unsigned char out[AB_HELLO_REP_SIZE];
    int i;
    for (i = 0; i < (int)sizeof(out); i++) out[i] = 0;
    int st = ab_call(AB_SVC_HELLO, 1, 0, 0, out, sizeof(out));
    if (st != AB_OK) return st < 0 ? -1 : -st;
    /* The HELLO reply body *is* struct ab_hello_rep, whose first byte is the status
     * ab_call already stripped. So `out` starts at the pad field and every offset is
     * the struct's minus one:
     *   out[0] pad, out[1..2] ver, out[3..4] max_body, out[7..10] pid, out[11..] name
     * Note pid at 7 — the struct pads max_body (offset 4..5) out to 8, so the two
     * bytes at out[5..6] are padding, not pid. Reading ver from out[2] would fold
     * max_body's low byte into it; both mistakes are what this host test exists for. */
    if (ver) *ver = (unsigned short)((out[1] & 0xff) | ((out[2] & 0xff) << 8));
    if (maxBody) *maxBody = (unsigned short)((out[3] & 0xff) | ((out[4] & 0xff) << 8));
    return 0;
}

/*
 * The same HELLO round trip, but keeping the pid and the name the host reported.
 * ab_hello() above stops at the two fields bring-up cares about; anything that
 * identifies the instance in a log needs these instead.
 */
AB_EXPORT int ab_hello_full(unsigned short *ver, unsigned short *maxBody,
                            unsigned *pid, char *name, unsigned nameCap) {
    unsigned char out[AB_HELLO_REP_SIZE];
    int i;
    for (i = 0; i < (int)sizeof(out); i++) out[i] = 0;
    int st = ab_call(AB_SVC_HELLO, 1, 0, 0, out, sizeof(out));
    if (st != AB_OK) return st < 0 ? -1 : -st;
    if (ver) *ver = (unsigned short)((out[1] & 0xff) | ((out[2] & 0xff) << 8));
    if (maxBody) *maxBody = (unsigned short)((out[3] & 0xff) | ((out[4] & 0xff) << 8));
    if (pid) *pid = (unsigned)((out[7] & 0xff) | ((out[8] & 0xff) << 8) |
                               ((out[9] & 0xff) << 16) | ((out[10] & 0xff) << 24));
    if (name && nameCap) {
        unsigned n = 0;
        while (n + 1 < nameCap && out[11 + n] && 11 + n < sizeof(out)) {
            name[n] = (char)out[11 + n];
            n++;
        }
        name[n] = 0;
    }
    return 0;
}
