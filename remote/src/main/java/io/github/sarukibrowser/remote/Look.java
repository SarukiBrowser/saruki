package io.github.sarukibrowser.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v1.0.2: Saruki Remote's design tokens, the same "Window" system as Saruki on the TV: graphite surfaces, hairline
 * borders, Inter, and Saruki pink only where it means "this one" (the main action, a value, the live state).
 */
final class Look {
    static final int BG = 0xFF0B0C0E;
    static final int CARD = 0xFF131418;
    static final int FIELD = 0xFF1B1D22;
    static final int PRESSED = 0x1FFFFFFF;
    static final int LINE = 0x1FFFFFFF;
    static final int LINE_STRONG = 0x33FFFFFF;
    static final int TEXT = 0xFFECEDEF;
    static final int MUTED = 0xFF8E929B;
    static final int ACCENT = 0xFFFF5CA8;
    static final int ON_ACCENT = 0xFF1A0610;
    static final int GOOD = 0xFF5FD38D;
    static final int WARN = 0xFFF0A267;

    static final float R_SM = 10, R_MD = 14, R_LG = 18;

    static Typeface REGULAR = Typeface.DEFAULT, MEDIUM = Typeface.DEFAULT, SEMIBOLD = Typeface.DEFAULT_BOLD;
    private static boolean loaded;

    static void init(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            REGULAR = c.getResources().getFont(R.font.inter_400);
            MEDIUM = c.getResources().getFont(R.font.inter_500);
            SEMIBOLD = c.getResources().getFont(R.font.inter_600);
        } catch (RuntimeException e) {
            REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            SEMIBOLD = MEDIUM;
        }
    }

    static int px(Context c, float dp) { return Math.round(dp * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(Context c, int color, float r, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(c, r));
        if (strokeDp > 0) g.setStroke(Math.max(1, px(c, strokeDp)), stroke);
        return g;
    }

    /** A background that shows a soft ripple when pressed (touch feedback within 100ms). */
    static Drawable pressable(Context c, Drawable rest, float r) {
        GradientDrawable mask = round(c, 0xFFFFFFFF, r, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(PRESSED), rest, mask);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        init(c);
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(weight >= 600 ? SEMIBOLD : weight >= 500 ? MEDIUM : REGULAR);
        if (sp >= 22) t.setLetterSpacing(-0.015f);
        return t;
    }

    /** Small all-caps section label above a card. */
    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.US), 12, MUTED, 500);
        t.setLetterSpacing(0.08f);
        t.setPadding(px(c, 4), px(c, 18), 0, px(c, 8));
        return t;
    }

    /** A card: graphite with a hairline edge. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, CARD, R_LG, LINE, 1));
        l.setPadding(px(c, 16), px(c, 16), px(c, 16), px(c, 16));
        return l;
    }

    /** Primary (pink fill) or secondary (hairline outline) button, 48dp tall. */
    static TextView button(Context c, String s, boolean primary) {
        TextView t = text(c, s, 15, primary ? ON_ACCENT : TEXT, 600);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(px(c, 48));
        t.setPadding(px(c, 16), px(c, 12), px(c, 16), px(c, 12));
        Drawable rest = primary ? round(c, ACCENT, R_MD, 0, 0) : round(c, 0x00000000, R_MD, LINE_STRONG, 1);
        t.setBackground(pressable(c, rest, R_MD));
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(c, 10);
        t.setLayoutParams(lp);
        return t;
    }

    /** A tinted Material Symbols icon. */
    static ImageView icon(Context c, int res, int tint) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setColorFilter(tint);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return i;
    }

    /** A small filled circle (status). */
    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(c, sizeDp), px(c, sizeDp)));
        return v;
    }

    static void setDot(View v, int color) {
        if (v.getBackground() instanceof GradientDrawable) ((GradientDrawable) v.getBackground()).setColor(color);
    }

    /** A one-pixel divider. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, px(c, 0.5f))));
        return v;
    }
}
