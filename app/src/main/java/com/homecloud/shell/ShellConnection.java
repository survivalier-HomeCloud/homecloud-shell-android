package com.homecloud.shell;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One connection to HomeCloud Shell: HTTP upgrade on /shell, authentication, then framed terminal traffic.
 * All Listener callbacks come from the reader thread.
 */
final class ShellConnection {
    interface Listener {
        void onInfo(String user, String backend, String home, String network);

        void onData(byte[] data, int len);

        void onPortOpened(int port);

        /** Called once, unless close() was called first. error == null means the shell ended normally. */
        void onClosed(int exitCode, String error);
    }

    static final String USER_AGENT = "homecloud-shell-android/1.0";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int AUTH_TIMEOUT_MS = 45_000;
    private static final int KEEPALIVE_MS = 25_000;
    private static final int CHUNK = 8192;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String term;
    private final Listener listener;
    private volatile int rows;
    private volatile int cols;

    private final Object writeLock = new Object();
    /** Android forbids network I/O on the UI thread: every write after the handshake goes through this single thread. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hcshell-writer");
        t.setDaemon(true);
        return t;
    });
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed;
    private volatile boolean ready;

    ShellConnection(String host, int port, String user, String password, int rows, int cols, String term, Listener l) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.rows = rows;
        this.cols = cols;
        this.term = term;
        this.listener = l;
    }

    void start() {
        Thread t = new Thread(this::run, "hcshell-reader");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        String error = null;
        int code = 0;
        Thread pinger = null;
        try {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                s.close();
                throw e;
            }
            synchronized (writeLock) {
                socket = s;
                out = s.getOutputStream();
            }
            if (closed) {
                closeQuietly();
                return;
            }
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(AUTH_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);

            String hostHeader = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            String req = "GET /shell HTTP/1.1\r\nHost: " + hostHeader + ":" + port
                    + "\r\nConnection: Upgrade\r\nUpgrade: hcshell\r\nUser-Agent: " + USER_AGENT + "\r\n\r\n";
            byte[] rb = req.getBytes(StandardCharsets.US_ASCII);
            synchronized (writeLock) {
                out.write(rb);
                out.flush();
            }

            String head = readHead(in);
            if (!head.startsWith("HTTP/1.1 101")) {
                if (head.contains(" 404 ")) error = "HomeCloud Shell n'est pas installé ou pas démarré sur ce serveur.";
                else if (head.contains(" 503 ")) error = "Le serveur HomeCloud n'est pas actif.";
                else error = host + ":" + port + " ne répond pas comme HomeCloud Web.";
                return;
            }

            byte[] auth = Protocol.auth(user, password, "", rows, cols, term);
            sendFrame(Protocol.F_AUTH, auth, 0, auth.length);

            Protocol.Frame f = Protocol.read(in);
            if (f == null) {
                error = "Connexion interrompue.";
                return;
            }
            if (f.type == Protocol.F_ERR) {
                error = f.text();
                return;
            }
            if (f.type != Protocol.F_INFO) {
                error = "Réponse inattendue du serveur.";
                return;
            }
            String[] info = f.text().split("\n", -1);
            s.setSoTimeout(0);
            ready = true;
            listener.onInfo(at(info, 0), at(info, 1), at(info, 2), at(info, 3));

            pinger = new Thread(this::keepAlive, "hcshell-ping");
            pinger.setDaemon(true);
            pinger.start();

            while (true) {
                f = Protocol.read(in);
                if (f == null) {
                    code = 255;
                    error = "Connexion perdue.";
                    break;
                }
                if (f.type == Protocol.F_DATA) {
                    listener.onData(f.data, f.data.length);
                } else if (f.type == Protocol.F_OPEN) {
                    try {
                        int p = Integer.parseInt(f.text().trim());
                        if (p > 0 && p <= 65535) listener.onPortOpened(p);
                    } catch (NumberFormatException ignored) {
                        // not a port: ignore
                    }
                } else if (f.type == Protocol.F_EXIT) {
                    code = Protocol.exitCode(f.data);
                    break;
                } else if (f.type == Protocol.F_ERR) {
                    error = f.text();
                    code = 255;
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            error = ready ? "Connexion perdue." : "Le serveur ne répond pas.";
            code = 255;
        } catch (UnknownHostException e) {
            error = "Hôte introuvable : " + host;
        } catch (ConnectException | NoRouteToHostException e) {
            error = "Impossible de joindre " + host + ":" + port + ".";
        } catch (IOException e) {
            if (!closed) {
                error = ready ? "Connexion perdue." : "Connexion interrompue.";
                code = 255;
            }
        } finally {
            if (pinger != null) pinger.interrupt();
            writer.shutdown();
            closeQuietly();
            if (!closed) listener.onClosed(code, error);
        }
    }

    private static String at(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2047) {
            int c = in.read();
            if (c < 0) break;
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n' && sb.charAt(n - 2) == '\r' && sb.charAt(n - 1) == '\n') break;
        }
        return sb.toString();
    }

    /** Idle connections get dropped by NATs: a window-size frame every few seconds is a harmless no-op for the server. */
    private void keepAlive() {
        try {
            while (!closed) {
                Thread.sleep(KEEPALIVE_MS);
                if (ready && !closed) sendWin();
            }
        } catch (InterruptedException ignored) {
            // connection ended
        }
    }

    private void sendWin() {
        byte[] w = Protocol.winsize(rows, cols);
        sendFrame(Protocol.F_WIN, w, 0, w.length);
    }

    private boolean sendFrame(int type, byte[] data, int off, int len) {
        synchronized (writeLock) {
            if (out == null || closed) return false;
            try {
                Protocol.write(out, type, data, off, len);
                return true;
            } catch (IOException e) {
                closeQuietly();
                return false;
            }
        }
    }

    /** Sends keyboard input; may be called from any thread, bytes leave in call order. */
    void send(byte[] data, int off, int len) {
        if (!ready || len <= 0) return;
        final byte[] copy = Arrays.copyOfRange(data, off, off + len);
        post(() -> {
            int pos = 0;
            while (pos < copy.length) {
                int n = Math.min(copy.length - pos, CHUNK);
                if (!sendFrame(Protocol.F_DATA, copy, pos, n)) return;
                pos += n;
            }
        });
    }

    private void post(Runnable r) {
        try {
            writer.execute(r);
        } catch (RejectedExecutionException ignored) {
            // connection already closed
        }
    }

    void send(byte[] data) {
        send(data, 0, data.length);
    }

    void resize(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        if (ready) post(this::sendWin);
    }

    boolean isReady() {
        return ready && !closed;
    }

    /** User-initiated close: the listener is not called. */
    void close() {
        closed = true;
        writer.shutdown();
        closeQuietly();
    }

    private void closeQuietly() {
        Socket s;
        synchronized (writeLock) {
            s = socket;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
