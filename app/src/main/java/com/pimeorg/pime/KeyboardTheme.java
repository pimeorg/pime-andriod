package com.pimeorg.pime;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Keyboard colour tokens.
 *
 * The input view is drawn by an {@link android.inputmethodservice.InputMethodService},
 * which is not an AppCompat context, so the palette is built here in code instead of
 * resolving theme attributes: the host display preference decides light or dark.
 *
 * Only host view colours live here. Which keys exist and what they mean is the
 * plugin chain's business, not this file's.
 */
public final class KeyboardTheme {
    /** Gap colour showing between keys. */
    public final int board;
    /** Letter and digit key face. */
    public final int key;
    public final int keyPressed;
    /** 1dp cap under each key face; gives the keys their height. */
    public final int keyEdge;
    /** Shift, backspace, page switch — the keys that are not characters. */
    public final int keyFunc;
    public final int keyFuncPressed;
    public final int keyFuncEdge;
    public final int accent;
    public final int accentPressed;
    public final int onAccent;
    /** Soft accent fill (first candidate pill). */
    public final int accentSoft;
    public final int keyText;
    public final int keyFuncText;
    /** Top bar and candidate row background. */
    public final int bar;
    public final int onBar;
    public final int hint;
    public final int divider;
    public final int candidateText;
    public final int ripple;
    /** Candidate table surface (unfolded panel). */
    public final int panel;
    /** Syllable column behind the table. */
    public final int rail;
    public final int railText;
    /** Line between table rows, and the same line inside the rail. */
    public final int rowLine;
    public final int railLine;
    /** Segmented category track and the active tab inside it. */
    public final int tabTrack;
    public final int tabActive;
    public final int tabText;
    public final int tabActiveText;
    /** Flat fill behind the table's delete key. */
    public final int keyCap;

    private KeyboardTheme(boolean dark) {
        if (dark) {
            board = 0xFF101114;
            key = 0xFF2C2F35;
            keyPressed = 0xFF43474F;
            keyEdge = 0xFF212429;
            keyFunc = 0xFF1D1F24;
            keyFuncPressed = 0xFF33363D;
            keyFuncEdge = 0xFF15171B;
            accent = 0xFF8AAEFF;
            accentPressed = 0xFF6A93EE;
            onAccent = 0xFF0A2566;
            accentSoft = 0xFF1F3A7A;
            keyText = 0xFFE9EBEF;
            keyFuncText = 0xFFC6CBD4;
            bar = 0xFF17181B;
            onBar = 0xFFE9EBEF;
            hint = 0xFFA2A8B2;
            divider = 0xFF2A2C31;
            candidateText = 0xFFE9EBEF;
            ripple = 0x33FFFFFF;
            panel = 0xFF202124;
            rail = 0xFF33353A;
            railText = 0xFFC9CCD2;
            rowLine = 0xFF35373B;
            railLine = 0xFF4A4D52;
            tabTrack = 0xFF292B2F;
            tabActive = 0xFF43464C;
            tabText = 0xFFD5D7DB;
            tabActiveText = 0xFFF3F4F6;
            keyCap = 0xFF3D4045;
        } else {
            board = 0xFFE9ECF1;
            key = 0xFFFFFFFF;
            keyPressed = 0xFFD5DBE6;
            keyEdge = 0xFFDCE0E8;
            keyFunc = 0xFFD9DFE9;
            keyFuncPressed = 0xFFC2CAD6;
            keyFuncEdge = 0xFFC6CDD9;
            accent = 0xFF2E6CF6;
            accentPressed = 0xFF2559D0;
            onAccent = 0xFFFFFFFF;
            accentSoft = 0xFFDCE6FD;
            keyText = 0xFF1B1E23;
            keyFuncText = 0xFF3B4350;
            bar = 0xFFF5F6F8;
            onBar = 0xFF1B1E23;
            hint = 0xFF5E6570;
            divider = 0xFFDDE1E8;
            candidateText = 0xFF1B1E23;
            ripple = 0x1F2E6CF6;
            panel = 0xFFF3F4F7;
            rail = 0xFFE7E9EE;
            railText = 0xFF5E6570;
            rowLine = 0xFFE3E6EC;
            railLine = 0xFFD3D8E0;
            tabTrack = 0xFFE8EAF0;
            tabActive = 0xFFFFFFFF;
            tabText = 0xFF5E6570;
            tabActiveText = 0xFF1B1E23;
            keyCap = 0xFFE1E5EC;
        }
    }

    /** Palette matching the host display preference, falling back to the system. */
    public static KeyboardTheme of(Context context) {
        final String theme = Prefs.theme(context);
        if (Prefs.THEME_DARK.equals(theme)) return new KeyboardTheme(true);
        if (Prefs.THEME_LIGHT.equals(theme)) return new KeyboardTheme(false);
        final int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return new KeyboardTheme(mode == Configuration.UI_MODE_NIGHT_YES);
    }
}
