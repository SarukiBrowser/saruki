package io.github.sarukibrowser.remote;

import io.github.sarukibrowser.tv.WsServer;

import io.github.sarukibrowser.link.CamProto;
import io.github.sarukibrowser.link.CamRelay;
import io.github.sarukibrowser.link.PadProto;
import io.github.sarukibrowser.link.TypeProto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saruki Remote link, end to end on this computer: Saruki's real WsServer (the TV side) against the phone app's real
 * WsClient, with the same routing RemoteLink does on the TV (trackpad lines to the pointer, typing lines to Typing,
 * camera lines through CamRelay). No TV or phone needed.
 */
public class LinkTest {
    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    /** What reached "Saruki" (the app side). */
    static final List<String> pad = Collections.synchronizedList(new ArrayList<>());
    static final List<String> type = Collections.synchronizedList(new ArrayList<>());
    static final List<String> cam = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean front = true;
    static final byte[] APK = "PK-fake-apk".getBytes(StandardCharsets.UTF_8);
    static final String CODE = "ABC234";

    static final CamRelay relay = new CamRelay(a -> front);
    static final CamRelay.App app = new CamRelay.App() {
        public void cam(String line) { cam.add(line); }
        public String pkg() { return "io.github.sarukibrowser.tv"; }
        public String label() { return "Saruki"; }
    };
    static final Map<WsServer.Client, CamRelay.Phone> phones = new ConcurrentHashMap<>();

    /** The same routing as RemoteLink (Android-free copy for the test). */
    static final WsServer.Handler HANDLER = new WsServer.Handler() {
        public void onOpen(WsServer.Client c) {
            c.send(TypeProto.id("abcdefghjk23", "Living room TV"));
            c.send(PadProto.front(front, "Saruki"));
        }
        public void onText(WsServer.Client c, String text) {
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.length() < 3) continue;
                switch (line.charAt(0)) {
                    case 'P': { String l = PadProto.toApp(line); if (l != null && front) pad.add(l); break; }
                    case 'T': { TypeProto.Msg m = TypeProto.parse(line); if (m != null) type.add(m.kind + ":" + m.field + ":" + m.text); break; }
                    case 'V': relay.fromPhone(phones.computeIfAbsent(c, k -> k::send), line); break;
                    default:
                }
            }
        }
        public void onClose(WsServer.Client c) { CamRelay.Phone p = phones.remove(c); if (p != null) relay.phoneGone(p); }
        public byte[] page(String path, String[] t) {
            if (path.startsWith("/SarukiRemote")) { t[0] = "application/vnd.android.package-archive"; return APK; }
            if (path.startsWith("/pair")) { t[0] = "text/html; charset=utf-8"; return (path.contains("k=" + CODE) ? "PAIR-PAGE" : "WRONG").getBytes(StandardCharsets.UTF_8); }
            return null;
        }
    };

    public static void main(String[] args) throws Exception {
        int base = 18790;
        // a port already taken: Saruki moves up one
        ServerSocket squat = new ServerSocket(base);
        WsServer srv = new WsServer(base, HANDLER);
        srv.setGate(() -> CODE);
        check(srv.start(), "server starts");
        int port = srv.port();
        check(port == base + 1, "busy port " + base + " -> uses " + port);
        relay.appHello(app);
        check(cam.remove(0).equals("y 0"), "Saruki told: no phone camera yet");

        // ---- web pages ----
        String[] r = http(port, "/pair?k=" + CODE);
        check(r[0].contains("200") && r[1].equals("PAIR-PAGE"), "pairing page with the right code");
        r = http(port, "/pair?k=WRONG1");
        check(r[1].equals("WRONG"), "pairing page with a wrong code says so");
        r = http(port, "/SarukiRemote-v1.0.apk");
        check(r[0].contains("200") && r[2].contains("application/vnd.android.package-archive") && r[1].equals("PK-fake-apk"), "phone app download from the TV");
        r = http(port, "/nothing");
        check(r[0].contains("404"), "unknown page is 404");

        // ---- pairing ----
        try {
            WsClient bad = WsClient.connect("127.0.0.1", port, "/kb", 2000);
            bad.send("k " + CODE);
            bad.readText();
            check(false, "other socket paths are refused");
        } catch (Exception e) {
            check(true, "other socket paths are refused");
        }
        WsClient w = WsClient.connect("127.0.0.1", port, "/remote", 2000);
        w.send("k ZZZ999");
        check("K bad".equals(w.readText()), "wrong code -> K bad");
        w.close();

        WsClient ph = WsClient.connect("127.0.0.1", port, "/remote", 2000);
        ph.send("k abc234");   // lower case is fine
        check("K ok".equals(ph.readText()), "right code -> K ok");
        String id = ph.readText();
        TypeProto.Msg im = TypeProto.parse(id);
        check(im != null && im.kind == 'i' && im.name.equals("Living room TV"), "TV says its id and name");
        PadProto.Front f = PadProto.parseFront(ph.readText());
        check(f != null && f.on && f.name.equals("Saruki"), "TV says Saruki is in front");
        check(srv.clientCount() == 1, "one phone connected");

        // ---- trackpad ----
        ph.send(PadProto.move(3.25f, -2f) + "\n" + PadProto.click() + "\n" + PadProto.key("back") + "\nP x 1\n" + PadProto.scroll(0, 120) + "\n" + PadProto.drag(true));
        waitFor(() -> pad.size() >= 5, 2000);
        check(pad.equals(java.util.Arrays.asList("m 3.3 -2", "c", "k back", "s 0 120", "d 1")) || pad.equals(java.util.Arrays.asList("m 3.2 -2", "c", "k back", "s 0 120", "d 1")),
                "trackpad lines reach Saruki in order, bad line dropped: " + pad);
        front = false;
        pad.clear();
        ph.send(PadProto.move(5, 5));
        Thread.sleep(300);
        check(pad.isEmpty(), "no trackpad while Saruki is not in front");
        front = true;

        // ---- typing ----
        ph.send(TypeProto.want() + "\n" + TypeProto.edit(1, 7, 0, 0, 5, "hello") + "\n" + TypeProto.action(7));
        waitFor(() -> type.size() >= 3, 2000);
        check(type.size() == 3 && type.get(0).startsWith("w") && type.get(1).equals("e:7:hello") && type.get(2).startsWith("a:7"), "typing lines reach Saruki: " + type);
        srv.broadcast(TypeProto.open(true, 7, 1, 5, 5, 0, "Search", "Search YouTube", "hello"));
        TypeProto.Msg om = TypeProto.parse(ph.readText());
        check(om != null && om.kind == 'o' && om.auto && om.hint.equals("Search YouTube") && om.text.equals("hello"), "box open reaches the phone");

        // ---- camera ----
        ph.send(CamProto.ready());
        waitFor(() -> !cam.isEmpty(), 2000);
        check(cam.size() == 1 && cam.get(0).equals("y 1"), "phone ready as a camera -> Saruki told y 1");
        cam.clear();
        relay.fromApp(app, "o Ab12Cd34 va u");
        CamProto.Msg om2 = CamProto.parseForPhone(ph.readText());
        check(om2 != null && om2.kind == 'o' && om2.sid.equals("Ab12Cd34") && om2.media.equals("va") && om2.name.equals("Saruki"), "camera open reaches the phone");
        String sdp = TypeProto.b64("v=0 fake offer");
        ph.send("V s Ab12Cd34 " + sdp);
        waitFor(() -> !cam.isEmpty(), 2000);
        check(cam.size() == 1 && cam.get(0).equals("s Ab12Cd34 " + sdp), "phone offer reaches Saruki");
        cam.clear();
        front = false;
        relay.fromApp(app, "o Zz99Yy88 v u");
        check(cam.size() == 1 && cam.get(0).startsWith("h Zz99Yy88 err"), "camera refused while Saruki is not in front");
        front = true;
        cam.clear();

        // ---- phone leaves ----
        ph.close();
        waitFor(() -> cam.size() >= 2, 3000);
        check(cam.contains("x Ab12Cd34") && cam.contains("y 0"), "phone gone -> session ended and y 0: " + cam);
        waitFor(() -> srv.clientCount() == 0, 2000);
        check(srv.clientCount() == 0, "no phone connected after it left");

        // ---- lockout after many wrong codes ----
        String last = "";
        for (int i = 0; i < 6; i++) {
            WsClient x = WsClient.connect("127.0.0.1", port, "/remote", 2000);
            x.send("k QQQ" + (222 + i));
            last = x.readText();
            x.close();
        }
        check("K wait".equals(last), "too many wrong codes -> K wait");

        srv.stop();
        squat.close();
        System.out.println(fails == 0 ? "ALL PASS" : "FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    interface Cond { boolean ok(); }

    static void waitFor(Cond c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.ok() && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    /** {status line, body, headers}. */
    static String[] http(int port, String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream o = s.getOutputStream();
            o.write(("GET " + path + " HTTP/1.1\r\nHost: tv\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String all = b.toString("UTF-8");
            int i = all.indexOf("\r\n\r\n");
            String head = all.substring(0, i);
            return new String[]{head.split("\r\n")[0], all.substring(i + 4), head};
        }
    }
}
