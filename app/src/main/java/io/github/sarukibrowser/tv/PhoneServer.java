package io.github.sarukibrowser.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Small web server on the home network so a phone can type, click and send links to the TV with nothing installed.
 * The phone opens http://TV-IP:PORT/?k=TOKEN from the QR code; every command must carry the same random token.
 */
final class PhoneServer {
    interface Commands { void onPhoneCommand(String line); }

    private final Context ctx;
    private final Commands cmd;
    private final Handler main = new Handler(Looper.getMainLooper());
    final String token;
    private ServerSocket server;
    int port;
    volatile long lastSeen;

    PhoneServer(Context c, Commands cmd) {
        this.ctx = c.getApplicationContext();
        this.cmd = cmd;
        Prefs.init(c);
        String t = Prefs.p.getString("phoneToken", null);
        if (t == null) {
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(r.nextInt(31)));
            t = b.toString();
            Prefs.p.edit().putString("phoneToken", t).apply();
        }
        token = t;
    }

    synchronized void start() {
        if (server != null) return;
        // Same port every time when possible, so a phone that already has the remote page open keeps working.
        int preferred = Prefs.p.getInt("phonePort", 8765);
        int[] order = new int[11];
        order[0] = preferred;
        for (int i = 0; i < 10; i++) order[i + 1] = 8765 + i;
        for (int p : order) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(p));
                server = ss;
                port = p;
                break;
            } catch (Exception ignored) { }
        }
        if (server == null) return;
        if (port != preferred) Prefs.p.edit().putInt("phonePort", port).apply();
        ServerSocket s = server;
        new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    new Thread(() -> serve(c), "phone-conn").start();
                } catch (Exception e) { break; }
            }
        }, "phone-server").start();
    }

    synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    String url() {
        String ip = ip();
        return ip == null ? null : "http://" + ip + ":" + port + "/?k=" + token;
    }

    static String ip() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(60000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (true) {
                String req = line(in);
                if (req == null || req.isEmpty()) return;
                int len = 0;
                String h;
                while ((h = line(in)) != null && !h.isEmpty()) {
                    if (h.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                }
                byte[] body = new byte[Math.min(len, 65536)];
                int got = 0;
                while (got < body.length) { int r = in.read(body, got, body.length - got); if (r < 0) break; got += r; }
                String[] parts = req.split(" ");
                String path = parts.length > 1 ? parts[1] : "/";
                boolean ok = path.contains("k=" + token);
                if (path.startsWith("/api") && ok && parts[0].equals("POST")) {
                    lastSeen = System.currentTimeMillis();
                    String text = new String(body, 0, got, StandardCharsets.UTF_8);
                    for (String l : text.split("\n")) if (!l.isEmpty()) main.post(() -> cmd.onPhoneCommand(l));
                    send(out, "200 OK", "text/plain", "ok".getBytes());
                } else if ((path.equals("/") || path.startsWith("/?")) && ok) {
                    lastSeen = System.currentTimeMillis();
                    byte[] page = page();
                    send(out, "200 OK", "text/html; charset=utf-8", page);
                } else {
                    send(out, "403 Forbidden", "text/plain", "Scan the QR code on the TV again.".getBytes());
                }
            }
        } catch (Exception ignored) { }
    }

    private static void send(OutputStream out, String status, String type, byte[] b) throws Exception {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length +
                "\r\nCache-Control: no-store\r\nConnection: keep-alive\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private volatile byte[] pageBytes;

    /** The phone page with this TV's token filled in, read from the app once. */
    private byte[] page() {
        byte[] p = pageBytes;
        if (p == null) pageBytes = p = readAsset("phone.html").replace("%TOKEN%", token).getBytes(StandardCharsets.UTF_8);
        return p;
    }

    private String readAsset(String name) {
        try (InputStream i = ctx.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = i.read(buf)) > 0) b.write(buf, 0, r);
            return b.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }
}
