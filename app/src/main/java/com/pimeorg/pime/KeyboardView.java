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
import android.view.ViewConfiguration;

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
    public static final int KIND_LANGUAGE = 7;

    private static final int PAGE_LETTERS = 0;
    private static final int PAGE_SYMBOLS = 1;

    /** No vertical drag on ⌫ in progress. */
    private static final int SWIPE_NONE = 0;
    /** ⌫ dragged up: clear the input in one go (the long press deletes slowly). */
    private static final int SWIPE_CLEAR = 1;
    /** ⌫ dragged down: undo the last run of deletes, once per gesture. */
    private static final int SWIPE_UNDO = 2;

    public static final class Key {
        public final String label;
        public final int code;
        public final float weight;
        public final int kind;
        /** Small label under the key (the character a swipe up types). */
        public final String hint;
        /** Key emitted by a swipe up; null when this key has no swipe output. */
        public final Key up;

        Key(String label, int code, float weight, int kind) {
            this(label, code, weight, kind, null, null);
        }

        Key(String label, int code, float weight, int kind, String hint) {
            this(label, code, weight, kind, hint, null);
        }

        Key(String label, int code, float weight, int kind, String hint, Key up) {
            this.label = label;
            this.code = code;
            this.weight = weight;
            this.kind = kind;
            this.hint = hint;
            this.up = up;
        }

        public boolean isLetter() {
            return kind == KIND_CHAR && code >= 'a' && code <= 'z';
        }
    }

    public interface Listener {
        void onKey(Key key);

        /**
         * The visible key page changed. The host mirrors it on the toolbar, so
         * a tap on the toolbar and the in-keyboard page keys stay in sync.
         */
        default void onPageChanged(boolean symbols) {
        }

        /**
         * The 中/英 key was tapped. The service switches the active generation:
         * the Chinese plan or the bundled English chain.
         */
        default void onLanguageChanged(boolean english) {
        }

        /**
         * The space key was held long enough to talk, which is the only way
         * into ASR. The host opens the microphone and returns true; false
         * leaves the press a plain space (the host has already told the user
         * why voice did not start).
         */
        default boolean onVoiceHold() {
            return false;
        }

        /**
         * The space key was released after {@link #onVoiceHold} returned true.
         * {@code cancelled} asks the host to drop the utterance instead of
         * committing it.
         */
        default void onVoiceRelease(boolean cancelled) {
        }

        /**
         * The ⌫ key was dragged up: clear the preedit and the text before the
         * cursor at once, which the long press (slow repeated deletes) cannot
         * do in one gesture.
         */
        default void onDeleteAll() {
        }

        /**
         * The ⌫ key was dragged down far enough to undo: the host puts back the
         * text the last run of deletes removed.
         */
        default void onUndo() {
        }
    }

    @SuppressWarnings("unchecked")
    private final List<List<Key>>[] pages = new List[2];
    private int page = PAGE_LETTERS;
    private RectF[][] bounds;
    private int boundsPage = -1;
    private Listener listener;
    private KeyboardTheme theme;
    private boolean shift;
    private boolean english;
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
    /** Which vertical drag the current ⌫ press took; see SWIPE_*. */
    private int deleteSwipe = SWIPE_NONE;
    /** Read once per press: the preference must not change under a finger. */
    private boolean deleteSwipeOn;
    /** True from a successful voice hold until the finger leaves the space key. */
    private boolean voiceLive;
    private float downX;
    private float downY;
    private int downRow = -1;
    private int downCol = -1;

    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatKey != null && listener != null) {
                listener.onKey(repeatKey);
                repeatHandler.postDelayed(this, 70);
            }
        }
    };

    /**
     * Push-to-talk: holding the space key opens the microphone, releasing it
     * ends the turn. The timer is the system long-press timeout so the gesture
     * matches what the platform already teaches (and honours accessibility
     * settings), and a shorter tap still types a space.
     */
    private final Runnable voiceHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (downRow < 0 || listener == null) return;
            final Key key = pages[page].get(downRow).get(downCol);
            if (key.kind != KIND_SPACE) return;
            if (!listener.onVoiceHold()) return;
            voiceLive = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
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

    /** True while the 中/英 key shows English: the English chain is active. */
    public boolean isEnglish() {
        return english;
    }

    public void setEnglish(boolean english) {
        if (this.english == english) return;
        this.english = english;
        invalidate();
    }

    /** True while the symbols page is showing; the toolbar highlights this. */
    public boolean isSymbolPage() {
        return page == PAGE_SYMBOLS;
    }

    /** Switches between the letter and symbol pages (toolbar tool). */
    public void togglePage() {
        setPage(page == PAGE_LETTERS ? PAGE_SYMBOLS : PAGE_LETTERS);
    }

    private void setPage(int next) {
        if (page == next) return;
        page = next;
        layoutKeys(getWidth(), getHeight());
        invalidate();
        if (listener != null) listener.onPageChanged(page == PAGE_SYMBOLS);
    }

    private void buildPages() {
        pages[PAGE_LETTERS] = letterPage();
        pages[PAGE_SYMBOLS] = symbolPage();
    }

    private List<List<Key>> letterPage() {
        final List<List<Key>> rows = new ArrayList<>(4);
        /* Swipe-up hints follow WeaveText's QWERTY map: digits on the top row,
         * symbols on the two rows below. */
        rows.add(chars("qwertyuiop", "1234567890"));
        rows.add(chars("asdfghjkl", "~!@#%“”*?"));

        final List<Key> third = new ArrayList<>();
        third.add(new Key("⇧", 0, 1.5f, KIND_SHIFT));
        third.addAll(chars("zxcvbnm", "（）-_：；、"));
        third.add(new Key("⌫", 8, 1.5f, KIND_BACKSPACE));
        rows.add(third);

        final List<Key> bottom = new ArrayList<>();
        bottom.add(new Key("符", 0, 1.2f, KIND_SYMBOLS));
        bottom.add(new Key(",", ',', 0.9f, KIND_CHAR));
        bottom.add(new Key("空格", ' ', 4.0f, KIND_SPACE));
        bottom.add(new Key(".", '.', 0.9f, KIND_CHAR));
        bottom.add(new Key("中/英", 0, 1.4f, KIND_LANGUAGE));
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
        bottom.add(new Key("空格", ' ', 4.0f, KIND_SPACE));
        bottom.add(new Key("。", '.', 0.9f, KIND_CHAR));
        bottom.add(new Key("中/英", 0, 1.4f, KIND_LANGUAGE));
        bottom.add(new Key("回车", 13, 1.8f, KIND_ENTER));
        rows.add(bottom);
        return rows;
    }

    private static List<Key> chars(String letters) {
        return chars(letters, null);
    }

    private static List<Key> chars(String letters, String hints) {
        final List<Key> keys = new ArrayList<>(letters.length());
        for (int i = 0; i < letters.length(); ++i) {
            final char c = letters.charAt(i);
            final String hint = hints != null && i < hints.length()
                    ? String.valueOf(hints.charAt(i)) : null;
            final Key up = hint == null ? null
                    : new Key(hint, hint.codePointAt(0), 1f, KIND_CHAR);
            keys.add(new Key(String.valueOf(c), c, 1f, KIND_CHAR, hint, up));
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
            case KIND_LANGUAGE:
                /* The two characters are drawn by drawLanguage: the active
                 * side needs its own colour and weight, so the frame is the
                 * plain function-key one. */
                onAccent = false;
                face = pressed ? theme.keyFuncPressed : theme.keyFunc;
                edge = theme.keyFuncEdge;
                label = theme.keyFuncText;
                break;
            case KIND_SPACE:
                /* A live voice turn paints the space key like the accent keys:
                 * it is the recording indicator, since the mic left the
                 * toolbar. */
                onAccent = voiceLive;
                face = voiceLive
                        ? (pressed ? theme.accentPressed : theme.accent)
                        : (pressed ? theme.keyFuncPressed : theme.keyFunc);
                edge = voiceLive ? 0 : theme.keyFuncEdge;
                label = voiceLive ? theme.onAccent : theme.keyFuncText;
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
            case KIND_LANGUAGE:
                drawLanguage(canvas, cx, cy, keyRect);
                break;
            case KIND_SYMBOLS:
            case KIND_LETTERS:
                drawPageGlyph(canvas, cx, cy, label, key.kind);
                break;
            case KIND_SPACE:
                drawSpace(canvas, keyRect, label);
                break;
            default:
                drawLabel(canvas, key, keyRect, label, onAccent);
                break;
        }
    }

    /**
     * The space bar is a blank bar with a small muted mic: the glyph is the only
     * mark on it, so the key says "press and hold to talk" without a single word
     * (and a blank wide bar is the usual space bar any thumb recognises). While a
     * turn is open the same mic grows and the key turns accent.
     */
    private void drawSpace(Canvas canvas, RectF rect, int color) {
        if (voiceLive) {
            drawMic(canvas, rect.centerX(), rect.centerY(), color,
                    Math.min(rect.height() * 0.19f, dp(13)));
            return;
        }
        final float scaled = getResources().getDisplayMetrics().scaledDensity;
        final float hint = Math.min(11f * scaled, rect.height() * 0.22f);
        drawMic(canvas, rect.centerX(), rect.centerY(), theme.hint,
                Math.min(hint * 0.55f, dp(6)));
    }

    /**
     * The page-switch key: a hashtag opens the symbols page, an A goes back to
     * the letters. Both are drawn as strokes like the other key glyphs.
     */
    private void drawPageGlyph(Canvas canvas, float cx, float cy, int color,
                               int kind) {
        glyphPaint.setColor(color);
        glyphPaint.setStrokeWidth(dp(2.1f));
        glyphPath.reset();
        if (kind == KIND_SYMBOLS) {
            final float s = dp(8);
            glyphPath.moveTo(cx - s * 0.30f, cy - s);
            glyphPath.lineTo(cx - s * 0.58f, cy + s);
            glyphPath.moveTo(cx + s * 0.44f, cy - s);
            glyphPath.lineTo(cx + s * 0.16f, cy + s);
            glyphPath.moveTo(cx - s, cy - s * 0.32f);
            glyphPath.lineTo(cx + s, cy - s * 0.32f);
            glyphPath.moveTo(cx - s, cy + s * 0.34f);
            glyphPath.lineTo(cx + s, cy + s * 0.34f);
        } else {
            final float s = dp(8.5f);
            glyphPath.moveTo(cx - s * 0.80f, cy + s * 0.85f);
            glyphPath.lineTo(cx, cy - s * 0.92f);
            glyphPath.lineTo(cx + s * 0.80f, cy + s * 0.85f);
            glyphPath.moveTo(cx - s * 0.40f, cy + s * 0.22f);
            glyphPath.lineTo(cx + s * 0.40f, cy + s * 0.22f);
        }
        canvas.drawPath(glyphPath, glyphPaint);
    }

    /**
     * 中/英 as a two-cell switch: 中 on the left, 英 on the right, a hairline
     * between them. The active side is bold and in the key text colour, the
     * other one recedes into the hint colour, so the state reads at a glance
     * without a second highlight. The whole label is scaled down as one piece
     * when the key is narrower than the two characters need.
     */
    private void drawLanguage(Canvas canvas, float cx, float cy, RectF rect) {
        final float scaled = getResources().getDisplayMetrics().scaledDensity;
        final float size = Math.min(14f * scaled, rect.height() * 0.34f);
        final float divider = Math.max(dp(1), size * 0.03f);
        final float gap = size * 0.26f;

        textPaint.setTextSize(size);
        textPaint.setFakeBoldText(true);
        final float active = Math.max(textPaint.measureText("中"),
                textPaint.measureText("英"));
        textPaint.setFakeBoldText(false);
        final float idle = Math.max(textPaint.measureText("中"),
                textPaint.measureText("英"));
        final float width = active + idle + gap * 2 + divider;
        final float fit = Math.min(1f, (rect.width() - dp(8)) / width);

        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        final float baseline = -(metrics.ascent + metrics.descent) / 2f;
        final int dividerColor = (theme.hint & 0x00FFFFFF) | 0x70000000;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(fit, fit);
        final float left = -width / 2f;

        drawLangChar(canvas, "中", left + active / 2f, baseline, size,
                !english ? theme.keyFuncText : theme.hint, !english);

        /* keyPaint is a fill paint and the key face re-sets its colour every
         * frame, so the hairline can borrow it instead of touching the stroke
         * paint the glyphs share. */
        keyPaint.setColor(dividerColor);
        canvas.drawRect(left + active + gap, -size * 0.52f,
                left + active + gap + divider, size * 0.52f, keyPaint);

        drawLangChar(canvas, "英", left + active + gap * 2 + divider + idle / 2f,
                baseline, size,
                english ? theme.keyFuncText : theme.hint, english);
        canvas.restore();
    }

    private void drawLangChar(Canvas canvas, String character, float x,
                              float baseline, float size, int color,
                              boolean bold) {
        textPaint.setTextSize(size);
        textPaint.setFakeBoldText(bold);
        textPaint.setColor(color);
        canvas.drawText(character, x, baseline, textPaint);
    }

    /**
     * A microphone: capsule head, cradle arc, stem and base, stroke-only like
     * the other key glyphs so it reads at the same weight as the arrows and the
     * backspace key. {@code s} is roughly the half height.
     */
    private void drawMic(Canvas canvas, float cx, float cy, int color, float s) {
        glyphPaint.setColor(color);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(Math.max(dp(1f), s * 0.22f));
        final float head = s * 0.34f;
        final RectF capsule = new RectF(cx - head, cy - s,
                cx + head, cy + s * 0.14f);
        canvas.drawRoundRect(capsule, head, head, glyphPaint);
        final RectF cradle = new RectF(cx - s * 0.64f, cy - s * 0.52f,
                cx + s * 0.64f, cy + s * 0.52f);
        canvas.drawArc(cradle, 0f, 180f, false, glyphPaint);
        canvas.drawLine(cx, cy + s * 0.52f, cx, cy + s * 0.82f, glyphPaint);
        canvas.drawLine(cx - s * 0.46f, cy + s * 0.82f,
                cx + s * 0.46f, cy + s * 0.82f, glyphPaint);
    }

    private void drawLabel(Canvas canvas, Key key, RectF rect, int color,
                           boolean onAccent) {
        String label = key.label;
        if (shift && key.isLetter()) label = label.toUpperCase();
        final String hint = key.hint;
        final float scaled = getResources().getDisplayMetrics().scaledDensity;
        float size = (label.length() > 1 ? 15f : 22f) * scaled;
        float centerY = rect.centerY();
        if (hint != null && key.kind == KIND_CHAR) {
            /* Leave a band at the bottom for the digit and keep the letter
             * above it; both sizes follow the key height so landscape rows do
             * not collide. */
            final float hintSize = Math.min(11f * scaled, rect.height() * 0.22f);
            final float band = hintSize * 1.1f + dp(4);
            size = Math.min(22f * scaled, rect.height() * 0.42f);
            centerY = rect.top + (rect.height() - band) / 2f;
        }
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setFakeBoldText(onAccent || (shift && !key.isLetter()));
        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(label, rect.centerX(),
                centerY - (metrics.ascent + metrics.descent) / 2f, textPaint);
        if (hint != null) drawHint(canvas, hint, rect);
    }

    /** The muted digit under a letter; a swipe up on that key types it. */
    private void drawHint(Canvas canvas, String hint, RectF rect) {
        final float scaled = getResources().getDisplayMetrics().scaledDensity;
        final float size = Math.min(11f * scaled, rect.height() * 0.22f);
        textPaint.setTextSize(size);
        textPaint.setColor(theme.hint);
        textPaint.setFakeBoldText(false);
        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(hint, rect.centerX(),
                rect.bottom - dp(4) - metrics.descent, textPaint);
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
                downRow = hit[0];
                downCol = hit[1];
                downX = event.getX();
                downY = event.getY();
                deleteSwipe = SWIPE_NONE;
                deleteSwipeOn = Prefs.swipeDelete(getContext());
                invalidate();
                final Key down = row(pressedRow).get(pressedCol);
                if (down.kind == KIND_BACKSPACE) {
                    repeatKey = down;
                    repeatHandler.postDelayed(repeatRunnable, 350);
                } else if (down.kind == KIND_SPACE) {
                    repeatHandler.postDelayed(voiceHoldRunnable,
                            ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                /* A live voice turn owns the gesture: sliding off the key must
                 * not turn it into a stray key press. */
                if (voiceLive) return true;
                if (deleteSwipeOn && downRow >= 0
                        && pages[page].get(downRow).get(downCol).kind
                        == KIND_BACKSPACE
                        && trackDeleteSwipe(event.getY())) {
                    return true;
                }
                /* Keep the starting key lit while the finger is above it: a
                 * swipe up leaves the key bounds (the top row has no row above),
                 * and hit testing would drop the press before ACTION_UP. */
                if (downRow >= 0 && isSwipeUp(pages[page].get(downRow).get(downCol),
                        downRow, downCol, event.getX(), event.getY())) {
                    if (pressedRow != downRow || pressedCol != downCol) {
                        pressedRow = downRow;
                        pressedCol = downCol;
                        invalidate();
                    }
                    return true;
                }
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
                if (voiceLive) {
                    voiceLive = false;
                    clearPressed();
                    if (listener != null) listener.onVoiceRelease(false);
                    return true;
                }
                /* A delete swipe already did its work (the repeat, or the
                 * undo); releasing must not also type the key. */
                if (deleteSwipe != SWIPE_NONE) {
                    clearPressed();
                    return true;
                }
                final Key start = downRow >= 0
                        ? pages[page].get(downRow).get(downCol) : null;
                if (isSwipeUp(start, downRow, downCol,
                        event.getX(), event.getY())) {
                    clearPressed();
                    fire(start.up);
                    return true;
                }
                if (pressedRow < 0) {
                    clearPressed();
                    return false;
                }
                int[] hit = hitTest(event.getX(), event.getY());
                final boolean same = hit != null && hit[0] == pressedRow
                        && hit[1] == pressedCol;
                final Key key = row(pressedRow).get(pressedCol);
                clearPressed();
                if (same) fire(key);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (voiceLive) {
                    voiceLive = false;
                    clearPressed();
                    if (listener != null) listener.onVoiceRelease(true);
                    return true;
                }
                clearPressed();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void clearPressed() {
        pressedRow = -1;
        pressedCol = -1;
        downRow = -1;
        downCol = -1;
        repeatKey = null;
        deleteSwipe = SWIPE_NONE;
        repeatHandler.removeCallbacks(repeatRunnable);
        repeatHandler.removeCallbacks(voiceHoldRunnable);
        invalidate();
    }

    /**
     * A vertical drag on ⌫. Up clears the input in one go — a long press only
     * deletes slowly, one key at a time, so the two gestures stay distinct;
     * down undoes the last run of deletes once. Whichever direction is taken
     * first owns the rest of the gesture, and the key stays lit throughout so
     * the finger can see it is still on the delete key.
     */
    private boolean trackDeleteSwipe(float y) {
        if (deleteSwipe != SWIPE_NONE) {
            pressedRow = downRow;
            pressedCol = downCol;
            return true;
        }
        final float dy = downY - y;
        final float threshold = Math.max(dp(22), keyHeight() * 0.45f);
        if (dy >= threshold) {
            deleteSwipe = SWIPE_CLEAR;
            pressedRow = downRow;
            pressedCol = downCol;
            /* The long press may already be repeating under this finger; the
             * clear replaces it rather than adding to it. */
            repeatKey = null;
            repeatHandler.removeCallbacks(repeatRunnable);
            swipeFeedback();
            invalidate();
            if (listener != null) listener.onDeleteAll();
            return true;
        }
        if (-dy >= threshold) {
            deleteSwipe = SWIPE_UNDO;
            pressedRow = downRow;
            pressedCol = downCol;
            repeatKey = null;
            repeatHandler.removeCallbacks(repeatRunnable);
            swipeFeedback();
            invalidate();
            if (listener != null) listener.onUndo();
            return true;
        }
        return false;
    }

    private float keyHeight() {
        if (bounds == null || boundsPage != page || downRow < 0
                || downRow >= bounds.length || downCol < 0
                || downCol >= bounds[downRow].length) {
            return dp(48);
        }
        return bounds[downRow][downCol].height();
    }

    /** One tap of feedback when a delete swipe starts; the repeat stays quiet. */
    private void swipeFeedback() {
        if (Prefs.vibrate(getContext())) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /**
     * A swipe up must be long and clearly vertical, and must start on a key with
     * a digit hint, so fast horizontal typing never turns a letter into a digit.
     */
    private boolean isSwipeUp(Key key, int row, int col, float x, float y) {
        if (key == null || key.up == null || key.hint == null || bounds == null
                || row < 0 || row >= bounds.length
                || col < 0 || col >= bounds[row].length) {
            return false;
        }
        final float up = downY - y;
        final float side = Math.abs(x - downX);
        final float min = Math.max(dp(28), bounds[row][col].height() * 0.55f);
        return up >= min && up > 1.5f * side;
    }

    private void fire(Key key) {
        if (key.kind == KIND_SHIFT) {
            shift = !shift;
            invalidate();
            return;
        }
        if (key.kind == KIND_SYMBOLS || key.kind == KIND_LETTERS) {
            feedback();
            setPage(key.kind == KIND_SYMBOLS ? PAGE_SYMBOLS : PAGE_LETTERS);
            return;
        }
        if (key.kind == KIND_LANGUAGE) {
            feedback();
            english = !english;
            invalidate();
            if (listener != null) listener.onLanguageChanged(english);
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
        repeatHandler.removeCallbacks(voiceHoldRunnable);
        /* The view can go away mid-hold (theme rebuild, editor switched). The
         * turn cannot outlive the finger that opened it, so it is dropped. */
        if (voiceLive) {
            voiceLive = false;
            if (listener != null) listener.onVoiceRelease(true);
        }
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
