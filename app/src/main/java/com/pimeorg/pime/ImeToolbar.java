package com.pimeorg.pime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Toolbar above the keys, in the spirit of WeaveText's idle top bar: one evenly
 * spread icon cell per host action, a soft rounded highlight for the active
 * one and a ripple for the press. The toolbar stays put while composing; the
 * composition and candidates get their own row below it, so settings and
 * collapse never move as the candidate strip comes and goes.
 *
 * Only host actions live here. What a key means is the plugin chain's business.
 */
final class ImeToolbar extends LinearLayout {
    static final int TOOL_KEYBOARD = 0;
    static final int TOOL_PASTE = 1;
    static final int TOOL_VOICE = 2;
    static final int TOOL_SETTINGS = 3;
    static final int TOOL_HIDE = 4;

    interface Listener {
        void onTool(int tool);
    }

    private static final int[] ICONS = {
            R.drawable.ic_keyboard,
            R.drawable.ic_clipboard,
            R.drawable.ic_mic,
            R.drawable.ic_settings,
            R.drawable.ic_arrow_down,
    };

    private static final int[] LABELS = {
            R.string.ime_action_symbols,
            R.string.ime_action_paste,
            R.string.ime_action_voice,
            R.string.ime_action_settings,
            R.string.ime_action_hide,
    };

    private final KeyboardTheme palette;
    private final ImageButton[] buttons = new ImageButton[ICONS.length];
    private Listener listener;
    private boolean symbolsActive;
    private boolean voiceActive;

    ImeToolbar(Context context, KeyboardTheme palette) {
        super(context);
        this.palette = palette;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(palette.bar);

        for (int i = 0; i < ICONS.length; i++) {
            final ImageButton button = new ImageButton(context);
            button.setImageResource(ICONS[i]);
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
            button.setBackground(cellBackground(false));
            button.setImageTintList(ColorStateList.valueOf(palette.hint));
            button.setContentDescription(context.getString(LABELS[i]));
            final int tool = i;
            button.setOnClickListener(v -> {
                if (Prefs.vibrate(context)) {
                    button.performHapticFeedback(
                            HapticFeedbackConstants.KEYBOARD_TAP);
                }
                if (listener != null) listener.onTool(tool);
            });
            addView(button, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            buttons[i] = button;
        }
        setSymbolsActive(false);
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
        final ImageButton button = buttons[TOOL_KEYBOARD];
        button.setImageTintList(ColorStateList.valueOf(
                active ? palette.accent : palette.hint));
        button.setContentDescription(getContext().getString(active
                ? R.string.ime_action_letters : R.string.ime_action_symbols));
    }

    /** Highlights the microphone while a voice turn is open. */
    void setVoiceActive(boolean active) {
        voiceActive = active;
        final ImageButton button = buttons[TOOL_VOICE];
        button.setImageTintList(ColorStateList.valueOf(
                active ? palette.accent : palette.hint));
        button.setContentDescription(getContext().getString(active
                ? R.string.ime_action_voice_stop : R.string.ime_action_voice));
        updateCellBackgrounds();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCellBackgrounds();
    }

    private void updateCellBackgrounds() {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] == null) continue;
            final boolean active = (i == TOOL_KEYBOARD && symbolsActive)
                    || (i == TOOL_VOICE && voiceActive);
            buttons[i].setBackground(cellBackground(active));
        }
    }

    /**
     * A rounded cell fill (transparent when idle) under the themed ripple. The
     * active pill is inset to icon size instead of filling the whole cell, so
     * the highlight reads as a selected tool rather than a tab bar.
     */
    private Drawable cellBackground(boolean active) {
        final int cell = getWidth() / ICONS.length;
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
