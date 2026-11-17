package io.github.sarukibrowser.tv;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class BrowserActivity extends Activity implements PhoneServer.Commands, Choreographer.FrameCallback {
    static final String HOME = "saruki:home";

    // ---- views ----
    FrameLayout root, webHolder, fsHolder, panelHost;
    CursorView cursor;
    View progress;
    TextView chip, pill, toastView;
    View home;

    // ---- browser state ----
    WebView web;
    Tab cur;
    final List<Tab> tabs = new ArrayList<>();
    volatile String curHost = "";
    String desktopUA, mobileUA, defaultUA;
    String chromeMajor = "130";
    String script;
    boolean docStart;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    boolean fitMode;
    volatile boolean pageVideo;
    String pendingPopup;
    long lastCrashAt;
    final Handler h = new Handler(Looper.getMainLooper());
    Panels panels;
    PhoneServer phone;
    PadLink pad;
    Calls calls;
    /** v1.7: Saruki Remote, Saruki's own phone app (trackpad, typing, camera), and typing into page boxes from it. */
    RemoteLink remote;
    Typing typing;
    String typeScript;
    /** When the last click happened (OK, the phone trackpad...): a text box focused right after it was clicked. */
    long lastClickAt;
    private boolean inFront;
    /** v1.7: the bar above pages (Back, Forward, Stop, Reload, address); pages start {@link #barH} pixels down. */
    TopBar bar;
    int barH;
    String callsScript;

    // ---- cursor engine ----
    static final int L = 0, R = 1, U = 2, D = 3;
    final boolean[] held = new boolean[4];
    final long[] heldAt = new long[4];
    boolean looping;
    long lastFrameNs;
    float tapRemX, tapRemY;
    long lastHover, lastHit, lastInput, lastJump;
    int targetKind = 0, tSx, tSy;
    long targetAt;
    float jsAccX, jsAccY;
    long lastJsScroll;
    boolean okHeld, okLong, dragging, dragByHold;
    long dragDownT;
    boolean backLong, swallowBackUp, swallowMenuUp;
    long videoCursorUntil;
    long lastBackOnHome;

    // ======================================================================================
    // lifecycle
    // ======================================================================================
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.init(this);
        Ui.init(this);
        AdBlock.load(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        script = asset("saruki.js");
        callsScript = asset("calls.js");
        typeScript = asset("type.js");
        docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        try {
            PackageInfo pi = WebViewCompat.getCurrentWebViewPackage(this);
            if (pi != null && pi.versionName != null) chromeMajor = pi.versionName.split("\\.")[0];
        } catch (Exception ignored) { }
        desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeMajor + ".0.0.0 Safari/537.36";
        mobileUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeMajor + ".0.0.0 Mobile Safari/537.36";
        defaultUA = WebSettings.getDefaultUserAgent(this);

        buildViews();
        panels = new Panels(this);
        calls = new Calls(this);
        typing = new Typing(this);
        phone = new PhoneServer(this, this);
        phone.start();
        remote = new RemoteLink(this);
        remote.start();
        pad = new PadLink(this, this::onPad, line -> { if (calls != null) calls.onSuite(line); });
        Tab active = restoreSession();
        boolean opened = handleIntent(getIntent(), true);
        if (!opened && active != null) activate(active);
        if (cur == null) { Tab t = new Tab(HOME); tabs.add(t); activate(t); }
        // v1.8: first start next to the earlier Saruki → offer to bring its data over (instead of the welcome guide)
        if (!Prefs.flag("migrateAsked", false) && !Prefs.flag("welcomed", false) && Migrate.available(this))
            h.postDelayed(() -> panels.importOffer(true), 600);
        else if (!Prefs.flag("welcomed", false)) h.postDelayed(() -> panels.welcome(), 600);
        h.postDelayed(idleCheck, 2000);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i, false); }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == Calls.REQ_PERMS) calls.onPermissionsResult();
    }


    private boolean handleIntent(Intent i, boolean first) {
        if (i != null && Intent.ACTION_WEB_SEARCH.equals(i.getAction())) {
            String u = toUrl(i.getStringExtra(android.app.SearchManager.QUERY));
            if (u == null) return false;
            openTab(u, 0);
            return true;
        }
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return false;
        String url = i.getData().toString();
        String type = i.getType();
        if ((type != null && type.startsWith("video/")) || (!url.startsWith("http") && Media.isMedia(url) != null)) {
            Intent p = new Intent(this, PlayerActivity.class);
            p.setData(i.getData());
            p.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(p);
            return false;
        }
        if (!url.startsWith("http")) return false;
        if (cur != null && url.equals(cur.url)) return true;
        openTab(url, 0);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        phone.start();
        inFront = true;
        pad.resume();
        remote.start();
        remote.resume();
        applyOverscan();
    }

    @Override protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        inFront = false;
        pad.pause();
        remote.pause();
        if (dragging) endDrag();
        saveSession();
        CookieManager.getInstance().flush();
    }

    @Override protected void onStop() { super.onStop(); phone.stop(); }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) for (Tab t : tabs) if (t != cur) t.thumb = null;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        calls.release();
        pad.close();
        remote.stop();
        if (web != null) web.destroy();
    }

    // ======================================================================================
    // views
    // ======================================================================================
    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        webHolder = new FrameLayout(this);
        barH = Ui.dp(TopBar.HEIGHT_DP);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-1, -1);
        wp.topMargin = barH;
        root.addView(webHolder, wp);
        bar = new TopBar(this);
        root.addView(bar.view, TopBar.params());
        fsHolder = new FrameLayout(this);
        fsHolder.setBackgroundColor(Color.BLACK);
        fsHolder.setVisibility(View.GONE);
        root.addView(fsHolder, new FrameLayout.LayoutParams(-1, -1));

        progress = new View(this);
        progress.setBackgroundColor(Ui.ACCENT);
        progress.setPivotX(0);
        progress.setScaleX(0);
        FrameLayout.LayoutParams prp = new FrameLayout.LayoutParams(-1, Ui.dp(3), Gravity.TOP);
        prp.topMargin = barH - Ui.dp(3);
        root.addView(progress, prp);

        chip = Ui.text(this, "", Ui.CAPTION, Ui.TEXT, false);
        chip.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        chip.setBackground(Ui.round(0xE01B212B, 18, 0x33FF5CA8, 1));
        chip.setAlpha(0);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cp.setMargins(Ui.dp(16), barH + Ui.dp(12), 0, 0);
        root.addView(chip, cp);

        pill = Ui.text(this, "▶  Play in TV Player", Ui.CAPTION, Ui.ON_ACCENT, true);
        pill.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9));
        pill.setBackground(Ui.round(Ui.ACCENT, 20, 0xFFFFFFFF, 1.5f));
        pill.setVisibility(View.GONE);
        pill.setOnClickListener(v -> playMedia());
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pp.setMargins(0, barH + Ui.dp(12), Ui.dp(16), 0);
        root.addView(pill, pp);

        cursor = new CursorView(this);
        root.addView(cursor, CursorView.params(cursor.sizePx()));

        toastView = Ui.text(this, "", Ui.BODY, Ui.TEXT, false);
        toastView.setPadding(Ui.dp(18), Ui.dp(11), Ui.dp(18), Ui.dp(11));
        toastView.setBackground(Ui.round(0xEE1B212B, 22, 0x55FF5CA8, 1));
        toastView.setAlpha(0);
        toastView.setMaxWidth(Ui.dp(640));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tp.bottomMargin = Ui.dp(36);
        root.addView(toastView, tp);

        panelHost = new FrameLayout(this);
        root.addView(panelHost, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.post(() -> cursor.moveTo(cursor.areaW() / 2f, cursor.areaH() / 2f));
    }

    void applyOverscan() {
        int pct = Prefs.overscan();
        int w = getResources().getDisplayMetrics().widthPixels, hh = getResources().getDisplayMetrics().heightPixels;
        root.setPadding(w * pct / 100, hh * pct / 100, w * pct / 100, hh * pct / 100);
    }

    void toast(String s) {
        toastView.setText(s);
        toastView.animate().cancel();
        long stay = Math.min(6000, 1800 + s.length() * 45L);
        if (toastView.getAlpha() < 0.05f) toastView.setTranslationY(Ui.dp(14));
        toastView.animate().alpha(1).translationY(0).setStartDelay(0).setDuration(170).setInterpolator(Ui.EASE)
                .withEndAction(() -> toastView.animate().alpha(0).translationY(Ui.dp(8)).setStartDelay(stay).setDuration(320).start())
                .start();
    }

    void showChip(String s) {
        chip.setText(s);
        chip.animate().cancel();
        if (chip.getAlpha() < 0.05f) chip.setTranslationY(-Ui.dp(10));
        chip.animate().alpha(1).translationY(0).setStartDelay(0).setDuration(180).setInterpolator(Ui.EASE)
                .withEndAction(() -> chip.animate().alpha(0).setStartDelay(3000).setDuration(450).start())
                .start();
    }

    // ======================================================================================
    // tabs and the single live WebView
    // ======================================================================================
    Tab openTab(String url, int parentId) {
        Tab t = new Tab(url);
        t.parentId = parentId;
        int at = cur == null ? tabs.size() : tabs.indexOf(cur) + 1;
        tabs.add(Math.min(at, tabs.size()), t);
        activate(t);
        return t;
    }

    void activate(Tab t) {
        if (t == cur && web != null) { hideHomeIfPage(); return; }
        if (cur != null && web != null) suspendCurrent();
        cur = t;
        curHost = Prefs.host(t.url);
        updatePill();
        web = buildWebView(t);
        webHolder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        if (t.url == null || HOME.equals(t.url)) {
            showHome();
        } else {
            hideHome();
            applyMode(Prefs.host(t.url));
            if (t.state == null || web.restoreState(t.state) == null) web.loadUrl(t.url);
        }
        t.state = null;
        web.requestFocus();
    }

    private void suspendCurrent() {
        if (customView != null) exitFullscreen();
        fitMode = false;
        try {
            int w = Math.max(1, web.getWidth() / 4), hh = Math.max(1, web.getHeight() / 4);
            Bitmap bm = Bitmap.createBitmap(w, hh, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(bm);
            c.scale(0.25f, 0.25f);
            web.draw(c);
            cur.thumb = bm;
        } catch (Throwable ignored) { }
        Bundle st = new Bundle();
        web.saveState(st);
        cur.state = st;
        destroyWeb();
    }

    private void destroyWeb() {
        if (web == null) return;
        WebView w = web;
        calls.reset();
        calls.forgetOnce();
        typing.pageGone();
        web = null;
        webHolder.removeView(w);
        w.stopLoading();
        w.onPause();
        w.setWebChromeClient(null);
        h.postDelayed(w::destroy, 1500);
        pageVideo = false;
        setKeepOn(false);
    }

    void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(i);
        if (t == cur) {
            destroyWeb();
            cur = null;
            Tab next = null;
            for (Tab x : tabs) if (x.id == t.parentId) next = x;
            if (next == null && !tabs.isEmpty()) next = tabs.get(Math.max(0, i - 1));
            if (next == null) { next = new Tab(HOME); tabs.add(next); }
            activate(next);
        }
    }

    WebView buildWebView(Tab tab) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, Prefs.dark());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(Color.WHITE);
        w.setWebViewClient(new Client(tab));
        w.setWebChromeClient(new Chrome(tab));
        w.addJavascriptInterface(new Bridge(tab), "SarukiNative");
        calls.attach(w);   // v1.5 video calls: before any page loads
        typing.attach(w);  // v1.7 Saruki Remote typing
        if (docStart) {
            try { WebViewCompat.addDocumentStartJavaScript(w, script, Collections.singleton("*")); } catch (Exception e) { docStart = false; }
            if (docStart && Calls.supported()) {
                try { WebViewCompat.addDocumentStartJavaScript(w, callsScript, Collections.singleton("*")); } catch (Exception ignored) { }
            }
            if (docStart && Typing.supported()) {
                try { WebViewCompat.addDocumentStartJavaScript(w, typeScript, Collections.singleton("*")); } catch (Exception ignored) { }
            }
        }
        w.setDownloadListener((url, ua, cd, mime, len) -> onDownload(url, ua, cd, mime, len));
        // Smoother scrolling: draw a little past the screen edges ahead of time, and no stretchy glow at page ends.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) WebSettingsCompat.setOffscreenPreRaster(s, true);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(true);
        w.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        appliedDesktop = null;
        return w;
    }

    Boolean appliedDesktop;

    /** Desktop or mobile: the user-agent string, the client-hint metadata and the layout width all change together. */
    void applyMode(String host) {
        if (web == null) return;
        boolean google = host.equals("accounts.google.com");
        boolean d = Prefs.desktop(host);
        WebSettings s = web.getSettings();
        // Google's sign-in page is left with the WebView's own identity: we don't disguise the app on Google sign-in.
        String ua = google ? defaultUA : (d ? desktopUA : mobileUA);
        if (!ua.equals(s.getUserAgentString())) s.setUserAgentString(ua);
        if (!google && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            if (appliedDesktop == null || appliedDesktop != d) {
                try { WebSettingsCompat.setUserAgentMetadata(s, meta(d)); } catch (Exception ignored) { }
            }
        }
        appliedDesktop = d;
    }

    private UserAgentMetadata meta(boolean desktop) {
        List<UserAgentMetadata.BrandVersion> brands = Arrays.asList(
                new UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(chromeMajor).setFullVersion(chromeMajor + ".0.0.0").build(),
                new UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build());
        return new UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(chromeMajor + ".0.0.0")
                .setPlatform(desktop ? "Windows" : "Android")
                .setPlatformVersion(desktop ? "10.0.0" : "14.0.0")
                .setArchitecture(desktop ? "x86" : "arm")
                .setModel(desktop ? "" : "Pixel 8")
                .setMobile(!desktop)
                .build();
    }

    boolean modeDiffers(String host) {
        return appliedDesktop == null || appliedDesktop != Prefs.desktop(host) || host.equals("accounts.google.com") != defaultUA.equals(web.getSettings().getUserAgentString());
    }

    /** Address bar, phone and home tiles all come through here. */
    void navigate(String input) {
        String url = toUrl(input);
        if (url == null) return;
        if (cur == null) { openTab(url, 0); return; }
        if (web == null) { cur.url = url; activate(cur); return; }
        hideHome();
        String host = Prefs.host(url);
        applyMode(host);
        curHost = host;
        web.loadUrl(url);
        web.requestFocus();
    }

    static final Pattern LOOKS_URL = Pattern.compile("^([a-z][a-z0-9+.-]*://)?([\\w-]+\\.)+[a-z]{2,}(:\\d+)?(/.*)?$|^localhost(:\\d+)?(/.*)?$|^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE);

    static String toUrl(String in) {
        if (in == null) return null;
        String s = in.trim();
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^(https?|file|about|data):.*")) return s;
        if (!s.contains(" ") && LOOKS_URL.matcher(s).matches()) return (s.contains("://") ? "" : "https://") + s;
        return Prefs.searchUrl(s);
    }

    // ======================================================================================
    // home screen
    // ======================================================================================
    boolean homeFromBack;

    void showHome() { showHome(false); }

    void showHome(boolean fromBack) {
        homeFromBack = fromBack;
        if (customView != null) exitFullscreen();
        // Home covers the page, so a video left playing behind it would only be heard, not seen: pause it.
        if (web != null) web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})", null);
        boolean fresh = home == null;
        if (home != null) root.removeView(home);
        home = panels.buildHome();
        // v1.7.1: Home goes under the pointer (and the toasts), so the pointer stays visible and usable on Home.
        root.addView(home, root.indexOfChild(cursor), new FrameLayout.LayoutParams(-1, -1));
        if (fresh) { home.setAlpha(0); home.animate().alpha(1).setDuration(180).setInterpolator(Ui.EASE).start(); }
        bar.hover(-1, -1);
        cursor.setKind(CursorView.NORMAL);
        if (!panels.isOpen()) cursor.showCursor(true);
        pill.setVisibility(View.GONE);
        panels.focusHome(home);
    }

    void hideHome() {
        if (home == null) return;
        View old = home;
        home = null;
        // Fade Home away over the page that is loading underneath, instead of a hard cut.
        if (old instanceof ViewGroup) ((ViewGroup) old).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        old.clearFocus();
        old.animate().alpha(0).setDuration(170).setInterpolator(Ui.EASE).withEndAction(() -> root.removeView(old)).start();
        updatePill();
        if (!panels.isOpen()) cursor.showCursor(true);
        if (web != null) web.requestFocus();
    }

    private void hideHomeIfPage() { if (cur != null && cur.url != null && !HOME.equals(cur.url)) hideHome(); }

    boolean homeShown() { return home != null; }

    // ======================================================================================
    // keys
    // ======================================================================================
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        lastInput = SystemClock.uptimeMillis();
        if (down && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER)) lastClickAt = lastInput;

        if (panels.isOpen()) {
            if (k == KeyEvent.KEYCODE_BACK || (k == KeyEvent.KEYCODE_MENU && panels.isQuickMenu())) {
                if (!down) {
                    if (k == KeyEvent.KEYCODE_BACK && swallowBackUp) swallowBackUp = false;
                    else if (k == KeyEvent.KEYCODE_MENU && swallowMenuUp) swallowMenuUp = false;
                    else panels.back();   // v1.8.2: a confirm or a choice list goes back to the list it came from
                }
                return true;
            }
            // v1.8.2: in a list, UP / DOWN scroll it when there is text to read that no button can be focused on
            if (down && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && panels.listKey(k)) return true;
            return super.dispatchKeyEvent(e);
        }
        if (homeShown()) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) {
                    long now = SystemClock.uptimeMillis();
                    if (!homeFromBack && cur != null && cur.url != null && !HOME.equals(cur.url) && web != null) { hideHome(); return true; }
                    if (now - lastBackOnHome < 2500) moveTaskToBack(true);
                    else { lastBackOnHome = now; toast("Press BACK again to leave Saruki"); }
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_MENU) { if (down && e.getRepeatCount() == 0) { swallowMenuUp = true; panels.quickMenu(); } return true; }
            if (k == KeyEvent.KEYCODE_SEARCH) { if (down) panels.address(""); return true; }
            // v1.7.1: the remote's arrows jump between Home's buttons as before; the pointer steps aside until the
            // phone trackpad moves it again (so it never points at one thing while another is highlighted).
            if (down && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) cursor.showCursor(false);
            return super.dispatchKeyEvent(e);
        }

        boolean videoKeys = (customView != null || fitMode) && SystemClock.uptimeMillis() > videoCursorUntil && !dragging;
        if (videoKeys && handleVideoKey(k, down, e)) return true;

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return dir(L, down, e);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return dir(R, down, e);
            case KeyEvent.KEYCODE_DPAD_UP: return dir(U, down, e);
            case KeyEvent.KEYCODE_DPAD_DOWN: return dir(D, down, e);
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                if (down) okDown(e); else okUp();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) { backLong = false; h.postDelayed(backLongRun, 650); }
                } else {
                    h.removeCallbacks(backLongRun);
                    if (!backLong) onBackShort();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) { swallowMenuUp = true; panels.quickMenu(); }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (down) panels.address(cur == null ? "" : cur.url);
                return true;
            case KeyEvent.KEYCODE_BOOKMARK:
                if (down) panels.favorites();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP:
                if (down) pageScroll(-1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) pageScroll(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0) playPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) {
                    int dir = k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 1 : -1;
                    if (pageVideo) jsSeek(dir * 10); else pageScroll(dir);
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** After 5 s without input the pointer fades to a faint dot (OK still clicks); after 30 s it hides. */
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            long idle = SystemClock.uptimeMillis() - lastInput;
            if (cursor.isShown2() && !panels.isOpen() && !dragging && !anyHeld()) {
                if (idle > 30000) cursor.showCursor(false);
                else if (idle > 5000) cursor.dim();
            }
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable backLongRun = () -> { backLong = true; swallowBackUp = true; panels.quickMenu(); };

    void onBackShort() {
        if (dragging) { endDrag(); return; }
        if (customView != null) { exitFullscreen(); return; }
        if (fitMode) { toggleFit(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        if (cur != null && cur.parentId != 0) {
            for (Tab t : tabs) if (t.id == cur.parentId) { Tab done = cur; activate(t); tabs.remove(done); toast("Closed the pop-up tab"); return; }
        }
        showHome(true);
    }

    /** Keys while a video fills the screen: OK pauses, left/right seek, up/down bring the pointer back for 6 s. */
    private boolean handleVideoKey(int k, boolean down, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) playPause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) {
                    int dir = (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) ? -1 : 1;
                    int r = e.getRepeatCount();
                    if (r > 0 && r % 3 != 0) return true;
                    jsSeek(dir * (r < 6 ? 10 : 30));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    videoCursorUntil = SystemClock.uptimeMillis() + 6000;
                    cursor.showCursor(true);
                    toast("Pointer on for a few seconds · BACK leaves full screen");
                }
                return true;
        }
        return false;
    }

    // ---------- pointer movement ----------
    private float unit() { return Math.max(1, cursor.areaW()) / 1920f; }
    private float speedFactor() { return 0.55f + Prefs.cursorSpeed() * 0.22f; }   // 1..5 -> 0.77 .. 1.65

    private boolean dir(int d, boolean down, KeyEvent e) {
        if (customView != null || fitMode) videoCursorUntil = SystemClock.uptimeMillis() + 6000;
        if (down) {
            if (okHeld && !dragging) startDrag(true);
            boolean wasHidden = !cursor.isShown2();
            cursor.showCursor(true);
            if (Prefs.jumpMode() && !dragging) {
                long now = SystemClock.uptimeMillis();
                if (e.getRepeatCount() == 0 || now - lastJump > 170) { lastJump = now; if (overBar()) barJump(d); else jump(d); }
                return true;
            }
            if (e.getRepeatCount() > 0 || held[d]) return true;
            if (wasHidden && e.getRepeatCount() == 0) { /* first press only wakes the pointer up */ }
            held[d] = true;
            heldAt[d] = SystemClock.uptimeMillis();
            startLoop();
        } else {
            if (!held[d]) return true;
            held[d] = false;
            long t = SystemClock.uptimeMillis() - heldAt[d];
            if (t < 150) tap(d);
            if (!anyHeld()) h.postDelayed(this::afterMove, 160);
        }
        return true;
    }

    private boolean anyHeld() { return held[0] || held[1] || held[2] || held[3]; }

    /** A short press always moves a small, fixed step; at the edge of the screen it scrolls instead. */
    private void tap(int d) {
        float step = 46 * unit() * speedFactor();
        float dx = d == L ? -step : d == R ? step : 0, dy = d == U ? -step : d == D ? step : 0;
        boolean atEdge = (d == L && cursor.x <= 1) || (d == R && cursor.x >= cursor.areaW() - 2)
                || (d == U && cursor.y <= 1) || (d == D && cursor.y >= cursor.areaH() - 2);
        if (atEdge && !dragging) {
            if (d == U || d == D) smoothScroll(0, (d == D ? 1 : -1) * cursor.areaH() * 0.28f);
            else smoothScroll((d == R ? 1 : -1) * cursor.areaW() * 0.28f, 0);
            return;
        }
        tapRemX += dx;
        tapRemY += dy;
        startLoop();
    }

    private void startLoop() {
        if (looping) return;
        looping = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long ns) {
        float dt = lastFrameNs == 0 ? 1 / 60f : Math.min(0.05f, (ns - lastFrameNs) / 1e9f);
        lastFrameNs = ns;
        long now = SystemClock.uptimeMillis();
        float f = speedFactor() * unit();
        float vx = 0, vy = 0;
        for (int d = 0; d < 4; d++) {
            if (!held[d] || now - heldAt[d] < 150) continue;
            float t = (now - heldAt[d] - 150) / 1000f;
            float k = Math.min(1f, t / 1.1f);
            float v = (260 + 1700 * k * k) * f;          // px per second, eases in so short holds stay precise
            if (d == L) vx -= v; else if (d == R) vx += v; else if (d == U) vy -= v; else vy += v;
            if (now - heldAt[d] > 12000) held[d] = false;  // safety if the remote never sends key-up
        }
        float mx = vx * dt, my = vy * dt;
        // Frame-rate independent easing: the same feel at 30, 50 or 60 frames a second.
        float frames = dt * 60f;
        if (Math.abs(tapRemX) > 0.4f || Math.abs(tapRemY) > 0.4f) {
            float k = 1f - (float) Math.pow(1 - 0.36f, frames);
            float px = tapRemX * k, py = tapRemY * k;
            mx += px; my += py;
            tapRemX -= px; tapRemY -= py;
        } else { tapRemX = 0; tapRemY = 0; }
        if (Math.abs(phoneRemX) > 0.4f || Math.abs(phoneRemY) > 0.4f) {
            float k = 1f - (float) Math.pow(1 - 0.5f, frames);
            float px = phoneRemX * k, py = phoneRemY * k;
            mx += px; my += py;
            phoneRemX -= px; phoneRemY -= py;
        } else { phoneRemX = 0; phoneRemY = 0; }
        // Phone trackpad (TV Overlay Suite): 70% of what arrived goes out this frame and nearly all the rest the next,
        // so the pointer keeps up with the finger but a Wi-Fi hiccup that bunches two messages doesn't make it jump.
        if (Math.abs(padRemX) > 0.3f || Math.abs(padRemY) > 0.3f) {
            float k = 1f - (float) Math.pow(1 - 0.7f, frames);
            float px = padRemX * k, py = padRemY * k;
            mx += px; my += py;
            padRemX -= px; padRemY -= py;
        } else { mx += padRemX; my += padRemY; padRemX = 0; padRemY = 0; }
        if (mx != 0 || my != 0) move(mx, my);
        stepScroll(frames);
        if (anyHeld() || tapRemX != 0 || tapRemY != 0 || phoneRemX != 0 || phoneRemY != 0 || padRemX != 0 || padRemY != 0
                || scrollRemX != 0 || scrollRemY != 0)
            Choreographer.getInstance().postFrameCallback(this);
        else looping = false;
    }

    // ---------- smooth scrolling ----------
    float phoneRemX, phoneRemY;     // phone touchpad movement still to glide through
    float padRemX, padRemY;         // phone trackpad (TV Overlay Suite app) movement still to apply
    float scrollRemX, scrollRemY;   // page scroll still to glide through (native, in screen pixels)

    /** Eases the page itself towards where it should be: about 20% of the rest every frame, like a flick that settles. */
    private void stepScroll(float frames) {
        if (scrollRemX == 0 && scrollRemY == 0) return;
        if (web == null) { scrollRemX = scrollRemY = 0; return; }
        float k = 1f - (float) Math.pow(1 - 0.2f, frames);
        int sx = Math.round(scrollRemX * k), sy = Math.round(scrollRemY * k);
        if (sx == 0 && scrollRemX != 0) sx = (int) Math.signum(scrollRemX);
        if (sy == 0 && scrollRemY != 0) sy = (int) Math.signum(scrollRemY);
        int bx = web.getScrollX(), by = web.getScrollY();
        web.scrollBy(sx, sy);
        boolean stuck = web.getScrollX() == bx && web.getScrollY() == by;
        scrollRemX -= sx;
        scrollRemY -= sy;
        if (stuck || Math.abs(scrollRemX) < 1) scrollRemX = 0;
        if (stuck || Math.abs(scrollRemY) < 1) scrollRemY = 0;
    }

    /**
     * Smoothly scrolls whatever is under the pointer by (dx, dy) screen pixels. The page itself glides natively;
     * a box inside the page glides with the page engine's own smooth scrolling.
     */
    void smoothScroll(float dx, float dy) {
        if (web == null) return;
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy);
        if (Math.abs(dx) > Math.abs(dy)) sy = 0; else sx = 0;
        if (sx == 0 && sy == 0) return;
        float fdx = sx != 0 ? dx : 0, fdy = sy != 0 ? dy : 0;
        long now = SystemClock.uptimeMillis();
        if (now - targetAt <= 400 && sx == tSx && sy == tSy) {
            targetAt = now;
            applySmooth(fdx, fdy);
            return;
        }
        targetAt = now; tSx = sx; tSy = sy;
        js("target(" + (int) cursor.x + "," + (int) py() + "," + sx + "," + sy + ")", r -> {
            try { targetKind = Integer.parseInt(r.trim()); } catch (Exception e) { targetKind = 0; }
            applySmooth(fdx, fdy);
        });
    }

    private void applySmooth(float dx, float dy) {
        if (targetKind == 1) { js("sbs(" + Math.round(dx) + "," + Math.round(dy) + ")", null); return; }
        if ((dx > 0 && scrollRemX < 0) || (dx < 0 && scrollRemX > 0)) scrollRemX = 0;
        if ((dy > 0 && scrollRemY < 0) || (dy < 0 && scrollRemY > 0)) scrollRemY = 0;
        scrollRemX += dx;
        scrollRemY += dy;
        startLoop();
    }

    void move(float dx, float dy) {
        float nx = cursor.x + dx, ny = cursor.y + dy, ex = 0, ey = 0;
        int w = cursor.areaW(), hh = cursor.areaH();
        if (nx < 0) { ex = nx; nx = 0; }
        if (nx > w - 1) { ex = nx - (w - 1); nx = w - 1; }
        if (ny < 0) { ey = ny; ny = 0; }
        if (ny > hh - 1) { ey = ny - (hh - 1); ny = hh - 1; }
        cursor.moveTo(nx, ny);
        if (homeShown()) {
            // v1.7.1: on Home the pointer highlights the button under it; pushing past the top or bottom scrolls Home
            if (ey != 0) panels.homeScroll(home, ey * 1.8f);
            long now = SystemClock.uptimeMillis();
            if (now - lastHover > 50) { lastHover = now; homeHover(); }
            return;
        }
        if (dragging) { touch(MotionEvent.ACTION_MOVE, dragDownT); return; }
        if (ex != 0 || ey != 0) scrollAt(ex * 1.8f, ey * 1.8f, false);
        long now = SystemClock.uptimeMillis();
        if (now - lastHover > 90) { lastHover = now; hover(); }
        if (now - lastHit > 220) { lastHit = now; hitTest(); }
    }

    private void afterMove() {
        if (anyHeld() || dragging) return;
        if (homeShown()) { homeHover(); return; }
        if (web == null || customView != null) return;
        if (overBar()) { hover(); return; }
        hover();
        hitTest();
        if (Prefs.jumpMode()) return;
        // gentle magnet: if the pointer stopped just beside a link or button, slide onto it
        js("near(" + (int) cursor.x + "," + (int) py() + "," + (int) (26 * unit() * 1.0f) + ")", r -> {
            float[] p = point(r);
            if (p != null && !anyHeld()) { tapRemX = p[0] - cursor.x; tapRemY = p[1] - py(); startLoop(); h.postDelayed(this::hitTest, 200); }
        });
    }

    private void jump(int d) {
        js("jump('" + "lrud".charAt(d) + "'," + (int) cursor.x + "," + (int) py() + ")", r -> {
            float[] p = point(r);
            if (p != null) {
                tapRemX = p[0] - cursor.x; tapRemY = p[1] - py(); startLoop();
                h.postDelayed(this::hover, 150);
            } else if (d == U && r != null && r.contains("bar")) {
                // v1.8.2: UP with no link above and the page already at its top: onto the top bar's address
                if (bar.shown()) {
                    float[] t = bar.addressCenter();
                    js("clearHl()", null);
                    glideTo(t[0], t[1]);
                }
            } else if (d == U || d == D) {
                pageScroll(d == D ? 1 : -1);
            }
        });
    }

    /** v1.8.2: slides the pointer to a place in its own coordinates and lights up whatever is there. */
    private void glideTo(float x, float y) {
        tapRemX = x - cursor.x;
        tapRemY = y - cursor.y;
        startLoop();
        bar.highlightAt(x, y);
        h.postDelayed(this::hover, 200);
    }

    /**
     * v1.8.2: link jumping while the pointer is on the top bar. LEFT / RIGHT hop between Back, Forward, Stop, Reload and
     * the address (dimmed ones are skipped); OK clicks the one lit up; DOWN goes back to the first link on the page.
     */
    private void barJump(int d) {
        if (d == U) return;
        if (d == D) {
            bar.hover(-1, -1);
            js("firstLink(" + (int) cursor.x + ",0)", r -> {
                float[] p = point(r);
                if (p != null) glideTo(p[0], p[1] + barH);
                else glideTo(cursor.x, barH + Ui.dp(40));   // a page with no links: just below the bar
            });
            return;
        }
        float[] t = bar.hop(cursor.x, cursor.y, d == R ? 1 : -1);
        if (t != null) glideTo(t[0], t[1]);
    }

    /** v1.7.1: on Home, give the button under the pointer the pink ring (focus), so OK and a tap both click it. */
    private void homeHover() {
        if (!homeShown() || !cursor.isShown2()) return;
        View t = panels.homeTargetAt(home, cursor.x, cursor.y);
        if (t != null && !t.isFocused()) t.requestFocus();
        cursor.setKind(t != null ? CursorView.LINK : CursorView.NORMAL);
    }

    private void hover() {
        if (homeShown()) { homeHover(); return; }
        if (overBar()) { bar.hover(cursor.x, cursor.y); cursor.setKind(bar.at(cursor.x, cursor.y) != null ? CursorView.LINK : CursorView.NORMAL); return; }
        bar.hover(-1, -1);
        if (web == null || customView != null) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cursor.x, py(), 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        web.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void hitTest() {
        if (dragging) return;
        if (homeShown()) { homeHover(); return; }
        if (overBar()) { cursor.setKind(bar.at(cursor.x, cursor.y) != null ? CursorView.LINK : CursorView.NORMAL); return; }
        js("hit(" + (int) cursor.x + "," + (int) py() + ")", r -> {
            if (dragging) return;
            cursor.setKind(r.contains("c") ? CursorView.LINK : r.contains("t") ? CursorView.TEXT : CursorView.NORMAL);
        });
    }

    /** Scrolls whatever is under the pointer: an inner list, a drop-down, a chat box, or the page itself. */
    void scrollAt(float dx, float dy, boolean smooth) {
        if (web == null) return;
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy);
        if (Math.abs(dx) > Math.abs(dy)) sy = 0; else sx = 0;
        if (sx == 0 && sy == 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - targetAt > 300 || sx != tSx || sy != tSy) {
            targetAt = now; tSx = sx; tSy = sy;
            final int fx = sx, fy = sy;
            js("target(" + (int) cursor.x + "," + (int) py() + "," + fx + "," + fy + ")", r -> {
                try { targetKind = Integer.parseInt(r.trim()); } catch (Exception e) { targetKind = 0; }
            });
        }
        if (smooth) {
            js("sb(" + (int) (sx * Math.abs(dx)) + "," + (int) (sy * Math.abs(dy)) + ")", null);
            return;
        }
        jsAccX += sx != 0 ? dx : 0;
        jsAccY += sy != 0 ? dy : 0;
        if (targetKind == 0 && jsAccY != 0 && sx == 0) {
            web.scrollBy(0, Math.round(jsAccY));    // the page itself: native scrolling is the smoothest
            jsAccX = jsAccY = 0;
        } else if (now - lastJsScroll > 30) {
            lastJsScroll = now;
            js("sb(" + Math.round(jsAccX) + "," + Math.round(jsAccY) + ")", null);
            jsAccX = jsAccY = 0;
        }
    }

    void pageScroll(int dir) {
        cursor.showCursor(true);
        js("page(" + (int) cursor.x + "," + (int) py() + "," + dir + ")", null);
    }

    // ---------- OK button: click, long-press menu, hold-and-move drag ----------
    private final Runnable okLongRun = () -> {
        if (okHeld && !dragging) { okLong = true; panels.context(); }
    };

    private void okDown(KeyEvent e) {
        if (e.getRepeatCount() > 0) return;
        okHeld = true;
        okLong = false;
        boolean wasHidden = !cursor.isShown2();
        cursor.showCursor(true);
        if (wasHidden && customView == null && !fitMode) { okHeld = false; return; }   // first press just shows the pointer
        h.postDelayed(okLongRun, 600);
    }

    private void okUp() {
        h.removeCallbacks(okLongRun);
        if (!okHeld) return;
        okHeld = false;
        if (dragging) { endDrag(); return; }
        if (okLong) return;
        click();
    }

    /** The pointer's height on the page (the page starts below the top bar). */
    float py() { return cursor.y - barH; }

    /** The pointer is over the top bar. */
    boolean overBar() { return customView == null && !homeShown() && bar.shown() && cursor.y < barH; }

    void click() {
        lastClickAt = SystemClock.uptimeMillis();
        cursor.clickPulse();
        float x = cursor.x, y = cursor.y;
        if (homeShown()) {
            // v1.7.1: a click on Home presses the button under the pointer tip (never the page hidden behind Home)
            View t = panels.homeTargetAt(home, x, y);
            if (t != null) { if (!t.isFocused()) t.requestFocus(); t.performClick(); }
            return;
        }
        if (overBar()) { bar.click(x, y); return; }
        if (pill.getVisibility() == View.VISIBLE && hit(pill, x + root.getPaddingLeft(), y + root.getPaddingTop())) { playMedia(); return; }
        long t = SystemClock.uptimeMillis();
        touchAt(MotionEvent.ACTION_DOWN, t, x, y);
        h.postDelayed(() -> touchAt(MotionEvent.ACTION_UP, t, x, y), 50);
        h.postDelayed(this::hitTest, 400);
    }

    private boolean hit(View v, float x, float y) {
        return x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
    }

    void startDrag(boolean byHold) {
        if (web == null || overBar()) return;
        h.removeCallbacks(okLongRun);
        dragging = true;
        dragByHold = byHold;
        dragDownT = SystemClock.uptimeMillis();
        cursor.setKind(CursorView.DRAG);
        touch(MotionEvent.ACTION_DOWN, dragDownT);
        if (!byHold) toast("Drag mode: move with the arrows, press OK to let go");
    }

    void endDrag() {
        if (!dragging) return;
        touch(MotionEvent.ACTION_UP, dragDownT);
        dragging = false;
        cursor.setKind(CursorView.NORMAL);
    }

    private void touch(int action, long downT) { touchAt(action, downT, cursor.x, cursor.y); }

    private void touchAt(int action, long downT, float x, float y) {
        View target = customView != null ? customView : web;
        if (target == null) return;
        if (target == web) y -= barH;   // the page starts below the top bar
        MotionEvent ev = MotionEvent.obtain(downT, SystemClock.uptimeMillis(), action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(ev);
        ev.recycle();
    }

    // ======================================================================================
    // page scripting
    // ======================================================================================
    interface Result { void got(String r); }

    void js(String call, Result cb) {
        if (web == null) { if (cb != null) cb.got("null"); return; }
        String code = "(function(o){if(!o||!o.setVW)return null;o.setVW(" + Math.max(1, web.getWidth()) + ");return o." + call + ";})(window.__saruki)";
        web.evaluateJavascript(code, cb == null ? null : v -> cb.got(v == null ? "null" : v));
    }

    static float[] point(String r) {
        if (r == null || !r.startsWith("[")) return null;
        try { JSONArray a = new JSONArray(r); return new float[]{(float) a.getDouble(0), (float) a.getDouble(1)}; } catch (Exception e) { return null; }
    }

    void playPause() {
        // on a call, play / pause mutes and unmutes the microphone (there's no video of yours to pause)
        if (calls.active()) { calls.toggleMic(); return; }
        js("toggle()", r -> {
            if (!"1".equals(r)) { if (cur != null && !cur.mediaSnapshot().isEmpty()) playMedia(); else toast("No video on this page"); }
        });
    }

    void jsSeek(int s) {
        js("seek(" + s + ")", r -> {
            if (r != null && r.contains("/")) {
                String[] p = r.replace("\"", "").split("/");
                try { toast((s > 0 ? "⏩ " : "⏪ ") + PlayerActivity.fmt(Long.parseLong(p[0]) * 1000) + " / " + PlayerActivity.fmt(Long.parseLong(p[1]) * 1000)); } catch (Exception ignored) { }
            }
        });
    }

    void toggleFit() {
        js("fit()", r -> {
            if ("1".equals(r)) { fitMode = true; cursor.showCursor(false); setKeepOn(true); toast("Video fills the screen · OK pause · ◀ ▶ seek · BACK to undo"); }
            else if ("0".equals(r)) { fitMode = false; cursor.showCursor(true); }
            else toast("No video found on this page");
        });
    }

    void zapAtCursor() {
        js("zap(" + (int) cursor.x + "," + (int) py() + ")", r -> toast("1".equals(r) ? "Removed it. Reload the page to bring it back." : "Nothing to remove there"));
    }

    // ======================================================================================
    // video found on pages
    // ======================================================================================
    static final class Media {
        static String isMedia(String url) {
            String u = url.toLowerCase(Locale.US);
            int q = u.indexOf('?');
            String path = q > 0 ? u.substring(0, q) : u;
            if (path.endsWith(".m3u8")) return "hls";
            if (path.endsWith(".mpd")) return "dash";
            if (path.matches(".*\\.(mp4|m4v|mkv|webm|mov|avi|flv|wmv|mpg|mpeg|3gp|ogv)$")) return "file";
            return null;
        }
    }

    void sniff(Tab tab, String url, boolean mainFrame) {
        String kind = Media.isMedia(url);
        if (kind == null) return;
        String u = url.toLowerCase(Locale.US);
        if (kind.equals("file") && (u.contains("range=") || u.contains("bytes=") || u.contains("/seg") || u.contains("frag") || u.contains("chunk"))) return;
        if (Prefs.adblock() && (u.contains("/ads/") || u.contains("advert") || u.contains("preroll"))) return;
        tab.addMedia(new Tab.Media(url, tab.url, kind));
        h.post(() -> { if (tab == cur) updatePill(); });
    }

    void updatePill() {
        if (bar != null) bar.refresh();
        boolean has = cur != null && !cur.mediaSnapshot().isEmpty() && home == null && customView == null;
        if (has) {
            int n = cur.mediaSnapshot().size();
            pill.setText(n > 1 ? "▶  Play in TV Player (" + n + ")" : "▶  Play in TV Player");
            if (pill.getVisibility() != View.VISIBLE) {
                pill.setVisibility(View.VISIBLE);
                pill.setAlpha(0);
                pill.animate().alpha(1).setDuration(200).start();
            }
        } else pill.setVisibility(View.GONE);
    }

    void playMedia() {
        if (cur == null) return;
        List<Tab.Media> list = cur.mediaSnapshot();
        if (list.isEmpty()) { toast("No video found yet. Start the video on the page first, then try again."); return; }
        if (list.size() == 1) { startPlayer(list.get(0).url, list.get(0).kind, list.get(0).referer); return; }
        panels.pickMedia(list);
    }

    void startPlayer(String url, String kind, String referer) {
        if (web != null) web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", url);
        i.putExtra("kind", kind);
        i.putExtra("referer", referer);
        i.putExtra("ua", web != null ? web.getSettings().getUserAgentString() : desktopUA);
        i.putExtra("cookie", CookieManager.getInstance().getCookie(url));
        i.putExtra("title", cur != null ? cur.title : null);
        startActivity(i);
    }

    // ======================================================================================
    // full screen
    // ======================================================================================
    void enterFullscreen(View v, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = v;
        customCb = cb;
        fsHolder.addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        fsHolder.setVisibility(View.VISIBLE);
        cursor.showCursor(false);
        pill.setVisibility(View.GONE);
        setKeepOn(true);
        videoCursorUntil = 0;
        toast("Full screen · OK pause · ◀ ▶ seek · ▲ pointer · BACK exit");
    }

    void exitFullscreen() {
        if (customView == null) return;
        fsHolder.removeView(customView);
        fsHolder.setVisibility(View.GONE);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        cursor.showCursor(true);
        setKeepOn(pageVideo);
        updatePill();
    }

    void setKeepOn(boolean on) {
        on |= calls != null && calls.active();   // a call never lets the TV fall asleep
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ======================================================================================
    // downloads
    // ======================================================================================
    void onDownload(String url, String ua, String cd, String mime, long len) {
        String name = URLUtil.guessFileName(url, cd, mime);
        String kind = Media.isMedia(name);
        boolean video = (mime != null && mime.startsWith("video/")) || kind != null;
        String size = len > 0 ? " (" + Formatter.formatShortFileSize(this, len) + ")" : "";
        panels.downloadPrompt(name + size, video, () -> startPlayer(url, kind == null ? "file" : kind, cur != null ? cur.url : null),
                () -> download(url, ua, name, mime));
    }

    void download(String url, String ua, String name, String mime) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.addRequestHeader("User-Agent", ua);
            String c = CookieManager.getInstance().getCookie(url);
            if (c != null) r.addRequestHeader("Cookie", c);
            if (cur != null && cur.url != null) r.addRequestHeader("Referer", cur.url);
            r.setTitle(name);
            if (mime != null) r.setMimeType(mime);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            long id = dm.enqueue(r);
            String ids = Prefs.p.getString("downloads", "");
            Prefs.p.edit().putString("downloads", id + (ids.isEmpty() ? "" : "," + ids)).apply();
            toast("Downloading " + name + " · MENU → Downloads to see it");
        } catch (Exception e) {
            toast("Couldn't start the download: " + e.getMessage());
        }
    }

    // ======================================================================================
    // pop-ups
    // ======================================================================================
    // The last real link clicked on the page (reported by saruki.js), used by the shield.
    volatile String clickedHref = "";
    volatile long clickedAt;
    String pendingRedirect;
    int shieldCount;

    long clickAge() { return clickedAt == 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() - clickedAt; }

    void onPopup(String url, boolean gesture) {
        String host = Prefs.host(url);
        boolean ad = Prefs.adblock() && AdBlock.blocked(host);
        int d = !Prefs.blockPopups() ? (ad ? Shield.BLOCK_AD : Shield.ALLOW)
                : Shield.popup(Prefs.strictShield(), Prefs.popupsAllowed(curHost), ad, gesture, url, clickedHref, clickAge());
        if (d == Shield.ALLOW) {
            openTab(url, cur != null ? cur.id : 0);
            toast("Opened in a new tab · BACK closes it and returns");
            return;
        }
        shieldCount++;
        if (d != Shield.BLOCK_AD) pendingPopup = url;
        // The first few times say what happened and how to undo it; after that keep it short.
        if (shieldCount > 3) toast("🛡 Blocked a pop-up · " + shieldCount + " stopped on this site");
        else if (d == Shield.BLOCK_AD) toast("🛡 Blocked an ad pop-up");
        else toast("🛡 Blocked a pop-up to " + host + " · MENU to open it anyway");
        unjackAfterClick();
    }

    /** A page tried to send this tab to another site. Returns true when the shield stopped it. */
    boolean shieldNavigation(WebResourceRequest req, String url) {
        if (!Prefs.blockPopups()) return false;
        String to = Prefs.host(url);
        boolean ad = Prefs.adblock() && AdBlock.blocked(to);
        boolean redirect = req.isRedirect();
        int d = Shield.navigation(Prefs.strictShield(), Prefs.popupsAllowed(curHost), ad, req.hasGesture(), redirect,
                req.getMethod(), curHost, url, clickedHref, clickAge());
        if (d == Shield.ALLOW) return false;
        shieldCount++;
        if (d != Shield.BLOCK_AD) pendingRedirect = url;
        if (shieldCount > 3) toast("🛡 Stopped a redirect · " + shieldCount + " stopped on this site");
        else if (d == Shield.BLOCK_AD) toast("🛡 Blocked a redirect to an ad site");
        else toast("🛡 Stopped a redirect to " + to + " · MENU to go there anyway");
        unjackAfterClick();
        return true;
    }

    /** When a click set off a pop-up or redirect, remove the invisible layer that caught the click (if there is one). */
    private void unjackAfterClick() {
        if (clickAge() > Shield.CLICK_WINDOW_MS || web == null) return;
        js("unjack(" + (int) cursor.x + "," + (int) py() + ")", r -> {
            if ("1".equals(r)) toast("🛡 Removed an invisible ad layer · click again");
        });
    }

    // ======================================================================================
    // Saruki Remote (v1.7)
    // ======================================================================================
    boolean inFront() { return inFront; }

    void lastInputNow() { lastInput = SystemClock.uptimeMillis(); }

    void remoteChanged(boolean connected) { panels.remoteChanged(connected); }

    /** "Type" on the phone with no box open: focus the box under the pointer (or the page's focused box). The box
     *  then announces itself; {@code found} false means there is none (Saruki opens its address bar instead). */
    void typeTarget(java.util.function.Consumer<Boolean> found) {
        if (panels.isOpen() || homeShown() || web == null || customView != null) { found.accept(false); return; }
        js("focusAt(" + (int) cursor.x + "," + (int) py() + ")", r -> {
            if (!"1".equals(r) || web == null) { found.accept(false); return; }
            web.evaluateJavascript("(window.__sarukiTypeNow?__sarukiTypeNow():0)", v -> found.accept(true));
        });
    }

    /** The phone wants to type and the page has no box: Saruki's address bar (mirrored on the phone). */
    boolean openAddressForPhone() {
        if (!inFront) return false;
        if (customView != null || fitMode) return false;
        boolean page = cur != null && cur.url != null && !HOME.equals(cur.url) && !homeShown();
        panels.close();
        panels.address(page ? cur.url : "");
        return true;
    }

    // ======================================================================================
    // phone remote
    // ======================================================================================
    @Override public void onPhoneCommand(String line) {
        try { phoneCommand(line); } catch (Exception ignored) { }
    }

    private void phoneCommand(String line) {
        lastInput = SystemClock.uptimeMillis();
        String c = line, arg = "";
        int sp = line.indexOf(' ');
        if (sp > 0) { c = line.substring(0, sp); arg = line.substring(sp + 1); }
        switch (c) {
            case "hello": panels.phoneConnected(); break;
            case "m": {
                String[] p = arg.split(" ");
                if (p.length != 2) break;
                float dx = Float.parseFloat(p[0]), dy = Float.parseFloat(p[1]);
                if (panels.isOpen()) { padFocus(dx, dy, PAD_STEP); break; }   // v1.7.1: Home uses the pointer now
                cursor.showCursor(true);
                // Glide there over the next frames instead of jumping, so the pointer moves smoothly
                // even when the phone's messages arrive unevenly over Wi-Fi.
                phoneRemX += dx * unit() * 1.4f;
                phoneRemY += dy * unit() * 1.4f;
                startLoop();
                h.removeCallbacks(phoneSettle);
                h.postDelayed(phoneSettle, 180);
                break;
            }
            case "s":
                if (panels.isOpen()) break;
                if (homeShown()) panels.homeScroll(home, Float.parseFloat(arg) * unit() * 1.5f);
                else smoothScroll(0, Float.parseFloat(arg) * unit() * 1.5f);
                break;
            case "c":
                if (panels.isOpen() || (homeShown() && !cursor.isShown2())) key(KeyEvent.KEYCODE_DPAD_CENTER);
                else click();
                break;
            case "long": if (!homeShown() && !panels.isOpen()) panels.context(); break;
            case "t": typeText(arg); break;
            case "bs": if (!panels.typeBackspace()) js("backspace()", null); break;
            case "enter": if (!panels.typeEnter() && web != null) key(KeyEvent.KEYCODE_ENTER); break;
            case "go": navigate(arg); panels.close(); break;
            case "new": String u = toUrl(arg); if (u != null) { panels.close(); openTab(u, 0); } break;
            case "back": key(KeyEvent.KEYCODE_BACK); break;
            case "menu": if (panels.isOpen()) panels.close(); else panels.quickMenu(); break;
            case "home": panels.close(); showHome(); break;
            case "pp": playPause(); break;
        }
    }

    private final Runnable phoneSettle = () -> { hover(); hitTest(); };

    /** In menus a swipe on the phone pad moves the highlight like the remote's arrows (v1.7.1: Home uses the pointer); about one finger-length per tile. */
    private static final float PAD_STEP = 200f;
    private long padLast;
    private float padX, padY;

    private void padFocus(float dx, float dy, float step) {
        long now = SystemClock.uptimeMillis();
        if (now - padLast > 450) { padX = 0; padY = 0; }
        padLast = now;
        padX += dx;
        padY += dy;
        while (Math.abs(padX) >= step || Math.abs(padY) >= step) {
            if (Math.abs(padX) >= Math.abs(padY)) {
                key(padX > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                padX -= Math.signum(padX) * step;
                padY = 0;
            } else {
                key(padY > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                padY -= Math.signum(padY) * step;
                padX = 0;
            }
        }
    }

    // ---------- phone trackpad (TV Overlay Suite phone app, through PadLink) ----------
    /** Highlight step in menus, in trackpad pixels (the phone already sped up quick swipes). */
    private static final float APP_PAD_STEP = 230f;

    void onPad(String line) {
        try { padCommand(line); } catch (Exception ignored) { }
    }

    private void padCommand(String line) {
        lastInput = SystemClock.uptimeMillis();
        String[] a = line.split(" ");
        boolean menus = homeShown() || panels.isOpen();
        switch (a[0]) {
            case "m": {
                float dx = Float.parseFloat(a[1]), dy = Float.parseFloat(a[2]);
                if (panels.isOpen()) { padFocus(dx, dy, APP_PAD_STEP); break; }   // v1.7.1: Home uses the pointer now
                if (customView != null || fitMode) videoCursorUntil = SystemClock.uptimeMillis() + 6000;
                cursor.showCursor(true);
                padRemX += dx * unit();
                padRemY += dy * unit();
                startLoop();
                h.removeCallbacks(phoneSettle);
                h.postDelayed(phoneSettle, 120);
                break;
            }
            case "s": {
                if (panels.isOpen() || customView != null) break;
                if (homeShown()) { panels.homeScroll(home, Float.parseFloat(a[2]) * unit()); break; }
                float dx = Float.parseFloat(a[1]) * unit(), dy = Float.parseFloat(a[2]) * unit();
                cursor.showCursor(true);
                scrollAt(dx, dy, false);
                break;
            }
            case "c": {
                if (panels.isOpen() || (homeShown() && !cursor.isShown2())) { key(KeyEvent.KEYCODE_DPAD_CENTER); break; }
                if (homeShown()) { click(); break; }
                boolean video = (customView != null || fitMode) && SystemClock.uptimeMillis() > videoCursorUntil && !dragging;
                if (video) { playPause(); break; }
                cursor.showCursor(true);
                click();
                break;
            }
            case "r":
                if (panels.isOpen()) break;
                if (homeShown()) panels.quickMenu(); else panels.context();
                break;
            case "d":
                if ("1".equals(a[1])) {
                    if (menus || web == null || dragging) break;
                    cursor.showCursor(true);
                    startDrag(true);
                } else {
                    endDrag();
                }
                break;
            case "k":
                switch (a[1]) {
                    case "back": phoneCommand("back"); break;
                    case "home": phoneCommand("home"); break;
                    case "menu": phoneCommand("menu"); break;
                    case "pp": phoneCommand("pp"); break;
                }
                break;
        }
    }

    /** A Bluetooth mouse brings its own arrow, so Saruki's pink pointer steps aside until an arrow key is pressed. */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) && cursor.isShown2()) cursor.showCursor(false);
        return super.dispatchGenericMotionEvent(e);
    }

    private void key(int code) {
        long t = SystemClock.uptimeMillis();
        dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0));
    }

    private void typeText(String s) {
        if (panels.typeInto(s)) return;
        if (homeShown()) { panels.address(s); return; }
        js("focusAt(" + (int) cursor.x + "," + (int) py() + ")", r -> {
            if ("1".equals(r)) js("type(" + JSONObject.quote(s) + ")", null);
            else panels.address(s);
        });
    }

    // ======================================================================================
    // session
    // ======================================================================================
    /** v1.8: after an import. The imported tabs must not be overwritten by the tabs open now. */
    private boolean importing;
    void restartAfterImport() {
        importing = true;
        h.postDelayed(() -> Restart.now(this), 900);
    }

    private void saveSession() {
        if (importing) return;
        try {
            JSONArray a = new JSONArray();
            for (Tab t : tabs) {
                String u = t == cur && web != null && web.getUrl() != null ? web.getUrl() : t.url;
                if (u == null) continue;
                a.put(new JSONObject().put("u", u).put("t", t.title).put("c", t == cur));
            }
            Prefs.p.edit().putString("session", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private Tab restoreSession() {
        try {
            JSONArray a = new JSONArray(Prefs.p.getString("session", "[]"));
            Tab active = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tab t = new Tab(o.getString("u"));
                t.title = o.optString("t", t.url);
                tabs.add(t);
                if (o.optBoolean("c")) active = t;
            }
            if (active == null && !tabs.isEmpty()) active = tabs.get(tabs.size() - 1);
            return active;
        } catch (Exception ignored) { return null; }
    }

    String asset(String name) {
        try (InputStream i = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = i.read(buf)) > 0) b.write(buf, 0, r);
            return b.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    // ======================================================================================
    // WebView callbacks
    // ======================================================================================
    final class Bridge {
        final Tab tab;
        Bridge(Tab t) { tab = t; }

        @JavascriptInterface public String flags() { return flagsFor(curHost); }

        /** Settings that apply to this page; the page passes its own host so a fresh page never gets the last site's settings. */
        @JavascriptInterface public String flagsFor(String pageHost) {
            String host = pageHost == null || pageHost.isEmpty() ? curHost : Prefs.host("https://" + pageHost);
            boolean shield = Prefs.blockPopups() && Prefs.strictShield() && !Prefs.popupsAllowed(host);
            return (Prefs.adblock() && !Prefs.adsAllowed(host) ? "a" : "") + (Prefs.hideCookies() ? "c" : "") + (Prefs.ytSkip() ? "y" : "")
                    + (shield ? "s" : "");
        }

        /** Every click on the page reports the real link it landed on ("" if it wasn't a visible link). */
        @JavascriptInterface public void clicked(String href) {
            clickedHref = href == null ? "" : href;
            clickedAt = SystemClock.uptimeMillis();
        }

        @JavascriptInterface public int vw(String hostPort) {
            String host = hostPort == null ? "" : hostPort.toLowerCase(Locale.US).replaceFirst(":\\d+$", "");
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.equals("accounts.google.com")) return 0;
            return Prefs.layoutWidth(host, Prefs.desktop(host));
        }

        @JavascriptInterface public void media(int playing, String src, int top) {
            h.post(() -> {
                if (tab != cur) return;
                pageVideo = playing == 1;
                setKeepOn(pageVideo || customView != null || fitMode);
                if (src != null && src.startsWith("http")) {
                    String kind = Media.isMedia(src);
                    tab.addMedia(new Tab.Media(src, tab.url, kind == null ? "file" : kind));
                    updatePill();
                }
            });
        }
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    final class Client extends WebViewClient {
        final Tab tab;
        Client(Tab t) { tab = t; }

        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String url = u.toString();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) return false;
                if (Prefs.blockPopups() && Shield.externalApp(Prefs.strictShield(), Prefs.popupsAllowed(curHost), req.hasGesture(), url,
                        clickedHref, clickAge()) != Shield.ALLOW) {
                    shieldCount++;
                    toast("🛡 Stopped this page from opening another app");
                    return true;
                }
                if (scheme.equals("intent")) {
                    try {
                        Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        String fb = i.getStringExtra("browser_fallback_url");
                        try { startActivity(i); } catch (Exception e) { if (fb != null) v.loadUrl(fb); }
                    } catch (Exception ignored) { }
                    return true;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { toast("The TV has no app for this link"); }
                return true;
            }
            if (!req.isForMainFrame()) return false;
            String host = Prefs.host(url);
            if (v == web && shieldNavigation(req, url)) return true;
            if (!Prefs.blockPopups() && Prefs.adblock() && !req.hasGesture() && AdBlock.blocked(host) && !host.equals(curHost)) {
                toast("Blocked a redirect to an ad site");
                return true;
            }
            String kind = Media.isMedia(url);
            if (kind != null) { startPlayer(url, kind, tab.url); return true; }
            if (v == web && modeDiffers(host) && "GET".equals(req.getMethod())) {
                applyMode(host);
                curHost = host;
                v.loadUrl(url);
                return true;
            }
            return false;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String host = u.getHost();
            if (!req.isForMainFrame() && Prefs.adblock() && host != null && !Prefs.adsAllowed(curHost) && AdBlock.blocked(host)) {
                tab.blockedCount++;
                return empty();
            }
            sniff(tab, u.toString(), req.isForMainFrame());
            return null;
        }

        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            if (v == web) bar.refresh();   // v1.7: Back / Forward and the address follow in-page navigation too
        }

        @Override public void onPageStarted(WebView v, String url, Bitmap fav) {
            if (v != web) return;
            bar.setLoading(true);
            String host = Prefs.host(url);
            if (!host.equals(Prefs.host(tab.url))) {
                tab.clearMedia();
                if (!Shield.sameSite(host, Prefs.host(tab.url))) { pendingPopup = null; pendingRedirect = null; shieldCount = 0; }
            }
            tab.url = url;
            curHost = host;
            calls.reset();   // a new page: the old page's camera and phone sessions end with it
            typing.pageGone();
            if (appliedDesktop == null) applyMode(host);
            if (!docStart) v.evaluateJavascript(script, null);
            if (!docStart && Calls.supported()) v.evaluateJavascript(callsScript, null);   // late, but better than none
            if (!docStart && Typing.supported()) v.evaluateJavascript(typeScript, null);
            fitMode = false;
            updatePill();
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (v != web) return;
            bar.setLoading(false);
            if (!docStart) v.evaluateJavascript(script, null);
            tab.url = url;
            String t = v.getTitle();
            if (t != null && !t.isEmpty()) tab.title = t;
            Db.get(BrowserActivity.this).visit(url, tab.title);
            String host = Prefs.host(url);
            boolean d = appliedDesktop != null && appliedDesktop;
            int z = Prefs.zoom(host, d);
            String lock = url.startsWith("https") ? "🔒 " : "⚠ ";
            showChip(lock + host + "  ·  " + (d ? "Desktop" : "Mobile") + " " + z + "%" + (tab.blockedCount > 0 ? "  ·  " + tab.blockedCount + " blocked" : ""));
            if (host.equals("accounts.google.com") && (url.contains("disallowed_useragent") || (t != null && t.toLowerCase(Locale.US).contains("couldn")))) panels.googleSignIn();
            panels.maybeSaveIcon(url);
            h.postDelayed(BrowserActivity.this::hitTest, 300);
        }

        @Override public void onReceivedSslError(WebView v, SslErrorHandler hnd, SslError err) {
            hnd.cancel();
            toast("This site's security certificate isn't valid, so it was not opened");
        }

        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            if (v != web) { h.post(v::destroy); return true; }
            long now = SystemClock.uptimeMillis();
            boolean again = now - lastCrashAt < 20000;
            lastCrashAt = now;
            String url = tab.url;
            webHolder.removeView(v);
            v.destroy();
            web = null;
            tab.state = null;
            if (again) {
                tab.url = HOME;
                activate(tab);
                toast("That page keeps running the TV out of memory, so it was closed");
            } else {
                tab.url = url;
                cur = null;
                activate(tab);
                toast("The page ran out of memory and was reloaded");
            }
            return true;
        }
    }

    final class Chrome extends WebChromeClient {
        final Tab tab;
        Chrome(Tab t) { tab = t; }

        @Override public void onProgressChanged(WebView v, int p) {
            if (v != web) return;
            bar.setLoading(p < 100);
            float target = Math.max(0.04f, p / 100f);
            progress.animate().cancel();
            progress.setAlpha(1);
            if (target < progress.getScaleX()) progress.setScaleX(target);   // a new page started: restart from the left
            if (p >= 100) {
                progress.animate().scaleX(1f).setDuration(160).setInterpolator(Ui.EASE)
                        .withEndAction(() -> progress.animate().alpha(0).setDuration(320).start()).start();
            } else {
                progress.animate().scaleX(target).setDuration(260).setInterpolator(Ui.EASE).start();
            }
        }

        // Page dialogs never freeze the TV: alerts become a short note, "leave this page?" always leaves,
        // and a real question (OK / Cancel) opens as a Saruki panel you answer with the remote.
        @Override public boolean onJsAlert(WebView v, String url, String msg, android.webkit.JsResult r) {
            r.confirm();
            if (v == web && msg != null && !msg.trim().isEmpty()) toast("💬 " + (msg.length() > 160 ? msg.substring(0, 160) + "…" : msg.trim()));
            return true;
        }

        @Override public boolean onJsConfirm(WebView v, String url, String msg, android.webkit.JsResult r) {
            if (v != web || homeShown()) { r.cancel(); return true; }
            panels.confirm(msg, r);
            return true;
        }

        @Override public boolean onJsPrompt(WebView v, String url, String msg, String def, android.webkit.JsPromptResult r) {
            r.cancel();
            return true;
        }

        @Override public boolean onJsBeforeUnload(WebView v, String url, String msg, android.webkit.JsResult r) {
            r.confirm();
            return true;
        }

        @Override public void onReceivedTitle(WebView v, String title) { if (title != null) tab.title = title; }

        @Override public void onShowCustomView(View view, CustomViewCallback cb) { enterFullscreen(view, cb); }

        @Override public void onHideCustomView() { exitFullscreen(); }

        @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); }

        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean gesture, Message msg) {
            WebView tmp = new WebView(BrowserActivity.this);
            final boolean[] done = {false};
            Runnable cleanup = () -> { if (!done[0]) { done[0] = true; tmp.destroy(); } };
            tmp.setWebViewClient(new WebViewClient() {
                void got(String url) {
                    if (done[0] || url == null || url.startsWith("about:")) return;
                    done[0] = true;
                    h.post(() -> { tmp.stopLoading(); tmp.destroy(); onPopup(url, gesture); });
                }
                @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) { got(r.getUrl().toString()); return true; }
                @Override public void onPageStarted(WebView w, String url, Bitmap f) { got(url); }
                @Override public boolean onRenderProcessGone(WebView w, RenderProcessGoneDetail d) { cleanup.run(); return true; }
            });
            ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
            msg.sendToTarget();
            h.postDelayed(cleanup, 5000);
            return true;
        }

        @Override public void onPermissionRequest(PermissionRequest r) {
            if (r == null) return;
            // protected video (DRM) as before; camera and microphone go through the video-call rules (asks once per site)
            calls.onPermissionRequest(r);
        }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback cb) { cb.invoke(origin, false, false); }

        @Override public void onReceivedIcon(WebView v, Bitmap icon) { panels.saveSmallIcon(tab.url, icon); }
    }
}
