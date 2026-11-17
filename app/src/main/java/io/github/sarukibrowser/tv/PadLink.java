package io.github.sarukibrowser.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/**
 * v1.4: the phone trackpad from TV Overlay Suite's phone app. The suite (already paired with the phone) hands the
 * finger moves to Saruki with Android's own app-to-app messages: no network hop inside the TV, no token. Saruki binds
 * to the suite's PadService by its exact package and class, and only acts on messages the suite's app id sent.
 *
 * Saruki -> suite: HELLO, FRONT (arg1 1 = Saruki in front, 0 = left it; data "name").
 * Suite -> Saruki: LINE with data "l": "m DX DY", "s DX DY", "c", "r", "d 1", "d 0", "k back|home|menu|pp"
 * (distances in pixels of a 1920-wide screen).
 */
final class PadLink {
    interface Commands { void onPad(String line); }
    /** v1.5: phone-camera lines from the suite (see Calls). */
    interface CamLines { void onCam(String line); }

    static final String SUITE = "io.github.tvoverlaysuite";
    private static final ComponentName SERVICE = new ComponentName(SUITE, SUITE + ".PadService");
    private static final int HELLO = 1, FRONT = 2, CAM = 3, LINE = 10, CAM_LINE = 11;

    private final Context ctx;
    private final Commands cmd;
    private final CamLines cams;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger me;
    private Messenger suite;
    private int suiteUid = -1;
    private boolean bound, front, closed;
    private WifiManager.WifiLock wifi;
    private final Conn conn = new Conn(this);

    PadLink(Context c, Commands cmd, CamLines cams) {
        ctx = c.getApplicationContext();
        this.cmd = cmd;
        this.cams = cams;
        me = new Messenger(new Handler(Looper.getMainLooper(), this::fromSuite));
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = wm.createWifiLock(mode, "saruki:trackpad");
            wifi.setReferenceCounted(false);
        } catch (RuntimeException e) {
            wifi = null;
        }
    }

    boolean linked() { return suite != null; }

    /** Activity resumed: Saruki is in front. Main thread. */
    void resume() {
        front = true;
        if (suite != null) tellFront();
        else bind();
        wifiLock();
    }

    /** Activity paused: the trackpad goes back to other apps. The binding stays for a fast return. Main thread. */
    void pause() {
        front = false;
        tellFront();
        try { if (wifi != null) wifi.release(); } catch (RuntimeException ignored) { }
    }

    void close() {
        closed = true;
        front = false;
        tellFront();
        if (bound) { try { ctx.unbindService(conn); } catch (RuntimeException ignored) { } }
        bound = false;
        suite = null;
        try { if (wifi != null) wifi.release(); } catch (RuntimeException ignored) { }
    }

    private void bind() {
        if (bound || closed) return;
        try {
            suiteUid = ctx.getPackageManager().getApplicationInfo(SUITE, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return;   // TV Overlay Suite not installed: nothing to do (checked again on the next resume)
        }
        try {
            bound = ctx.bindService(new Intent().setComponent(SERVICE), conn, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            bound = false;
        }
    }

    /** Wi-Fi power saving can hold each packet from the phone for 100 ms or more; low-latency mode turns it off while
     *  Saruki is in front and the trackpad link is up. */
    private void wifiLock() {
        try { if (wifi != null && front && suite != null) wifi.acquire(); } catch (RuntimeException ignored) { }
    }

    private void tellFront() {
        Messenger s = suite;
        if (s == null) return;
        Message m = Message.obtain(null, FRONT, front ? 1 : 0, 0);
        Bundle b = new Bundle();
        b.putString("name", "Saruki");
        m.setData(b);
        m.replyTo = me;
        try { s.send(m); } catch (RemoteException e) { lost(); }
    }

    private void connected(IBinder b) {
        suite = new Messenger(b);
        Message hello = Message.obtain(null, HELLO);
        hello.replyTo = me;
        try { suite.send(hello); } catch (RemoteException e) { lost(); return; }
        tellFront();
        wifiLock();
    }

    private void lost() {
        suite = null;
        cams.onCam("y 0");   // no suite: no phone camera until it is back
        try { if (wifi != null) wifi.release(); } catch (RuntimeException ignored) { }
    }

    /** v1.5: sends a phone-camera line to the suite. False when the suite isn't linked. Main thread. */
    boolean cam(String line) {
        Messenger s = suite;
        if (s == null) return false;
        Message m = Message.obtain(null, CAM);
        Bundle b = new Bundle();
        b.putString("l", line);
        m.setData(b);
        m.replyTo = me;
        try { s.send(m); return true; } catch (RemoteException e) { lost(); return false; }
    }

    /** Main thread: a trackpad line, only from the suite's own app id, only while Saruki is in front. Phone-camera
     *  lines (also only from the suite) arrive in front or not: a call set-up must not stall on a short pause. */
    private boolean fromSuite(Message m) {
        if (m.what == CAM_LINE && m.sendingUid == suiteUid) {
            Bundle b = m.peekData();
            String l = b == null ? null : b.getString("l");
            if (l != null && l.length() <= 60000) cams.onCam(l);
            return true;
        }
        if (m.what != LINE || m.sendingUid != suiteUid || !front) return true;
        Bundle b = m.peekData();
        String l = b == null ? null : b.getString("l");
        if (l != null && l.length() <= 64) cmd.onPad(l);
        return true;
    }

    /** The binding callbacks. */
    static final class Conn implements ServiceConnection {
        private final PadLink p;
        Conn(PadLink p) { this.p = p; }
        @Override public void onServiceConnected(ComponentName n, IBinder b) { p.connected(b); }
        @Override public void onServiceDisconnected(ComponentName n) { p.lost(); }   // the system re-binds by itself
        @Override public void onBindingDied(ComponentName n) {
            p.lost();
            try { p.ctx.unbindService(this); } catch (RuntimeException ignored) { }
            p.bound = false;
            if (p.front) p.main.postDelayed(p::bind, 1000);
        }
    }
}
