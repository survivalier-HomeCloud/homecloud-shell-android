package com.homecloud.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HomeCloud Shell wire protocol, spoken after the HTTP upgrade on /shell.
 * Every frame is [type:1][length:4 big endian][payload]; the payload is at most {@link #FRAME_MAX} bytes.
 */
final class Protocol {
    static final int F_AUTH = 'A';   // C->S  user\0pass\0cmd\0rows\0cols\0term\0
    static final int F_DATA = 'D';   // both  terminal bytes
    static final int F_WIN = 'W';    // C->S  rows(2) cols(2) big endian
    static final int F_INFO = 'K';   // S->C  user\nbackend\nhome\nnetwork
    static final int F_ERR = 'E';    // S->C  error text
    static final int F_EXIT = 'Q';   // S->C  exit status (int32 big endian)
    static final int F_OPEN = 'O';   // S->C  a service started listening in the shell: port as decimal text

    static final int FRAME_MAX = 65536;

    private Protocol() {}

    static final class Frame {
        final int type;
        final byte[] data;

        Frame(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Writes one frame with a single write() so frames never interleave. */
    static void write(OutputStream out, int type, byte[] payload, int off, int len) throws IOException {
        if (len < 0 || len > FRAME_MAX) throw new IOException("frame too large");
        byte[] b = new byte[5 + len];
        b[0] = (byte) type;
        b[1] = (byte) (len >>> 24);
        b[2] = (byte) (len >>> 16);
        b[3] = (byte) (len >>> 8);
        b[4] = (byte) len;
        if (len > 0) System.arraycopy(payload, off, b, 5, len);
        out.write(b);
        out.flush();
    }

    /** Reads one frame; returns null when the peer closed the stream between two frames. */
    static Frame read(InputStream in) throws IOException {
        byte[] h = new byte[5];
        if (!readFully(in, h, 5, true)) return null;
        int len = (h[1] & 0xFF) << 24 | (h[2] & 0xFF) << 16 | (h[3] & 0xFF) << 8 | (h[4] & 0xFF);
        if (len < 0 || len > FRAME_MAX) throw new IOException("bad frame length");
        byte[] d = new byte[len];
        if (len > 0) readFully(in, d, len, false);
        return new Frame(h[0] & 0xFF, d);
    }

    private static boolean readFully(InputStream in, byte[] b, int n, boolean eofOk) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) {
                if (eofOk && got == 0) return false;
                throw new IOException("connection closed");
            }
            got += r;
        }
        return true;
    }

    /** F_AUTH payload: six NUL-terminated strings. */
    static byte[] auth(String user, String pass, String cmd, int rows, int cols, String term) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        String[] f = { user, pass, cmd, Integer.toString(rows), Integer.toString(cols), term };
        for (String s : f) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            o.write(b, 0, b.length);
            o.write(0);
        }
        return o.toByteArray();
    }

    static byte[] winsize(int rows, int cols) {
        return new byte[] { (byte) (rows >> 8), (byte) rows, (byte) (cols >> 8), (byte) cols };
    }

    static int exitCode(byte[] d) {
        if (d.length != 4) return 0;
        return (d[0] & 0xFF) << 24 | (d[1] & 0xFF) << 16 | (d[2] & 0xFF) << 8 | (d[3] & 0xFF);
    }
}
