package io.github.sarukibrowser.tv;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Everything drawn over the page: home screen, quick menu, address bar, tabs, lists, settings and the phone QR. */
final class Panels {
    private final BrowserActivity a;
    private View open;
    private String kind = "";
    private EditText address;
    private TextView phoneStatus;
    private boolean zoomChanged;
    private static final int[] ZOOMS = {50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250};

    Panels(BrowserActivity a) { this.a = a; }

    boolean isOpen() { return open != null; }
    boolean isQuickMenu() { return "menu".equals(kind); }

    /** Runs once when the open panel goes away without an answer (used to answer a page's question with Cancel). */
    private Runnable onDismiss;
    /** v1.8.2: what BACK does in a confirm or a choice list: go back to the list it came from (null = just close). */
    private Runnable backAction;
    /** v1.8.2: the open side list's rows and scroll area (UP / DOWN scroll it when text sits between buttons). */
    private LinearLayout listCol;
    private ScrollView listScroll;
    private View listFooter;      // v1.8.2: the pinned button under a list (null when it has none)

    private void show(View v, String k, View focus) {
        runDismiss();
        backAction = null;
        if (!"list".equals(k)) { listCol = null; listScroll = null; listFooter = null; }
        // another panel takes the address bar's place: its phone mirror closes too
        if (address != null && !"address".equals(k)) { a.typing.nativeClosed(address); address = null; }
        a.panelHost.removeAllViews();
        a.panelHost.addView(v);
        open = v;
        kind = k;
        a.cursor.showCursor(false);
        // Motion: the dark backdrop fades in while the panel itself glides into place.
        v.setAlpha(0);
        v.animate().alpha(1).setDuration(170).setInterpolator(Ui.EASE).start();
        View content = v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0 ? ((ViewGroup) v).getChildAt(0) : null;
        if (content != null) {
            if (k.equals("menu")) content.setTranslationY(Ui.dp(48));
            else if (k.equals("list")) content.setTranslationX(Ui.dp(70));
            else { content.setScaleX(0.96f); content.setScaleY(0.96f); }
            content.animate().translationX(0).translationY(0).scaleX(1).scaleY(1).setDuration(230).setInterpolator(Ui.EASE).start();
        }
        if (focus != null) focus.post(focus::requestFocus);
    }

    private void runDismiss() {
        Runnable r = onDismiss;
        onDismiss = null;
        if (r != null) r.run();
    }

    void close() {
        if (open == null) return;
        hideKeyboard();
        runDismiss();
        backAction = null;
        listCol = null;
        listScroll = null;
        View v = open;
        open = null;
        if (address != null) a.typing.nativeClosed(address);
        address = null;
        phoneStatus = null;
        String was = kind;
        kind = "";
        // Fade out instead of vanishing; the panel can't take focus or clicks while it leaves.
        if (v instanceof ViewGroup) ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        v.clearFocus();
        View content = v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0 ? ((ViewGroup) v).getChildAt(0) : null;
        if (content != null) {
            if (was.equals("menu")) content.animate().translationY(Ui.dp(30)).setDuration(140).start();
            else if (was.equals("list")) content.animate().translationX(Ui.dp(40)).setDuration(140).start();
        }
        v.animate().alpha(0).setDuration(140).withEndAction(() -> { if (v.getParent() == a.panelHost) a.panelHost.removeView(v); }).start();
        if (zoomChanged) { zoomChanged = false; if (a.web != null) a.web.reload(); }
        if (a.homeShown()) focusHome(a.home);
        else { a.cursor.showCursor(true); if (a.web != null) a.web.requestFocus(); }
    }

    /** v1.8.2: BACK. A confirm or a choice list goes back to the list it came from; any other panel just closes. */
    void back() {
        Runnable r = backAction;
        if (r != null) { backAction = null; r.run(); }
        else close();
    }

    /**
     * v1.8.2: UP / DOWN in a side list. When text (a plain row) sits between the focused button and the next one, or
     * after the last one, the list scrolls by about 120dp instead of moving focus, so every word can be read with the
     * remote. Returns true when it scrolled (the key is used up); false lets the normal focus movement happen.
     */
    boolean listKey(int keyCode) {
        if (listCol == null || listScroll == null || !"list".equals(kind)) return false;
        int dir = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
        int n = listCol.getChildCount();
        View f = a.getCurrentFocus();
        int idx = dir > 0 ? -1 : n;                      // nothing focused yet in the list
        boolean outside = false;
        if (f != null) {
            View v = f;
            while (v != null && v.getParent() != listCol) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            outside = v == null;                         // on the pinned button under the list
            idx = outside ? n : listCol.indexOfChild(v);
        }
        int target = -1;
        boolean textBetween = false;
        for (int i = idx + dir; i >= 0 && i < n; i += dir) {
            if (listCol.getChildAt(i).isFocusable()) { target = i; break; }
            textBetween = true;
        }
        // is there a button inside the panel to move to? (the next row, or the pinned button after the last row)
        boolean dest = target >= 0 || (dir > 0 && !outside && listFooter != null);
        if (!textBetween) {
            if (dest) return false;                      // the next stop is a button: normal focus movement
            // nowhere to go: from the pinned button DOWN reads on; otherwise the key is used up, so focus can't
            // wander out to whatever sits behind the panel
            if (outside && dir > 0 && listScroll.canScrollVertically(1)) listScroll.smoothScrollBy(0, Ui.dp(120));
            return true;
        }
        View edge = listCol.getChildAt(target >= 0 ? target : (dir > 0 ? n - 1 : 0));
        int top = listScroll.getScrollY(), bottom = top + listScroll.getHeight();
        boolean seen = dir > 0 ? edge.getBottom() <= bottom : edge.getTop() >= top;
        if (seen || !listScroll.canScrollVertically(dir)) return !dest;   // all of it is on screen: move on, or stay put
        listScroll.smoothScrollBy(0, dir * Ui.dp(120));
        return true;
    }

    /**
     * v1.8.2: "Are you sure?" before something that can't be undone. Two rows: Cancel (first, focused) and the yes
     * label. Cancel and BACK both run {@code back}, which reopens the list the person came from.
     */
    void confirmAction(String question, String detail, String yesLabel, Runnable yes, Runnable back) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Cancel", null, back).focused());
        items.add(new Item(yesLabel, null, yes));
        list(question, detail, items);
        backAction = back;
    }

    /**
     * v1.8.2: pick one value of a setting from a list (instead of pressing OK to step through them). The current one
     * starts with a dot and is focused. OK applies the pick and goes back; BACK goes back with no change.
     */
    private void choose(String title, String hint, String[] names, String[] subs, int current,
                        java.util.function.IntConsumer apply, Runnable back) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Item it = new Item((i == current ? "● " : "    ") + names[i], subs == null ? null : subs[i], () -> { apply.accept(idx); back.run(); });
            if (i == current) it.focused();
            items.add(it);
        }
        list(title, hint, items);
        backAction = back;
    }

    /** A page asks a yes/no question (window.confirm). Answer with the remote; BACK means Cancel. */
    void confirm(String msg, android.webkit.JsResult r) {
        List<Item> items = new ArrayList<>();
        final boolean[] answered = {false};
        items.add(new Item("OK", null, () -> { answered[0] = true; onDismiss = null; r.confirm(); close(); }));
        items.add(new Item("Cancel", null, () -> { answered[0] = true; onDismiss = null; r.cancel(); close(); }));
        list("This page asks", msg == null ? "" : (msg.length() > 400 ? msg.substring(0, 400) + "…" : msg), items);
        onDismiss = () -> { if (!answered[0]) { answered[0] = true; r.cancel(); } };
    }

    /** v1.5: a site wants the camera / microphone. 1 = allow (remembered), 2 = this time, 3 = block, 0 = BACK. */
    interface CamChoice { void chose(int choice); }

    void askCamera(String host, CamChoice c) {
        List<Item> items = new ArrayList<>();
        final boolean[] answered = {false};
        items.add(new Item("Allow", "Remembered for " + host, () -> { answered[0] = true; onDismiss = null; close(); c.chose(1); }));
        items.add(new Item("Allow this time", "Until you close this tab", () -> { answered[0] = true; onDismiss = null; close(); c.chose(2); }));
        items.add(new Item("Block", "This site can't ask again (Settings → Video calls changes it)", () -> { answered[0] = true; onDismiss = null; close(); c.chose(3); }));
        list("📷 " + host + " wants the camera and microphone",
                "For a video call. Saruki uses " + a.calls.devicesNow() + ". BACK = not now.", items);
        onDismiss = () -> { if (!answered[0]) { answered[0] = true; c.chose(0); } };
    }

    // =====================================================================================
    // quick menu (MENU key or hold BACK)
    // =====================================================================================
    void quickMenu() {
        Context c = a;
        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Ui.dp(28), Ui.dp(18), Ui.dp(28), Ui.dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFA0E1116, 0xF2161B23});
        bg.setCornerRadii(new float[]{Ui.dp(26), Ui.dp(26), Ui.dp(26), Ui.dp(26), 0, 0, 0, 0});
        sheet.setBackground(bg);

        boolean page = a.cur != null && a.cur.url != null && !BrowserActivity.HOME.equals(a.cur.url) && !a.homeShown();
        String host = page ? Prefs.host(a.cur.url) : "";
        boolean desk = page ? Prefs.desktop(host) : Prefs.desktopDefault();

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(c, page ? a.cur.title : "Saruki", Ui.BODY, Ui.TEXT, true);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        TextView t2 = Ui.text(c, page ? a.cur.url : "Home", Ui.CAPTION, Ui.DIM, false);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(t1);
        titles.addView(t2);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = Ui.text(c, page ? (desk ? "Desktop " : "Mobile ") + Prefs.zoom(host, desk) + "%  ·  " + (Prefs.jumpMode() ? "Jump" : "Pointer")
                + "  ·  🛡 " + shieldName(host) + (a.shieldCount > 0 ? " (" + a.shieldCount + " stopped)" : "") : "", Ui.CAPTION, Ui.ACCENT, false);
        head.addView(state);
        sheet.addView(head);

        LinearLayout row1 = row(sheet), row2 = row(sheet);
        View first = null;
        boolean blocked = page && (a.pendingPopup != null || a.pendingRedirect != null);
        // v1.8.2: the host of a blocked redirect / pop-up sits on line 2 of its tile
        if (page && a.pendingRedirect != null) {
            String ru = a.pendingRedirect;
            add(row1, R.drawable.ic_open_in_new, "Go to redirect\n" + Prefs.host(ru), () -> { close(); a.pendingRedirect = null; a.navigate(ru); });
        }
        if (page && a.pendingPopup != null) {
            String pu = a.pendingPopup;
            add(row1, R.drawable.ic_new_window, "Open pop-up\n" + Prefs.host(pu), () -> { close(); a.pendingPopup = null; a.openTab(pu, a.cur.id); });
        }
        if (blocked && !Prefs.popupsAllowed(a.curHost)) {
            add(row1, R.drawable.ic_shield, "Allow pop-ups & redirects here", () -> {
                Prefs.setPopupsAllowed(a.curHost, true);
                a.toast("🛡 Shield off for " + a.curHost + " · Settings turns it back on");
                close();
            });
        }
        View callFirst = null;
        if (a.calls.active()) {
            // v1.5: on a call, its controls come first
            callFirst = add(row1, a.calls.micMuted ? R.drawable.ic_mic : R.drawable.ic_mic_off, a.calls.micMuted ? "Unmute microphone" : "Mute microphone", () -> { close(); a.calls.toggleMic(); });
            add(row1, a.calls.camOff ? R.drawable.ic_videocam : R.drawable.ic_videocam_off, a.calls.camOff ? "Turn camera on" : "Turn camera off", () -> { close(); a.calls.toggleCamera(); });
            if (a.calls.phoneVideo()) add(row1, R.drawable.ic_cameraswitch, "Switch phone camera", () -> { close(); a.calls.flipAll(); });
            add(row1, R.drawable.ic_call_end, "Hang up (closes this tab)", () -> { close(); a.calls.hangUp(); });
        }
        View search = add(row1, R.drawable.ic_search, "Search / go to", () -> address(page ? a.cur.url : ""));
        first = callFirst != null ? callFirst : search;
        add(row1, R.drawable.ic_home, "Home", () -> { close(); a.showHome(); });
        int realTabs = 0;
        for (Tab t : a.tabs) if (t.url != null && !BrowserActivity.HOME.equals(t.url)) realTabs++;
        add(row1, R.drawable.ic_tab, "Tabs (" + realTabs + ")", this::tabs);
        if (page) {
            add(row1, R.drawable.ic_arrow_back, "Back", () -> { close(); if (a.web != null && a.web.canGoBack()) a.web.goBack(); });
            add(row1, R.drawable.ic_arrow_forward, "Forward", () -> { close(); if (a.web != null && a.web.canGoForward()) a.web.goForward(); });
            add(row1, R.drawable.ic_refresh, "Reload", () -> { close(); if (a.web != null) a.web.reload(); });
            boolean fav = Db.get(a).isFav(a.cur.url);
            add(row1, fav ? R.drawable.ic_star_fill : R.drawable.ic_star, fav ? "Remove from Home" : "Add to Home", () -> {
                if (Db.get(a).isFav(a.cur.url)) { Db.get(a).removeFav(a.cur.url); a.toast("Removed from Home"); }
                else { Db.get(a).addFav(a.cur.url, a.cur.title); a.toast("Added to Home"); maybeSaveIcon(a.cur.url); }
                close();
            });
        }
        add(row1, R.drawable.ic_smartphone, "Phone remote", this::remote);
        if (page) add(row1, R.drawable.ic_send_to_mobile, "Open on my phone", () -> qr("Open this page on your phone", a.cur.url, "Scan with your phone's camera."));

        if (page) {
            // (the mobile-site tile uses its own phone shape, so it never matches the Phone remote tile)
            View mode = add(row2, desk ? R.drawable.ic_stay_current_portrait : R.drawable.ic_desktop_windows, desk ? "Switch to Mobile site" : "Switch to Desktop site", () -> {
                boolean d = !Prefs.desktop(host);
                Prefs.setDesktop(host, d);
                a.applyMode(host);
                close();
                if (a.web != null) a.web.reload();
                a.toast((d ? "Desktop" : "Mobile") + " site for " + host + " (remembered)");
            });
            View[] zoomTiles = new View[2];
            zoomTiles[0] = add(row2, R.drawable.ic_zoom_out, "Smaller (" + Prefs.zoom(host, desk) + "%)", () -> zoomStep(host, -1, zoomTiles, state));
            zoomTiles[1] = add(row2, R.drawable.ic_zoom_in, "Bigger (" + Prefs.zoom(host, desk) + "%)", () -> zoomStep(host, 1, zoomTiles, state));
            add(row2, Prefs.jumpMode() ? R.drawable.ic_arrow_selector_tool : R.drawable.ic_link, Prefs.jumpMode() ? "Use free pointer" : "Use link jumping", () -> {
                Prefs.setFlag("jump", !Prefs.jumpMode());
                a.toast(Prefs.jumpMode() ? "Link jumping: arrows hop between links and buttons" : "Free pointer: arrows move the pointer (hold = faster)");
                close();
            });
            int n = a.cur.mediaSnapshot().size();
            add(row2, R.drawable.ic_play_circle, n > 0 ? "Play video in TV Player" + (n > 1 ? " (" + n + ")" : "") : "Play video in TV Player", () -> { close(); a.playMedia(); });
            add(row2, R.drawable.ic_fit_screen, "Video fills screen", () -> { close(); a.toggleFit(); });
            add(row2, R.drawable.ic_layers_clear, "Remove box under pointer", () -> { close(); a.zapAtCursor(); });
        }
        add(row2, R.drawable.ic_download, "Downloads", this::downloads);
        add(row2, R.drawable.ic_history, "History", this::history);
        add(row2, R.drawable.ic_settings, "Settings", () -> settings());
        add(row2, R.drawable.ic_logout, "Leave Saruki", () -> { close(); a.moveTaskToBack(true); });

        FrameLayout wrap = new FrameLayout(c);
        wrap.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        show(wrap, "menu", first);
    }

    private void zoomStep(String host, int dir, View[] tiles, TextView state) {
        boolean desk = Prefs.desktop(host);
        int z = Prefs.zoom(host, desk), i = 0;
        while (i < ZOOMS.length - 1 && ZOOMS[i] < z) i++;
        if (ZOOMS[i] != z && dir < 0) i++;
        i = Math.max(0, Math.min(ZOOMS.length - 1, i + dir));
        Prefs.setZoom(host, desk, ZOOMS[i]);
        zoomChanged = true;
        Ui.setTileLabel(tiles[0], "Smaller (" + ZOOMS[i] + "%)");
        Ui.setTileLabel(tiles[1], "Bigger (" + ZOOMS[i] + "%)");
        state.setText((desk ? "Desktop " : "Mobile ") + ZOOMS[i] + "%  ·  applies when you close this menu");
    }

    /**
     * One row of quick-menu tiles. v1.8.2: more tiles than fit across, so each side shows an edge cue (a dark fade and a
     * chevron) whenever the row can scroll that way. The cues don't take focus or clicks.
     */
    private LinearLayout row(LinearLayout parent) {
        HorizontalScrollView hs = Ui.hScroll(a, 56);   // room for the focus ring and to keep a focused tile clear of the cues
        LinearLayout r = new LinearLayout(a);
        r.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), Ui.dp(4));
        r.setClipChildren(false);
        hs.addView(r);
        FrameLayout box = new FrameLayout(a);
        box.setClipChildren(false);
        box.addView(hs, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout left = Ui.edgeCue(a, false), right = Ui.edgeCue(a, true);
        box.addView(left, new FrameLayout.LayoutParams(Ui.dp(48), -1, Gravity.START));
        box.addView(right, new FrameLayout.LayoutParams(Ui.dp(48), -1, Gravity.END));
        Runnable cues = () -> {
            left.setVisibility(hs.canScrollHorizontally(-1) ? View.VISIBLE : View.INVISIBLE);
            right.setVisibility(hs.canScrollHorizontally(1) ? View.VISIBLE : View.INVISIBLE);
        };
        hs.setOnScrollChangeListener((v, x, y, ox, oy) -> cues.run());
        hs.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, or, ob) -> cues.run());
        r.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, or, ob) -> cues.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(6);
        parent.addView(box, lp);
        return r;
    }

    private View add(LinearLayout row, int icon, String label, Runnable r) {
        LinearLayout t = Ui.tile(a, icon, label, 112, 100);   // v1.8.2: 112x100dp so two lines of 14sp always fit
        t.setOnClickListener(v -> r.run());
        row.addView(t);
        return t;
    }

    // =====================================================================================
    // address / search
    // =====================================================================================
    void address(String prefill) {
        Context c = a;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(22), Ui.dp(18), Ui.dp(22), Ui.dp(18));
        card.setBackground(Ui.round(Ui.PANEL, 22, 0x33FF5CA8, 1));

        EditText e = new EditText(c);
        address = e;
        e.setSingleLine(true);
        e.setTextSize(Ui.TITLE);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setHint("Search or type a web address");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
        e.setBackground(Ui.focusBg(16));
        e.setSelectAllOnFocus(true);
        String pre = prefill == null || BrowserActivity.HOME.equals(prefill) ? "" : prefill;
        e.setText(pre);
        e.setOnEditorActionListener((v, id, ev) -> { go(e.getText().toString()); return true; });
        card.addView(e, new LinearLayout.LayoutParams(-1, -2));
        a.h.post(() -> { if (address == e) a.typing.nativeOpen(e, "Search or type a web address"); });   // v1.7: Saruki Remote mirrors it

        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(0, Ui.dp(10), 0, Ui.dp(4));
        chip(chips, "Go", () -> go(e.getText().toString()));
        chip(chips, "Type on my phone", () -> { if (a.remote.phones() > 0) a.toast("📱 Type in Saruki Remote on your phone"); else remote(); });
        chip(chips, ".com", () -> { e.append(".com"); e.setSelection(e.length()); });
        chip(chips, "Clear", () -> e.setText(""));
        card.addView(chips);

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = Ui.vScroll(c, 6);
        sv.addView(list);
        card.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(300)));

        Runnable refresh = new Runnable() {
            String last = null;
            @Override public void run() {
                String q = e.getText().toString().trim();
                if (q.equals(last)) return;
                last = q;
                fillSuggestions(list, q);
            }
        };
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int co, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int be, int co) { }
            @Override public void afterTextChanged(Editable s) { a.h.removeCallbacks(refresh); a.h.postDelayed(refresh, 180); }
        });
        fillSuggestions(list, pre.startsWith("http") ? "" : pre);

        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(760), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.dp(24);
        wrap.addView(card, lp);

        // v1.8.2: the suggestion list stops above the on-screen keyboard (12dp clear, never under 3 rows). With the
        // keyboard closed it is 300dp again. If the keyboard is up but reports no size, it is capped at 4 rows (200dp).
        final int[] kb = {0, 0};   // [0] keyboard height in px, [1] 1 = keyboard asked for but its size isn't reported
        final boolean[] reported = {false};
        Runnable fit = () -> {
            int h = Ui.dp(300);
            if (kb[0] > 0) {
                int[] loc = new int[2];
                sv.getLocationOnScreen(loc);
                int screenH = a.getResources().getDisplayMetrics().heightPixels;
                h = Math.max(Ui.dp(150), Math.min(Ui.dp(300), screenH - kb[0] - loc[1] - Ui.dp(12)));
            } else if (kb[1] == 1) h = Ui.dp(200);
            ViewGroup.LayoutParams slp = sv.getLayoutParams();
            if (slp.height != h) { slp.height = h; sv.setLayoutParams(slp); }
        };
        wrap.setOnApplyWindowInsetsListener((v, ins) -> {
            int b = Build.VERSION.SDK_INT >= 30 ? ins.getInsets(WindowInsets.Type.ime()).bottom : ins.getSystemWindowInsetBottom();
            if (b > 0) { reported[0] = true; kb[1] = 0; }
            if (b > 0 || reported[0]) { kb[0] = b; sv.post(fit); }
            return ins;
        });
        sv.addOnLayoutChangeListener((v, l, t, r2, b2, ol, ot, or, ob) -> { if (kb[0] > 0) sv.post(fit); });
        show(wrap, "address", e);
        wrap.post(wrap::requestApplyInsets);
        e.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            boolean asked = im != null && address == e && im.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
            if (asked) e.postDelayed(() -> { if (address == e && !reported[0]) { kb[1] = 1; sv.post(fit); } }, 900);
        }, 250);
    }

    private void go(String s) {
        if (s.trim().isEmpty()) return;
        close();
        a.navigate(s);
    }

    private void chip(LinearLayout row, String label, Runnable r) {
        TextView t = Ui.text(a, label, Ui.CAPTION, Ui.TEXT, false);
        t.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9));
        Ui.focusable(t, 18, 1.06f);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(5);    // v1.7.1: even on both sides, so a centered row is truly centered
        lp.rightMargin = Ui.dp(5);
        row.addView(t, lp);
    }

    private void fillSuggestions(LinearLayout list, String q) {
        list.removeAllViews();
        List<Db.Entry> local = q.isEmpty() ? Db.get(a).history(7) : Db.get(a).search(q, 6);
        if (!q.isEmpty()) {
            String asUrl = BrowserActivity.toUrl(q);
            boolean isUrl = asUrl != null && !asUrl.equals(Prefs.searchUrl(q));
            if (isUrl) suggestionRow(list, "Go to " + q, asUrl, () -> go(q));
            suggestionRow(list, "Search “" + q + "”", Prefs.ENGINES[Prefs.engine()], () -> go(Prefs.searchUrl(q)));
        }
        for (Db.Entry en : local) suggestionRow(list, en.title, en.url, () -> go(en.url));
        if (q.isEmpty() || q.contains("://")) return;
        final String query = q;
        new Thread(() -> {
            List<String> sug = remoteSuggest(query);
            a.h.post(() -> {
                if (address == null || !address.getText().toString().trim().equals(query)) return;
                int n = 0;
                for (String s : sug) {
                    if (s.equalsIgnoreCase(query) || n++ >= 6) continue;
                    suggestionRow(list, s, "Search", () -> go(s));
                }
            });
        }).start();
    }

    private void suggestionRow(LinearLayout list, String title, String sub, Runnable r) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9));
        Ui.focusable(row, 12, 1f);   // v1.7.1: full-width row, ring inside, no growing past the list
        TextView t = Ui.text(a, title, Ui.BODY, Ui.TEXT, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        TextView s = Ui.text(a, sub, Ui.CAPTION, Ui.DIM, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(t);
        row.addView(s);
        row.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(4);
        list.addView(row, lp);
    }

    private static List<String> remoteSuggest(String q) {
        List<String> out = new ArrayList<>();
        try {
            boolean ddg = Prefs.engine() != 0;
            String u = ddg ? "https://duckduckgo.com/ac/?type=list&q=" + Uri.encode(q)
                    : "https://suggestqueries.google.com/complete/search?client=firefox&q=" + Uri.encode(q);
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try (InputStream in = c.getInputStream()) {
                String body = new String(readAll(in), "UTF-8");
                JSONArray arr = new JSONArray(body).getJSONArray(1);
                for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0 && b.size() < 4_000_000) b.write(buf, 0, r);
        return b.toByteArray();
    }

    boolean typeInto(String s) {
        if (address == null) return false;
        address.append(s);
        address.setSelection(address.length());
        return true;
    }

    boolean typeBackspace() {
        if (address == null) return false;
        Editable t = address.getText();
        if (t.length() > 0) t.delete(t.length() - 1, t.length());
        return true;
    }

    boolean typeEnter() {
        if (address == null) return false;
        go(address.getText().toString());
        return true;
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null && open != null) im.hideSoftInputFromWindow(open.getWindowToken(), 0);
    }

    // =====================================================================================
    // generic side list
    // =====================================================================================
    static final class Item {
        final String title;
        String sub;
        Runnable click, longClick;
        Supplier<String> toggle;   // for settings: runs and returns the new value, panel stays open
        boolean focus;             // v1.8.2: the row that starts focused (default: the first button row)
        boolean accent;            // v1.8.2: the sub line is a setting's current value (pink)
        Item(String title, String sub, Runnable click) { this.title = title; this.sub = sub; this.click = click; }
        Item onLong(Runnable r) { longClick = r; return this; }
        Item focused() { focus = true; return this; }
        /** v1.8.2: a row that does nothing is plain text: no card, no ring, never focused. */
        boolean isInfo() { return click == null && toggle == null && longClick == null; }
        static Item setting(String title, Supplier<String> value, Supplier<String> toggle) {
            Item i = new Item(title, value.get(), null);
            i.toggle = toggle;
            return i;
        }
        /** v1.8.2: a setting with several values: OK opens a list of them. */
        static Item choice(String title, String value, Runnable open) {
            Item i = new Item(title, value, open);
            i.accent = true;
            return i;
        }
    }

    void list(String title, String hint, List<Item> items) { list(title, hint, items, null, null); }

    /** v1.8.2: {@code footerLabel} adds a button pinned under the scrolling rows (it starts focused when no row can be). */
    void list(String title, String hint, List<Item> items, String footerLabel, Runnable footerAction) {
        Context c = a;
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(24), Ui.dp(24), Ui.dp(24), Ui.dp(16));
        panel.setBackground(Ui.round(Ui.PANEL, 0, 0, 0));
        TextView t = Ui.text(c, title, Ui.TITLE, Ui.TEXT, true);
        panel.addView(t);
        if (hint != null) {
            TextView hv = Ui.text(c, hint, Ui.CAPTION, Ui.DIM, false);
            hv.setPadding(0, Ui.dp(6), 0, 0);
            panel.addView(hv);
        }
        ScrollView sv = Ui.vScroll(c, 8);
        sv.setClipToPadding(false);
        sv.setFocusable(false);   // v1.8.2: a list with only text rows must not give the invisible scroll area a focus stop
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10));
        sv.addView(col);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        View first = null, flagged = null;
        for (Item it : items) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(11));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(6);
            if (it.isInfo()) {
                // v1.8.2: text only. No card, no pink ring, not focusable; the whole text shows (the list scrolls).
                row.addView(Ui.text(c, it.title, Ui.BODY, Ui.TEXT, false));
                if (it.sub != null) {
                    TextView st = Ui.text(c, it.sub, Ui.CAPTION, Ui.DIM, false);
                    st.setPadding(0, Ui.dp(3), 0, 0);
                    row.addView(st);
                }
                col.addView(row, lp);
                continue;
            }
            Ui.focusable(row, 12, 1f);   // v1.7.1: full-width row, ring inside, no growing past the list
            TextView tt = Ui.text(c, it.title, Ui.BODY, Ui.TEXT, false);
            tt.setMaxLines(2);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tt);
            TextView st = null;
            if (it.sub != null) {
                st = Ui.text(c, it.sub, Ui.CAPTION, it.toggle != null || it.accent ? Ui.ACCENT : Ui.DIM, false);
                st.setMaxLines(3);
                st.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                st.setPadding(0, Ui.dp(3), 0, 0);
                row.addView(st);
            }
            final TextView fst = st;
            row.setOnClickListener(v -> {
                if (it.toggle != null) { String nv = it.toggle.get(); if (fst != null && nv != null) fst.setText(nv); return; }
                if (it.click != null) it.click.run();
            });
            if (it.longClick != null) row.setOnLongClickListener(v -> { it.longClick.run(); return true; });
            col.addView(row, lp);
            if (first == null) first = row;
            if (it.focus && flagged == null) flagged = row;
        }
        View footer = null;
        if (footerLabel != null) {
            TextView fb = Ui.text(c, footerLabel, Ui.BODY, Ui.TEXT, true);
            fb.setGravity(Gravity.CENTER);
            fb.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
            Ui.focusable(fb, 12, 1f);
            fb.setOnClickListener(v -> { if (footerAction != null) footerAction.run(); });
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
            fp.topMargin = Ui.dp(8);
            panel.addView(fb, fp);
            footer = fb;
        }
        listFooter = footer;
        View start = flagged != null ? flagged : first != null ? first : footer;
        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(0x66000000);
        wrap.addView(panel, new FrameLayout.LayoutParams(Ui.dp(480), -1, Gravity.END));
        show(wrap, "list", start);
        listCol = col;
        listScroll = sv;
    }

    // =====================================================================================
    // long-press OK on the page
    // =====================================================================================
    void context() {
        a.js("info(" + (int) a.cursor.x + "," + (int) a.py() + ")", r -> {
            JSONObject parsed;
            try { parsed = new JSONObject(new JSONArray("[" + r + "]").getString(0)); } catch (Exception e) { parsed = new JSONObject(); }
            final JSONObject o = parsed;
            List<Item> items = new ArrayList<>();
            String link = o.optString("link", ""), img = o.optString("img", ""), video = o.optString("video", "");
            if (!link.isEmpty()) {
                String mk = BrowserActivity.Media.isMedia(link);
                if (mk != null) items.add(new Item("Play in TV Player", link, () -> { close(); a.startPlayer(link, mk, a.cur.url); }));
                items.add(new Item("Open link in a new tab", link, () -> { close(); a.openTab(link, a.cur.id); }));
                items.add(new Item("Open link here", null, () -> { close(); a.navigate(link); }));
                items.add(new Item("Add link to Home", o.optString("text", ""), () -> { Db.get(a).addFav(link, o.optString("text", link)); a.toast("Added to Home"); close(); }));
                items.add(new Item("Open link on my phone", null, () -> qr("Open this link on your phone", link, "Scan with your phone's camera.")));
                items.add(new Item("Download link", null, () -> { close(); a.download(link, a.web.getSettings().getUserAgentString(), android.webkit.URLUtil.guessFileName(link, null, null), null); }));
            }
            if (!video.isEmpty()) {
                if (video.startsWith("http")) items.add(new Item("Play this video in TV Player", video, () -> { close(); String k = BrowserActivity.Media.isMedia(video); a.startPlayer(video, k == null ? "file" : k, a.cur.url); }));
                else if (!a.cur.mediaSnapshot().isEmpty()) items.add(new Item("Play this video in TV Player", "Stream found on this page", () -> { close(); a.playMedia(); }));
                items.add(new Item("Make the video fill the screen", null, () -> { close(); a.toggleFit(); }));
            }
            if (!img.isEmpty()) {
                items.add(new Item("Open image", img, () -> { close(); a.navigate(img); }));
                items.add(new Item("Download image", null, () -> { close(); a.download(img, a.web.getSettings().getUserAgentString(), android.webkit.URLUtil.guessFileName(img, null, null), null); }));
            }
            if (o.has("edit")) items.add(new Item("Type in this box from my phone", null, this::remote));
            items.add(new Item("Drag from here", "For sliders, carousels and maps: move with the arrows, OK to let go", () -> { close(); a.startDrag(false); }));
            items.add(new Item("Remove the box under the pointer", "Gets rid of stuck banners and overlays", () -> { close(); a.zapAtCursor(); }));
            items.add(new Item("Open the browser menu", null, this::quickMenu));
            list("Here", null, items);
        });
    }

    // =====================================================================================
    // tabs
    // =====================================================================================
    void tabs() { tabs(0); }

    /** v1.8.2: {@code focusCloseOf} = the id of the tab whose Close button starts focused; -1 = the New tab card; 0 = the open tab. */
    void tabs(int focusCloseOf) {
        Context c = a;
        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Ui.dp(32), Ui.dp(22), Ui.dp(32), Ui.dp(26));
        sheet.setBackground(Ui.round(0xFA0E1116, 0, 0, 0));
        sheet.addView(Ui.text(c, "Tabs", Ui.TITLE, Ui.TEXT, true));
        TextView hint = Ui.text(c, "OK opens a tab · ▼ then OK closes it · only the open tab uses memory", Ui.CAPTION, Ui.DIM, false);
        hint.setPadding(0, Ui.dp(4), 0, Ui.dp(10));
        sheet.addView(hint);
        HorizontalScrollView hs = Ui.hScroll(c, 16);
        LinearLayout r = new LinearLayout(c);
        r.setPadding(Ui.dp(10), Ui.dp(12), Ui.dp(10), Ui.dp(12));
        r.setClipChildren(false);
        hs.addView(r);
        sheet.addView(hs);

        View focus = null, closeFocus = null;
        LinearLayout nt = new LinearLayout(c);
        nt.setOrientation(LinearLayout.VERTICAL);
        nt.setGravity(Gravity.CENTER);
        Ui.focusable(nt, 14, 1.06f);
        nt.addView(Ui.text(c, "+", 40, Ui.ACCENT, false));
        nt.addView(Ui.text(c, "New tab", Ui.CAPTION, Ui.TEXT, false));
        nt.setOnClickListener(v -> { close(); a.openTab(BrowserActivity.HOME, 0); });
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(150));
        ntp.rightMargin = Ui.dp(14);
        r.addView(nt, ntp);
        for (Tab t : new ArrayList<>(a.tabs)) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClipChildren(false);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(8));
            Ui.focusable(card, 14, 1.06f);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap thumb = t == a.cur ? null : t.thumb;
            if (thumb != null) iv.setImageBitmap(thumb);
            else { iv.setBackground(Ui.round(t == a.cur ? 0xFF2B3647 : 0xFF1B212B, 10, 0, 0)); }
            card.addView(iv, new LinearLayout.LayoutParams(Ui.dp(208), Ui.dp(117)));
            boolean isHome = t.url == null || BrowserActivity.HOME.equals(t.url);
            TextView tt = Ui.text(c, (t == a.cur ? "● " : "") + (isHome ? "Home" : t.title), Ui.CAPTION, Ui.TEXT, false);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            tt.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), 0);
            card.addView(tt, new LinearLayout.LayoutParams(Ui.dp(208), -2));
            card.setOnClickListener(v -> { close(); a.activate(t); });
            col.addView(card);
            TextView x = Ui.text(c, "✕  Close", Ui.CAPTION, Ui.DIM, false);
            x.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
            Ui.focusable(x, 14, 1.06f);
            x.setOnClickListener(v -> {
                // v1.8.2: focus stays on a Close button: the tab that took this one's place (the next, or the previous
                // when this was the last), or the New tab card when none is left, so OK can close tab after tab.
                List<Tab> before = new ArrayList<>(a.tabs);
                int at = before.indexOf(t);
                a.closeTab(t);
                int nextId = -1;
                if (at >= 0 && at + 1 < before.size()) nextId = before.get(at + 1).id;
                else if (at - 1 >= 0) nextId = before.get(at - 1).id;
                tabs(nextId);
            });
            if (focusCloseOf > 0 && t.id == focusCloseOf) closeFocus = x;
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(-2, -2);
            xp.topMargin = Ui.dp(8);
            col.addView(x, xp);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = Ui.dp(14);
            r.addView(col, cp);
            if (t == a.cur) focus = card;
        }
        if (focusCloseOf != 0) focus = closeFocus != null ? closeFocus : nt;   // after a Close
        else if (focus == null) focus = nt;
        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(0x88000000);
        wrap.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        View f = focus;
        show(wrap, "tabs", f);
        f.post(() -> hs.smoothScrollTo(Math.max(0, f.getLeft() + ((View) f.getParent()).getLeft() - Ui.dp(200)), 0));
    }

    // =====================================================================================
    // lists: favourites, history, downloads, videos, settings
    // =====================================================================================
    void favorites() {
        List<Item> items = new ArrayList<>();
        for (Db.Entry en : Db.get(a).favs())
            items.add(new Item(en.title, en.url, () -> { close(); a.navigate(en.url); })
                    .onLong(() -> { Db.get(a).removeFav(en.url); a.toast("Removed " + en.title); favorites(); }));
        list("Home favorites", "Hold OK on one to remove it", items);
    }

    /**
     * v1.8.2: newest page first, and the panel opens on it (OK reopens the page, never wipes anything). "Clear all
     * history" is the last row and asks first.
     */
    void history() {
        List<Item> items = new ArrayList<>();
        List<Db.Entry> all = Db.get(a).history(150);
        for (Db.Entry en : all)
            items.add(new Item(en.title, en.url, () -> { close(); a.navigate(en.url); })
                    .onLong(() -> { Db.get(a).deleteHistory(en.url); history(); }));
        if (all.isEmpty()) items.add(new Item("No history yet", "Pages you visit show up here", null));
        else items.add(new Item("Clear all history", null, () -> confirmClearHistory(this::history)));
        list("History", "Hold OK on an entry to delete it", items);
    }

    private void confirmClearHistory(Runnable back) {
        confirmAction("Clear all history?", "Every page you've visited is removed. Favorites stay.", "Yes, clear all history",
                () -> { Db.get(a).clearHistory(); a.toast("History cleared"); close(); }, back);
    }

    void downloads() {
        List<Item> items = new ArrayList<>();
        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        String ids = Prefs.p.getString("downloads", "");
        List<Long> idList = new ArrayList<>();
        for (String s : ids.split(",")) try { idList.add(Long.parseLong(s)); } catch (Exception ignored) { }
        if (!idList.isEmpty()) {
            long[] arr = new long[idList.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = idList.get(i);
            try (Cursor cu = dm.query(new DownloadManager.Query().setFilterById(arr))) {
                while (cu != null && cu.moveToNext()) {
                    long id = cu.getLong(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                    String title = cu.getString(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    int st = cu.getInt(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long done = cu.getLong(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long tot = cu.getLong(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    String mime = cu.getString(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                    String status = st == DownloadManager.STATUS_SUCCESSFUL ? "Done · " + Formatter.formatShortFileSize(a, tot)
                            : st == DownloadManager.STATUS_FAILED ? "Failed"
                            : st == DownloadManager.STATUS_PAUSED ? "Paused (waiting for network)"
                            : "Downloading · " + Formatter.formatShortFileSize(a, done) + (tot > 0 ? " of " + Formatter.formatShortFileSize(a, tot) : "");
                    items.add(new Item(title, status, () -> {
                        if (st != DownloadManager.STATUS_SUCCESSFUL) { a.toast(status); return; }
                        openDownload(dm, id, title, mime);
                    }).onLong(() -> confirmAction("Delete " + title + "?", "The file is removed from the TV.", "Yes, delete it",
                            () -> { dm.remove(id); a.toast("Deleted " + title); downloads(); }, this::downloads)));
                }
            } catch (Exception ignored) { }
        }
        if (items.isEmpty()) items.add(new Item("No downloads yet", "Files you download from pages show up here", null));
        list("Downloads", "OK opens · videos play in the TV Player · hold OK to delete", items);
    }

    private void openDownload(DownloadManager dm, long id, String title, String mime) {
        Uri u = dm.getUriForDownloadedFile(id);
        if (u == null) { a.toast("The file is gone"); return; }
        close();
        String lower = title.toLowerCase(Locale.US);
        if ((mime != null && mime.startsWith("video/")) || BrowserActivity.Media.isMedia(lower) != null) {
            Intent i = new Intent(a, PlayerActivity.class);
            i.setData(u);
            i.putExtra("title", title);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
            return;
        }
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(u, lower.endsWith(".apk") ? "application/vnd.android.package-archive" : mime);
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(v); } catch (Exception e) { a.toast("No app on the TV opens this kind of file"); }
    }

    void pickMedia(List<Tab.Media> media) {
        List<Item> items = new ArrayList<>();
        for (Tab.Media m : media) items.add(new Item(m.label(), m.url, () -> { close(); a.startPlayer(m.url, m.kind, m.referer); }));
        list("Videos on this page", "The first one is usually the main video", items);
    }

    void downloadPrompt(String label, boolean video, Runnable play, Runnable download) {
        List<Item> items = new ArrayList<>();
        if (video) items.add(new Item("Play now in TV Player", null, () -> { close(); play.run(); }));
        items.add(new Item("Download to the TV", "Saved in the TV's Downloads folder", () -> { close(); download.run(); }));
        items.add(new Item("Cancel", null, this::close));
        list(label, null, items);
    }

    void settings() { settings(-1); }

    void settings(int focusRow) {
        List<Item> s = new ArrayList<>();
        String host = a.curHost;
        final int rEngine = s.size();
        s.add(Item.choice("Search engine", Prefs.ENGINES[Prefs.engine()], () -> choose("Search engine", null, Prefs.ENGINES, null,
                Prefs.engine(), i -> Prefs.setEngine(i), () -> settings(rEngine))));
        final int rSpeed = s.size();
        s.add(Item.choice("Pointer speed", speedName(Prefs.cursorSpeed()), () -> choose("Pointer speed", "How fast the pointer moves with the arrows",
                new String[]{speedName(1), speedName(2), speedName(3), speedName(4), speedName(5)}, null,
                Prefs.cursorSpeed() - 1, i -> Prefs.setCursorSpeed(i + 1), () -> settings(rSpeed))));
        final int rArrows = s.size();
        s.add(Item.choice("Arrows move", Prefs.jumpMode() ? "Between links (link jumping)" : "A free pointer", () -> choose("Arrows move", null,
                new String[]{"A free pointer", "Between links (link jumping)"},
                new String[]{"The arrows move the pointer (hold = faster)", "The arrows hop between links and buttons"},
                Prefs.jumpMode() ? 1 : 0, i -> Prefs.setFlag("jump", i == 1), () -> settings(rArrows))));
        final int rNew = s.size();
        s.add(Item.choice("New sites open as", Prefs.desktopDefault() ? "Desktop sites" : "Mobile sites", () -> choose("New sites open as", null,
                new String[]{"Desktop sites", "Mobile sites"}, null,
                Prefs.desktopDefault() ? 0 : 1, i -> Prefs.setDesktopDefault(i == 0), () -> settings(rNew))));
        final int rZoom = s.size();
        s.add(Item.choice("Desktop page size for new sites", Prefs.defaultZoom(true) + "%", () -> {
            int[] opts = {100, 110, 125, 150, 175, 200};
            String[] names = new String[opts.length];
            int cur = 0;
            for (int i = 0; i < opts.length; i++) { names[i] = opts[i] + "%"; if (opts[i] == Prefs.defaultZoom(true)) cur = i; }
            choose("Desktop page size for new sites", "Bigger = larger text and buttons on desktop pages", names, null, cur,
                    i -> Prefs.setDefaultZoom(true, opts[i]), () -> settings(rZoom));
        }));
        s.add(Item.setting("Ad and tracker blocking", () -> onOff(Prefs.adblock()) + " · " + AdBlock.count() + " ad sites listed", () -> {
            Prefs.setFlag("adblock", !Prefs.adblock()); return onOff(Prefs.adblock()) + " · " + AdBlock.count() + " ad sites listed"; }));
        if (!host.isEmpty()) s.add(Item.setting("Allow ads on " + host, () -> Prefs.adsAllowed(host) ? "Yes (blocking off here)" : "No", () -> {
            Prefs.setAdsAllowed(host, !Prefs.adsAllowed(host)); return Prefs.adsAllowed(host) ? "Yes (blocking off here)" : "No"; }));
        final int rShield = s.size();
        s.add(Item.choice("Pop-up & redirect shield", shieldMode(), () -> choose("Pop-up & redirect shield", null,
                new String[]{"Strict", "Standard", "Off"},
                new String[]{"Only links you click may open pop-ups or leave the site", "Blocks pop-ups that open without a click", "Pop-ups open as new tabs"},
                !Prefs.blockPopups() ? 2 : Prefs.strictShield() ? 0 : 1,
                i -> {
                    if (i == 2) Prefs.setFlag("popups", false);
                    else { Prefs.setFlag("popups", true); Prefs.setFlag("strictShield", i == 0); }
                }, () -> settings(rShield))));
        if (!host.isEmpty()) s.add(Item.setting("Pop-ups & redirects on " + host, () -> Prefs.popupsAllowed(host) ? "Allowed (shield off here)" : "Shield on", () -> {
            Prefs.setPopupsAllowed(host, !Prefs.popupsAllowed(host)); return Prefs.popupsAllowed(host) ? "Allowed (shield off here)" : "Shield on"; }));
        s.add(Item.setting("Hide cookie banners", () -> onOff(Prefs.hideCookies()), () -> { Prefs.setFlag("cookies", !Prefs.hideCookies()); return onOff(Prefs.hideCookies()); }));
        s.add(Item.setting("Skip YouTube ads on youtube.com", () -> onOff(Prefs.ytSkip()), () -> { Prefs.setFlag("ytskip", !Prefs.ytSkip()); return onOff(Prefs.ytSkip()); }));
        s.add(Item.setting("Dark pages", () -> onOff(Prefs.dark()) + " · takes effect on the next tab", () -> {
            Prefs.setFlag("dark", !Prefs.dark()); return onOff(Prefs.dark()) + " · takes effect on the next tab"; }));
        final int rEdge = s.size();
        s.add(Item.choice("Screen edge margin (if the TV cuts off edges)", Prefs.overscan() + "%", () -> choose("Screen edge margin",
                "Leaves a border so a TV that cuts off its edges shows everything",
                new String[]{"0%", "1%", "2%", "3%", "4%", "5%"}, null, Prefs.overscan(),
                i -> { Prefs.setOverscan(i); a.applyOverscan(); }, () -> settings(rEdge))));
        s.add(new Item("Saruki Remote (phone app)", a.remote.phones() == 0 ? "Trackpad, keyboard and camera on your phone · no phone connected"
                : "Trackpad, keyboard and camera on your phone · " + a.remote.phones() + " connected", this::remoteSettings));
        s.add(new Item("Video calls", a.calls.devicesNow(), () -> callSettings(-1)));
        if (Migrate.available(a)) s.add(new Item("Import favorites, tabs and settings",
                "From the earlier Saruki on this TV (1.7.2) · replaces what's here", () -> importOffer(false)));
        s.add(new Item("Clear browsing data", "History, cookies and sign-ins, cached files", this::clearData));
        s.add(new Item("Show the welcome guide", null, this::welcome));
        s.add(new Item("About Saruki", "Version " + BuildConfigVersion() + " · WebView " + a.chromeMajor, () -> list("About Saruki",
                null, aboutItems(), "Close", this::close)));
        if (focusRow >= 0 && focusRow < s.size()) s.get(focusRow).focus = true;
        list("Settings", "OK changes a setting", s);
    }

    private String BuildConfigVersion() {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private List<Item> aboutItems() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("Saruki TV Browser " + BuildConfigVersion(), "For Android TV and Google TV. Uses the TV's Android System WebView.", null));
        l.add(new Item("Ad blocking list", "StevenBlack/hosts (MIT licence, includes AdAway data under CC BY 3.0)", null));
        l.add(new Item("Video player", "AndroidX Media3 (Apache 2.0) with NextLib FFmpeg decoders (GPL-3.0)", null));
        l.add(new Item("QR codes", "ZXing (Apache 2.0)", null));
        l.add(new Item("Icons", "Material Symbols (Apache 2.0)", null));
        l.add(new Item("Phone link", "Trackpad, typing and camera protocols adapted from TV Overlay Suite (MIT)", null));
        l.add(new Item("Tip: update the browser engine", "Play Store → Manage apps → update Android System WebView to make every page faster.", null));
        return l;
    }

    private static String shieldMode() {
        if (!Prefs.blockPopups()) return "Off · pop-ups open as new tabs";
        return Prefs.strictShield() ? "Strict · only links you click may open pop-ups or leave the site"
                : "Standard · blocks pop-ups that open without a click";
    }

    private static String shieldName(String host) {
        if (!Prefs.blockPopups() || Prefs.popupsAllowed(host)) return "Off";
        return Prefs.strictShield() ? "Strict" : "Standard";
    }

    private static String speedName(int v) { return new String[]{"", "1 · slowest", "2 · slow", "3 · normal", "4 · fast", "5 · fastest"}[v]; }
    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    /** v1.5: which camera and microphone calls use, and which sites may use them. */
    void callSettings() { callSettings(-1); }

    void callSettings(int focusRow) {
        List<Item> l = new ArrayList<>();
        l.add(new Item("Right now", a.calls.devicesNow(), null));
        final int rCam = l.size();
        l.add(Item.choice("Camera", Prefs.CAMS[Prefs.callCam()], () -> choose("Camera", "Which camera video calls use", Prefs.CAMS, null,
                Prefs.callCam(), i -> Prefs.setCallCam(i), () -> callSettings(rCam))));
        final int rMic = l.size();
        l.add(Item.choice("Microphone", Prefs.MICS[Prefs.callMic()], () -> choose("Microphone", "Which microphone video calls use", Prefs.MICS, null,
                Prefs.callMic(), i -> Prefs.setCallMic(i), () -> callSettings(rMic))));
        l.add(new Item("How to use the phone", "USB: plug your phone into the TV, pull down its USB notification and choose Webcam (if your phone has it). "
                + "Wi-Fi: pair Saruki Remote (MENU → Phone remote); its camera starts by itself when a call starts here. "
                + "TV Overlay Suite 1.7 or newer works too.", null));
        l.add(new Item("On a call", "⏯ mutes / unmutes the microphone · MENU: camera off, switch phone camera, hang up", null));
        for (String host : Prefs.callSites()) {
            boolean ok = "y".equals(Prefs.callSite(host));
            l.add(new Item(host, (ok ? "Allowed" : "Blocked") + " · OK forgets (the site asks again)", () -> {
                Prefs.setCallSite(host, null);
                a.toast("📷 " + host + " will ask again");
                callSettings();
            }));
        }
        if (focusRow >= 0 && focusRow < l.size()) l.get(focusRow).focus = true;
        list("Video calls", "OK changes a setting", l);
    }

    /**
     * v1.8: bring over favorites, history, tabs, settings and phone pairing from the earlier Saruki (the app id before
     * the rename). Offered once on first start, and in Settings while the earlier Saruki is installed.
     */
    void importOffer(boolean firstStart) {
        Prefs.setFlag("migrateAsked", true);
        close();
        a.toast("Looking for your Saruki data…");
        new Thread(() -> {
            JSONObject o = Migrate.fetch(a);
            a.runOnUiThread(() -> {
                if (o == null) {
                    if (firstStart) { welcome(); a.toast("Couldn't read the earlier Saruki. Settings → Import tries again."); }
                    else a.toast("Couldn't read the earlier Saruki. Check that Saruki 1.7.2 is installed, then try again.");
                    return;
                }
                int favs = Migrate.favs(o), tabs = Migrate.tabs(o);
                String what = favs + (favs == 1 ? " favorite · " : " favorites · ") + tabs + (tabs == 1 ? " tab" : " tabs")
                        + (Migrate.paired(o) ? " · phone pairing" : "") + " · settings and site choices";
                List<Item> l = new ArrayList<>();
                l.add(new Item("Import your favorites, tabs and settings", what, () -> {
                    close();
                    a.toast("Importing…");
                    new Thread(() -> {
                        boolean ok = Migrate.apply(a, o);
                        a.runOnUiThread(() -> {
                            if (ok) { a.toast("Imported. Saruki is restarting…"); a.restartAfterImport(); }
                            else a.toast("Import failed. Your earlier Saruki still has everything.");
                        });
                    }, "saruki-import").start();
                }));
                l.add(new Item(firstStart ? "Not now" : "Cancel", firstStart ? "Start fresh. Settings → Import is there later." : null, () -> {
                    close();
                    if (firstStart) welcome();
                }));
                list("Bring over your Saruki data?", "From the earlier Saruki on this TV. Site sign-ins don't move: sign in again where needed.", l);
            });
        }, "saruki-import").start();
    }

    private void clearData() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("Clear history", null, () -> confirmClearHistory(this::clearData)));
        l.add(new Item("Clear cached files", "Frees space; you stay signed in", () -> { if (a.web != null) a.web.clearCache(true); a.toast("Cache cleared"); close(); }));
        l.add(new Item("Clear cookies and site data", "Signs you out of every site", () -> confirmAction("Clear cookies and site data?",
                "Signs you out of every site.", "Yes, clear cookies and site data", () -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    a.toast("Cookies and site data cleared");
                    close();
                }, this::clearData)));
        list("Clear browsing data", null, l);
    }

    void googleSignIn() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("Why this happens", "Google doesn't allow its sign-in page inside apps that build in a browser engine. Every TV browser has this limit, and disguising the browser to get around it breaks Google's rules.", null));
        l.add(new Item("YouTube", "Use the YouTube app on the TV, or sign in to it at youtube.com/activate on your phone.", null));
        l.add(new Item("Open this page on my phone", "Finish signing in there", () -> qr("Open on your phone", a.cur.url, "Scan with your phone's camera.")));
        l.add(new Item("Go back", null, () -> { close(); if (a.web != null && a.web.canGoBack()) a.web.goBack(); }));
        list("Google sign-in can't be done in a TV browser", null, l);
    }

    // =====================================================================================
    // phone remote + QR
    // =====================================================================================
    /** v1.7: MENU → Phone remote. Saruki Remote (the phone app: trackpad, keyboard, camera) by QR code; the old
     *  no-app touchpad page is one button away. */
    void remote() {
        a.remote.start();
        String url = a.remote.pairUrl();
        if (url == null) { a.toast("The TV isn't on a network right now"); return; }
        qr("Saruki Remote: your phone as trackpad, keyboard and camera", url,
                "1. Phone on the same Wi-Fi as the TV\n2. Scan with the phone's camera\n3. Tap Open Saruki Remote (no app yet? the page installs it from this TV)"
                        + "\n\nPairing code: " + io.github.sarukibrowser.link.Pairing.pretty(a.remote.code()),
                "No app: simple touchpad page", this::touchpadPage);
        if (phoneStatus != null) phoneStatus.setText(phonesText());
    }

    private String phonesText() {
        int n = a.remote.phones();
        return n == 0 ? "Waiting for your phone…" : n == 1 ? "Phone connected ✓" : n + " phones connected ✓";
    }

    /** The no-app phone page (Chrome on the phone, from the QR code). */
    void touchpadPage() {
        a.phone.start();
        String url = a.phone.url();
        if (url == null) { a.toast("The TV isn't on a network right now"); return; }
        qr("Control Saruki from your phone (no app)", url, "1. Phone on the same Wi-Fi as the TV\n2. Scan with the phone's camera\n3. Use the touchpad, or type into boxes on the page");
        if (phoneStatus != null) phoneStatus.setText(System.currentTimeMillis() - a.phone.lastSeen < 60000 ? "Phone connected ✓" : "Waiting for your phone…");
    }

    /** Saruki Remote settings: phones, pairing code. */
    void remoteSettings() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("Pair a phone", "Shows the QR code · code " + io.github.sarukibrowser.link.Pairing.pretty(a.remote.code()), () -> { close(); remote(); }));
        l.add(new Item("Phones connected now", a.remote.phones() + "", null));
        l.add(new Item("New pairing code", "Disconnects every phone; each one pairs again with the new QR code", () -> confirmAction(
                "Make a new pairing code?", "Every phone disconnects and has to scan the new QR code.", "Yes, make a new code", () -> {
                    a.remote.newCode();
                    a.toast("📱 New pairing code: " + io.github.sarukibrowser.link.Pairing.pretty(a.remote.code()));
                    close();
                    remote();
                }, this::remoteSettings)));
        l.add(new Item("No app? Simple touchpad page", "Chrome on the phone, from a QR code", () -> { close(); touchpadPage(); }));
        l.add(new Item("Get Saruki Remote", "Scan the pairing QR: the page installs it straight from this TV. Also on " + RemoteLink.REPO.replace("https://", ""), null));
        list("Saruki Remote", "Your phone as a trackpad, keyboard and camera for Saruki", l);
    }

    /** v1.7: a Saruki Remote phone connected or left. */
    void remoteChanged(boolean connected) {
        if (phoneStatus != null && "qr".equals(kind)) {
            phoneStatus.setText(phonesText());
            phoneStatus.setTextColor(a.remote.phones() > 0 ? 0xFF66BB6A : Ui.GOLD);
            if (connected) a.h.postDelayed(() -> { if (phoneStatus != null) close(); }, 1400);
        }
        // otherwise silent: the phone reconnects by itself every time its screen comes on
    }

    void phoneConnected() {
        if (phoneStatus != null) {
            phoneStatus.setText("Phone connected ✓");
            phoneStatus.setTextColor(0xFF66BB6A);
            a.h.postDelayed(() -> { if (phoneStatus != null) close(); }, 1400);
        } else a.toast("Phone connected");
    }

    void qr(String title, String text, String help) { qr(title, text, help, null, null); }

    void qr(String title, String text, String help, String extraLabel, Runnable extra) {
        Context c = a;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(28), Ui.dp(28), Ui.dp(34), Ui.dp(28));
        card.setBackground(Ui.round(Ui.PANEL, 24, 0x33FF5CA8, 1));
        ImageView iv = new ImageView(c);
        iv.setImageBitmap(qrBitmap(text, Ui.dp(240)));
        iv.setBackgroundColor(Color.WHITE);
        iv.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        card.addView(iv, new LinearLayout.LayoutParams(Ui.dp(260), Ui.dp(260)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(28), 0, 0, 0);
        col.addView(Ui.text(c, title, Ui.TITLE, Ui.TEXT, true));
        TextView hv = Ui.text(c, help, Ui.BODY, Ui.DIM, false);
        hv.setPadding(0, Ui.dp(12), 0, Ui.dp(12));
        hv.setLineSpacing(Ui.dp(4), 1);
        col.addView(hv);
        TextView u = Ui.text(c, text, Ui.CAPTION, Ui.ACCENT, false);
        u.setMaxLines(3);
        u.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(u);
        phoneStatus = Ui.text(c, "", Ui.BODY, Ui.GOLD, true);
        phoneStatus.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        col.addView(phoneStatus);
        TextView done = Ui.text(c, "Done", Ui.BODY, Ui.TEXT, false);
        done.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(10));
        Ui.focusable(done, 18, 1.06f);
        done.setOnClickListener(v -> close());
        if (extra != null) {
            LinearLayout btns = new LinearLayout(c);
            btns.addView(done, new LinearLayout.LayoutParams(-2, -2));
            TextView more = Ui.text(c, extraLabel, Ui.BODY, Ui.DIM, false);
            more.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(10));
            Ui.focusable(more, 18, 1.06f);
            more.setOnClickListener(v -> { close(); extra.run(); });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.leftMargin = Ui.dp(10);
            btns.addView(more, mlp);
            col.addView(btns, new LinearLayout.LayoutParams(-2, -2));
        } else {
            col.addView(done, new LinearLayout.LayoutParams(-2, -2));
        }
        card.addView(col, new LinearLayout.LayoutParams(Ui.dp(400), -2));
        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(0xAA000000);
        wrap.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        show(wrap, "qr", done);
    }

    static Bitmap qrBitmap(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), hh = m.getHeight();
            int[] px = new int[w * hh];
            for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        }
    }

    // =====================================================================================
    // welcome guide
    // =====================================================================================
    void welcome() {
        Prefs.setFlag("welcomed", true);
        List<Item> l = new ArrayList<>();
        l.add(new Item("Arrows move the pointer", "Tap for a small step, hold to glide faster. Push past the edge of the screen to scroll whatever is under the pointer, even boxes inside the page.", null));
        l.add(new Item("OK clicks · hold OK for more", "Holding OK opens options for what's under the pointer: new tab, play the video, drag, remove a stuck banner.", null));
        l.add(new Item("BACK goes back · hold BACK (or MENU) for the menu", "BACK only ever goes back a page. At the start it takes you Home; press it twice on Home to leave.", null));
        l.add(new Item("The bar at the top", "Back, Forward, Stop, Reload and the address. Move the pointer up to it; with link jumping, press UP at the top of the page.", null));
        l.add(new Item("Videos play in the TV Player", "When a page has a video, a ▶ Play in TV Player button appears at the top right. It plays smoothly with full remote control.", null));
        l.add(new Item("Pop-ups and redirects are stopped", "Only a link you click may open a new tab or take you to another site. When something is stopped, MENU shows a button to open it anyway, or to allow that site.", null));
        l.add(new Item("Your phone as trackpad, keyboard and camera", "Menu → Phone remote shows a QR code. Scan it with your phone to get Saruki Remote (it installs straight from the TV).", null));
        l.add(new Item("Desktop or mobile, bigger or smaller", "Menu → Switch site / Smaller / Bigger. Saruki remembers it for each site.", null));
        list("Welcome to Saruki", "A quick guide · this is also in Settings", l, "Got it", this::close);
    }

    // =====================================================================================
    // home screen
    // =====================================================================================
    View buildHome() {
        Context c = a;
        FrameLayout root = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF101826, 0xFF0B0E13, 0xFF0E1116});
        root.setBackground(bg);
        ScrollView sv = Ui.vScroll(c, 24);
        sv.setClipChildren(false);
        sv.setTag("homeScroll");
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(56), Ui.dp(30), Ui.dp(56), Ui.dp(30));
        col.setClipChildren(false);
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // v1.6 brand: the S-Line mark and the "saruki" wordmark in Archivo Black
        LinearLayout logo = new LinearLayout(c);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(c);
        mark.setImageResource(R.drawable.saruki_mark);
        mark.setAdjustViewBounds(true);
        logo.addView(mark, new LinearLayout.LayoutParams(-2, Ui.dp(38)));
        TextView word = Ui.text(c, "saruki", Ui.DISPLAY, Ui.TEXT, false);
        try { word.setTypeface(c.getResources().getFont(R.font.archivo_black)); } catch (RuntimeException ignored) { }
        word.setIncludeFontPadding(false);
        word.setPadding(Ui.dp(12), 0, 0, Ui.dp(3));
        logo.addView(word);
        top.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clock = Ui.text(c, new SimpleDateFormat("h:mm a", Locale.US).format(new Date()), Ui.TITLE, Ui.DIM, false);
        top.addView(clock);
        clock.postDelayed(new Runnable() {     // keep the clock right while Home stays open
            @Override public void run() {
                if (!clock.isAttachedToWindow()) return;
                clock.setText(new SimpleDateFormat("h:mm a", Locale.US).format(new Date()));
                clock.postDelayed(this, 15000);
            }
        }, 15000);
        col.addView(top);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(26), 0, Ui.dp(26), 0);
        bar.setFocusable(true);
        bar.setClickable(true);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, Ui.round(0xFF2B3647, 32, Ui.ACCENT, 3));
        sl.addState(new int[]{}, Ui.round(0xFF1E2531, 32, 0x33FF5CA8, 1));
        bar.setBackground(sl);
        // v1.7.1: the bar no longer grows on focus. It is as wide as the screen, so growing pushed its rounded ends
        // past the edge of what can be drawn and they were cut flat. The pink ring is drawn inside the bar instead.
        ImageView si = new ImageView(c);
        si.setImageResource(R.drawable.ic_search);
        si.setColorFilter(Ui.DIM);
        bar.addView(si, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));
        TextView bt = Ui.text(c, "Search or type a web address", Ui.TITLE, Ui.DIM, false);
        bt.setPadding(Ui.dp(14), 0, 0, 0);
        bar.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(Ui.text(c, "OK to type", Ui.CAPTION, Ui.DIM, false));
        bar.setOnClickListener(v -> address(""));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(62));
        bp.topMargin = Ui.dp(22);
        col.addView(bar, bp);
        bar.setId(View.generateViewId());
        bar.setTag("focus");

        // v1.7.1: everything below the search bar is centered on the screen. The chips row, the Favorites block and
        // the Continue block are each centered; the labels sit on the left edge of their own block.
        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(0, Ui.dp(16), 0, Ui.dp(6));
        chips.setClipChildren(false);
        chip(chips, "📱  Phone remote & keyboard", this::remote);
        View firstChip = chips.getChildAt(0);
        firstChip.setId(View.generateViewId());
        bar.setNextFocusDownId(firstChip.getId());
        int real = 0;
        for (Tab t : a.tabs) if (t.url != null && !BrowserActivity.HOME.equals(t.url)) real++;
        chip(chips, "Tabs (" + real + ")", this::tabs);
        chip(chips, "History", this::history);
        chip(chips, "Downloads", this::downloads);
        chip(chips, "Settings", this::settings);
        col.addView(chips, centered());

        LinearLayout favBlock = block(c);
        TextView fl = Ui.text(c, "Favorites", Ui.BODY, Ui.DIM, true);
        fl.setPadding(Ui.dp(8), Ui.dp(14), 0, Ui.dp(4));
        favBlock.addView(fl);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(6);
        grid.setClipChildren(false);
        List<Db.Entry> favs = Db.get(a).favs();
        for (Db.Entry en : favs) grid.addView(favTile(en));
        favBlock.addView(grid);
        col.addView(favBlock, centered());

        List<Tab> open = new ArrayList<>();
        for (Tab t : a.tabs) if (t.url != null && !BrowserActivity.HOME.equals(t.url)) open.add(t);
        if (!open.isEmpty()) {
            LinearLayout contBlock = block(c);
            TextView ol = Ui.text(c, "Continue", Ui.BODY, Ui.DIM, true);
            ol.setPadding(Ui.dp(8), Ui.dp(16), 0, Ui.dp(4));
            contBlock.addView(ol);
            LinearLayout r = new LinearLayout(c);
            r.setClipChildren(false);
            for (int i = open.size() - 1; i >= 0 && open.size() - i <= 4; i--) {
                Tab t = open.get(i);
                LinearLayout card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
                Ui.focusable(card, 14, 1.06f);
                TextView tt = Ui.text(c, t.title, Ui.CAPTION, Ui.TEXT, false);
                tt.setSingleLine(true);
                tt.setEllipsize(TextUtils.TruncateAt.END);
                TextView th = Ui.text(c, Prefs.host(t.url), Ui.CAPTION, Ui.DIM, false);
                th.setSingleLine(true);
                card.addView(tt);
                card.addView(th);
                card.setOnClickListener(v -> { a.activate(t); if (t == a.cur) a.hideHome(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(200), -2);
                lp.setMargins(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));   // v1.7.1: even on both sides, so the row centers
                r.addView(card, lp);
            }
            contBlock.addView(r);
            col.addView(contBlock, centered());
        }
        fetchMissingIcons(favs, grid);
        return root;
    }

    /** v1.7.1: a column that is as wide as what it holds, so it can be centered as one block. */
    private static LinearLayout block(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setClipChildren(false);
        return b;
    }

    private static LinearLayout.LayoutParams centered() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /**
     * v1.7.1: the button or tile on Home under the pointer, or null. x, y are the pointer's position in the area it
     * moves in, which is also Home's own box (Home fills that area). The smallest clickable box under the point wins.
     */
    View homeTargetAt(View home, float x, float y) {
        if (home == null) return null;
        View[] best = {null};
        long[] bestArea = {Long.MAX_VALUE};
        findAt(home, 0, 0, x, y, best, bestArea);
        return best[0];
    }

    /** Walks Home's views by their layout boxes (not their grown-on-focus size), looking for clickable buttons. */
    private static void findAt(View v, float left, float top, float x, float y, View[] best, long[] bestArea) {
        if (v.getVisibility() != View.VISIBLE) return;
        boolean inside = x >= left && x < left + v.getWidth() && y >= top && y < top + v.getHeight();
        if (v.isFocusable() && v.isClickable() && v.isEnabled() && inside) {
            long area = (long) v.getWidth() * v.getHeight();
            if (area < bestArea[0]) { bestArea[0] = area; best[0] = v; }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                findAt(ch, left + ch.getLeft() - g.getScrollX(), top + ch.getTop() - g.getScrollY(), x, y, best, bestArea);
            }
        }
    }

    /** v1.7.1: scrolls Home up or down (the pointer pushed past the screen edge, or a two-finger scroll on the phone). */
    void homeScroll(View home, float dy) {
        if (home == null) return;
        View sv = home.findViewWithTag("homeScroll");
        if (sv != null) sv.scrollBy(0, Math.round(dy));
    }

    void focusHome(View home) {
        if (home == null) return;
        View f = home.findViewWithTag("focus");
        if (f != null) f.post(f::requestFocus);
    }

    private View favTile(Db.Entry en) {
        Context c = a;
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), Ui.dp(8));
        Ui.focusable(t, 16, 1.1f);
        String host = Prefs.host(en.url);
        FrameLayout iconBox = new FrameLayout(c);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap b = loadIcon(host);
        TextView letter = Ui.text(c, host.isEmpty() ? "?" : host.substring(0, 1).toUpperCase(Locale.US), 24, Color.WHITE, true);
        letter.setGravity(Gravity.CENTER);
        if (b != null) { iv.setImageBitmap(b); letter.setVisibility(View.GONE); }
        else letter.setBackground(Ui.letterBg(host));
        iconBox.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        iconBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        iconBox.setTag(host);
        t.addView(iconBox, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
        TextView l = Ui.text(c, en.title, Ui.CAPTION, Ui.TEXT, false);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(8), 0, 0);
        t.addView(l, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> a.navigate(en.url));
        t.setOnLongClickListener(v -> {
            List<Item> items = new ArrayList<>();
            items.add(new Item("Open in a new tab", null, () -> { close(); a.openTab(en.url, 0); }));
            items.add(new Item("Move to the front", null, () -> { Db.get(a).moveFavFirst(en.url); close(); a.showHome(); }));
            items.add(new Item("Remove from Home", null, () -> { Db.get(a).removeFav(en.url); close(); a.showHome(); }));
            list(en.title, en.url, items);
            return true;
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = Ui.dp(122);
        lp.height = Ui.dp(112);
        lp.setMargins(Ui.dp(7), Ui.dp(6), Ui.dp(7), Ui.dp(6));   // v1.7.1: even on both sides, so the grid centers
        t.setLayoutParams(lp);
        return t;
    }

    // =====================================================================================
    // site icons for the home tiles
    // =====================================================================================
    private File iconFile(String host) {
        File d = new File(a.getFilesDir(), "icons");
        d.mkdirs();
        return new File(d, host.replaceAll("[^a-z0-9.-]", "_") + ".png");
    }

    private Bitmap loadIcon(String host) {
        File f = iconFile(host);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private void storeIcon(String host, Bitmap b) {
        if (b == null || b.getWidth() < 16) return;
        Bitmap old = loadIcon(host);
        if (old != null && old.getWidth() >= b.getWidth()) return;
        try (FileOutputStream o = new FileOutputStream(iconFile(host))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception ignored) { }
    }

    void saveSmallIcon(String url, Bitmap b) {
        String host = Prefs.host(url);
        if (!host.isEmpty() && loadIcon(host) == null) storeIcon(host, b);
    }

    void maybeSaveIcon(String url) {
        String host = Prefs.host(url);
        Bitmap old = loadIcon(host);
        if (host.isEmpty() || (old != null && old.getWidth() >= 96)) return;
        a.js("icon()", r -> {
            String iconUrl;
            try { iconUrl = new JSONArray("[" + r + "]").getString(0); } catch (Exception e) { return; }
            if (iconUrl == null || !iconUrl.startsWith("http")) return;
            new Thread(() -> storeIcon(host, fetchBitmap(iconUrl))).start();
        });
    }

    private void fetchMissingIcons(List<Db.Entry> favs, GridLayout grid) {
        for (Db.Entry en : favs) {
            String host = Prefs.host(en.url);
            if (host.isEmpty() || loadIcon(host) != null) continue;
            long tried = Prefs.p.getLong("icontry_" + host, 0);
            if (System.currentTimeMillis() - tried < 3L * 24 * 3600 * 1000) continue;
            Prefs.p.edit().putLong("icontry_" + host, System.currentTimeMillis()).apply();
            String base = Uri.parse(en.url).getScheme() + "://" + Uri.parse(en.url).getHost();
            new Thread(() -> {
                Bitmap b = fetchBitmap(base + "/apple-touch-icon.png");
                if (b == null) b = fetchBitmap(base + "/favicon.ico");
                if (b == null) return;
                storeIcon(host, b);
                Bitmap fb = b;
                a.h.post(() -> {
                    View box = grid.findViewWithTag(host);
                    if (box instanceof FrameLayout) {
                        ((ImageView) ((FrameLayout) box).getChildAt(1)).setImageBitmap(fb);
                        ((FrameLayout) box).getChildAt(0).setVisibility(View.GONE);
                    }
                });
            }).start();
        }
    }

    private static Bitmap fetchBitmap(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] b = readAll(in);
                return BitmapFactory.decodeByteArray(b, 0, b.length);
            }
        } catch (Exception e) { return null; }
    }
}
