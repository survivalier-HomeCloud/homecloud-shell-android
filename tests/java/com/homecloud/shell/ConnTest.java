package com.homecloud.shell;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ConnTest {
    static int fails = 0;
    static void eq(String n, Object g, Object w) { if (!Objects.equals(g, w)) { fails++; System.out.println("FAIL " + n + ": got [" + g + "] want [" + w + "]"); } else System.out.println("ok   " + n); }

    static class Rec implements ShellConnection.Listener {
        final BlockingQueue<String> ev = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        public void onInfo(String u, String b, String h, String n) { ev.add("info:" + u + "|" + b + "|" + h + "|" + n); }
        public void onData(byte[] d, int l) { data.write(d, 0, l); ev.add("data"); }
        public void onPortOpened(int p) { ev.add("port:" + p); }
        public void onClosed(int c, String e) { ev.add("closed:" + c + ":" + e); }
        String next() throws Exception { String s = ev.poll(5, TimeUnit.SECONDS); return s == null ? "TIMEOUT" : s; }
        String until(String prefix) throws Exception { for (int i = 0; i < 50; i++) { String s = next(); if (s.startsWith(prefix)) return s; if (s.equals("TIMEOUT")) return s; } return "NOTFOUND"; }
    }

    static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) >= 0) { sb.append((char) c); if (sb.toString().endsWith("\r\n\r\n")) break; }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        final List<String> serverSaw = Collections.synchronizedList(new ArrayList<>());
        final String[] mode = { "ok" };
        ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        int port = ss.getLocalPort();
        Thread srv = new Thread(() -> {
            try {
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> {
                        try (Socket sock = s) {
                            InputStream in = new BufferedInputStream(sock.getInputStream());
                            OutputStream out = sock.getOutputStream();
                            String head = readHead(in);
                            serverSaw.add("HEAD " + head.split("\r\n")[0] + " | " + (head.contains("Upgrade: hcshell") ? "upgrade" : "noupgrade"));
                            if (mode[0].equals("404")) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes()); return; }
                            if (mode[0].equals("503")) { out.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".getBytes()); return; }
                            if (mode[0].equals("html")) { out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes()); return; }
                            out.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: hcshell\r\n\r\n".getBytes());
                            Protocol.Frame f = Protocol.read(in);
                            String[] fields = new String(f.data, StandardCharsets.UTF_8).split("\0", -1);
                            serverSaw.add("AUTH " + (char) f.type + " " + String.join(",", fields));
                            if (mode[0].equals("badpass")) { byte[] m = "Identifiants invalides.".getBytes(StandardCharsets.UTF_8); Protocol.write(out, Protocol.F_ERR, m, 0, m.length); return; }
                            byte[] info = (fields[0] + "\nns\n/home/" + fields[0] + "\nréseau").getBytes(StandardCharsets.UTF_8);
                            Protocol.write(out, Protocol.F_INFO, info, 0, info.length);
                            byte[] hello = "bonjour é\r\n".getBytes(StandardCharsets.UTF_8);
                            Protocol.write(out, Protocol.F_DATA, hello, 0, hello.length);
                            byte[] pt = "8000".getBytes(); Protocol.write(out, Protocol.F_OPEN, pt, 0, pt.length);
                            while (true) {
                                f = Protocol.read(in);
                                if (f == null) return;
                                if (f.type == Protocol.F_DATA) {
                                    String t = new String(f.data, StandardCharsets.UTF_8);
                                    serverSaw.add("DATA " + t.length());
                                    if (t.equals("exit")) { byte[] c = { 0, 0, 0, 3 }; Protocol.write(out, Protocol.F_EXIT, c, 0, 4); return; }
                                    if (t.equals("drop")) return;
                                    Protocol.write(out, Protocol.F_DATA, f.data, 0, f.data.length);
                                } else if (f.type == Protocol.F_WIN) {
                                    serverSaw.add("WIN " + (((f.data[0] & 0xFF) << 8) | (f.data[1] & 0xFF)) + "x" + (((f.data[2] & 0xFF) << 8) | (f.data[3] & 0xFF)));
                                }
                            }
                        } catch (Exception e) { serverSaw.add("SERVER-ERR " + e); }
                    }).start();
                }
            } catch (IOException e) { }
        });
        srv.setDaemon(true); srv.start();

        // happy path
        Rec r = new Rec();
        ShellConnection c = new ShellConnection("127.0.0.1", port, "nathan", "pä:ss\"wd", 30, 100, "xterm-256color", r);
        c.start();
        eq("info", r.until("info"), "info:nathan|ns|/home/nathan|réseau");
        eq("data hello", r.until("data"), "data");
        eq("port event", r.until("port"), "port:8000");
        c.send("ls é\r".getBytes(StandardCharsets.UTF_8));
        r.until("data");
        eq("echo", new String(r.data.toByteArray(), StandardCharsets.UTF_8), "bonjour é\r\nls é\r");
        c.resize(40, 120);
        Thread.sleep(300);
        // large paste is chunked (8192) and arrives complete and in order
        byte[] big = new byte[50000]; Arrays.fill(big, (byte) 'x');
        r.data.reset(); c.send(big);
        for (int i = 0; i < 40 && r.data.size() < 50000; i++) Thread.sleep(50);
        eq("big paste", r.data.size(), 50000);
        c.send("exit".getBytes());
        eq("exit code", r.until("closed"), "closed:3:null");
        System.out.println("server saw: " + serverSaw);
        eq("http head", serverSaw.get(0), "HEAD GET /shell HTTP/1.1 | upgrade");
        eq("auth fields", serverSaw.get(1), "AUTH A nathan,pä:ss\"wd,,30,100,xterm-256color,");
        eq("window frame", serverSaw.contains("WIN 40x120"), true);

        // errors
        String[][] cases = { {"badpass", "closed:0:Identifiants invalides."}, {"404", "closed:0:HomeCloud Shell n'est pas installé ou pas démarré sur ce serveur."},
            {"503", "closed:0:Le serveur HomeCloud n'est pas actif."}, {"html", "closed:0:127.0.0.1:" + port + " ne répond pas comme HomeCloud Web."} };
        for (String[] cs : cases) {
            mode[0] = cs[0]; r = new Rec();
            new ShellConnection("127.0.0.1", port, "u", "p", 24, 80, "xterm", r).start();
            eq("error " + cs[0], r.until("closed"), cs[1]);
        }
        mode[0] = "ok"; r = new Rec(); c = new ShellConnection("127.0.0.1", port, "u", "p", 24, 80, "xterm", r); c.start();
        r.until("info"); c.send("drop".getBytes());
        eq("server drop", r.until("closed"), "closed:255:Connexion perdue.");

        // user close: no callback
        r = new Rec(); c = new ShellConnection("127.0.0.1", port, "u", "p", 24, 80, "xterm", r); c.start();
        r.until("info"); c.close(); Thread.sleep(500);
        eq("no callback after close()", r.ev.stream().anyMatch(s -> s.startsWith("closed")), false);

        // connection refused / unknown host
        ss.close(); Thread.sleep(100);
        r = new Rec(); new ShellConnection("127.0.0.1", port, "u", "p", 24, 80, "xterm", r).start();
        eq("refused", r.until("closed"), "closed:0:Impossible de joindre 127.0.0.1:" + port + ".");
        r = new Rec(); new ShellConnection("nexiste-pas.invalid", 8680, "u", "p", 24, 80, "xterm", r).start();
        String uh = r.until("closed"); eq("unknown host", uh.startsWith("closed:0:Hôte introuvable") || uh.startsWith("closed:0:Impossible"), true);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
