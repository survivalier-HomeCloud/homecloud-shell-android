package com.homecloud.shell;

import java.util.*;
import java.util.concurrent.*;

public class E2E {
    public static void main(String[] a) throws Exception {
        final BlockingQueue<String> ev = new LinkedBlockingQueue<>();
        final TerminalEmulator[] emu = new TerminalEmulator[1];
        final ShellConnection[] conn = new ShellConnection[1];
        emu[0] = new TerminalEmulator(100, 30, new TerminalEmulator.Host() {
            public void write(byte[] d) { conn[0].send(d); }
            public void onTitle(String t) { ev.add("title:" + t); }
            public void onBell() { }
        });
        String pass = a.length > 0 ? a[0] : "se&cr et";
        conn[0] = new ShellConnection("127.0.0.1", 18681, "nathan", pass, 30, 100, "xterm-256color", new ShellConnection.Listener() {
            public void onInfo(String u, String b, String h, String n) { ev.add("info:" + u + "|" + b + "|" + h + "|" + n); }
            public void onData(byte[] d, int l) { emu[0].feed(d, 0, l); }
            public void onPortOpened(int p) { ev.add("port:" + p); }
            public void onClosed(int c, String e) { ev.add("closed:" + c + ":" + e); }
        });
        conn[0].start();
        String first = ev.poll(20, TimeUnit.SECONDS);
        System.out.println("first event: " + first);
        if (first == null || !first.startsWith("info")) System.exit(first != null && first.startsWith("closed") ? 0 : 1);
        Thread.sleep(800);
        String[] cmds = { "uname -a\r", "whoami; hostname; lsb_release -ds\r", "ls --color=always /\r",
            "echo -e '\\033[1;31mrouge\\033[0m \\033[48;5;75m fond \\033[0m é日本'\r",
            "python3 -m http.server 8765 --bind 0.0.0.0 >/dev/null 2>&1 &\r" };
        for (String c : cmds) { conn[0].send(c.getBytes("UTF-8")); Thread.sleep(700); }
        String port = null;
        for (int i = 0; i < 20 && port == null; i++) { String e = ev.poll(1, TimeUnit.SECONDS); if (e != null && e.startsWith("port:")) port = e; else if (e != null) System.out.println("event: " + e); }
        System.out.println("PORT EVENT: " + port);
        conn[0].resize(40, 120); Thread.sleep(300);
        conn[0].send("stty size\r".getBytes()); Thread.sleep(600);
        conn[0].send("exit\r".getBytes());
        String closed = null;
        for (int i = 0; i < 20 && closed == null; i++) { String e = ev.poll(1, TimeUnit.SECONDS); if (e != null && e.startsWith("closed")) closed = e; }
        System.out.println("CLOSED: " + closed);
        System.out.println("---------------- screen (history + screen) ----------------");
        TerminalEmulator e = emu[0];
        for (int i = 0; i < e.totalLines(); i++) {
            TerminalEmulator.Line l = e.lineAt(i); StringBuilder sb = new StringBuilder();
            for (int c = 0; c < l.ch.length; c++) if (l.ch[c] != TerminalEmulator.WIDE_TAIL) sb.appendCodePoint(l.ch[c]);
            String s = sb.toString().replaceAll(" +$", ""); if (!s.isEmpty()) System.out.println(s);
        }
        // colour check on the 'rouge' line
        for (int i = 0; i < e.totalLines(); i++) {
            TerminalEmulator.Line l = e.lineAt(i); String s = e.rowString(i);
            int k = s.indexOf("rouge");
            if (k >= 0 && !s.contains("echo")) System.out.println("rouge cell: fg=" + l.fg[k] + " bold=" + (l.at[k] & 1) + " | fond cell bg=" + l.bg[s.indexOf("fond")]);
        }
        System.exit(0);
    }
}
