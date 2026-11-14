package com.pimeorg.pime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard surface. It only reports key presses; whether a key is consumed by
 * pime-core or passed to the editor is decided by the service.
 *
 * Two pages (letters and symbols) of four rows each. Everything is drawn here
 * rather than assembled from child views so a key press costs no allocation and
 * the whole surface reacts as one.
 */
public final class KeyboardView extends View {
    public static final int KIND_CHAR = 0;
    public static final int KIND_SHIFT = 1;
    public static final int KIND_BACKSPACE = 2;
    public static final int KIND_SPACE = 3;
    public static final int KIND_ENTER = 4;
    public static final int KIND_SYMBOLS = 5;
    public static final int KIND_LETTERS = 6;

    private static final int PAGE_LETTERS = 0;
    private static final int PAGE_SYMBOLS = 1;

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

    @SuppressWarnings("unchecked")
    private final List<List<Key>>[] pages = new List[2];
    private int page = PAGE_LETTERS;
    private RectF[][] bounds;
    private int boundsPage = -1;
    private Listener listener;
    private KeyboardTheme theme;
    private boolean shift;
    private int pressedRow = -1;
    private int pressedCol = -1;

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphPath = new Path();
    private final RectF keyRect = new RectF();
    private final RectF edgeRect = new RectF();
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
        theme = KeyboardTheme.of(context);
        textPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        buildPages();
        setFocusable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setKeyboardTheme(KeyboardTheme theme) {
        this.theme = theme;
        invalidate();
    }

    public boolean isShiftDown() {
        return shift;
    }

    private void buildPages() {
        pages[PAGE_LETTERS] = letterPage();
        pages[PAGE_SYMBOLS] = symbolPage();
    }

    private List<List<Key>> letterPage() {
        final List<List<Key>> rows = new ArrayList<>(4);
        rows.add(chars("qwertyuiop"));
        rows.add(chars("asdfghjkl"));

        final List<Key> third = new ArrayList<>();
        third.add(new Key("⇧", 0, 1.5f, KIND_SHIFT));
        third.addAll(chars("zxcvbnm"));
        third.add(new Key("⌫", 8, 1.5f, KIND_BACKSPACE));
        rows.add(third);

        final List<Key> bottom = new ArrayList<>();
        bottom.add(new Key("符", 0, 1.2f, KIND_SYMBOLS));
        bottom.add(new Key(",", ',', 0.9f, KIND_CHAR));
        bottom.add(new Key("空格", ' ', 4.6f, KIND_SPACE));
        bottom.add(new Key(".", '.', 0.9f, KIND_CHAR));
        bottom.add(new Key("回车", 13, 1.8f, KIND_ENTER));
        rows.add(bottom);
        return rows;
    }

    private List<List<Key>> symbolPage() {
        final List<List<Key>> rows = new ArrayList<>(4);
        rows.add(chars("1234567890"));
        rows.add(chars("@#¥&*()-+/"));

        final List<Key> third = new ArrayList<>();
        third.add(new Key("ABC", 0, 1.5f, KIND_LETTERS));
        third.addAll(chars(":;'\"?!"));
        third.add(new Key("⌫", 8, 1.5f, KIND_BACKSPACE));
        rows.add(third);

        final List<Key> bottom = new ArrayList<>();
        bottom.add(new Key("字母", 0, 1.2f, KIND_LETTERS));
        bottom.add(new Key("，", ',', 0.9f, KIND_CHAR));
        bottom.add(new Key("空格", ' ', 4.6f, KIND_SPACE));
        bottom.add(new Key("。", '.', 0.9f, KIND_CHAR));
        bottom.add(new Key("回车", 13, 1.8f, KIND_ENTER));
        rows.add(bottom);
        return rows;
    }

    private static List<Key> chars(String letters) {
        final List<Key> keys = new ArrayList<>(letters.length());
        for (int i = 0; i < letters.length(); ++i) {
            final char c = letters.charAt(i);
            keys.add(new Key(String.valueOf(c), c, 1f, KIND_CHAR));
        }
        return keys;
    }

    private List<Key> row(int r) {
        return pages[page].get(r);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        final List<List<Key>> rows = pages[page];
        final float padH = dp(4);
        final float padTop = dp(9);
        final float padBottom = dp(7);
        final float gap = dp(6);
        final float rowGap = dp(8);
        final int rowCount = rows.size();
        final float rowHeight = (height - padTop - padBottom
                - rowGap * (rowCount - 1)) / rowCount;
        bounds = new RectF[rowCount][];
        for (int r = 0; r < rowCount; ++r) {
            final List<Key> keys = rows.get(r);
            float total = 0;
            for (Key key : keys) total += key.weight;
            final float available = width - padH * 2 - gap * (keys.size() - 1);
            final float y = padTop + r * (rowHeight + rowGap);
            float x = padH;
            bounds[r] = new RectF[keys.size()];
            for (int c = 0; c < keys.size(); ++c) {
                final float kw = available * keys.get(c).weight / total;
                bounds[r][c] = new RectF(x, y, x + kw, y + rowHeight);
                x += kw + gap;
            }
        }
        boundsPage = page;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(theme.board);
        if (bounds == null || boundsPage != page) return;
        for (int r = 0; r < bounds.length; ++r) {
            for (int c = 0; c < bounds[r].length; ++c) {
                drawKey(canvas, row(r).get(c), bounds[r][c],
                        r == pressedRow && c == pressedCol);
            }
        }
    }

    private void drawKey(Canvas canvas, Key key, RectF rect, boolean pressed) {
        final int face;
        final int edge;
        final int label;
        final boolean onAccent;
        switch (key.kind) {
            case KIND_SHIFT:
                onAccent = shift;
                face = shift ? (pressed ? theme.accentPressed : theme.accent)
                        : (pressed ? theme.keyFuncPressed : theme.keyFunc);
                edge = shift ? 0 : theme.keyFuncEdge;
                label = shift ? theme.onAccent : theme.keyFuncText;
                break;
            case KIND_ENTER:
                onAccent = true;
                face = pressed ? theme.accentPressed : theme.accent;
                edge = 0;
                label = theme.onAccent;
                break;
            case KIND_CHAR:
                onAccent = false;
                face = pressed ? theme.keyPressed : theme.key;
                edge = theme.keyEdge;
                label = theme.keyText;
                break;
            default:
                onAccent = false;
                face = pressed ? theme.keyFuncPressed : theme.keyFunc;
                edge = theme.keyFuncEdge;
                label = theme.keyFuncText;
                break;
        }

        final float radius = dp(9);
        final float cap = pressed ? 0 : dp(1.5f);
        if (cap > 0) {
            edgeRect.set(rect.left, rect.top + cap, rect.right, rect.bottom);
            edgePaint.setColor(edge);
            canvas.drawRoundRect(edgeRect, radius, radius, edgePaint);
        }
        keyRect.set(rect.left, rect.top, rect.right, rect.bottom - cap);
        keyPaint.setColor(face);
        canvas.drawRoundRect(keyRect, radius, radius, keyPaint);

        final float cx = keyRect.centerX();
        final float cy = keyRect.centerY();
        switch (key.kind) {
            case KIND_SHIFT:
                drawShift(canvas, cx, cy, label);
                break;
            case KIND_BACKSPACE:
                drawBackspace(canvas, cx, cy, label);
                break;
            case KIND_ENTER:
                drawEnter(canvas, cx, cy, label);
                break;
            default:
                drawLabel(canvas, key, cx, cy, label, onAccent);
                break;
        }
    }

    private void drawLabel(Canvas canvas, Key key, float cx, float cy,
                           int color, boolean onAccent) {
        String label = key.label;
        if (shift && key.isLetter()) label = label.toUpperCase();
        final float sp = label.length() > 1 ? 15f : 22f;
        textPaint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setColor(color);
        textPaint.setFakeBoldText(onAccent || (shift && !key.isLetter()));
        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(label, cx, cy - (metrics.ascent + metrics.descent) / 2f,
                textPaint);
    }

    private void drawShift(Canvas canvas, float cx, float cy, int color) {
        final float s = dp(9);
        glyphPaint.setColor(color);
        glyphPaint.setStrokeWidth(dp(2.1f));
        glyphPath.reset();
        glyphPath.moveTo(cx - s, cy + s * 0.1f);
        glyphPath.lineTo(cx, cy - s * 0.85f);
        glyphPath.lineTo(cx + s, cy + s * 0.1f);
        canvas.drawPath(glyphPath, glyphPaint);
        glyphPath.reset();
        glyphPath.moveTo(cx, cy - s * 0.5f);
        glyphPath.lineTo(cx, cy + s);
        canvas.drawPath(glyphPath, glyphPaint);
    }

    private void drawBackspace(Canvas canvas, float cx, float cy, int color) {
        final float s = dp(10);
        glyphPaint.setColor(color);
        glyphPaint.setStrokeWidth(dp(1.9f));
        glyphPath.reset();
        glyphPath.moveTo(cx + s, cy - s * 0.72f);
        glyphPath.lineTo(cx - s * 0.45f, cy - s * 0.72f);
        glyphPath.lineTo(cx - s, cy);
        glyphPath.lineTo(cx - s * 0.45f, cy + s * 0.72f);
        glyphPath.lineTo(cx + s, cy + s * 0.72f);
        glyphPath.close();
        canvas.drawPath(glyphPath, glyphPaint);
        final float x = cx + s * 0.12f;
        final float d = s * 0.26f;
        glyphPath.reset();
        glyphPath.moveTo(x - d, cy - d);
        glyphPath.lineTo(x + d, cy + d);
        glyphPath.moveTo(x + d, cy - d);
        glyphPath.lineTo(x - d, cy + d);
        canvas.drawPath(glyphPath, glyphPaint);
    }

    private void drawEnter(Canvas canvas, float cx, float cy, int color) {
        final float s = dp(9);
        glyphPaint.setColor(color);
        glyphPaint.setStrokeWidth(dp(2.1f));
        glyphPath.reset();
        glyphPath.moveTo(cx + s, cy - s * 0.5f);
        glyphPath.lineTo(cx + s, cy + s * 0.55f);
        glyphPath.lineTo(cx - s * 0.95f, cy + s * 0.55f);
        canvas.drawPath(glyphPath, glyphPaint);
        glyphPath.reset();
        glyphPath.moveTo(cx - s * 0.95f, cy + s * 0.55f);
        glyphPath.lineTo(cx - s * 0.3f, cy - s * 0.1f);
        glyphPath.moveTo(cx - s * 0.95f, cy + s * 0.55f);
        glyphPath.lineTo(cx - s * 0.3f, cy + s * 1.2f);
        canvas.drawPath(glyphPath, glyphPaint);
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
                if (row(pressedRow).get(pressedCol).kind == KIND_BACKSPACE) {
                    repeatKey = row(pressedRow).get(pressedCol);
                    repeatHandler.postDelayed(repeatRunnable, 350);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressedRow < 0) return false;
                int[] hit = hitTest(event.getX(), event.getY());
                int r = hit == null ? -1 : hit[0];
                int c = hit == null ? -1 : hit[1];
                if (r != pressedRow || c != pressedCol) {
                    pressedRow = r;
                    pressedCol = c;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (pressedRow < 0) return false;
                int[] hit = hitTest(event.getX(), event.getY());
                final boolean same = hit != null && hit[0] == pressedRow
                        && hit[1] == pressedCol;
                final Key key = row(pressedRow).get(pressedCol);
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
        if (key.kind == KIND_SYMBOLS || key.kind == KIND_LETTERS) {
            feedback();
            page = key.kind == KIND_SYMBOLS ? PAGE_SYMBOLS : PAGE_LETTERS;
            layoutKeys(getWidth(), getHeight());
            invalidate();
            return;
        }
        feedback();
        if (listener != null) listener.onKey(key);
        if (shift && key.isLetter()) {
            shift = false;
            invalidate();
        }
    }

    private void feedback() {
        final Context context = getContext();
        if (Prefs.vibrate(context)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        if (Prefs.sound(context)) {
            final AudioManager audio =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) audio.playSoundEffect(SoundEffectConstants.CLICK);
        }
    }

    private int[] hitTest(float x, float y) {
        if (bounds == null || boundsPage != page) return null;
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
