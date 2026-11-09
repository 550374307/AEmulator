package app.aemu.stub;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The first guest-side consumer of bproto2: a resident process that talks to the host over
 * /dev/socket/aemu_bridge and mirrors the clipboard through a file.
 *
 * Why a process and not a library: on the arm64 engine there is no LD_PRELOAD shim at all (the
 * engine redirects paths inside its own kernel, native/guestshim is armv7 only), so nothing in the
 * guest could call ab_call(). A Java process started by app_process is the one shape that works on
 * every engine and every API level, and the engine already starts five of them (BtStub, VoldStub,
 * NetStub, ApexStub, StandIn) from the same jar.
 *
 * Why a file instead of the guest's clipboard: reaching the guest's ClipboardService means
 * parceling IClipboard.setPrimaryClip, whose argument list changed in 28, 29, 31 and 33 --
 * getting it wrong corrupts the binder stream rather than failing loudly. The file is honest:
 * anything in the guest (a shell, a script, an app with root) can read and write
 * /data/local/tmp/aemu_clip.txt, and the host's clipboard is mirrored into it. A binder front end
 * can replace the file later without touching the wire protocol.
 *
 * The wire format is native/guest64/common/bproto2.h; the host half is Bridge.kt + HostServices.kt.
 */
public final class BridgeStub {

    // ---- bproto2, from native/guest64/common/bproto2.h
    private static final int MAGIC = 0x41454252;      // "AEBR"
    private static final int VER = 1;
    private static final int HDR = 12;                // magic(4) ver(1) type(1) seq(2) len(4)
    private static final int MAX = 65536;

    private static final int OK = 0, EPERM = 1, ENOENT = 2, EINVAL = 3, EIO = 4, ETOOBIG = 5;

    private static final int SVC_HELLO = 0x00, SVC_PROPS = 0x01, SVC_CLIPBOARD = 0x02,
            SVC_POWER = 0x03, SVC_LMKD = 0x04, SVC_HOST = 0x10;
    private static final int CLIP_GET = 0, CLIP_SET = 1;

    // sizeof(struct ab_hello_rep) == 76: pid is 4-byte aligned, so name lands at 12, not 10.
    private static final int HELLO_SIZE = 76;
    private static final int HELLO_VER = 2, HELLO_MAX = 4, HELLO_PID = 8, HELLO_NAME = 12;

    /** The host writes the phone's clipboard here; the guest writes what it wants on the phone. */
    private static final String CLIP_FILE = "/data/local/tmp/aemu_clip.txt";
    /** Nothing in the guest resolves that name for us: the engine rewrites it into the image root. */
    private static final String SOCKET = "/dev/socket/aemu_bridge";

    private static final long DEFAULT_INTERVAL_MS = 800;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int CLIP_MAX = 1 << 20;

    // SOCK_SEQPACKET, not SOCK_STREAM: the host listens on one (BridgeLink passes seq = true) and a
    // stream client connecting to a seqpacket listener fails with EPROTOTYPE. It is also what makes
    // one read equal one datagram, which the whole protocol assumes.
    // Not final: a broken socket is closed and replaced, never reused.
    private LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
    private final byte[] rx = new byte[MAX];
    private int seq;
    private boolean connected;

    // ---------------------------------------------------------------- wire

    private void connect() throws IOException {
        socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
        // Without a read timeout a host that stopped answering (its accept loop died, the VM is
        // stopping) leaves us blocked in read() forever with a socket that still looks open. The
        // reply to a request is already on the wire when the host is healthy, so a few seconds is
        // generous.
        socket.setSoTimeout(READ_TIMEOUT_MS);
        connected = true;
    }

    private void close() {
        connected = false;
        try { socket.close(); } catch (IOException ignored) { }
        // A LocalSocket on the Java side wraps an fd it will not hand out again after close(); the
        // resident loop needs a fresh one for the next attempt.
        socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
    }

    /**
     * One request, one reply. Returns the status byte, or -1 when the transport failed (the caller
     * then reconnects). Payload is left in {@link #lastReply}.
     */
    private int call(int type, byte[] body) {
        if (!connected) return -1;
        try {
            int n = body == null ? 0 : body.length;
            if (n > MAX - HDR) return -1;
            ByteBuffer out = ByteBuffer.allocate(HDR + n).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(MAGIC).put((byte) VER).put((byte) type).putShort((short) (seq & 0xffff)).putInt(n);
            if (n > 0) out.put(body);
            OutputStream os = socket.getOutputStream();
            os.write(out.array(), 0, out.position());
            os.flush();

            InputStream is = socket.getInputStream();
            // SOCK_SEQPACKET: one read is exactly one datagram. A short read means a short reply,
            // not a truncated one, so there is nothing to loop over.
            int got = is.read(rx);
            if (got < HDR) { close(); return -1; }
            ByteBuffer in = ByteBuffer.wrap(rx, 0, got).order(ByteOrder.LITTLE_ENDIAN);
            int magic = in.getInt();
            in.get();                                  // ver
            in.get();                                  // type
            in.getShort();                             // seq (ours, echoed)
            int len = in.getInt();
            if (magic != MAGIC || len < 1 || len > got - HDR) { close(); return -1; }
            lastReplyLen = len - 1;
            System.arraycopy(rx, HDR + 1, lastReply, 0, Math.min(lastReplyLen, lastReply.length));
            return rx[HDR] & 0xff;
        } catch (IOException e) {
            close();
            return -1;
        }
    }

    private final byte[] lastReply = new byte[MAX];
    private int lastReplyLen;

    private static byte[] op(int op) { return new byte[] { (byte) op }; }

    private static byte[] opBody(int op, String text) {
        byte[] b = text.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] out = new byte[1 + b.length];
        out[0] = (byte) op;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }

    private String replyText() {
        // Reply bodies are status + raw bytes with no terminator; the length came off the header.
        return new String(lastReply, 0, lastReplyLen, java.nio.charset.Charset.forName("UTF-8"));
    }

    /** HELLO: the host answers with its pid, version and a name for the engine tag it is running. */
    private String hello() {
        int st = call(SVC_HELLO, null);
        if (st != OK || lastReplyLen < HELLO_NAME - 1) return null;
        // The reply body starts at the status byte, which call() has already stripped, so every
        // struct offset is one lower: ver at 1, max at 3, pid at 7, name at 11.
        ByteBuffer b = ByteBuffer.wrap(lastReply, 0, lastReplyLen).order(ByteOrder.LITTLE_ENDIAN);
        int ver = b.getShort(1) & 0xffff;
        int maxBody = b.getShort(3) & 0xffff;
        int pid = b.getInt(7);
        int end = lastReplyLen;
        for (int i = HELLO_NAME - 1; i < lastReplyLen; i++) {
            if (lastReply[i] == 0) { end = i; break; }
        }
        String name = new String(lastReply, HELLO_NAME - 1, Math.max(0, end - (HELLO_NAME - 1)),
                java.nio.charset.Charset.forName("UTF-8"));
        return name + " ver=" + ver + " max=" + maxBody + " hostpid=" + pid;
    }

    // ---------------------------------------------------------------- the file

    private static String readFile(File f) {
        if (!f.isFile() || f.length() > CLIP_MAX) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int off = 0;
                while (off < b.length) {
                    int r = in.read(b, off, b.length - off);
                    if (r < 0) break;
                    off += r;
                }
                return new String(b, 0, off, java.nio.charset.Charset.forName("UTF-8"));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            } finally {
                out.close();
            }
            // We run as root, so the file is 0600 by default and nothing else in the guest could
            // read it. /data/local/tmp is the one directory where that is not what we want.
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- resident loop

    private void runResident(long intervalMs) throws InterruptedException {
        File file = new File(CLIP_FILE);
        String fromHost = null;      // last text the host reported
        String fromGuest = null;     // last text we pushed to the host
        int backoff = 500;

        while (!Thread.currentThread().isInterrupted()) {
            if (!connected) {
                try {
                    connect();
                    String who = hello();
                    if (who == null) {
                        System.out.println("aemu-bridge: HELLO failed, retrying");
                        close();
                        Thread.sleep(backoff);
                        backoff = Math.min(backoff * 2, 15000);
                        continue;
                    }
                    System.out.println("aemu-bridge: connected to " + who);
                    backoff = 500;
                    fromHost = null;
                    fromGuest = null;
                } catch (IOException e) {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 15000);
                    continue;
                }
            }

            // host -> guest
            int st = call(SVC_CLIPBOARD, op(CLIP_GET));
            if (st == OK) {
                String text = replyText();
                if (!text.equals(fromHost)) {
                    fromHost = text;
                    if (!text.isEmpty()) {
                        writeFile(file, text);
                        System.out.println("aemu-bridge: clipboard <- host (" + text.length() + " chars)");
                    }
                }
            } else if (st == EPERM) {
                // The user turned the clipboard capability off in the VM settings. Say so once per
                // change rather than every round.
                if (!deniedLogged) {
                    System.out.println("aemu-bridge: clipboard not granted to this VM (EPERM)");
                    deniedLogged = true;
                }
            } else if (st < 0) {
                Thread.sleep(intervalMs);
                continue;
            }

            // guest -> host
            String want = readFile(file);
            if (want != null && !want.equals(fromGuest) && !want.equals(fromHost)) {
                int put = call(SVC_CLIPBOARD, opBody(CLIP_SET, want));
                if (put == OK) {
                    fromGuest = want;
                    System.out.println("aemu-bridge: clipboard -> host (" + want.length() + " chars)");
                } else if (put == EPERM) {
                    if (!deniedLogged) {
                        System.out.println("aemu-bridge: clipboard not granted to this VM (EPERM)");
                        deniedLogged = true;
                    }
                }
            }

            Thread.sleep(intervalMs);
        }
    }

    private boolean deniedLogged;

    /** `once`: one clipboard read for bring-up, then exit. Anything else: stay up. */
    public static void main(String[] args) throws Exception {
        BridgeStub stub = new BridgeStub();
        String mode = args.length > 0 ? args[0] : "";
        long interval = DEFAULT_INTERVAL_MS;
        if (args.length > 1) {
            try { interval = Long.parseLong(args[1]); } catch (NumberFormatException ignored) { }
        }
        if ("once".equals(mode)) {
            stub.connect();
            String who = stub.hello();
            if (who == null) {
                System.out.println("aemu-bridge: no host on " + SOCKET);
                System.exit(1);
            }
            System.out.println("aemu-bridge: " + who);
            int st = stub.call(SVC_CLIPBOARD, op(CLIP_GET));
            System.out.println("aemu-bridge: clipboard status=" + st
                    + (st == OK ? " text=\"" + stub.replyText() + "\"" : ""));
            stub.close();
            System.exit(st == OK ? 0 : 1);
        }
        try {
            stub.runResident(interval);
        } catch (InterruptedException ignored) {
            // the engine kills us on stop; nothing to unwind
        }
    }
}
