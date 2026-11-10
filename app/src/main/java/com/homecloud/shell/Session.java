package com.homecloud.shell;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One shell session: its connection, its terminal screen and its state. It lives in the process (kept alive by
 * ShellService), not in an Activity, so it survives rotation, navigation and the app going to the background.
 */
final class Session implements ShellConnection.Listener, TerminalEmulator.Host {
    interface UiListener {
        void onScreen();

        void onState();

        void onTitle();

        void onBell();
    }

    static final int CONNECTING = 0, OPEN = 1, CLOSED = 2;
    private static final long QUIET_MS = 250;

    final int id;
    final Profile profile;
    final TerminalEmulator emu;
    private final String password;               // kept in memory only, to reconnect and to open more tabs
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean redrawPending = new AtomicBoolean();
    private final List<Integer> ports = new ArrayList<>();      // every port announced by the server
    private final List<Integer> pending = new ArrayList<>();    // announced, framed line not printed yet

    private volatile UiListener ui;
    private volatile ShellConnection conn;
    private volatile int state = CONNECTING;
    private volatile String error;
    private volatile int exitCode;
    private volatile String title = "";
    private volatile String details = "";
    private volatile long lastDataMs;
    private int lastCols = 80, lastRows = 24;

    private final Runnable redraw = () -> {
        redrawPending.set(false);
        UiListener u = ui;
        if (u != null) u.onScreen();
    };
    private final Runnable flush = this::flushPorts;

    Session(int id, Profile profile, String password) {
        this.id = id;
        this.profile = profile;
        this.password = password;
        this.emu = new TerminalEmulator(lastCols, lastRows, this);
    }

    // ------------------------------------------------------------------ state

    int state() { return state; }

    String error() { return error; }

    int exitCode() { return exitCode; }

    /** "backend · network" as reported by the server, once connected. */
    String details() { return details; }

    String tabLabel() {
        String t = title;
        return t.isEmpty() ? profile.user + "@" + profile.host : t;
    }

    void setUi(UiListener l) { ui = l; }

    String password() { return password; }

    List<Integer> announcedPorts() {
        synchronized (ports) {
            return new ArrayList<>(ports);
        }
    }

    /** Address HomeCloud Web serves a port of this user's shell on. */
    String urlFor(int port) {
        String h = profile.host.indexOf(':') >= 0 ? "[" + profile.host + "]" : profile.host;
        return "http://" + h + ":" + profile.port + "/port/" + profile.user + "/" + port + "/";
    }

    // ------------------------------------------------------------------ lifecycle

    void connect(int rows, int cols) {
        lastRows = rows;
        lastCols = cols;
        emu.resize(cols, rows);
        startConnection();
    }

    private void startConnection() {
        state = CONNECTING;
        error = null;
        ShellConnection c = new ShellConnection(profile.host, profile.port, profile.user, password, lastRows, lastCols,
                "xterm-256color", this);
        conn = c;
        c.start();
        notifyState();
    }

    /** New connection after the previous one ended; the shell starts afresh below the old output. */
    void reconnect() {
        if (state != CLOSED) return;
        emu.feed("\r\n\033[38;5;245m— reconnexion —\033[0m\r\n");
        requestRedraw();
        startConnection();
    }

    void close() {
        ShellConnection c = conn;
        if (c != null) c.close();
        state = CLOSED;
        main.removeCallbacks(redraw);
        main.removeCallbacks(flush);
    }

    void resize(int cols, int rows) {
        if (cols < 2 || rows < 1) return;
        lastCols = cols;
        lastRows = rows;
        emu.resize(cols, rows);
        ShellConnection c = conn;
        if (c != null) c.resize(rows, cols);
    }

    /** Keyboard input from the view. */
    void sendInput(byte[] data) {
        write(data);
    }

    // ------------------------------------------------------------------ ShellConnection.Listener

    @Override
    public void onInfo(String user, String backend, String home, String network) {
        details = backend + " · " + network;
        state = OPEN;
        notifyState();
    }

    @Override
    public void onData(byte[] data, int len) {
        emu.feed(data, 0, len);
        lastDataMs = SystemClock.uptimeMillis();
        requestRedraw();
    }

    @Override
    public void onPortOpened(int port) {
        synchronized (ports) {
            if (!ports.contains(port)) ports.add(port);
        }
        synchronized (pending) {
            pending.add(port);
        }
        main.post(flush);
    }

    @Override
    public void onClosed(int code, String err) {
        exitCode = code;
        error = err;
        state = CLOSED;
        String msg = err != null ? "\033[31m" + err + "\033[0m"
                : "\033[38;5;245m[Session terminée — code " + code + "]\033[0m";
        emu.feed("\r\n" + msg + "\r\n");
        requestRedraw();
        notifyState();
    }

    // ------------------------------------------------------------------ TerminalEmulator.Host

    /** Answers the emulator gives to terminal queries (cursor position...). */
    @Override
    public void write(byte[] data) {
        ShellConnection c = conn;
        if (c != null) c.send(data);
    }

    @Override
    public void onTitle(String t) {
        title = t == null ? "" : t.trim();
        main.post(() -> {
            UiListener u = ui;
            if (u != null) u.onTitle();
            SessionManager.get().changed();
        });
    }

    @Override
    public void onBell() {
        main.post(() -> {
            UiListener u = ui;
            if (u != null) u.onBell();
        });
    }

    // ------------------------------------------------------------------ helpers

    private void notifyState() {
        main.post(() -> {
            UiListener u = ui;
            if (u != null) u.onState();
            SessionManager.get().changed();
        });
    }

    void requestRedraw() {
        if (redrawPending.compareAndSet(false, true)) main.postDelayed(redraw, 16);
    }

    /**
     * A service started listening in the shell: print the framed link in the terminal, like the command-line client,
     * once the output is quiet and no full-screen program (vim, htop...) owns the screen.
     */
    private void flushPorts() {
        synchronized (pending) {
            if (pending.isEmpty()) return;
        }
        if (emu.isAltScreen() || SystemClock.uptimeMillis() - lastDataMs < QUIET_MS) {
            main.removeCallbacks(flush);
            main.postDelayed(flush, 100);
            return;
        }
        List<Integer> todo;
        synchronized (pending) {
            todo = new ArrayList<>(pending);
            pending.clear();
        }
        for (int port : todo) emu.feed(box(port));
        requestRedraw();
        UiListener u = ui;
        if (u != null) u.onState();
    }

    private String box(int port) {
        String url = urlFor(port);
        String text = "Port " + port + " ouvert : " + url;
        int cols;
        synchronized (emu) {
            cols = emu.cols;
        }
        if (text.codePointCount(0, text.length()) + 4 > cols) text = url;     // narrow screen: the link only
        StringBuilder bar = new StringBuilder();
        for (int i = text.codePointCount(0, text.length()) + 2; i > 0; i--) bar.append('─');
        String c = "\033[38;5;75m", r = "\033[0m";
        return "\r\n" + c + "┌" + bar + "┐" + r + "\r\n"
                + c + "│" + r + " \033[1m" + text + r + " " + c + "│" + r + "\r\n"
                + c + "└" + bar + "┘" + r + "\r\n";
    }
}
