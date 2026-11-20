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
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The handwriting tab's writing surface.
 *
 * <p>Collects finger strokes, draws them with the keyboard's palette, and hands
 * the finished ink to the listener after every lift so the table can show
 * fresh candidates. Points are reported in a 0..255 square, which is the
 * coordinate space the stroke templates were built in.
 */
final class HandwritingView extends View {

    /** How long one stroke takes to thin out once its hold is over. */
    private static final long FADE_DURATION_MS = 500;
    /** Old ink stops here instead of vanishing, so the whole character stays. */
    private static final float MIN_INK_ALPHA = 0.35f;

    /** One finger stroke and when it was lifted, so it can fade on its own. */
    private static final class Stroke {
        final List<int[]> points = new ArrayList<>();
        final Path path = new Path();
        /** Uptime of the lift; 0 while the finger is still down. */
        long endedAt;
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

    /**
     * Each stroke thins out on its own once its hold is over, so a character
     * written slowly dims stroke by stroke rather than all at once, and stops
     * at {@link #MIN_INK_ALPHA} instead of vanishing: the whole character
     * stays on the pad. Frames keep coming only while some stroke still moves.
     */
    private final Runnable fadeFrame = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (isAttachedToWindow()
                    && stillFading(SystemClock.uptimeMillis(),
                            Prefs.handwritingFadeDelay(getContext()))) {
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

    /** Drops a pending or running whole-ink dissolve and restores full strength. */
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
        animator.setDuration(FADE_DURATION_MS);
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

    /**
     * True while some stroke still has to move (hold running or thinning out);
     * the frame loop stops once every stroke sits at its floor.
     */
    private boolean stillFading(long now, int hold) {
        if (hold <= 0) return false;
        for (Stroke stroke : strokes) {
            if (stroke != current && now - stroke.endedAt - hold < FADE_DURATION_MS) {
                return true;
            }
        }
        return false;
    }

    private void startFadeLoop() {
        if (fadeLoopRunning) return;
        if (Prefs.handwritingFadeDelay(getContext()) <= 0) return;
        fadeLoopRunning = true;
        postOnAnimation(fadeFrame);
    }

    private float strokeAlpha(Stroke stroke, long now, int hold) {
        if (stroke == current || hold <= 0) return 1f;
        final long age = now - stroke.endedAt - hold;
        if (age <= 0) return 1f;
        if (age >= FADE_DURATION_MS) return MIN_INK_ALPHA;
        final float t = (float) age / FADE_DURATION_MS;
        return 1f - (1f - MIN_INK_ALPHA) * t;
    }

    boolean isEmpty() {
        return strokes.isEmpty();
    }

    private void emit() {
        if (listener != null) listener.onInkChanged(normalized());
    }

    private List<int[][]> normalized() {
        List<int[][]> result = new ArrayList<>(strokes.size());
        final int width = Math.max(1, getWidth());
        final int height = Math.max(1, getHeight());
        for (Stroke stroke : strokes) {
            int[][] points = new int[stroke.points.size()][];
            for (int i = 0; i < stroke.points.size(); ++i) {
                int[] point = stroke.points.get(i);
                points[i] = new int[]{
                        Math.max(0, Math.min(255, point[0] * 255 / width)),
                        Math.max(0, Math.min(255, point[1] * 255 / height)),
                };
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
        final int hold = Prefs.handwritingFadeDelay(getContext());
        for (Stroke stroke : strokes) {
            final float alpha = strokeAlpha(stroke, now, hold) * inkAlpha;
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
                /* Writing again keeps the ink that is already there, even if
                 * it had started to fade. */
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
                current.endedAt = SystemClock.uptimeMillis();
                rebuildPath(current);
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
