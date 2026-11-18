package io.github.sarukibrowser.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v1.8.3 "Window": one quiet design system for the whole TV app.
 * Graphite surfaces, hairline borders, one accent (Saruki pink) used only for focus and for values. Nothing has a
 * colour or a border unless it means something; the focused item is always the brightest thing on screen.
 */
final class Ui {
    // ---- colour tokens ----
    static final int DESK = 0xFF000000;        // outside the window (the "desk" the window sits on)
    static final int BG = 0xFF0B0C0E;          // window base, Home
    static final int CHROME = 0xFF101114;      // top bar
    static final int PANEL = 0xFF121317;       // floating panels (menu, lists, dialogs): opaque, nothing ghosts through
    static final int CARD = 0xFF191B20;        // a pressable thing at rest
    static final int CARD_FOCUS = 0xFF262930;  // the same thing, focused
    static final int LINE = 0x1FFFFFFF;        // hairline border (12% white)
    static final int LINE_STRONG = 0x33FFFFFF; // the window frame (20% white)
    static final int SCRIM = 0x8C000000;       // behind a panel
    static final int ACCENT = 0xFFFF5CA8;      // Saruki pink: focus ring, values, progress
    static final int ON_ACCENT = 0xFF1A0610;   // text on a pink fill
    static final int GOLD = 0xFFE9C46A;        // waiting / warning
    static final int GOOD = 0xFF5FD38D;        // connected / done
    static final int TEXT = 0xFFECEDEF;
    static final int DIM = 0xFF8E929B;         // 6:1 on PANEL, still readable from the couch

    // ---- shape tokens (dp) ----
    static final float R_SM = 8, R_MD = 12, R_LG = 16;
    static final float RING = 2;                // focus ring width
    static final float FRAME_GAP = 12;          // window inset from the screen edge
    static final float FRAME_RADIUS = 14;

    // v1.8.2: one type scale for the whole TV app (couch distance on a 55" TV): nothing is smaller than CAPTION.
    static final float CAPTION = 14, BODY = 16, TITLE = 20, HEADLINE = 24, DISPLAY = 28;

    static float density = 2f;
    /** v1.8.3: Inter (SIL OFL 1.1) in three weights, falling back to the system sans if a file can't load. */
    static Typeface REGULAR = Typeface.DEFAULT, MEDIUM = Typeface.DEFAULT, SEMIBOLD = Typeface.DEFAULT_BOLD;

    /** One easing curve for every movement in the app: quick start, soft landing. */
    static final android.view.animation.DecelerateInterpolator EASE = new android.view.animation.DecelerateInterpolator(1.8f);

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        try {
            REGULAR = c.getResources().getFont(R.font.inter_400);
            MEDIUM = c.getResources().getFont(R.font.inter_500);
            SEMIBOLD = c.getResources().getFont(R.font.inter_600);
        } catch (RuntimeException e) {
            REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            SEMIBOLD = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
    }

    static int dp(float v) { return Math.round(v * density); }

    static GradientDrawable round(int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    /** A floating surface: panel fill, hairline edge. */
    static GradientDrawable surface(float radiusDp) { return round(PANEL, radiusDp, LINE, 1); }

    /** Card background: quiet at rest, brighter with a thin pink ring when focused. */
    static Drawable focusBg(float radiusDp) { return focusBg(radiusDp, CARD); }

    /** v1.8.3: the same with a chosen resting fill (0 = clear at rest: icon tiles and text buttons). */
    static Drawable focusBg(float radiusDp, int rest) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(CARD_FOCUS, radiusDp, ACCENT, RING));
        s.addState(new int[]{android.R.attr.state_pressed}, round(CARD_FOCUS, radiusDp, ACCENT, RING));
        s.addState(new int[]{}, round(rest, radiusDp, 0, 0));
        s.setEnterFadeDuration(90);
        s.setExitFadeDuration(120);
        return s;
    }

    /** v1.8.3: a secondary button (chips, Close, Done): hairline outline at rest, the standard focus look when focused. */
    static <T extends View> T outlined(T v, float radiusDp) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(CARD_FOCUS, radiusDp, ACCENT, RING));
        s.addState(new int[]{android.R.attr.state_pressed}, round(CARD_FOCUS, radiusDp, ACCENT, RING));
        s.addState(new int[]{}, round(0x00000000, radiusDp, LINE_STRONG, 1));
        s.setEnterFadeDuration(90);
        s.setExitFadeDuration(120);
        v.setBackground(s);
        return v;
    }

    /** v1.8.3: a one-pixel divider line. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
        return v;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? (sp >= TITLE ? SEMIBOLD : MEDIUM) : REGULAR);
        if (sp >= TITLE) t.setLetterSpacing(-0.01f);   // Inter reads tighter at display sizes
        t.setIncludeFontPadding(false);
        return t;
    }

    /** v1.8.3: a small all-caps section label ("FAVORITES", "CONTINUE"). */
    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.US), CAPTION, DIM, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    /**
     * Makes a view focusable with the standard ring (and, for small tiles, lift) behaviour.
     * v1.7.1: the ring is drawn inside the view's own box. Full-width rows pass lift 1 so they never grow past their
     * list; small tiles still grow a little, inside scroll areas that keep room around them (vScroll / hScroll).
     */
    static <T extends View> T focusable(T v, float radiusDp, float lift) { return focusable(v, radiusDp, lift, CARD); }

    static <T extends View> T focusable(T v, float radiusDp, float lift, int rest) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setBackground(focusBg(radiusDp, rest));
        if (lift > 1f) {
            float l = 1f + (lift - 1f) * 0.6f;   // v1.8.3: a calmer lift
            v.setOnFocusChangeListener((view, has) -> view.animate().scaleX(has ? l : 1f).scaleY(has ? l : 1f)
                    .setDuration(has ? 160 : 120).setInterpolator(EASE).start());
        }
        return v;
    }

    /** v1.7.1: a vertical scroll area that scrolls a focused item into view with room to spare for its ring and lift. */
    static android.widget.ScrollView vScroll(Context c, float roomDp) {
        android.widget.ScrollView sv = new android.widget.ScrollView(c) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                android.graphics.Rect big = new android.graphics.Rect(r);
                big.inset(0, -dp(roomDp));
                return super.computeScrollDeltaToGetChildRectOnScreen(big);
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return sv;
    }

    /** v1.7.1: the same for a row that scrolls sideways (quick menu rows, tabs). */
    static android.widget.HorizontalScrollView hScroll(Context c, float roomDp) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                android.graphics.Rect big = new android.graphics.Rect(r);
                big.inset(-dp(roomDp), 0);
                return super.computeScrollDeltaToGetChildRectOnScreen(big);
            }
        };
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        return hs;
    }

    /** Icon-over-label tile used by the quick menu. v1.8.3: clear at rest; only the focused tile gets a card. */
    static LinearLayout tile(Context c, int iconRes, String label, float wDp, float hDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(6), dp(4), dp(6), dp(4));
        focusable(l, R_MD, 1.08f, 0x00000000);
        ImageView i = new ImageView(c);
        i.setImageResource(iconRes);
        i.setColorFilter(TEXT);
        l.addView(i, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = text(c, label, CAPTION, DIM, false);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setLineSpacing(dp(2), 1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        l.addView(t, tp);
        l.setTag(t);
        // the label brightens with focus, so the focused tile reads first
        View.OnFocusChangeListener lift = l.getOnFocusChangeListener();
        l.setOnFocusChangeListener((v, has) -> { t.setTextColor(has ? TEXT : DIM); if (lift != null) lift.onFocusChange(v, has); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        l.setLayoutParams(lp);
        return l;
    }

    static void setTileLabel(View tile, String s) {
        if (tile.getTag() instanceof TextView) ((TextView) tile.getTag()).setText(s);
    }

    /** v1.8.2: a Material Symbols vector (res/drawable/ic_*.xml) tinted with one colour. */
    static ImageView icon(Context c, int res, float sizeDp, int tint) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setColorFilter(tint);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setMinimumWidth(dp(sizeDp));
        i.setMinimumHeight(dp(sizeDp));
        return i;
    }

    /**
     * v1.8.2: an edge cue for a row that scrolls sideways: a fade over the last 48dp with a small chevron in it.
     * Not focusable and not clickable, so it never takes a key or a click from the tiles under it.
     */
    static FrameLayout edgeCue(Context c, boolean right) {
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(false);
        f.setClickable(false);
        int base = PANEL | 0xFF000000;
        GradientDrawable g = new GradientDrawable(right ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{base, base & 0x00FFFFFF});
        f.setBackground(g);
        ImageView chev = new ImageView(c);
        chev.setImageResource(right ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_left);
        chev.setColorFilter(DIM);
        chev.setFocusable(false);
        chev.setClickable(false);
        f.addView(chev, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL | (right ? Gravity.END : Gravity.START)));
        f.setVisibility(View.INVISIBLE);
        return f;
    }

    /** v1.8.3: a neutral rounded square behind a site's first letter, for sites without an icon yet. */
    static Drawable letterBg(String host) { return round(0xFF23262D, R_MD, LINE, 1); }

    static int alpha(int color, float a) { return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color)); }

    /**
     * v1.8.3: the window frame. Drawn as the root's foreground: it paints the desk (black) outside a rounded window
     * whose edges are the root's padding, then a hairline around the window. Because it is a foreground it also rounds
     * the corners of whatever is inside (the page, Home), and it never takes focus, keys or clicks.
     */
    static final class WindowFrame extends Drawable {
        private final View host;
        private final Paint desk = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF win = new RectF();
        boolean on = true;

        WindowFrame(View host) {
            this.host = host;
            desk.setColor(DESK);
            line.setColor(LINE_STRONG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1, dp(1)));
        }

        @Override public void draw(Canvas c) {
            if (!on) return;
            android.graphics.Rect b = getBounds();
            win.set(b.left + host.getPaddingLeft(), b.top + host.getPaddingTop(),
                    b.right - host.getPaddingRight(), b.bottom - host.getPaddingBottom());
            float r = dp(FRAME_RADIUS);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(b.left, b.top, b.right, b.bottom, Path.Direction.CW);
            path.addRoundRect(win, r, r, Path.Direction.CW);
            c.drawPath(path, desk);
            float h = line.getStrokeWidth() / 2f;
            win.inset(-h, -h);   // the hairline sits just outside the content, so it never covers a pixel of the page
            c.drawRoundRect(win, r + h, r + h, line);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
