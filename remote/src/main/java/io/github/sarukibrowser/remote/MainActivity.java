package io.github.sarukibrowser.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.content.res.ColorStateList;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Setup and status: pair with Saruki on the TV, grant the two permissions, open the trackpad / typing / camera, settings. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0F1218, CARD = 0xFF1B202A, FIELD = 0xFF252B38, TEXT = 0xFFE9ECF2,
            MUTED = 0xFF8A93A3, ACCENT = 0xFFFF5CA8, GOOD = 0xFF45D483, WARN = 0xFFFF8A5F;
    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private Store store;
    private float d;
    private TextView statusDot, statusText, statusSub, permOverlay, permNotes, scanStatus, pairMsg;
    private LinearLayout pairCard, readyCard, tvList, permCard;
    private EditText hostEdit, codeEdit;
    private Switch autoSw, enterSw, padSw;
    private TextView speedLabel;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean scanning;
    /** Saruki's port, from the TV list or the pairing link (the address box may also say "address:port"). */
    private int pickedPort = Store.PORT;
    private String pickedId = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
        handleLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleLink(i);
    }

    /** sarukiremote://pair?h=ADDRESS:PORT&k=CODE&i=TV_ID, from "Open Saruki Remote" on the page the TV's QR code opens. */
    private void handleLink(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"sarukiremote".equals(u.getScheme()) || !"pair".equals(u.getHost())) return;
        String h = u.getQueryParameter("h"), k = u.getQueryParameter("k"), id = u.getQueryParameter("i");
        if (h == null) return;
        int port = Store.PORT;
        int colon = h.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(h.substring(colon + 1)); } catch (NumberFormatException e) { port = Store.PORT; }
            h = h.substring(0, colon);
        }
        if (!h.matches("[0-9.]{7,15}") || port <= 0 || port > 65535) return;
        pickedPort = port;
        pickedId = id != null && id.matches("[a-z0-9]{12}") ? id : "";
        hostEdit.setText(h);
        if (k != null && validCode(k)) {
            codeEdit.setText(k.toUpperCase(Locale.ROOT));
            pair(h, k);
        } else {
            pairMsg.setText("Type the pairing code shown on the TV.");
            codeEdit.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinkService.onStatus = this::refresh;
        if (store.paired()) LinkService.start(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("remote", MODE_PRIVATE).getBoolean("askedNotes", false)) {
            getSharedPreferences("remote", MODE_PRIVATE).edit().putBoolean("askedNotes", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        refresh();
        if (!store.paired()) scan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (LinkService.onStatus != null) LinkService.onStatus = null;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) { refresh(); }

    // ---------------------------------------------------------------- layout

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(18), px(22), px(18), px(28));
        sv.addView(col);

        TextView h = text("saruki remote", 28, ACCENT, true);
        h.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        col.addView(h);
        // v1.0.1: the slogan, with THE in bold
        android.text.SpannableString subText = new android.text.SpannableString("Your phone as the trackpad, keyboard and camera for Saruki, THE tv browser.");
        int theAt = subText.toString().indexOf("THE");
        subText.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), theAt, theAt + 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView sub = text("", 15, MUTED, false);
        sub.setText(subText);
        sub.setPadding(0, px(4), 0, px(16));
        col.addView(sub);

        // status
        LinearLayout st = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 18, MUTED, true);
        statusDot.setPadding(0, 0, px(10), 0);
        row.addView(statusDot);
        statusText = text("", 18, TEXT, true);
        row.addView(statusText);
        st.addView(row);
        statusSub = text("", 14, MUTED, false);
        statusSub.setPadding(0, px(4), 0, 0);
        st.addView(statusSub);
        col.addView(st);

        // pairing
        pairCard = card();
        pairCard.addView(text("Pair with your TV", 18, TEXT, true));
        TextView ph = text("On the TV, open Saruki → MENU → Phone remote. Scan its QR code with this phone's camera and tap "
                + "“Open Saruki Remote”. Or pick your TV below and type the code shown there.", 14, MUTED, false);
        ph.setPadding(0, px(4), 0, px(10));
        pairCard.addView(ph);
        scanStatus = text("", 14, MUTED, false);
        pairCard.addView(scanStatus);
        tvList = new LinearLayout(this);
        tvList.setOrientation(LinearLayout.VERTICAL);
        pairCard.addView(tvList);
        hostEdit = field("TV address, e.g. 192.168.1.23:8790", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pairCard.addView(hostEdit);
        codeEdit = field("Pairing code (6 characters)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7), new InputFilter.AllCaps()});
        pairCard.addView(codeEdit);
        TextView go = button("Pair", true);
        go.setOnClickListener(v -> pair(hostEdit.getText().toString().trim(), codeEdit.getText().toString()));
        pairCard.addView(go);
        pairMsg = text("", 14, WARN, false);
        pairMsg.setPadding(0, px(8), 0, 0);
        pairCard.addView(pairMsg);
        col.addView(pairCard);

        // permissions
        permCard = card();
        permCard.addView(text("Two permissions", 18, TEXT, true));
        TextView pe = text("So the typing bar can pop up over any app the moment a text box opens on the TV.", 14, MUTED, false);
        pe.setPadding(0, px(4), 0, px(6));
        permCard.addView(pe);
        permOverlay = button("Allow “Display over other apps”", true);
        permOverlay.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        });
        permCard.addView(permOverlay);
        permNotes = button("Allow notifications", false);
        permNotes.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        });
        permCard.addView(permNotes);
        col.addView(permCard);

        // ready: actions + settings
        readyCard = card();
        TextView padBtn = button("Trackpad", true);
        padBtn.setOnClickListener(v -> startActivity(new Intent(this, PadActivity.class)));
        readyCard.addView(padBtn);
        TextView type = button("Type in Saruki now", true);
        type.setOnClickListener(v -> startActivity(new Intent(this, TypeActivity.class).putExtra(TypeActivity.EXTRA_WANT, true)));
        readyCard.addView(type);
        TextView camBtn = button("Phone camera for video calls", false);
        camBtn.setOnClickListener(v -> startActivity(new Intent(this, CamActivity.class)));
        readyCard.addView(camBtn);
        autoSw = toggle("Pop up when you click a text box in Saruki", store.autoPop());
        autoSw.setOnCheckedChangeListener((bt, on) -> store.setAutoPop(on));
        readyCard.addView(autoSw);
        enterSw = toggle("Close after Search / Go / Enter", store.closeOnEnter());
        enterSw.setOnCheckedChangeListener((bt, on) -> store.setCloseOnEnter(on));
        readyCard.addView(enterSw);
        padSw = toggle("Open the trackpad when Saruki opens on the TV", store.padAutoPop());
        padSw.setOnCheckedChangeListener((bt, on) -> store.setPadAutoPop(on));
        readyCard.addView(padSw);
        speedLabel = text("", 15, TEXT, false);
        speedLabel.setPadding(0, px(14), 0, px(2));
        readyCard.addView(speedLabel);
        SeekBar speed = new SeekBar(this);
        speed.setMax(4);
        speed.setProgress(store.padSpeed() - 1);
        speed.setProgressTintList(ColorStateList.valueOf(ACCENT));
        speed.setThumbTintList(ColorStateList.valueOf(ACCENT));
        speed.setOnSeekBarChangeListener(new SpeedListener(this));
        readyCard.addView(speed);
        showSpeed();
        TextView tip = text("Tip: add the “Type on TV” tile to Quick Settings (swipe down twice, tap the pencil) "
                + "to start typing from anywhere. With no text box open, typing goes to Saruki's address bar: "
                + "type a site or a search. The connection notification has Type on TV and Trackpad buttons too.", 13, MUTED, false);
        tip.setPadding(0, px(10), 0, 0);
        readyCard.addView(tip);
        TextView forgetCam = button("Forget camera permissions", false);
        forgetCam.setOnClickListener(v -> {
            store.clearCamAllowed();
            android.widget.Toast.makeText(this, "Saruki will ask again before using the camera", android.widget.Toast.LENGTH_SHORT).show();
        });
        readyCard.addView(forgetCam);
        TextView forget = button("Forget this TV", false);
        forget.setOnClickListener(v -> {
            // v1.0.1: one tap no longer unpairs. Ask first; only "Forget" does it.
            String tv = store.tvName().isEmpty() ? "this TV" : store.tvName();
            new android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Forget " + tv + "?")
                    .setMessage("The trackpad, typing and camera stop until you pair again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forget", (d, w) -> {
                        store.forget();
                        stopService(new Intent(this, LinkService.class));
                        refresh();
                        scan();
                    })
                    .show();
        });
        readyCard.addView(forget);
        col.addView(readyCard);

        TextView ver = text("Version " + versionName() + "  ·  github.com/SarukiBrowser/saruki", 12, MUTED, false);
        ver.setPadding(0, px(18), 0, 0);
        col.addView(ver);
        setContentView(sv);
    }

    private void refresh() {
        boolean paired = store.paired();
        int s = LinkService.status;
        String name = store.tvName().isEmpty() ? "your TV" : store.tvName();
        int dot;
        String t, sub;
        if (!paired) { dot = MUTED; t = "Not paired"; sub = "Pair once; the app finds the TV by itself after that."; }
        else switch (s) {
            case LinkService.S_CONNECTED: dot = GOOD; t = "Connected to " + name; sub = "Open the trackpad, or click a text box in Saruki to type here."; break;
            case LinkService.S_REFUSED: dot = WARN; t = "The TV changed its pairing code"; sub = "Type the new code below."; break;
            case LinkService.S_NOT_FOUND: dot = WARN; t = "Can't find " + name; sub = "Is the TV on with Saruki open, and is this phone on the same Wi-Fi?"; break;
            case LinkService.S_ASLEEP: dot = MUTED; t = "Paused"; sub = "Reconnects when you use the phone."; break;
            default: dot = ACCENT; t = "Connecting to " + name + "…"; sub = "";
        }
        statusDot.setTextColor(dot);
        statusText.setText(t);
        statusSub.setText(sub);
        statusSub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        boolean needPair = !paired || s == LinkService.S_REFUSED;
        pairCard.setVisibility(needPair ? View.VISIBLE : View.GONE);
        if (needPair && paired && hostEdit.getText().length() == 0) {
            hostEdit.setText(store.host());
            pickedPort = store.port();
            pickedId = store.tvId();
        }
        readyCard.setVisibility(paired && !needPair ? View.VISIBLE : View.GONE);
        boolean overlay = TypingBar.allowed(this);
        boolean notes = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permOverlay.setVisibility(overlay ? View.GONE : View.VISIBLE);
        permNotes.setVisibility(notes ? View.GONE : View.VISIBLE);
        permCard.setVisibility(paired && (!overlay || !notes) ? View.VISIBLE : View.GONE);
    }

    private static final String[] SPEED_NAMES = {"slowest", "slow", "normal", "fast", "fastest"};

    void speedChanged(int progress) {
        store.setPadSpeed(progress + 1);
        showSpeed();
    }

    private void showSpeed() { speedLabel.setText("Trackpad speed: " + SPEED_NAMES[store.padSpeed() - 1]); }

    /** Named class, not anonymous (d8 8.2.2 bug). */
    static final class SpeedListener implements SeekBar.OnSeekBarChangeListener {
        private final MainActivity a;
        SpeedListener(MainActivity a) { this.a = a; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) a.speedChanged(p); }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    // ---------------------------------------------------------------- pairing

    private static boolean validCode(String c) {
        c = c.replace(" ", "").toUpperCase(Locale.ROOT);
        if (c.length() != 6) return false;
        for (int i = 0; i < 6; i++) if (CODE_CHARS.indexOf(c.charAt(i)) < 0) return false;
        return true;
    }

    private void pair(String host, String code) {
        code = code.replace(" ", "").toUpperCase(Locale.ROOT);
        int port = pickedPort;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(host.substring(colon + 1)); } catch (NumberFormatException e) { port = -1; }
            host = host.substring(0, colon);
        }
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || port <= 0 || port > 65535) { pairMsg.setText("Choose your TV above, or type its address (numbers and dots, as on the TV)."); return; }
        if (!validCode(code)) { pairMsg.setText("The code is 6 letters and numbers, as shown on the TV."); return; }
        pairMsg.setText("");
        store.pair(host, port, code, pickedId);
        LinkService s = LinkService.instance;
        if (s != null) s.kick(); else LinkService.start(this);
        refresh();
    }

    private void scan() {
        if (scanning) return;
        scanning = true;
        scanStatus.setText("Looking for TVs on this Wi-Fi…");
        new Thread(() -> {
            List<Finder.Tv> tvs = new Finder(this).scan(3500);
            ui.post(() -> showTvs(tvs));
        }, "scan").start();
    }

    private void showTvs(List<Finder.Tv> tvs) {
        scanning = false;
        if (isFinishing()) return;
        tvList.removeAllViews();
        scanStatus.setText(tvs.isEmpty()
                ? "No TV found automatically. Is Saruki open on the TV? Or type the TV address shown under the QR code."
                : "Found on this Wi-Fi — tap yours, then type the code:");
        for (Finder.Tv t : tvs) {
            TextView b = button("📺  " + t.name + "   " + t.host, false);
            b.setOnClickListener(v -> pick(t));
            tvList.addView(b);
        }
        if (tvs.size() == 1 && hostEdit.getText().length() == 0) pick(tvs.get(0));
    }

    private void pick(Finder.Tv t) {
        hostEdit.setText(t.host);
        pickedPort = t.port > 0 ? t.port : Store.PORT;
        pickedId = t.id.matches("[a-z0-9]{12}") ? t.id : "";
        codeEdit.requestFocus();
    }

    // ---------------------------------------------------------------- small view helpers

    private int px(int dp) { return Math.round(dp * d); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(20 * d);
        c.setBackground(g);
        c.setPadding(px(16), px(16), px(16), px(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView button(String s, boolean primary) {
        TextView t = text(s, 16, primary ? 0xFF151820 : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), px(13), px(14), px(13));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : FIELD);
        g.setCornerRadius(14 * d);
        t.setBackground(g);
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        GradientDrawable g = new GradientDrawable();
        g.setColor(FIELD);
        g.setCornerRadius(14 * d);
        e.setBackground(g);
        e.setPadding(px(14), px(12), px(14), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(8);
        e.setLayoutParams(lp);
        return e;
    }

    private Switch toggle(String label, boolean on) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setChecked(on);
        s.setPadding(0, px(14), 0, px(4));
        return s;
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }
}
