package io.github.sarukibrowser.tv;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v1.7: the slim bar always shown above pages: Back, Forward, Stop, Reload and the address. Clicked with the pointer
 * (remote OK or the phone trackpad), like anything on the page. Clicking the address opens the address bar panel
 * (search or type a site; mirrored on Saruki Remote). Hidden on Home and when a video is full screen.
 * v1.8.2: real icons (Material Symbols) instead of text symbols, a lock icon before secure addresses, and the bar's
 * items can be reached one by one (link jumping: UP at the top of a page lands on the address).
 */
final class TopBar {
    /** Height of the bar; pages start below it. */
    static final int HEIGHT_DP = 40;

    private final BrowserActivity a;
    final LinearLayout view;
    private final ImageView back, fwd, stop, reload;
    private final TextView addr;
    private boolean loading;
    private Drawable lock;

    TopBar(BrowserActivity a) {
        this.a = a;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackgroundColor(0xF2111318);
        view.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        back = button(R.drawable.ic_arrow_back, "Back");
        fwd = button(R.drawable.ic_arrow_forward, "Forward");
        stop = button(R.drawable.ic_close, "Stop");
        reload = button(R.drawable.ic_refresh, "Reload");
        addr = Ui.text(a, "", Ui.CAPTION, Ui.TEXT, false);
        addr.setSingleLine(true);
        addr.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addr.setGravity(Gravity.CENTER_VERTICAL);
        addr.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        addr.setBackground(Ui.round(0xFF1F232C, 14, 0x22FFFFFF, 1));
        addr.setContentDescription("Address: search or type a web address");
        addr.setCompoundDrawablePadding(Ui.dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(30), 1);
        lp.leftMargin = Ui.dp(8);
        view.addView(addr, lp);
        back.setOnClickListener(v -> { if (a.web != null && a.web.canGoBack()) a.web.goBack(); });
        fwd.setOnClickListener(v -> { if (a.web != null && a.web.canGoForward()) a.web.goForward(); });
        stop.setOnClickListener(v -> { if (a.web != null) a.web.stopLoading(); setLoading(false); });
        reload.setOnClickListener(v -> { if (a.web != null) a.web.reload(); });
        addr.setOnClickListener(v -> a.panels.address(a.cur != null && !BrowserActivity.HOME.equals(a.cur.url) ? a.cur.url : ""));
    }

    /** v1.8.2: an icon button (20dp icon in the same 34x30dp box the text symbols had). */
    private ImageView button(int iconRes, String label) {
        ImageView t = new ImageView(a);
        t.setImageResource(iconRes);
        t.setColorFilter(Ui.TEXT);
        t.setContentDescription(label);
        t.setBackground(Ui.round(0x00000000, 10, 0x00000000, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(30));
        lp.rightMargin = Ui.dp(2);
        view.addView(t, lp);
        // the vector is drawn on a 24dp grid; shown at 20dp
        t.setPadding(Ui.dp(7), Ui.dp(5), Ui.dp(7), Ui.dp(5));
        t.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return t;
    }

    static FrameLayout.LayoutParams params() {
        return new FrameLayout.LayoutParams(-1, Ui.dp(HEIGHT_DP), Gravity.TOP);
    }

    void setLoading(boolean on) {
        loading = on;
        refresh();
    }

    /** Address and button states for the current tab. */
    void refresh() {
        boolean page = a.web != null && a.cur != null && a.cur.url != null && !BrowserActivity.HOME.equals(a.cur.url);
        dim(back, page && a.web.canGoBack());
        dim(fwd, page && a.web.canGoForward());
        dim(stop, page && loading);
        dim(reload, page);
        String u = page ? (a.web.getUrl() != null ? a.web.getUrl() : a.cur.url) : "";
        boolean secure = u.startsWith("https://");
        String shown = u.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        addr.setText(shown.isEmpty() ? "Search or type a web address" : shown);
        addr.setTextColor(shown.isEmpty() ? Ui.DIM : Ui.TEXT);
        // v1.8.2: a 14dp lock icon before the address instead of the lock emoji
        if (secure && !shown.isEmpty()) {
            if (lock == null) {
                lock = a.getDrawable(R.drawable.ic_lock).mutate();
                lock.setBounds(0, 0, Ui.dp(14), Ui.dp(14));
                lock.setTint(Ui.DIM);
            }
            addr.setCompoundDrawables(lock, null, null, null);
        } else addr.setCompoundDrawables(null, null, null, null);
    }

    private static void dim(View v, boolean on) {
        v.setAlpha(on ? 1f : 0.3f);
        v.setEnabled(on);
    }

    void show(boolean on) { view.setVisibility(on ? View.VISIBLE : View.GONE); }

    boolean shown() { return view.getVisibility() == View.VISIBLE; }

    /** The bar item under the pointer (root coordinates), or null. */
    View at(float x, float y) {
        if (!shown() || y < 0 || y >= view.getHeight()) return null;
        for (int i = 0; i < view.getChildCount(); i++) {
            View c = view.getChildAt(i);
            if (x >= c.getLeft() && x <= c.getRight() && y >= c.getTop() - Ui.dp(4) && y <= c.getBottom() + Ui.dp(4)) return c;
        }
        return null;
    }

    /** A click from the pointer. True if it landed on the bar (even between its buttons). */
    boolean click(float x, float y) {
        if (!shown() || y < 0 || y >= view.getHeight()) return false;
        View c = at(x, y);
        if (c != null && c.isEnabled()) {
            c.animate().cancel();
            c.setScaleX(0.88f);
            c.setScaleY(0.88f);
            c.animate().scaleX(1).scaleY(1).setDuration(160).start();
            c.performClick();
        }
        return true;
    }

    /** Light highlight under the pointer. */
    void hover(float x, float y) {
        View h = at(x, y);
        for (int i = 0; i < view.getChildCount(); i++) {
            View c = view.getChildAt(i);
            boolean on = c == h && c.isEnabled();
            if (c == addr) c.setBackground(Ui.round(on ? 0xFF2A2F3A : 0xFF1F232C, 14, on ? 0x88FF5CA8 : 0x22FFFFFF, 1));
            else c.setBackground(Ui.round(on ? 0x33FF5CA8 : 0x00000000, 10, 0x00000000, 0));
        }
    }

    // ---- v1.8.2: reaching the bar's items one by one (link jumping) ----

    /** The address field's centre, in the pointer's coordinates. */
    float[] addressCenter() { return centerOf(addr); }

    /** The pointer position that sits on the middle of a bar item. */
    private float[] centerOf(View c) {
        return new float[]{c.getLeft() + c.getWidth() / 2f, view.getHeight() / 2f};
    }

    /**
     * The next enabled item to the left (dir < 0) or right (dir > 0) of the one under (x, y), in the order Back, Forward,
     * Stop, Reload, Address. Disabled (dimmed) ones are skipped. Null when there is none that way.
     */
    float[] hop(float x, float y, int dir) {
        View here = at(x, y);
        int n = view.getChildCount(), i = -1;
        for (int k = 0; k < n; k++) if (view.getChildAt(k) == here) i = k;
        if (i >= 0) {
            for (int k = i + (dir > 0 ? 1 : -1); k >= 0 && k < n; k += dir > 0 ? 1 : -1) {
                View c = view.getChildAt(k);
                if (c.isEnabled() && c.getVisibility() == View.VISIBLE) return centerOf(c);
            }
            return null;
        }
        // between two items: the nearest enabled one on that side
        View best = null;
        for (int k = 0; k < n; k++) {
            View c = view.getChildAt(k);
            if (!c.isEnabled() || c.getVisibility() != View.VISIBLE) continue;
            if (dir > 0 && c.getLeft() > x) { best = c; break; }
            if (dir < 0 && c.getRight() < x) best = c;
        }
        return best == null ? null : centerOf(best);
    }

    /** Highlights the item at a pointer position straight away (the pointer glides there a moment later). */
    void highlightAt(float x, float y) { hover(x, y); }
}
