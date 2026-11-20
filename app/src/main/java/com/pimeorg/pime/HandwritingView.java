package com.pimeorg.pime;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The handwriting tab's writing surface.
 *
 * <p>Collects finger strokes, draws them with the keyboard's palette, and hands
 * the finished ink to the listener after every lift so the table can show
 * fresh candidates. Points are reported in view pixels; the recogniser
 * normalises by the ink's own extent, so no particular scale is required.
 *
 * <p>Every stroke dims a moment after it was written, strictly in the order it
 * was drawn — one stroke finishes dimming before the next begins — and stops
 * at {@link #FADED_ALPHA} instead of vanishing: the finished parts of a
 * character sit lighter behind the strokes still being written, and the whole
 * character stays on the pad for recognition.
 */
final class HandwritingView extends View {

    /** Where a stroke's ink stops once its turn to dim has come. */
    private static final float FADED_ALPHA = 0.3f;
    /** One stroke's dimming; slow enough that the eye follows it down. */
    private static final long DIM_MS = 1500;
    /** The dissolve after a commit, which should feel immediate. */
    private static final long DISSOLVE_MS = 300;

    /** One finger stroke; it remembers when its turn to dim comes and starts. */
    private static final class Stroke {
        final List<int[]> points = new ArrayList<>();
        final Path path = new Path();
        /** Earliest uptime dimming may begin; -1 while the finger is still down. */
        long fadeAt = -1;
        /** Uptime dimming actually began; -1 until it is this stroke's turn. */
        long fadeStart = -1;
    }

    interface Listener {
        /** Called after a stroke ends (and after clear/undo, with fewer strokes). */
        void onInkChanged(List<int[][]> strokes);
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();

    private Listener listener;
    private KeyboardTheme palette;
    private Stroke current;
    private float lastX;
    private float lastY;
    /** Guide box and the hint in the middle; the keyboard pad keeps both off. */
    private boolean chromeVisible = true;
    /** Whole-ink strength, used only by the dissolve after a commit. */
    private float inkAlpha = 1f;
    private ValueAnimator fadeAnimator;
    private boolean fadeLoopRunning;
    private final AccelerateDecelerateInterpolator dimCurve =
            new AccelerateDecelerateInterpolator();

    /**
     * Frames keep coming only while some stroke is still waiting or on its way
     * down to {@link #FADED_ALPHA}; once every stroke sits there the loop stops.
     */
    private final Runnable fadeFrame = new Runnable() {
        @Override
        public void run() {
            pumpFade(SystemClock.uptimeMillis());
            invalidate();
            if (isAttachedToWindow() && stillFading(SystemClock.uptimeMillis())) {
                postOnAnimation(this);
            } else {
                fadeLoopRunning = false;
            }
        }
    };

    HandwritingView(Context context) {
        super(context);
        setClickable(true);
        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);
        guidePaint.setStyle(Paint.Style.STROKE);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * The guide box and the "在这里手写" hint are for the candidate panel's
     * small tab, where the surface needs explaining. The keyboard's pad fills
     * the key area like any other layout and stays blank until it is written
     * on, as a mainstream IME's handwriting page does.
     */
    void setChromeVisible(boolean visible) {
        this.chromeVisible = visible;
        invalidate();
    }

    void setPalette(KeyboardTheme palette) {
        this.palette = palette;
        inkPaint.setStrokeWidth(dp(5));
        guidePaint.setStrokeWidth(Math.max(1, dp(1)));
        hintPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                15f, getResources().getDisplayMetrics()));
        hintPaint.setColor(palette.hint);
        inkPaint.setColor(palette.candidateText);
        invalidate();
    }

    void clear() {
        cancelFade();
        strokes.clear();
        current = null;
        invalidate();
        emit();
    }

    void undo() {
        if (strokes.isEmpty()) return;
        cancelFade();
        strokes.remove(strokes.size() - 1);
        current = null;
        invalidate();
        emit();
    }

    /**
     * Dissolves the ink after the caller committed from it. The caller clears
     * the recognition results itself, so nothing is emitted here.
     */
    void fadeOut() {
        if (strokes.isEmpty()) return;
        animateInk(0f, () -> {
            strokes.clear();
            current = null;
            inkAlpha = 1f;
            invalidate();
        });
    }

    /** Marks when this stroke may start dimming: a short beat after it landed. */
    private void scheduleFade(Stroke stroke) {
        final int delay = Prefs.handwritingFadeDelay(getContext());
        if (delay <= 0) return;
        stroke.fadeAt = SystemClock.uptimeMillis() + delay;
    }

    private void startFadeLoop() {
        if (fadeLoopRunning) return;
        if (Prefs.handwritingFadeDelay(getContext()) <= 0) return;
        fadeLoopRunning = true;
        postOnAnimation(fadeFrame);
    }

    /**
     * Hands each stroke its turn in drawing order: a stroke may begin dimming
     * only once it is due and the stroke before it has finished dimming, so
     * the ink always lightens first stroke to last, never several at once.
     */
    private void pumpFade(long now) {
        long previousEnd = 0;
        for (Stroke stroke : strokes) {
            if (stroke == current) break;
            if (stroke.fadeAt < 0) continue;
            final long start = stroke.fadeStart >= 0
                    ? stroke.fadeStart
                    : Math.max(stroke.fadeAt, previousEnd);
            if (stroke.fadeStart < 0 && now >= start) stroke.fadeStart = start;
            previousEnd = start + DIM_MS;
        }
    }

    private boolean stillFading(long now) {
        for (Stroke stroke : strokes) {
            if (stroke == current || stroke.fadeAt < 0) continue;
            if (stroke.fadeStart < 0 || now < stroke.fadeStart + DIM_MS) {
                return true;
            }
        }
        return false;
    }

    private float strokeAlpha(Stroke stroke, long now) {
        if (stroke == current || stroke.fadeStart < 0) return 1f;
        final long age = now - stroke.fadeStart;
        if (age <= 0) return 1f;
        if (age >= DIM_MS) return FADED_ALPHA;
        final float t = dimCurve.getInterpolation((float) age / DIM_MS);
        return 1f - (1f - FADED_ALPHA) * t;
    }

    /** Drops the whole-ink dissolve and restores full strength. */
    private void cancelFade() {
        if (fadeAnimator != null) {
            final ValueAnimator animator = fadeAnimator;
            fadeAnimator = null;
            animator.cancel();
        }
        if (inkAlpha != 1f) {
            inkAlpha = 1f;
            invalidate();
        }
    }

    private void animateInk(float target, Runnable end) {
        final ValueAnimator previous = fadeAnimator;
        fadeAnimator = null;
        if (previous != null) previous.cancel();
        final ValueAnimator animator = ValueAnimator.ofFloat(inkAlpha, target);
        fadeAnimator = animator;
        animator.setDuration(DISSOLVE_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            inkAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (fadeAnimator != animator) return;
                fadeAnimator = null;
                end.run();
            }
        });
        animator.start();
    }

    boolean isEmpty() {
        return strokes.isEmpty();
    }

    private void emit() {
        if (listener != null) listener.onInkChanged(inkPoints());
    }

    /**
     * The ink in view pixels. The recogniser stretches whatever it is given so
     * the ink's longer side fills its 0..255 working space, so it needs a
     * uniform space, not a particular size. Mapping into 0..255 here used to
     * divide a character drawn at a comfortable size down to some sixty
     * integer units, and one unit of rounding is then more than a percent of
     * the character: 好's first stroke gained a corner and 她 overtook it.
     */
    private List<int[][]> inkPoints() {
        List<int[][]> result = new ArrayList<>(strokes.size());
        for (Stroke stroke : strokes) {
            int[][] points = new int[stroke.points.size()][];
            for (int i = 0; i < stroke.points.size(); ++i) {
                int[] point = stroke.points.get(i);
                points[i] = new int[]{point[0], point[1]};
            }
            result.add(points);
        }
        return result;
    }

    private void rebuildPath(Stroke stroke) {
        stroke.path.reset();
        if (stroke.points.isEmpty()) return;
        final int[] first = stroke.points.get(0);
        stroke.path.moveTo(first[0], first[1]);
        if (stroke.points.size() == 1) {
            stroke.path.lineTo(first[0] + 0.1f, first[1]);
            return;
        }
        for (int i = 1; i < stroke.points.size(); ++i) {
            final int[] point = stroke.points.get(i);
            stroke.path.lineTo(point[0], point[1]);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (palette != null && chromeVisible) {
            guidePaint.setColor(palette.divider);
            float inset = dp(10);
            guide.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(guide, dp(12), dp(12), guidePaint);
            if (strokes.isEmpty()) {
                canvas.drawText(getContext().getString(R.string.ime_handwriting_hint),
                        getWidth() / 2f,
                        getHeight() / 2f + dp(5),
                        hintPaint);
            }
        }
        final long now = SystemClock.uptimeMillis();
        for (Stroke stroke : strokes) {
            final float alpha = strokeAlpha(stroke, now) * inkAlpha;
            if (alpha <= 0f) continue;
            inkPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawPath(stroke.path, inkPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelFade();
        fadeLoopRunning = false;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelFade();
                current = new Stroke();
                strokes.add(current);
                current.points.add(new int[]{Math.round(event.getX()), Math.round(event.getY())});
                lastX = event.getX();
                lastY = event.getY();
                rebuildPath(current);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (current == null) return true;
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (dx * dx + dy * dy < dp(1) * dp(1)) return true;
                current.points.add(new int[]{Math.round(event.getX()), Math.round(event.getY())});
                lastX = event.getX();
                lastY = event.getY();
                rebuildPath(current);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (current == null) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    current.points.add(new int[]{Math.round(event.getX()), Math.round(event.getY())});
                }
                rebuildPath(current);
                scheduleFade(current);
                current = null;
                invalidate();
                emit();
                startFadeLoop();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
