package io.github.sarukibrowser.remote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.sarukibrowser.link.PadProto;

/**
 * The phone as a trackpad for Saruki on the TV. Opens from the app, the notification, or by itself when Saruki comes
 * to the front on the TV.
 */
public class PadActivity extends Activity implements PadView.Host {
    static final String EXTRA_AUTO = "auto";

    private static final int BG = Look.BG, FIELD = Look.FIELD, TEXT = Look.TEXT, MUTED = Look.MUTED,
            ACCENT = Look.ACCENT, GOOD = Look.GOOD, WARN = Look.WARN;   // v1.0.2: Look tokens
    private static final long AUTO_CLOSE_MS = 3000;

    private Store store;
    private float d;
    private PadView pad;
    private TextView dot, title, sub;
    private boolean auto;
    private WifiManager.WifiLock wifi;
    private final Runnable autoClose = this::finish;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        d = getResources().getDisplayMetrics().density;
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build();
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = wm.createWifiLock(mode, "saruki:trackpad");
            wifi.setReferenceCounted(false);
        } catch (RuntimeException e) {
            wifi = null;
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        // opened again by hand: it stays open even if the app on the TV goes away
        if (!i.getBooleanExtra(EXTRA_AUTO, false)) auto = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.setSpeed(store.padSpeed());
        // Wi-Fi power saving can add 50-200 ms per packet; low-latency mode turns it off while this screen is open.
        try { if (wifi != null) wifi.acquire(); } catch (RuntimeException ignored) { }
        LinkService s = LinkService.instance;
        if (s == null) {
            LinkService.start(this);
            front(false, "");
            pad.postDelayed(this::attach, 800);
        } else {
            attach();
        }
        LinkService.onStatus = this::refresh;
    }

    private void attach() {
        LinkService s = LinkService.instance;
        if (s == null || isFinishing()) return;
        s.padActivity = this;
        front(s.padOn, s.padName);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pad.removeCallbacks(autoClose);
        pad.stopFling();
        LinkService s = LinkService.instance;
        if (s != null && s.padActivity == this) s.padActivity = null;
        if (LinkService.onStatus != null) LinkService.onStatus = null;
        try { if (wifi != null) wifi.release(); } catch (RuntimeException ignored) { }
    }

    private void refresh() {
        LinkService s = LinkService.instance;
        front(s != null && s.padOn, s == null ? "" : s.padName);
    }

    /** The TV says whether a trackpad app is in front. Main thread. */
    void front(boolean on, String name) {
        LinkService s = LinkService.instance;
        boolean linked = s != null && s.connected();
        if (!linked) {
            dot.setTextColor(WARN);
            title.setText("Not connected to the TV");
            sub.setText("Is the TV on, and is this phone on the same Wi-Fi?");
        } else if (on) {
            dot.setTextColor(GOOD);
            title.setText("Saruki");
            sub.setText("The trackpad is driving Saruki on the TV");
        } else {
            dot.setTextColor(ACCENT);
            title.setText("Open Saruki on the TV");
            sub.setText("The trackpad works while Saruki is on the TV's screen");
        }
        pad.setAlpha(linked && on ? 1f : 0.55f);
        pad.removeCallbacks(autoClose);
        if (auto && linked && !on) pad.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    // ---------------------------------------------------------------- PadView.Host

    @Override public void padMove(float dx, float dy) { LinkService s = LinkService.instance; if (s != null) s.padMove(dx, dy); }

    @Override public void padScroll(float dx, float dy) { LinkService s = LinkService.instance; if (s != null) s.padScroll(dx, dy); }

    @Override public void padEvent(String line) { LinkService s = LinkService.instance; if (s != null) s.padEvent(line); }

    // ---------------------------------------------------------------- layout

    private void build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(px(14), px(12), px(14), px(14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(6), 0, 0, px(10));
        dot = text("●", 11, MUTED, false);
        dot.setPadding(0, 0, px(10), 0);
        top.addView(dot);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        title = text("", 17, TEXT, true);
        title.setSingleLine(true);
        names.addView(title);
        sub = text("", 13, MUTED, false);
        sub.setSingleLine(true);
        sub.setPadding(0, px(2), 0, 0);
        names.addView(sub);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView close = Look.icon(this, R.drawable.ic_close, MUTED);
        close.setContentDescription("Close");
        close.setPadding(px(12), px(12), px(12), px(12));
        close.setBackground(Look.pressable(this, Look.round(this, 0x00000000, 24, 0, 0), 24));
        close.setClickable(true);
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(px(48), px(48)));
        col.addView(top);

        pad = new PadView(this, this);
        col.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout keys = new LinearLayout(this);
        keys.setPadding(0, px(12), 0, 0);
        keys.addView(key(R.drawable.ic_arrow_back, "Back", v -> padEvent(PadProto.key("back"))));
        keys.addView(key(R.drawable.ic_home, "Home", v -> padEvent(PadProto.key("home"))));
        keys.addView(key(R.drawable.ic_menu, "Menu", v -> padEvent(PadProto.key("menu"))));
        keys.addView(key(R.drawable.ic_play_pause, "Play", v -> padEvent(PadProto.key("pp"))));
        keys.addView(key(R.drawable.ic_keyboard, "Type", v -> startActivity(new Intent(this, TypeActivity.class)
                .putExtra(TypeActivity.EXTRA_WANT, true))));
        col.addView(keys);
        setContentView(col);
    }

    /** v1.0.2: a key under the pad: a Material Symbols icon over its name, hairline at rest, ripple on touch. */
    private LinearLayout key(int icon, String label, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(10), 0, px(9));
        t.setMinimumHeight(px(56));
        t.setBackground(Look.pressable(this, Look.round(this, Look.CARD, Look.R_MD, Look.LINE, 1), Look.R_MD));
        t.setClickable(true);
        t.setContentDescription(label);
        t.setHapticFeedbackEnabled(true);
        t.addView(Look.icon(this, icon, TEXT), new LinearLayout.LayoutParams(px(22), px(22)));
        TextView n = Look.text(this, label, 12, MUTED, 500);
        n.setPadding(0, px(5), 0, 0);
        n.setGravity(Gravity.CENTER);
        t.addView(n);
        t.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); l.onClick(v); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(px(4), 0, px(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        Look.init(this);
        t.setTypeface(bold ? Look.SEMIBOLD : Look.REGULAR);
        return t;
    }

    private int px(int dp) { return Math.round(dp * d); }
}
