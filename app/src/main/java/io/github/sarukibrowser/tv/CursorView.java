package io.github.sarukibrowser.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * The on-screen pointer. It is a small view that is moved with translation, so moving it never redraws the screen:
 * the TV's GPU only shifts a tiny ready-made layer. Movement logic lives in BrowserActivity.
 * v1.7.1: drawn as a classic arrow whose tip sits at the view's centre, which is the click point (x, y).
 */
final class CursorView extends View {
    static final int NORMAL = 0, LINK = 1, TEXT = 2, DRAG = 3;

    /** Pointer position inside the page area (the parent's content box), in screen pixels. */
    float x, y;
    private int kind = NORMAL;
    private int state = 2;          // 2 shown, 1 dimmed after a short idle, 0 hidden
    private final int size;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pulse;            // 0..1, animates on click
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    CursorView(Context c) {
        super(c);
        size = Ui.dp(84);
        ring.setStyle(Paint.Style.STROKE);
        shadow.setColor(0x55000000);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    int sizePx() { return size; }

    /** Width and height of the area the pointer moves in (the parent minus its edge margin). */
    int areaW() {
        View p = (View) getParent();
        return p == null ? 0 : Math.max(1, p.getWidth() - p.getPaddingLeft() - p.getPaddingRight());
    }

    int areaH() {
        View p = (View) getParent();
        return p == null ? 0 : Math.max(1, p.getHeight() - p.getPaddingTop() - p.getPaddingBottom());
    }

    void setKind(int k) {
        if (k == kind) return;
        kind = k;
        float s = k == LINK ? 1.14f : 1f;   // v1.7.1: the arrow grows a little over links; scaling keeps the tip in place
        animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(EASE).start();
        invalidate();
    }

    int kind() { return kind; }

    void showCursor(boolean s) { setState(s ? 2 : 0); }

    /** Fades to a faint pointer after a short idle; it still counts as shown, so OK clicks right away. */
    void dim() { if (state == 2) setState(1); }

    private void setState(int st) {
        if (st == state) return;
        state = st;
        float a = st == 2 ? 1f : st == 1 ? 0.32f : 0f;
        animate().alpha(a).setDuration(st == 2 ? 90 : st == 1 ? 600 : 250).setInterpolator(EASE).start();
    }

    boolean isShown2() { return state > 0; }

    boolean isDimmed() { return state == 1; }

    void moveTo(float nx, float ny) {
        x = Math.max(0, Math.min(areaW() - 1, nx));
        y = Math.max(0, Math.min(areaH() - 1, ny));
        setTranslationX(x - size / 2f);
        setTranslationY(y - size / 2f);
    }

    void clickPulse() {
        pulse = 1f;
        postOnAnimation(new Runnable() {
            @Override public void run() {
                pulse -= 0.09f;
                invalidate();
                if (pulse > 0) postOnAnimation(this); else pulse = 0;
            }
        });
    }

    // v1.7.1: the classic desktop arrow, black inside with a Saruki-pink border. Its TIP is drawn exactly at the
    // centre of this view, and the centre of this view is the point that gets clicked (x, y), so a click always
    // lands where the tip points. Size changes (setKind) scale around the same centre, so the tip never moves.
    // Arrow outline in "units" with the tip at (0, 0); one unit is ARROW_UNIT dp (about 28 dp tall in all).
    private static final float[] ARROW = {
            0, 0,
            0, 17.2f,
            4.1f, 13.3f,
            6.9f, 19.6f,
            9.6f, 18.4f,
            6.9f, 12.3f,
            12.3f, 12.3f,
    };
    private static final float ARROW_UNIT = 1.42f;
    private static final int INK = 0xFF111111;
    private static final int PINK = 0xFFFF5CA8;
    private static final int PINK_BRIGHT = 0xFFFF86C0;
    private final android.graphics.Path arrow = new android.graphics.Path();
    private final android.graphics.Path work = new android.graphics.Path();
    private final android.graphics.Matrix shift = new android.graphics.Matrix();

    private void buildArrow(float cx, float cy) {
        arrow.reset();
        float u = ARROW_UNIT * Ui.density;
        arrow.moveTo(cx + ARROW[0] * u, cy + ARROW[1] * u);
        for (int i = 2; i < ARROW.length; i += 2) arrow.lineTo(cx + ARROW[i] * u, cy + ARROW[i + 1] * u);
        arrow.close();
    }

    @Override protected void onDraw(Canvas c) {
        float d = Ui.density, cx = size / 2f, cy = size / 2f;
        fill.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeJoin(Paint.Join.ROUND);
        ring.setStrokeCap(Paint.Cap.ROUND);
        int edge = kind == DRAG ? Ui.GOLD : kind == LINK ? PINK_BRIGHT : PINK;
        float edgeW = (kind == LINK ? 2.9f : 2.3f) * d;

        if (kind == TEXT) {
            // I-beam centred on the click point, in the same black-with-pink look
            float hh = 11 * d, serif = 4 * d;
            work.reset();
            work.moveTo(cx - serif, cy - hh); work.lineTo(cx + serif, cy - hh);
            work.moveTo(cx, cy - hh); work.lineTo(cx, cy + hh);
            work.moveTo(cx - serif, cy + hh); work.lineTo(cx + serif, cy + hh);
            // soft shadow
            shadow.setColor(0x44000000);
            shadow.setStyle(Paint.Style.STROKE);
            shadow.setStrokeCap(Paint.Cap.ROUND);
            shadow.setStrokeJoin(Paint.Join.ROUND);
            shadow.setStrokeWidth(2.4f * d + 2 * edgeW + 2 * d);
            c.save(); c.translate(1.2f * d, 1.8f * d); c.drawPath(work, shadow); c.restore();
            ring.setColor(edge);
            ring.setStrokeWidth(2.4f * d + 2 * edgeW);
            c.drawPath(work, ring);
            ring.setColor(INK);
            ring.setStrokeWidth(2.4f * d);
            c.drawPath(work, ring);
        } else {
            buildArrow(cx, cy);
            // soft drop shadow: two offset copies, the wider one fainter, so it shows on light and dark pages
            shadow.setStyle(Paint.Style.FILL_AND_STROKE);
            shadow.setStrokeJoin(Paint.Join.ROUND);
            shadow.setColor(0x33000000);
            shadow.setStrokeWidth(edgeW + 2.4f * d);
            shift.setTranslate(1.6f * d, 2.4f * d);
            arrow.transform(shift, work);
            c.drawPath(work, shadow);
            shadow.setColor(0x55000000);
            shadow.setStrokeWidth(edgeW);
            shift.setTranslate(1.0f * d, 1.5f * d);
            arrow.transform(shift, work);
            c.drawPath(work, shadow);
            // black body, then the pink border on top of its edge
            fill.setColor(INK);
            c.drawPath(arrow, fill);
            ring.setColor(edge);
            ring.setStrokeWidth(edgeW);
            c.drawPath(arrow, ring);
        }
        if (pulse > 0) {
            // click pulse: a pink ring that grows out of the tip (the exact click point) and fades
            float t = 1 - pulse;
            ring.setColor(Ui.alpha(PINK, pulse));
            ring.setStrokeWidth(3 * d);
            c.drawCircle(cx, cy, 5 * d + (1 - (1 - t) * (1 - t)) * 20 * d, ring);
        }
    }

    static ViewGroup.LayoutParams params(int size) {
        return new android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP | android.view.Gravity.START);
    }
}
