package io.github.sarukibrowser.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sarukibrowser.link.CamRelay;
import io.github.sarukibrowser.link.PadProto;
import io.github.sarukibrowser.link.Pairing;
import io.github.sarukibrowser.link.TypeProto;

/**
 * v1.7: Saruki's own link to the Saruki Remote phone app: trackpad, typing and the phone camera, with no other app on
 * the TV. A small WebSocket server (port 8790, path /remote) that phones pair with once (6-character code, in the QR).
 * The TV announces itself on the home Wi-Fi ({@code _saruki._tcp}) so the phone finds it again when its address changes.
 *
 * Phones only drive Saruki while Saruki is on screen; they are told when it comes and goes ("P f 1" / "P f 0") and the
 * phone opens its trackpad by itself when Saruki comes to the front.
 *
 * The same server answers plain web requests: /pair?k=CODE is the page the TV's QR code opens on the phone
 * ("Open Saruki Remote", or install it straight from the TV), /SarukiRemote.apk is the phone app itself.
 */
final class RemoteLink implements WsServer.Handler {
    static final int PORT = 8790;
    static final String NSD_TYPE = "_saruki._tcp";
    /** The phone app bundled inside Saruki (assets), served to phones that don't have it yet. */
    static final String APK_ASSET = "SarukiRemote.apk";
    static final String REMOTE_PKG = "io.github.sarukibrowser.remote";
    static final String REPO = "https://github.com/SarukiBrowser/saruki";

    private final BrowserActivity a;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WsServer server;
    private volatile String code;
    final String id;
    private volatile boolean front;
    private WifiManager.WifiLock wifi;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    private final CamRelay relay = new CamRelay(app -> front);
    private final Map<WsServer.Client, CamRelay.Phone> camPhones = new ConcurrentHashMap<>();
    private final CamRelay.App camApp;

    RemoteLink(BrowserActivity a) {
        this.a = a;
        Prefs.init(a);
        String c = Prefs.p.getString("remoteCode", null);
        if (!Pairing.valid(c)) { c = Pairing.newCode(); Prefs.p.edit().putString("remoteCode", c).apply(); }
        code = c;
        String i = Prefs.p.getString("remoteId", null);
        if (i == null || !i.matches("[a-z0-9]{12}")) {
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < 12; k++) b.append("abcdefghijkmnpqrstuvwxyz23456789".charAt(r.nextInt(32)));
            i = b.toString();
            Prefs.p.edit().putString("remoteId", i).apply();
        }
        id = i;
        String pkg = a.getPackageName();
        camApp = new SarukiCam(this, pkg);
        try {
            WifiManager wm = a.getApplicationContext().getSystemService(WifiManager.class);
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = wm.createWifiLock(mode, "saruki:remote");
            wifi.setReferenceCounted(false);
        } catch (RuntimeException e) {
            wifi = null;
        }
    }

    /** Saruki as the camera "app" for the relay: its lines go to Calls on the main thread. */
    static final class SarukiCam implements CamRelay.App {
        private final RemoteLink l;
        private final String pkg;
        SarukiCam(RemoteLink l, String pkg) { this.l = l; this.pkg = pkg; }
        @Override public void cam(String line) { l.main.post(() -> { if (l.a.calls != null) l.a.calls.onLine(Calls.REMOTE, line); }); }
        @Override public String pkg() { return pkg; }
        @Override public String label() { return "Saruki"; }
    }

    // =====================================================================================
    // lifecycle (main thread)
    // =====================================================================================
    void start() {
        if (server != null) return;
        WsServer s = new WsServer(PORT, this);
        s.setGate(() -> code);
        if (!s.start()) return;
        server = s;
        relay.appHello(camApp);
        announce();
    }

    void stop() {
        unannounce();
        WsServer s = server;
        server = null;
        if (s != null) s.stop();
        release();
    }

    void resume() {
        front = true;
        broadcast(PadProto.front(true, "Saruki"));
        lock();
    }

    void pause() {
        front = false;
        broadcast(PadProto.front(false, ""));
        release();
    }

    private void lock() {
        try { if (wifi != null && front && phones() > 0) wifi.acquire(); } catch (RuntimeException ignored) { }
    }

    private void release() {
        try { if (wifi != null) wifi.release(); } catch (RuntimeException ignored) { }
    }

    int port() { WsServer s = server; return s == null ? PORT : s.port(); }

    int phones() { WsServer s = server; return s == null ? 0 : s.clientCount(); }

    void broadcast(String line) { WsServer s = server; if (s != null) s.broadcast(line); }

    String code() { return code; }

    /** The address the TV's QR code holds, or null without a network. */
    String pairUrl() {
        String ip = PhoneServer.ip();
        return ip == null || server == null ? null : "http://" + ip + ":" + port() + "/pair?k=" + code;
    }

    /** A new code: every paired phone is dropped and must pair again. */
    void newCode() {
        code = Pairing.newCode();
        Prefs.p.edit().putString("remoteCode", code).apply();
        WsServer s = server;
        if (s != null) s.dropAll();
    }

    /** The phone-camera line from Calls (main thread). */
    boolean cam(String line) {
        if (server == null) return false;
        return relay.fromApp(camApp, line);
    }

    /** The TV's name, shown on the phone. */
    String tvName() {
        String n = null;
        try { n = Settings.Global.getString(a.getContentResolver(), "device_name"); } catch (RuntimeException ignored) { }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n == null ? "TV" : n.trim();
    }

    // =====================================================================================
    // WsServer.Handler (network threads)
    // =====================================================================================
    @Override public void onOpen(WsServer.Client c) {
        c.send(TypeProto.id(id, tvName()));
        c.send(PadProto.front(front, front ? "Saruki" : ""));
        main.post(() -> { lock(); a.remoteChanged(true); });
    }

    @Override public void onText(WsServer.Client c, String text) {
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() < 3) continue;
            switch (line.charAt(0)) {
                case 'P': {
                    String l = PadProto.toApp(line);
                    if (l != null) main.post(() -> { if (front) a.onPad(l); });
                    break;
                }
                case 'T': {
                    TypeProto.Msg m = TypeProto.parse(line);
                    if (m != null && (m.kind == 'w' || m.kind == 'e' || m.kind == 'a')) main.post(() -> a.typing.fromPhone(m));
                    break;
                }
                case 'V':
                    relay.fromPhone(phone(c), line);
                    break;
                default:
            }
        }
    }

    @Override public void onClose(WsServer.Client c) {
        CamRelay.Phone p = camPhones.remove(c);
        if (p != null) relay.phoneGone(p);
        main.post(() -> { if (phones() == 0) release(); a.remoteChanged(false); });
    }

    private CamRelay.Phone phone(WsServer.Client c) {
        CamRelay.Phone p = camPhones.get(c);
        if (p == null) {
            p = c::send;
            CamRelay.Phone was = camPhones.putIfAbsent(c, p);
            if (was != null) p = was;
        }
        return p;
    }

    // ---- web pages ----
    private volatile byte[] apk;

    @Override public byte[] page(String path, String[] type) {
        String p = path, q = "";
        int i = path.indexOf('?');
        if (i >= 0) { p = path.substring(0, i); q = path.substring(i + 1); }
        if (p.equals("/" + APK_ASSET) || p.equals("/SarukiRemote-v" + remoteVersion() + ".apk")) {
            byte[] b = apk;
            if (b == null) apk = b = asset(APK_ASSET);
            if (b == null || b.length == 0) return null;
            type[0] = "application/vnd.android.package-archive";
            return b;
        }
        if (p.equals("/pair") || p.equals("/")) {
            type[0] = "text/html; charset=utf-8";
            String k = "";
            for (String kv : q.split("&")) if (kv.startsWith("k=")) k = kv.substring(2);
            boolean ok = WsServerCheck.matches(code, k);
            return (ok ? pairPage() : wrongPage()).getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Version of the bundled phone app (for the download name). */
    static String remoteVersion() { return BuildConfig.REMOTE_VERSION; }

    private String pairPage() {
        String ip = PhoneServer.ip();
        String hp = (ip == null ? "" : ip) + ":" + port();
        String deep = "sarukiremote://pair?h=" + hp + "&k=" + code + "&i=" + id;
        String dl = "/SarukiRemote-v" + remoteVersion() + ".apk";
        // installed: Chrome opens the app; not installed: the download from this TV instead of the Play Store
        String fallback = android.net.Uri.encode("http://" + hp + dl);
        String intent = "intent://pair?h=" + hp + "&k=" + code + "&i=" + id + "#Intent;scheme=sarukiremote;package=" + REMOTE_PKG
                + ";S.browser_fallback_url=" + fallback + ";end";
        String touch = a.phone != null ? a.phone.url() : null;
        String t = a.asset("pair.html");
        return t.replace("%INTENT%", intent).replace("%DEEP%", deep).replace("%APK%", dl)
                .replace("%CODE%", Pairing.pretty(code)).replace("%HOST%", hp).replace("%VER%", remoteVersion())
                .replace("%TOUCH%", touch == null ? "" : touch).replace("%REPO%", REPO);
    }

    private static String wrongPage() {
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Saruki Remote</title>"
                + "<body style='background:#0f0f14;color:#eee;font:17px system-ui;padding:28px'>"
                + "<h2 style='color:#FF5CA8'>Saruki Remote</h2><p>This link doesn't match the TV's pairing code (it may be old). On the TV, open Saruki → MENU → "
                + "Phone remote, and scan the QR code there.</p>";
    }

    private byte[] asset(String name) {
        try (InputStream in = a.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return b.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Constant-time code check for the pairing page (the socket check is inside WsServer). */
    static final class WsServerCheck {
        static boolean matches(String expected, String got) {
            if (expected == null || got == null) return false;
            byte[] x = expected.trim().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            byte[] y = got.trim().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(x, y);
        }
    }

    // =====================================================================================
    // finding the TV on the home Wi-Fi
    // =====================================================================================
    private void announce() {
        try {
            nsd = (NsdManager) a.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("Saruki on " + tvName());
            si.setServiceType(NSD_TYPE);
            si.setPort(port());
            si.setAttribute("id", id);
            si.setAttribute("v", "1.7");
            nsdListener = new NsdListener();
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            nsdListener = null;
        }
    }

    private void unannounce() {
        try { if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener); } catch (RuntimeException ignored) { }
        nsdListener = null;
    }

    static final class NsdListener implements NsdManager.RegistrationListener {
        @Override public void onRegistrationFailed(NsdServiceInfo si, int err) { }
        @Override public void onUnregistrationFailed(NsdServiceInfo si, int err) { }
        @Override public void onServiceRegistered(NsdServiceInfo si) { }
        @Override public void onServiceUnregistered(NsdServiceInfo si) { }
    }
}
