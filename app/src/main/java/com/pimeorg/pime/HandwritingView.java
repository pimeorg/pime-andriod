package com.pimeorg.pime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

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

    interface Listener {
        /** Called after a stroke ends (and after clear/undo, with fewer strokes). */
        void onInkChanged(List<int[][]> strokes);
    }

    private final List<List<int[]>> strokes = new ArrayList<>();
    private final Path inkPath = new Path();
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();

    private Listener listener;
    private KeyboardTheme palette;
    private List<int[]> current;
    private float lastX;
    private float lastY;

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
        strokes.clear();
        current = null;
        rebuildPath();
        invalidate();
        emit();
    }

    void undo() {
        if (strokes.isEmpty()) return;
        strokes.remove(strokes.size() - 1);
        current = null;
        rebuildPath();
        invalidate();
        emit();
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
        for (List<int[]> stroke : strokes) {
            int[][] points = new int[stroke.size()][];
            for (int i = 0; i < stroke.size(); ++i) {
                int[] point = stroke.get(i);
                points[i] = new int[]{
                        Math.max(0, Math.min(255, point[0] * 255 / width)),
                        Math.max(0, Math.min(255, point[1] * 255 / height)),
                };
            }
            result.add(points);
        }
        return result;
    }

    private void rebuildPath() {
        inkPath.reset();
        for (List<int[]> stroke : strokes) {
            if (stroke.isEmpty()) continue;
            inkPath.moveTo(stroke.get(0)[0], stroke.get(0)[1]);
            if (stroke.size() == 1) {
                inkPath.lineTo(stroke.get(0)[0] + 0.1f, stroke.get(0)[1]);
                continue;
            }
            for (int i = 1; i < stroke.size(); ++i) {
                inkPath.lineTo(stroke.get(i)[0], stroke.get(i)[1]);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (palette != null) {
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
        canvas.drawPath(inkPath, inkPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                current = new ArrayList<>();
                strokes.add(current);
                current.add(new int[]{Math.round(event.getX()), Math.round(event.getY())});
                lastX = event.getX();
                lastY = event.getY();
                rebuildPath();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (current == null) return true;
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (dx * dx + dy * dy < dp(1) * dp(1)) return true;
                current.add(new int[]{Math.round(event.getX()), Math.round(event.getY())});
                lastX = event.getX();
                lastY = event.getY();
                rebuildPath();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (current == null) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    current.add(new int[]{Math.round(event.getX()), Math.round(event.getY())});
                }
                current = null;
                rebuildPath();
                invalidate();
                emit();
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
