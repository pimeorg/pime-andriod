package dev.pime.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard surface. It only reports key presses; whether a key is consumed by
 * pime-core or passed to the editor is decided by the service.
 */
public final class KeyboardView extends View {
    public static final int KIND_CHAR = 0;
    public static final int KIND_SHIFT = 1;
    public static final int KIND_BACKSPACE = 2;
    public static final int KIND_SPACE = 3;
    public static final int KIND_ENTER = 4;

    public static final class Key {
        public final String label;
        public final int code;
        public final float weight;
        public final int kind;

        Key(String label, int code, float weight, int kind) {
            this.label = label;
            this.code = code;
            this.weight = weight;
            this.kind = kind;
        }

        public boolean isLetter() {
            return kind == KIND_CHAR && code >= 'a' && code <= 'z';
        }
    }

    public interface Listener {
        void onKey(Key key);
    }

    private final List<List<Key>> rows = new ArrayList<>();
    private RectF[][] bounds;
    private Listener listener;
    private boolean shift;
    private int pressedRow = -1;
    private int pressedCol = -1;

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Key repeatKey;

    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatKey != null && listener != null) {
                listener.onKey(repeatKey);
                repeatHandler.postDelayed(this, 70);
            }
        }
    };

    public KeyboardView(Context context) {
        this(context, null);
    }

    public KeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xFF202124);
        textPaint.setColor(0xFFE8EAED);
        textPaint.setTextAlign(Paint.Align.CENTER);
        buildRows();
        setFocusable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isShiftDown() {
        return shift;
    }

    private void buildRows() {
        rows.add(chars("1234567890"));
        rows.add(chars("qwertyuiop"));
        rows.add(chars("asdfghjkl"));

        List<Key> third = new ArrayList<>();
        third.add(new Key("⇧", 0, 1.5f, KIND_SHIFT));
        third.addAll(chars("zxcvbnm"));
        // Plugin keymaps match ASCII control codes (BS/CR), not the HAL's
        // 0x01000000 special range, so send the legacy values.
        third.add(new Key("⌫", 8, 1.5f, KIND_BACKSPACE));
        rows.add(third);

        List<Key> bottom = new ArrayList<>();
        bottom.add(new Key(",", ',', 1.2f, KIND_CHAR));
        bottom.add(new Key("空格", ' ', 5f, KIND_SPACE));
        bottom.add(new Key(".", '.', 1.2f, KIND_CHAR));
        bottom.add(new Key("回车", 13, 1.6f, KIND_ENTER));
        rows.add(bottom);
    }

    private static List<Key> chars(String letters) {
        List<Key> keys = new ArrayList<>(letters.length());
        for (int i = 0; i < letters.length(); ++i) {
            final char c = letters.charAt(i);
            keys.add(new Key(String.valueOf(c), Character.toLowerCase(c), 1f,
                    KIND_CHAR));
        }
        return keys;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        final float outer = dp(4);
        final float gap = dp(4);
        final float rowGap = dp(4);
        final int rowCount = rows.size();
        final float rowHeight =
                (height - outer * 2 - rowGap * (rowCount - 1)) / rowCount;
        bounds = new RectF[rowCount][];
        for (int r = 0; r < rowCount; ++r) {
            final List<Key> row = rows.get(r);
            float total = 0;
            for (Key key : row) total += key.weight;
            final float available = width - outer * 2 - gap * (row.size() - 1);
            final float y = outer + r * (rowHeight + rowGap);
            float x = outer;
            bounds[r] = new RectF[row.size()];
            for (int c = 0; c < row.size(); ++c) {
                final float kw = available * row.get(c).weight / total;
                bounds[r][c] = new RectF(x, y, x + kw, y + rowHeight);
                x += kw + gap;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bounds == null) return;
        final float radius = dp(6);
        for (int r = 0; r < bounds.length; ++r) {
            for (int c = 0; c < bounds[r].length; ++c) {
                final Key key = rows.get(r).get(c);
                final boolean pressed = r == pressedRow && c == pressedCol;
                keyPaint.setColor(pressed ? 0xFF5F6368 : colorFor(key));
                canvas.drawRoundRect(bounds[r][c], radius, radius, keyPaint);
                drawLabel(canvas, bounds[r][c], key);
            }
        }
    }

    private int colorFor(Key key) {
        if (key.kind == KIND_SHIFT && shift) return 0xFF8AB4F8;
        if (key.kind == KIND_CHAR && key.code >= '0' && key.code <= '9') {
            return 0xFF303134;
        }
        return 0xFF3C4043;
    }

    private void drawLabel(Canvas canvas, RectF rect, Key key) {
        String label = key.label;
        if (shift && key.isLetter()) label = label.toUpperCase();
        final float size = label.length() > 1 ? 16f : 22f;
        textPaint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setColor(key.kind == KIND_SHIFT && shift ? 0xFF202124 : 0xFFE8EAED);
        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        final float baseline =
                rect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(label, rect.centerX(), baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int[] hit = hitTest(event.getX(), event.getY());
                if (hit == null) return false;
                pressedRow = hit[0];
                pressedCol = hit[1];
                invalidate();
                Key key = rows.get(pressedRow).get(pressedCol);
                if (key.kind == KIND_BACKSPACE) {
                    repeatKey = key;
                    repeatHandler.postDelayed(repeatRunnable, 350);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressedRow < 0) return false;
                int[] hit = hitTest(event.getX(), event.getY());
                int row = hit == null ? -1 : hit[0];
                int col = hit == null ? -1 : hit[1];
                if (row != pressedRow || col != pressedCol) {
                    pressedRow = row;
                    pressedCol = col;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (pressedRow < 0) return false;
                int[] hit = hitTest(event.getX(), event.getY());
                final boolean same = hit != null && hit[0] == pressedRow
                        && hit[1] == pressedCol;
                final Key key = rows.get(pressedRow).get(pressedCol);
                clearPressed();
                if (same) fire(key);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                clearPressed();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void clearPressed() {
        pressedRow = -1;
        pressedCol = -1;
        repeatKey = null;
        repeatHandler.removeCallbacks(repeatRunnable);
        invalidate();
    }

    private void fire(Key key) {
        if (key.kind == KIND_SHIFT) {
            shift = !shift;
            invalidate();
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener != null) listener.onKey(key);
        if (shift && key.isLetter()) {
            shift = false;
            invalidate();
        }
    }

    private int[] hitTest(float x, float y) {
        if (bounds == null) return null;
        for (int r = 0; r < bounds.length; ++r) {
            for (int c = 0; c < bounds[r].length; ++c) {
                if (bounds[r][c].contains(x, y)) return new int[] {r, c};
            }
        }
        return null;
    }

    @Override
    protected void onDetachedFromWindow() {
        repeatHandler.removeCallbacks(repeatRunnable);
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
