package io.github.sarukibrowser.tv;

import android.net.Uri;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.EditText;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.Collections;
import java.util.regex.Pattern;

import io.github.sarukibrowser.link.TypeProto;

/**
 * v1.7: typing from Saruki Remote. One text box at a time is "open": a box on the page (type.js reports it, in any
 * frame) or Saruki's own address bar. The phone mirrors it and sends small edits back (TypeProto).
 *
 * The phone pops its typing bar by itself only when you clicked the box (with the remote OK, the phone trackpad, or
 * opened the address bar), not when a page puts the cursor in a box on its own while loading.
 */
final class Typing {
    static final String JS_NAME = "SarukiType";
    private static final Pattern FID = Pattern.compile("[A-Za-z0-9]{10}");
    /** A box that gets focus within this long after a click counts as clicked. */
    private static final long CLICK_WINDOW_MS = 1500;

    private final BrowserActivity a;

    /** The open box. */
    private static final class Field {
        int id;
        // a box on the page
        JavaScriptReplyProxy proxy;
        String frame;
        int n;
        // or Saruki's own box
        EditText edit;
        TextWatcher watcher;
        String action = "", hint = "";
        int flags, seq;
        String text = "";
        int selS, selE;
        /** The phone was told about it ("T o"). */
        boolean told;
    }

    private Field cur;
    private int nextId = 1;
    /** The phone asked to type ("T w") and is waiting for a box. */
    private long wantedAt;
    private boolean applying;

    Typing(BrowserActivity a) { this.a = a; }

    static boolean supported() { return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER); }

    /** A new WebView: before any page loads. */
    void attach(WebView w) {
        if (!supported()) return;
        try {
            WebViewCompat.addWebMessageListener(w, JS_NAME, Collections.singleton("*"), this::onMessage);
        } catch (RuntimeException ignored) { }
    }

    private boolean phones() { return a.remote != null && a.remote.phones() > 0; }

    private void send(String line) { if (a.remote != null) a.remote.broadcast(line); }

    // =====================================================================================
    // page -> Saruki (main thread)
    // =====================================================================================
    private void onMessage(WebView view, WebMessageCompat msg, Uri origin, boolean mainFrame, JavaScriptReplyProxy reply) {
        if (view != a.web || msg == null || msg.getData() == null || msg.getData().length() > 40000) return;
        JSONObject m;
        try { m = new JSONObject(msg.getData()); } catch (Exception e) { return; }
        String frame = m.optString("f");
        if (!FID.matcher(frame).matches()) return;
        int n = m.optInt("n");
        switch (m.optString("t")) {
            case "open": {
                Field f = cur != null && cur.edit == null && frame.equals(cur.frame) && cur.n == n ? cur : null;
                if (f == null) {
                    closeCur();
                    f = new Field();
                    f.id = nextId++;
                    f.frame = frame;
                    f.n = n;
                    cur = f;
                }
                f.proxy = reply;
                f.text = cut(m.optString("text"));
                f.selS = m.optInt("s");
                f.selE = m.optInt("e");
                f.flags = m.optInt("fl");
                f.action = m.optString("a");
                f.hint = m.optString("h");
                String why = m.optString("why");
                boolean asked = SystemClock.uptimeMillis() - wantedAt < 3000;
                boolean clicked = SystemClock.uptimeMillis() - a.lastClickAt < CLICK_WINDOW_MS;
                if (asked) { wantedAt = 0; tell(f, false); }
                else if (clicked || "ask".equals(why) || f.told) tell(f, true);
                break;
            }
            case "state": {
                Field f = cur;
                if (f == null || f.edit != null || !frame.equals(f.frame) || f.n != n) return;
                f.text = cut(m.optString("text"));
                f.selS = m.optInt("s");
                f.selE = m.optInt("e");
                if (f.told) send(TypeProto.state(f.id, f.seq, f.selS, f.selE, f.text));
                break;
            }
            case "close": {
                Field f = cur;
                if (f == null || f.edit != null || !frame.equals(f.frame) || f.n != n) return;
                closeCur();
                break;
            }
            default:
        }
    }

    private static String cut(String s) { return s.length() > TypeProto.MAX_TEXT ? s.substring(0, TypeProto.MAX_TEXT) : s; }

    /** Tells the phone a box is open. {@code auto}: it pops up by itself (if the phone allows it). */
    private void tell(Field f, boolean auto) {
        if (!phones() || f != cur) return;
        f.told = true;
        send(TypeProto.open(auto, f.id, f.seq, f.selS, f.selE, f.flags, f.action, f.hint, f.text));
    }

    private void closeCur() {
        Field f = cur;
        cur = null;
        if (f == null) return;
        if (f.edit != null && f.watcher != null) f.edit.removeTextChangedListener(f.watcher);
        if (f.told) send(TypeProto.closed(f.id));
    }

    /** A new page or tab: its box is gone. */
    void pageGone() {
        if (cur != null && cur.edit == null) closeCur();
    }

    /** A phone connected: if a box is open and was clicked, nothing pops by itself; "Type" on the phone finds it. */
    void phoneConnected() { }

    // =====================================================================================
    // Saruki's own text boxes (the address bar)
    // =====================================================================================
    /** Saruki's address bar opened (by you, or for the phone). */
    void nativeOpen(EditText e, String hint) {
        closeCur();
        Field f = new Field();
        f.id = nextId++;
        f.edit = e;
        f.flags = TypeProto.F_URI;
        f.action = "Go";
        f.hint = hint;
        f.text = e.getText().toString();
        f.selS = Math.max(0, e.getSelectionStart());
        f.selE = Math.max(0, e.getSelectionEnd());
        f.watcher = new Watch(this, f);
        e.addTextChangedListener(f.watcher);
        cur = f;
        boolean asked = SystemClock.uptimeMillis() - wantedAt < 3000;
        wantedAt = 0;
        tell(f, !asked);
    }

    /** Saruki's address bar closed. */
    void nativeClosed(EditText e) {
        if (cur != null && cur.edit == e) closeCur();
    }

    private void nativeChanged(Field f) {
        if (applying || f != cur) return;
        f.text = cut(f.edit.getText().toString());
        f.selS = Math.max(0, f.edit.getSelectionStart());
        f.selE = Math.max(0, f.edit.getSelectionEnd());
        if (f.told) send(TypeProto.state(f.id, f.seq, f.selS, f.selE, f.text));
    }

    static final class Watch implements TextWatcher {
        private final Typing t;
        private final Field f;
        Watch(Typing t, Field f) { this.t = t; this.f = f; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { t.nativeChanged(f); }
    }

    // =====================================================================================
    // phone -> Saruki (main thread)
    // =====================================================================================
    void fromPhone(TypeProto.Msg m) {
        switch (m.kind) {
            case 'w': want(); break;
            case 'e': edit(m); break;
            case 'a': action(m.field); break;
            default:
        }
    }

    private void want() {
        if (!a.inFront()) { send(TypeProto.none("off")); return; }
        Field f = cur;
        if (f != null) { tell(f, false); return; }
        wantedAt = SystemClock.uptimeMillis();
        // a box under the pointer, or the one with focus on the page; else Saruki's address bar
        a.typeTarget(found -> {
            if (wantedAt == 0) return;                       // answered meanwhile (the box announced itself)
            if (found) {
                // the box announces itself (type.js); if it doesn't within a moment, say so
                a.h.postDelayed(() -> {
                    if (wantedAt != 0 && SystemClock.uptimeMillis() - wantedAt >= 900) { wantedAt = 0; addressBar(); }
                }, 1000);
            } else {
                addressBar();
            }
        });
    }

    private void addressBar() {
        wantedAt = SystemClock.uptimeMillis();
        if (!a.openAddressForPhone()) { wantedAt = 0; send(TypeProto.none("none")); }
    }

    private void edit(TypeProto.Msg m) {
        Field f = cur;
        if (f == null || m.field != f.id) return;
        f.seq = Math.max(f.seq, m.seq);
        a.lastInputNow();
        if (f.edit != null) {
            Editable t = f.edit.getText();
            applying = true;
            try {
                int[] sel = {f.edit.getSelectionStart(), f.edit.getSelectionEnd()};
                TypeProto.play(new EditOps(f.edit), t.length(), Math.max(0, sel[0]), Math.max(0, sel[1]), 0, m.start, m.del, m.text, m.cursor);
            } catch (RuntimeException ignored) {
            } finally {
                applying = false;
            }
            f.text = cut(f.edit.getText().toString());
            f.selS = Math.max(0, f.edit.getSelectionStart());
            f.selE = Math.max(0, f.edit.getSelectionEnd());
            return;
        }
        // keep our copy in step (for "T o" if the phone asks again)
        f.text = cut(TypeProto.apply(f.text, m.start, m.del, m.text));
        f.selS = f.selE = Math.max(0, Math.min(m.cursor, f.text.length()));
        try {
            f.proxy.postMessage(new JSONObject().put("t", "edit").put("f", f.frame).put("n", f.n)
                    .put("s", m.start).put("d", m.del).put("c", m.cursor).put("x", m.text).toString());
        } catch (Exception ignored) { }
    }

    private void action(int field) {
        Field f = cur;
        if (f == null || field != f.id) return;
        a.lastInputNow();
        if (f.edit != null) {
            f.edit.onEditorAction(EditorInfo.IME_ACTION_GO);
            return;
        }
        try {
            f.proxy.postMessage(new JSONObject().put("t", "enter").put("f", f.frame).put("n", f.n).toString());
        } catch (Exception ignored) { }
    }

    /** Edits on Saruki's own EditText. */
    static final class EditOps implements TypeProto.Ops {
        private final EditText e;
        EditOps(EditText e) { this.e = e; }
        @Override public void deleteBefore(int n) {
            Editable t = e.getText();
            int c = Math.max(0, e.getSelectionStart());
            int s = Math.max(0, c - n);
            t.delete(s, c);
        }
        @Override public void select(int s, int end) {
            int l = e.getText().length();
            e.setSelection(Math.max(0, Math.min(s, l)), Math.max(0, Math.min(end, l)));
        }
        @Override public void commit(String text) {
            Editable t = e.getText();
            int s = Math.max(0, e.getSelectionStart()), end = Math.max(0, e.getSelectionEnd());
            t.replace(Math.min(s, end), Math.max(s, end), text);
        }
    }
}
