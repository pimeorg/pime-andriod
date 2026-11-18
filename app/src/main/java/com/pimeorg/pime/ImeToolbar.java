package com.pimeorg.pime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Toolbar above the keys, in the spirit of WeaveText's idle top bar: one evenly
 * spread icon cell per host action, a soft rounded highlight for the active
 * one and a ripple for the press. It shares a single band with the candidate
 * row: while a preedit or candidates are showing, the row owns the band and the
 * toolbar steps aside, so the keys never sit under two bars at once. The
 * toolbar comes back as soon as the row goes away.
 *
 * <p>Which tools show, and in what order, is the user's: the settings page
 * (or a long press on an icon; the voice key's long press is the utterance)
 * opens the customization list. An empty list hides the bar entirely.
 *
 * Only host actions live here. What a key means is the plugin chain's business.
 */
final class ImeToolbar extends LinearLayout {
    /* Stable ids; they are what Prefs stores, so renaming one resets it. */
    static final String TOOL_SYMBOLS = "symbols";
    static final String TOOL_LANGUAGE = "language";
    static final String TOOL_VOICE = "voice";
    static final String TOOL_PASTE = "paste";
    static final String TOOL_SETTINGS = "settings";
    static final String TOOL_HIDE = "hide";

    /** Every tool the customization page can offer, in catalog order. */
    static final String[] ALL_TOOLS = {
            TOOL_SYMBOLS,
            TOOL_LANGUAGE,
            TOOL_VOICE,
            TOOL_PASTE,
            TOOL_SETTINGS,
            TOOL_HIDE,
    };

    /** What a fresh install shows, and what "restore defaults" goes back to. */
    static final String[] DEFAULT_TOOLS = {
            TOOL_SYMBOLS,
            TOOL_PASTE,
            TOOL_SETTINGS,
            TOOL_HIDE,
    };

    interface Listener {
        void onTool(String tool);

        /**
         * A tool icon was long-pressed. Mainstream keyboards treat this as
         * "customize the toolbar", so the host opens that settings page.
         */
        void onCustomize();

        /**
         * A press-and-hold tool (voice) went down. True means the action
         * started and {@link #onToolRelease} will follow.
         */
        default boolean onToolHold(String tool) {
            return false;
        }

        default void onToolRelease(String tool, boolean cancelled) {
        }
    }

    private final KeyboardTheme palette;
    private final List<String> tools;
    private final ImageButton[] buttons;
    private Listener listener;
    private boolean symbolsActive;
    private boolean englishActive;
    /** A voice button is down and the microphone turn was opened. */
    private boolean voiceDown;

    ImeToolbar(Context context, KeyboardTheme palette, List<String> tools) {
        super(context);
        this.palette = palette;
        this.tools = new ArrayList<>(tools);
        this.buttons = new ImageButton[this.tools.size()];
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(palette.bar);

        for (int i = 0; i < this.tools.size(); i++) {
            final String tool = this.tools.get(i);
            final ImageButton button = new ImageButton(context);
            button.setImageResource(iconRes(tool));
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
            button.setBackground(cellBackground(false));
            button.setImageTintList(ColorStateList.valueOf(palette.hint));
            button.setContentDescription(context.getString(labelRes(tool)));
            if (TOOL_VOICE.equals(tool)) {
                button.setOnTouchListener((view, event) -> handleVoiceTouch(view, event, tool));
            } else {
                button.setOnClickListener(v -> {
                    if (Prefs.vibrate(context)) {
                        button.performHapticFeedback(
                                HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    if (listener != null) listener.onTool(tool);
                });
            }
            /* Long press is the shortcut into customization. It does not sit on
             * the voice button: there a long press is the utterance itself. */
            if (!TOOL_VOICE.equals(tool)) {
                button.setOnLongClickListener(v -> {
                    if (listener != null) listener.onCustomize();
                    return true;
                });
            }
            addView(button, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            buttons[i] = button;
        }
        if (this.tools.isEmpty()) setVisibility(GONE);
        updateCellBackgrounds();
    }

    /** False when the user removed every tool; the service hides the bar then. */
    boolean hasTools() {
        return !tools.isEmpty();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * The keyboard tool means "switch the key page", so the highlight says
     * which page is showing and the description says where a tap goes.
     */
    void setSymbolsActive(boolean active) {
        symbolsActive = active;
        updateCellBackgrounds();
    }

    /** The language tool is highlighted while the English chain is active. */
    void setLanguageActive(boolean english) {
        englishActive = english;
        updateCellBackgrounds();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCellBackgrounds();
    }

    @Override
    protected void onDetachedFromWindow() {
        /* The bar is rebuilt whenever the tools or the theme change. A turn
         * cannot outlive the finger that opened it, so drop it here, the same
         * way the key surface does. */
        if (voiceDown) {
            voiceDown = false;
            if (listener != null) listener.onToolRelease(TOOL_VOICE, true);
        }
        super.onDetachedFromWindow();
    }

    /**
     * Voice is press-and-hold, like the space key: down opens the microphone,
     * up commits and cancel drops the utterance. A down that cannot start
     * voice says why (or opens the permission screen) and stays a no-op.
     */
    private boolean handleVoiceTouch(android.view.View view, MotionEvent event, String tool) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                voiceDown = listener != null && listener.onToolHold(tool);
                if (voiceDown && Prefs.vibrate(getContext())) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                view.setPressed(true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                final boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (voiceDown && listener != null) listener.onToolRelease(tool, cancelled);
                voiceDown = false;
                view.setPressed(false);
                return true;
            default:
                return false;
        }
    }

    private void updateCellBackgrounds() {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] == null) continue;
            final String tool = tools.get(i);
            final boolean active = isActive(tool);
            buttons[i].setBackground(cellBackground(active));
            buttons[i].setImageTintList(ColorStateList.valueOf(
                    active ? palette.accent : palette.hint));
            if (TOOL_SYMBOLS.equals(tool)) {
                buttons[i].setContentDescription(getContext().getString(
                        symbolsActive ? R.string.ime_action_letters
                                : R.string.ime_action_symbols));
            } else if (TOOL_LANGUAGE.equals(tool)) {
                buttons[i].setContentDescription(getContext().getString(
                        englishActive ? R.string.ime_action_chinese
                                : R.string.ime_action_english));
            }
        }
    }

    private boolean isActive(String tool) {
        return TOOL_SYMBOLS.equals(tool) && symbolsActive
                || TOOL_LANGUAGE.equals(tool) && englishActive;
    }

    /**
     * A rounded cell fill (transparent when idle) under the themed ripple. The
     * active pill is inset to icon size instead of filling the whole cell, so
     * the highlight reads as a selected tool rather than a tab bar.
     */
    private Drawable cellBackground(boolean active) {
        final int count = Math.max(1, buttons.length);
        final int cell = getWidth() / count;
        final int side = cell > 0
                ? Math.max(dp(6), (cell - dp(56)) / 2)
                : dp(18);
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(active ? palette.accentSoft : 0);
        fill.setCornerRadius(dp(12));
        final InsetDrawable content = new InsetDrawable(fill,
                side, dp(4), side, dp(4));
        /* No ripple mask: with a masked RippleDrawable the icon itself came
         * out mirrored on the API 35 x86_64 emulator when the input view was
         * built in landscape. An unmasked cell-wide ripple looks the same and
         * leaves the icon alone. */
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple),
                content, null);
    }

    // ------------------------------------------------------------ catalog

    static boolean isTool(String id) {
        for (String tool : ALL_TOOLS) {
            if (tool.equals(id)) return true;
        }
        return false;
    }

    static int iconRes(String id) {
        switch (id) {
            case TOOL_SYMBOLS:
                return R.drawable.ic_keyboard;
            case TOOL_LANGUAGE:
                return R.drawable.ic_translate;
            case TOOL_VOICE:
                return R.drawable.ic_mic;
            case TOOL_PASTE:
                return R.drawable.ic_clipboard;
            case TOOL_SETTINGS:
                return R.drawable.ic_settings;
            default:
                return R.drawable.ic_arrow_down;
        }
    }

    static int labelRes(String id) {
        switch (id) {
            case TOOL_SYMBOLS:
                return R.string.tool_symbols;
            case TOOL_LANGUAGE:
                return R.string.tool_language;
            case TOOL_VOICE:
                return R.string.tool_voice;
            case TOOL_PASTE:
                return R.string.tool_paste;
            case TOOL_SETTINGS:
                return R.string.tool_settings;
            default:
                return R.string.tool_hide;
        }
    }

    static int descriptionRes(String id) {
        switch (id) {
            case TOOL_SYMBOLS:
                return R.string.tool_symbols_desc;
            case TOOL_LANGUAGE:
                return R.string.tool_language_desc;
            case TOOL_VOICE:
                return R.string.tool_voice_desc;
            case TOOL_PASTE:
                return R.string.tool_paste_desc;
            case TOOL_SETTINGS:
                return R.string.tool_settings_desc;
            default:
                return R.string.tool_hide_desc;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
