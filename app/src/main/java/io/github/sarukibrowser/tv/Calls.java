package io.github.sarukibrowser.tv;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * v1.5: video calls in Saruki (Google Meet, Zoom, Jitsi, FaceTime links... on their websites).
 *
 * Cameras: a USB webcam on the TV (the Pixel in Webcam mode is one), or the phone's camera over Wi-Fi: v1.7 through
 * Saruki Remote (Saruki's own phone app, RemoteLink), or through TV Overlay Suite 1.7 if it is installed. Either way the
 * phone sends the camera straight to this page with WebRTC; only the set-up text goes through the link.
 * Microphones: Bluetooth earbuds that support calls, the phone over Wi-Fi, or the TV's own input.
 *
 * The page side is calls.js (injected before the page's own scripts): it stands in for getUserMedia, asks here which
 * camera and microphone to use ("plan"), and builds one stream from a real device and/or the phone. Messages go
 * through a WebMessageListener, which tells us exactly which site and frame is asking.
 *
 * Every site asks once ("Allow" is remembered); the choice can be changed in Settings → Video calls.
 */
final class Calls {
    static final String JS_NAME = "SarukiCall";
    private static final Pattern SID = Pattern.compile("[A-Za-z0-9]{8}");
    private static final Pattern FID = Pattern.compile("[A-Za-z0-9]{6,16}");
    static final int REQ_PERMS = 41;

    private final BrowserActivity a;
    private final AudioManager am;
    private final TextView bar;

    /** Where a phone camera comes from: Saruki Remote (preferred) or TV Overlay Suite. */
    static final int SUITE = 0, REMOTE = 1;
    /** Phone apps ready to be a camera, per source. */
    private final int[] phones = new int[2];
    boolean micMuted, camOff;
    private boolean routed;
    private String videoSrc = "", audioSrc = "";

    /** A frame (page or iframe) using the camera: how it answers, and how many tracks it has live. */
    private static final class Frame {
        JavaScriptReplyProxy proxy;
        WebView web;
        String host = "";
        int na, nv;
        boolean phone;
    }

    private static final class Session {
        String fid, facing, media;
        int src;
    }

    private final Map<String, Frame> frames = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    /** Sites allowed for this visit only ("Allow this time"). */
    private final Set<String> onceAllowed = new HashSet<>();
    /** Plans waiting for an answer from you (site prompt) or from Android (permissions). */
    private final Map<String, List<Runnable>> waitingSite = new HashMap<>();
    private final List<Runnable> waitingPerms = new ArrayList<>();
    private boolean askingPerms;

    Calls(BrowserActivity a) {
        this.a = a;
        am = a.getSystemService(AudioManager.class);
        bar = Ui.text(a, "", Ui.CAPTION, Ui.TEXT, true);
        bar.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
        bar.setBackground(Ui.round(0xF0251A1C, 20, 0x88FF6B5E, 1.5f));
        bar.setAlpha(0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, a.barH + Ui.dp(12), Ui.dp(16), 0);
        a.root.addView(bar, lp);
    }

    static boolean supported() { return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER); }

    /** A new WebView: the page script can talk to us. Must run before the page loads. */
    void attach(WebView w) {
        if (!supported()) return;
        try {
            WebViewCompat.addWebMessageListener(w, JS_NAME, Collections.singleton("*"), this::onMessage);
        } catch (RuntimeException ignored) { }
    }

    boolean active() {
        for (Frame f : frames.values()) if (f.na + f.nv > 0) return true;
        return !sessions.isEmpty();
    }

    boolean phoneVideo() {
        for (Frame f : frames.values()) if (f.phone && f.nv > 0) return true;
        return false;
    }

    // =====================================================================================
    // page -> Saruki (main thread)
    // =====================================================================================
    private void onMessage(WebView view, WebMessageCompat msg, Uri origin, boolean mainFrame, JavaScriptReplyProxy reply) {
        if (view != a.web || msg == null || msg.getData() == null || msg.getData().length() > 60000) return;
        JSONObject m;
        try { m = new JSONObject(msg.getData()); } catch (Exception e) { return; }
        String fid = m.optString("f");
        if (!FID.matcher(fid).matches()) return;
        String scheme = origin == null ? "" : String.valueOf(origin.getScheme());
        if (!"https".equals(scheme) && !"http".equals(scheme)) return;
        String host = Prefs.host(origin.toString());
        if (host.isEmpty()) return;
        Frame f = frames.get(fid);
        if (f == null) { f = new Frame(); frames.put(fid, f); }
        f.proxy = reply;
        f.web = view;
        f.host = host;
        try {
            switch (m.optString("t")) {
                case "hello": post(f, avail()); break;
                case "plan": plan(f, m); break;
                case "open": open(fid, f, m); break;
                case "sdp": sdp(fid, m); break;
                case "close": close(m.optString("sid"), true); break;
                case "flip": flip(m.optString("sid")); break;
                case "live":
                    f.na = Math.max(0, m.optInt("na"));
                    f.nv = Math.max(0, m.optInt("nv"));
                    f.phone = m.optBoolean("phone");
                    refresh();
                    break;
                case "nomic":
                    a.toast("🎤 No microphone found · connect earbuds that support calls, or use the phone (Settings → Video calls)");
                    break;
                default:
            }
        } catch (Exception ignored) { }
    }

    private JSONObject avail() throws Exception {
        return new JSONObject().put("t", "avail").put("phone", phoneOk()).put("usb", cameras() > 0);
    }

    private void post(Frame f, JSONObject o) {
        if (f == null || f.proxy == null) return;
        try { f.proxy.postMessage(o.toString()); } catch (RuntimeException ignored) { }
    }

    // ---- which camera and microphone ----
    static final String PHONE_CAM = "saruki-phone-camera", PHONE_MIC = "saruki-phone-mic";

    private void plan(Frame f, JSONObject m) {
        int q = m.optInt("q");
        boolean wantV = m.optBoolean("v"), wantA = m.optBoolean("a");
        String vid = m.optString("vid"), aid = m.optString("aid");
        whenAllowed(f.host, allowed -> {
            if (!allowed) { reply(f, q, false, "", "", "NotAllowedError", "Camera and microphone are blocked for " + f.host); return; }
            boolean phone = phoneOk();
            int usb = cameras();
            String video = "", audio = "";
            if (wantV) {
                int pref = Prefs.callCam();
                if (PHONE_CAM.equals(vid)) video = phone ? "phone" : "";
                else if (!vid.isEmpty()) video = usb > 0 ? "usb" : "";
                else if (pref == 2) video = phone ? "phone" : usb > 0 ? "usb" : "";
                else video = usb > 0 ? "usb" : phone ? "phone" : "";
                if (video.isEmpty()) {
                    reply(f, q, false, "", "", "NotFoundError", "No camera");
                    a.toast("📷 No camera · open Saruki Remote on the phone, or plug the phone into the TV in Webcam mode");
                    return;
                }
            }
            boolean bt = false;
            if (wantA) {
                int pref = Prefs.callMic();
                bt = btMic() != null;
                if (PHONE_MIC.equals(aid)) audio = phone ? "phone" : "real";
                else if (pref == 3) audio = "real";                        // the TV's own input, as Android picks it
                else if (pref == 2) audio = phone ? "phone" : "real";
                else audio = bt ? "real" : phone ? "phone" : "real";       // automatic / earbuds: earbuds, else phone
                if (!audio.equals("real") || pref == 3 || PHONE_MIC.equals(aid)) bt = false;
            }
            final String fv = video, fa = audio;
            final boolean useBt = bt;
            List<String> perms = new ArrayList<>();
            if (fv.equals("usb")) perms.add(Manifest.permission.CAMERA);
            if (fa.equals("real")) perms.add(Manifest.permission.RECORD_AUDIO);
            if (useBt && Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            withPerms(perms, () -> {
                if (fv.equals("usb") && !granted(Manifest.permission.CAMERA)) {
                    reply(f, q, false, "", "", "NotAllowedError", "Saruki isn't allowed to use the camera");
                    a.toast("📷 Saruki needs the camera permission (Android settings → Apps → Saruki → Permissions)");
                    return;
                }
                String audio2 = fa;
                if (audio2.equals("real") && !granted(Manifest.permission.RECORD_AUDIO)) audio2 = phoneOk() ? "phone" : "";
                if (useBt && audio2.equals("real")) routeBt();
                videoSrc = fv.equals("usb") ? "USB webcam" : fv.equals("phone") ? "Phone camera" : videoSrc;
                audioSrc = audio2.equals("phone") ? "Phone mic" : audio2.equals("real") ? (routed ? "Earbuds" : "TV input") : audioSrc;
                reply(f, q, true, fv, audio2, "", "");
            });
        });
    }

    private void reply(Frame f, int q, boolean ok, String video, String audio, String err, String msg) {
        try {
            post(f, new JSONObject().put("t", "plan").put("q", q).put("ok", ok).put("video", video).put("audio", audio)
                    .put("err", err).put("msg", msg).put("muteA", micMuted).put("muteV", camOff));
        } catch (Exception ignored) { }
    }

    // ---- site permission ----
    interface Decision { void run(boolean allowed); }

    boolean allowed(String host) { return "y".equals(Prefs.callSite(host)) || onceAllowed.contains(host); }

    private void whenAllowed(String host, Decision d) {
        String s = Prefs.callSite(host);
        if ("n".equals(s)) { d.run(false); return; }
        if (allowed(host)) { d.run(true); return; }
        List<Runnable> w = waitingSite.get(host);
        boolean first = w == null;
        if (first) { w = new ArrayList<>(); waitingSite.put(host, w); }
        // the answer is read again when the question is answered
        w.add(() -> d.run(allowed(host)));
        if (first) a.panels.askCamera(host, choice -> {
            if (choice == 1) Prefs.setCallSite(host, "y");
            else if (choice == 2) onceAllowed.add(host);
            else if (choice == 3) Prefs.setCallSite(host, "n");
            List<Runnable> l = waitingSite.remove(host);
            if (l != null) for (Runnable r : l) r.run();
            if (choice == 3) a.toast("📷 Blocked for " + host + " · Settings → Video calls changes it");
        });
    }

    /** WebView asks for the camera / microphone itself (after a plan, or on a page the script couldn't reach). */
    void onPermissionRequest(PermissionRequest r) {
        Uri o = r.getOrigin();
        String host = o == null ? "" : Prefs.host(o.toString());
        List<String> media = new ArrayList<>(), other = new ArrayList<>();
        for (String res : r.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) media.add(res);
            else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) other.add(res);
        }
        if (media.isEmpty()) {
            if (other.isEmpty()) r.deny(); else r.grant(other.toArray(new String[0]));
            return;
        }
        whenAllowed(host, ok -> {
            if (!ok) { r.deny(); return; }
            List<String> perms = new ArrayList<>();
            if (media.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) perms.add(Manifest.permission.CAMERA);
            if (media.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) perms.add(Manifest.permission.RECORD_AUDIO);
            withPerms(perms, () -> {
                List<String> g = new ArrayList<>(other);
                if (media.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && granted(Manifest.permission.CAMERA)) g.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                if (media.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && granted(Manifest.permission.RECORD_AUDIO)) g.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                try { if (g.isEmpty()) r.deny(); else r.grant(g.toArray(new String[0])); } catch (RuntimeException ignored) { }
            });
        });
    }

    // ---- Android permissions ----
    private boolean granted(String p) { return a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private void withPerms(List<String> perms, Runnable then) {
        List<String> need = new ArrayList<>();
        for (String p : perms) if (!granted(p)) need.add(p);
        if (need.isEmpty()) { then.run(); return; }
        waitingPerms.add(then);
        if (askingPerms) return;
        askingPerms = true;
        a.requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    void onPermissionsResult() {
        askingPerms = false;
        List<Runnable> l = new ArrayList<>(waitingPerms);
        waitingPerms.clear();
        for (Runnable r : l) r.run();
    }

    // ---- phone camera sessions ----
    private void open(String fid, Frame f, JSONObject m) {
        String sid = m.optString("sid"), media = m.optString("kind"), facing = m.optString("facing");
        if (!SID.matcher(sid).matches() || !(media.equals("v") || media.equals("a") || media.equals("va")) || !(facing.equals("u") || facing.equals("e"))) return;
        if (!allowed(f.host) || sessions.containsKey(sid)) { state(f, sid, "deny", "Not allowed"); return; }
        int src = phones[REMOTE] > 0 ? REMOTE : a.pad.linked() && phones[SUITE] > 0 ? SUITE : -1;
        if (src < 0) { state(f, sid, "err", "No phone ready: open Saruki Remote on your phone"); return; }
        Session s = new Session();
        s.fid = fid;
        s.media = media;
        s.facing = facing;
        s.src = src;
        sessions.put(sid, s);
        if (!send(src, "o " + sid + " " + media + " " + facing)) { sessions.remove(sid); state(f, sid, "err", "No phone ready: open Saruki Remote on your phone"); return; }
        a.toast("📱 Starting the phone " + (media.contains("v") ? "camera" : "microphone") + "…");
        refresh();
    }

    private void sdp(String fid, JSONObject m) {
        String sid = m.optString("sid"), sdp = m.optString("sdp");
        Session s = sessions.get(sid);
        if (s == null || !s.fid.equals(fid) || sdp.isEmpty() || sdp.length() > 30000) return;
        send(s.src, "s " + sid + " " + b64(sdp));
    }

    /** A camera line to the phone, by the session's source. */
    private boolean send(int src, String line) {
        return src == REMOTE ? a.remote != null && a.remote.cam(line) : a.pad.cam(line);
    }

    private void close(String sid, boolean tellPhone) {
        Session s = sessions.remove(sid);
        if (s == null) return;
        if (tellPhone) send(s.src, "x " + sid);
        refresh();
    }

    void flip(String sid) {
        Session s = sessions.get(sid);
        if (s == null) return;
        s.facing = s.facing.equals("u") ? "e" : "u";
        send(s.src, "f " + sid + " " + s.facing);
    }

    /** Switches every phone camera in use between front and back (quick menu). */
    void flipAll() {
        boolean any = false;
        for (Map.Entry<String, Session> e : sessions.entrySet()) if (e.getValue().media.contains("v")) { flip(e.getKey()); any = true; }
        a.toast(any ? "🔄 Switching the phone camera" : "No phone camera in use");
    }

    private void state(Frame f, String sid, String st, String msg) {
        try { post(f, new JSONObject().put("t", "state").put("sid", sid).put("s", st).put("msg", msg == null ? "" : msg)); } catch (Exception ignored) { }
    }

    // =====================================================================================
    // phone (through Saruki Remote or TV Overlay Suite) -> Saruki (main thread)
    // =====================================================================================
    void onSuite(String line) { onLine(SUITE, line); }

    void onLine(int src, String line) {
        String[] p = line.split(" ");
        if (p.length < 2) return;
        if (p[0].equals("y")) {
            int n;
            try { n = Math.max(0, Integer.parseInt(p[1])); } catch (NumberFormatException e) { n = 0; }
            phones[src] = n;
            if (n == 0) for (Map.Entry<String, Session> e : new ArrayList<>(sessions.entrySet())) if (e.getValue().src == src) ended(e.getKey(), "The phone went away");
            for (Frame f : frames.values()) { try { post(f, avail()); } catch (Exception ignored) { } }
            return;
        }
        String sid = p[1];
        Session s = sessions.get(sid);
        if (s == null || s.src != src) return;
        Frame f = frames.get(s.fid);
        switch (p[0]) {
            case "s":
                if (p.length != 3) return;
                try { post(f, new JSONObject().put("t", "sdp").put("sid", sid).put("sdp", unb64(p[2]))); } catch (Exception ignored) { }
                break;
            case "h": {
                if (p.length < 3) return;
                String msg = p.length >= 4 ? unb64(p[3]) : "";
                state(f, sid, p[2], msg);
                if (p[2].equals("wait")) a.toast("📱 Answer on your phone: allow the camera for Saruki");
                else if (p[2].equals("live")) a.toast("📱 Phone " + (s.media.contains("v") ? "camera" : "microphone") + " connected");
                else if (p[2].equals("deny")) { a.toast("📱 " + (msg.isEmpty() ? "The phone said no" : msg)); sessions.remove(sid); refresh(); }
                else if (p[2].equals("err")) { a.toast("📱 " + (msg.isEmpty() ? "The phone camera failed" : msg)); sessions.remove(sid); refresh(); }
                break;
            }
            case "x":
                ended(sid, "");
                break;
            default:
        }
    }

    private void ended(String sid, String msg) {
        Session s = sessions.remove(sid);
        if (s == null) return;
        state(frames.get(s.fid), sid, "closed", msg);
        refresh();
    }

    // =====================================================================================
    // call controls
    // =====================================================================================
    void toggleMic() {
        micMuted = !micMuted;
        broadcast("mute", "on", micMuted);
        a.toast(micMuted ? "🎤 Microphone off" : "🎤 Microphone on");
        refresh();
    }

    void toggleCamera() {
        camOff = !camOff;
        broadcast("vid", "off", camOff);
        a.toast(camOff ? "📷 Camera off" : "📷 Camera on");
        refresh();
    }

    void hangUp() {
        broadcast("hangup", "x", true);
        for (String sid : new ArrayList<>(sessions.keySet())) close(sid, true);
        frames.clear();
        a.toast("📞 Call ended");
        refresh();
        Tab t = a.cur;
        // closing the tab is what really leaves the call (the site's own connection ends with the page)
        if (t != null) a.h.postDelayed(() -> { if (a.cur == t) a.closeTab(t); }, 250);
    }

    private void broadcast(String type, String key, boolean v) {
        for (Frame f : frames.values()) {
            if (f.na + f.nv == 0 && !"hangup".equals(type)) continue;
            try { post(f, new JSONObject().put("t", type).put(key, v)); } catch (Exception ignored) { }
        }
    }

    /** The page went away (navigation, tab closed): its camera and sessions end with it. */
    void reset() {
        for (String sid : new ArrayList<>(sessions.keySet())) close(sid, true);
        frames.clear();
        refresh();
    }

    // =====================================================================================
    // the call bar, the screen and the audio route
    // =====================================================================================
    private boolean wasActive;
    private long inactiveSince;

    void refresh() {
        boolean on = active();
        if (on) {
            String cam = camOff ? "📷 off" : "📷 " + (videoSrc.isEmpty() ? "–" : videoSrc);
            String mic = micMuted ? "🎤 muted" : "🎤 " + (audioSrc.isEmpty() ? "–" : audioSrc);
            boolean anyV = false, anyA = false;
            for (Frame f : frames.values()) { anyV |= f.nv > 0; anyA |= f.na > 0; }
            bar.setText("● On a call   " + (anyV ? cam + "   " : "") + (anyA ? mic + "   " : "") + "⏯ mute · MENU");
            if (!wasActive) bar.animate().alpha(1).setDuration(200).start();
        } else if (wasActive) {
            bar.animate().alpha(0).setDuration(300).start();
            inactiveSince = SystemClock.uptimeMillis();
            a.h.postDelayed(this::afterCall, 2500);
        }
        wasActive = on;
        a.setKeepOn(a.pageVideo);
    }

    private void afterCall() {
        if (active() || SystemClock.uptimeMillis() - inactiveSince < 2400) return;
        unrouteBt();
        micMuted = false;
        camOff = false;
        videoSrc = "";
        audioSrc = "";
    }

    /** Bluetooth earbuds that can be a call microphone (hands-free), or null. Android 12+ only. */
    private AudioDeviceInfo btMic() {
        if (Build.VERSION.SDK_INT < 31 || am == null) return null;
        try {
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) return d;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    boolean earbudsReady() { return btMic() != null; }

    private void routeBt() {
        if (Build.VERSION.SDK_INT < 31 || am == null || routed) return;
        AudioDeviceInfo d = btMic();
        if (d == null) return;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            routed = am.setCommunicationDevice(d);
            if (!routed) am.setMode(AudioManager.MODE_NORMAL);
        } catch (RuntimeException e) {
            routed = false;
        }
    }

    private void unrouteBt() {
        if (!routed || Build.VERSION.SDK_INT < 31 || am == null) return;
        try { am.clearCommunicationDevice(); am.setMode(AudioManager.MODE_NORMAL); } catch (RuntimeException ignored) { }
        routed = false;
    }

    void release() { reset(); unrouteBt(); }

    /** "Allow this time" lasts until the tab is closed or switched. */
    void forgetOnce() { onceAllowed.clear(); }

    int cameras() {
        try {
            CameraManager cm = a.getSystemService(CameraManager.class);
            return cm == null ? 0 : cm.getCameraIdList().length;
        } catch (Exception e) {
            return 0;
        }
    }

    boolean phoneOk() { return phones[REMOTE] > 0 || (a.pad.linked() && phones[SUITE] > 0); }

    /** A short summary for Settings. */
    String devicesNow() {
        int usb = cameras();
        return (usb > 0 ? "USB webcam plugged in" : "No USB webcam") + " · " + (phoneOk() ? "phone ready" : "no phone")
                + " · " + (earbudsReady() ? "earbuds mic ready" : "no earbuds mic");
    }

    static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String unb64(String s) {
        try { return new String(Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return ""; }
    }

}
