package io.github.sarukibrowser.tv;

import android.content.Context;
import android.graphics.Color;
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

/** Small view helpers so every screen shares one look: dark glass panels, pink focus ring, focus lift. */
final class Ui {
    static final int BG = 0xFF0E1116;
    static final int PANEL = 0xF2161B23;
    static final int CARD = 0xFF202733;
    static final int CARD_FOCUS = 0xFF2B3647;
    static final int ACCENT = 0xFFFF5CA8;   // v1.6 brand: Pointer Pink
    static final int ON_ACCENT = 0xFF111111; // text on a pink fill
    static final int GOLD = 0xFFFFD166;
    static final int TEXT = 0xFFF2F5F9;
    static final int DIM = 0xFF9AA6B8;

    // v1.8.2: one type scale for the whole TV app (couch distance on a 55" TV): nothing is smaller than CAPTION.
    static final float CAPTION = 14, BODY = 16, TITLE = 20, HEADLINE = 24, DISPLAY = 28;

    static float density = 2f;

    /** One easing curve for every movement in the app: quick start, soft landing. */
    static final android.view.animation.DecelerateInterpolator EASE = new android.view.animation.DecelerateInterpolator(1.8f);

    static void init(Context c) { density = c.getResources().getDisplayMetrics().density; }

    static int dp(float v) { return Math.round(v * density); }

    static GradientDrawable round(int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    /** Card background: plain when idle, brighter with a pink ring when focused. */
    static Drawable focusBg(float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(CARD_FOCUS, radiusDp, ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, round(CARD_FOCUS, radiusDp, ACCENT, 3));
        s.addState(new int[]{}, round(CARD, radiusDp, 0, 0));
        return s;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * Makes a view focusable with the standard ring (and, for small tiles, lift) behaviour.
     * v1.7.1: the pink ring is drawn inside the view's own box. Full-width rows pass lift 1 so they never grow past
     * their list (that is what cut the ring's ends flat); small tiles still grow a little, inside scroll areas that keep
     * room around them (vScroll / hScroll). No elevation shadow any more: lists clipped it into marks at their edges.
     */
    static <T extends View> T focusable(T v, float radiusDp, float lift) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setBackground(focusBg(radiusDp));
        if (lift > 1f) {
            v.setOnFocusChangeListener((view, has) -> view.animate().scaleX(has ? lift : 1f).scaleY(has ? lift : 1f)
                    .setDuration(has ? 150 : 120).setInterpolator(EASE).start());
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
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        return hs;
    }

    /** Icon-over-label tile used by the quick menu. */
    static LinearLayout tile(Context c, int iconRes, String label, float wDp, float hDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        focusable(l, 14, 1.08f);
        ImageView i = new ImageView(c);
        i.setImageResource(iconRes);
        i.setColorFilter(TEXT);
        l.addView(i, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView t = text(c, label, CAPTION, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        l.addView(t, tp);
        l.setTag(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
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
     * v1.8.2: an edge cue for a row that scrolls sideways: a dark fade over the last 48dp with a small chevron in it.
     * Not focusable and not clickable, so it never takes a key or a click from the tiles under it.
     */
    static FrameLayout edgeCue(Context c, boolean right) {
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(false);
        f.setClickable(false);
        GradientDrawable g = new GradientDrawable(right ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2141922, 0x00141922});
        f.setBackground(g);
        ImageView chev = new ImageView(c);
        chev.setImageResource(right ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_left);
        chev.setColorFilter(TEXT);
        chev.setFocusable(false);
        chev.setClickable(false);
        f.addView(chev, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER_VERTICAL | (right ? Gravity.END : Gravity.START)));
        f.setVisibility(View.INVISIBLE);
        return f;
    }

    /** Coloured circle with the site's first letter, for sites without an icon yet. */
    static Drawable letterBg(String host) {
        int[] palette = {0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC, 0xFFFFA726, 0xFF42A5F5, 0xFF66BB6A, 0xFFEC407A};
        int h = host == null ? 0 : Math.abs(host.hashCode());
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(palette[h % palette.length]);
        return g;
    }

    static int alpha(int color, float a) { return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color)); }
}
