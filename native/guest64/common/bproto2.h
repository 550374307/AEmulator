// AEMU bridge v1 — host<->guest control channel of the native arm64 engine.
//
// One SOCK_SEQPACKET connection; every message is one datagram: ab_hdr + len body bytes.
// The guest dictates the wire format of the legacy endpoints (rild, vold, dnsproxyd, lmkd);
// this protocol covers only the channels whose both ends are ours.
//
// Freestanding: no libc, no stdint — this header is included by the guest shim (armv7 + the
// arm64 kernels' helpers) and by host-side tests alike, so it only uses plain C types.
#ifndef AEMU_BRIDGE_H
#define AEMU_BRIDGE_H

#define AB_MAGIC  0x41454252u   /* "AEBR" */
#define AB_VER    1
#define AB_MAX    65536         /* one datagram, header included */

struct ab_hdr {                 /* 12 bytes, no padding (4+1+1+2+4) */
    unsigned       magic;       /* AB_MAGIC */
    unsigned char  ver;         /* AB_VER */
    unsigned char  type;        /* AB_SVC_* */
    unsigned short seq;         /* request id, echoed by the reply */
    unsigned       len;         /* body length following this header */
};

/* --- status (first byte of every reply body) -------------------------- */
#define AB_OK      0
#define AB_EPERM   1   /* capability not granted to this instance */
#define AB_ENOENT  2   /* unknown service / op */
#define AB_EINVAL  3   /* malformed body */
#define AB_EIO     4
#define AB_ETOOBIG 5

/* --- services --------------------------------------------------------- */
#define AB_SVC_HELLO     0x00  /* no body -> HELLO_REP */
#define AB_SVC_PROPS     0x01  /* op = AB_PROP_* */
#define AB_SVC_CLIPBOARD 0x02  /* op = AB_CLIP_* */
#define AB_SVC_POWER     0x03  /* op = AB_POWER_* */
#define AB_SVC_LMKD      0x04  /* op = AB_LMK_* in body[0] */
#define AB_SVC_HOST      0x10  /* generic host-service dispatcher (B1) */

struct ab_hello_rep {           /* AB_SVC_HELLO reply: 76 bytes on the wire, padding included */
    unsigned char  status, pad;
    unsigned short ver, max_body;
    unsigned       pid;
    char           name[64];    /* instance id / engine tag, for logs */
};

/* The Kotlin side (BridgeProto.HELLO_*) writes this record field by field at fixed
 * offsets, so the layout is a contract between two languages. `pid` is 4-byte aligned,
 * which puts 2 bytes of padding after `max_body` and pushes `name` to offset 12 —
 * anyone who reads these offsets off the field sizes alone gets 74, not 76. */
#define AB_HELLO_REP_SIZE 76

/* Fail the build here rather than on a device: a compiler that packs `ab_hello_rep`
 * differently from the Kotlin writer would turn every handshake into garbage, and the
 * symptom (a guest that boots but never learns the host's name) is not obvious. */
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(struct ab_hello_rep) == AB_HELLO_REP_SIZE,
               "ab_hello_rep layout changed; update BridgeProto.HELLO_* to match");
_Static_assert(sizeof(struct ab_hdr) == 12, "ab_hdr must stay 12 bytes");
#endif

/* --- PROPS: get/set/list over the guest property area ----------------- */
#define AB_PROP_GET   0   /* in: key\0                -> value\0        */
#define AB_PROP_SET   1   /* in: key\0value\0         -> empty         */
#define AB_PROP_LIST  2   /* in: empty                -> key\0val\0... */

/* --- CLIPBOARD -------------------------------------------------------- */
#define AB_CLIP_GET   0   /* in: empty                -> text          */
#define AB_CLIP_SET   1   /* in: text                 -> empty         */
#define AB_CLIP_WATCH 2   /* in: empty; server pushes AB_EV_CLIP       */

/* --- POWER ------------------------------------------------------------ */
#define AB_POWER_REBOOT   0   /* in: empty */
#define AB_POWER_SHUTDOWN 1
#define AB_POWER_WAKE     2

/* --- LMKD (replaces the oom FIFO once the kernel ships it) ------------- */
#define AB_LMK_PRIO   0   /* in: {u8 op=0, i32 pid, i32 uid, i32 adj} */
#define AB_LMK_REMOVE 1   /* in: {u8 op=1, i32 pid} */

/* --- AB_SVC_HOST body: {u8 nameLen, char name[], u16 op, bytes} ------- */
#define AB_HOST_MAX_NAME 32

/* The typed services (PROPS, CLIPBOARD, POWER, LMKD) carry their op as the first
 * body byte: body = {u8 op, payload...}. HEADER and the reply framing are shared.
 * LMKD included: there is no exception, so a dispatcher can always read body[0]
 * without knowing which service it is looking at. */

/* --- guest-side client (native/guestshim/bridge.c) --------------------- */
//
// One request, one reply. Returns the status byte (AB_OK / AB_E*), or -1 when the
// bridge is missing (an older kernel) — callers then fall back to the legacy
// channel, so old and new kernels can share the rest of the shim.
//
// Never call this from a SIGSYS handler: it does a round trip and may block,
// and that stack is tiny (see bclient.c).
int ab_call(unsigned char type, unsigned short seq,
            const void *in, unsigned inLen, void *out, unsigned outCap);

/* The last status the host answered with, for logs; -1 when never connected. */
int ab_status(void);

/* Payload bytes the last ab_call() delivered, status byte excluded, 0 after a failure.
 * Reply bodies are status + raw bytes with no terminator, so a caller that wants a
 * value as a string uses this instead of strlen(). */
unsigned ab_last_len(void);

#endif
