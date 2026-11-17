package io.github.sarukibrowser.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** All settings, global and per site. Per-site keys use the host without "www.". */
final class Prefs {
    static SharedPreferences p;

    static void init(Context c) { if (p == null) p = c.getSharedPreferences("saruki", Context.MODE_PRIVATE); }

    static String host(String url) {
        if (url == null) return "";
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return "";
            h = h.toLowerCase();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) { return ""; }
    }

    // ---- global settings ----
    static final String[] ENGINES = {"Google", "DuckDuckGo", "Bing", "Brave"};
    static int engine() { return p.getInt("engine", 0); }
    static void setEngine(int i) { p.edit().putInt("engine", i).apply(); }
    static String searchUrl(String q) {
        String e = android.net.Uri.encode(q);
        switch (engine()) {
            case 1: return "https://duckduckgo.com/?q=" + e;
            case 2: return "https://www.bing.com/search?q=" + e;
            case 3: return "https://search.brave.com/search?q=" + e;
            default: return "https://www.google.com/search?q=" + e;
        }
    }

    static int cursorSpeed() { return p.getInt("cursorSpeed", 3); }          // 1..5
    static void setCursorSpeed(int v) { p.edit().putInt("cursorSpeed", v).apply(); }
    static boolean desktopDefault() { return p.getBoolean("desktopDefault", true); }
    static void setDesktopDefault(boolean v) { p.edit().putBoolean("desktopDefault", v).apply(); }
    static int defaultZoom(boolean desktop) { return p.getInt(desktop ? "zoomD" : "zoomM", desktop ? 150 : 100); }
    static void setDefaultZoom(boolean desktop, int z) { p.edit().putInt(desktop ? "zoomD" : "zoomM", z).apply(); }
    static boolean flag(String k, boolean def) { return p.getBoolean(k, def); }
    static void setFlag(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }
    static boolean adblock() { return flag("adblock", true); }
    static boolean blockPopups() { return flag("popups", true); }
    static boolean hideCookies() { return flag("cookies", true); }
    static boolean ytSkip() { return flag("ytskip", true); }
    static boolean dark() { return flag("dark", false); }
    static boolean jumpMode() { return flag("jump", false); }
    static int overscan() { return p.getInt("overscan", 0); }                 // percent of each edge
    static void setOverscan(int v) { p.edit().putInt("overscan", v).apply(); }

    // ---- video calls (v1.5) ----
    /** Sites whose video calls need the desktop site in a TV browser; they open as desktop unless you chose otherwise. */
    static final String[] CALL_HOSTS = {"meet.google.com", "zoom.us", "zoom.com", "meet.jit.si", "8x8.vc",
            "facetime.apple.com", "whereby.com", "teams.microsoft.com", "teams.live.com"};
    static boolean callHost(String host) {
        if (host == null) return false;
        for (String c : CALL_HOSTS) if (host.equals(c) || host.endsWith("." + c)) return true;
        return false;
    }
    static final String[] CAMS = {"Automatic (USB webcam, else phone over Wi-Fi)", "USB webcam", "Phone over Wi-Fi"};
    static final String[] MICS = {"Automatic (earbuds, else phone over Wi-Fi)", "Bluetooth earbuds / headset", "Phone over Wi-Fi", "TV's own input"};
    static int callCam() { return Math.max(0, Math.min(2, p.getInt("callCam", 0))); }
    static void setCallCam(int v) { p.edit().putInt("callCam", v).apply(); }
    static int callMic() { return Math.max(0, Math.min(3, p.getInt("callMic", 0))); }
    static void setCallMic(int v) { p.edit().putInt("callMic", v).apply(); }
    /** Camera and microphone per site: "y" allowed, "n" blocked, null = ask. */
    static String callSite(String host) { return p.getString("call_" + host, null); }
    static void setCallSite(String host, String v) {
        if (v == null) p.edit().remove("call_" + host).apply(); else p.edit().putString("call_" + host, v).apply();
    }
    static java.util.List<String> callSites() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String k : p.getAll().keySet()) if (k.startsWith("call_")) l.add(k.substring(5));
        java.util.Collections.sort(l);
        return l;
    }

    // ---- per-site settings ----
    static boolean desktop(String host) {
        String m = p.getString("m_" + host, null);
        if (m == null && callHost(host)) return true;
        return m == null ? desktopDefault() : m.equals("d");
    }
    static void setDesktop(String host, boolean d) { p.edit().putString("m_" + host, d ? "d" : "m").apply(); }
    static int zoom(String host, boolean desktop) { return p.getInt((desktop ? "zd_" : "zm_") + host, defaultZoom(desktop)); }
    static void setZoom(String host, boolean desktop, int z) { p.edit().putInt((desktop ? "zd_" : "zm_") + host, z).apply(); }
    /** Pop-ups and redirects allowed on this site (the shield steps aside there). */
    static boolean popupsAllowed(String host) { return host != null && !host.isEmpty() && p.getBoolean("pop_" + host, false); }
    static void allowPopups(String host) { setPopupsAllowed(host, true); }
    static void setPopupsAllowed(String host, boolean v) { if (v) p.edit().putBoolean("pop_" + host, true).apply(); else p.edit().remove("pop_" + host).apply(); }
    /** Strict shield (default): only a click on a real link may open a pop-up or send you to another site. */
    static boolean strictShield() { return flag("strictShield", true); }
    static boolean adsAllowed(String host) { return p.getBoolean("ads_" + host, false); }
    static void setAdsAllowed(String host, boolean v) { p.edit().putBoolean("ads_" + host, v).apply(); }

    /**
     * Width in CSS pixels the page is laid out at. The TV screen is 1920 device pixels wide; at 150% a page is laid out
     * 1280 px wide and scaled up, which reads well from a sofa. 0 means "leave the page's own viewport alone".
     */
    static int layoutWidth(String host, boolean desktop) {
        int z = zoom(host, desktop);
        if (!desktop && z == 100) return 0;
        int base = desktop ? 1920 : 960;
        return Math.round(base * 100f / z);
    }

    // ---- video resume positions ----
    static long resumeAt(String url) { return p.getLong("pos_" + url.hashCode(), 0); }
    static void saveResume(String url, long ms) { p.edit().putLong("pos_" + url.hashCode(), ms).apply(); }
}
