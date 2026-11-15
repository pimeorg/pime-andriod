package com.pimeorg.pime;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Host-level display preferences: theme, keyboard size and key feedback, and how
 * many candidates the host shows. Input semantics stay in the plugin chain, so
 * nothing here is business logic.
 */
public final class Prefs {
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    /** Apostrophe or space between the syllables shown in the preedit line. */
    public static final String SEPARATOR_APOSTROPHE = "apostrophe";
    public static final String SEPARATOR_SPACE = "space";

    /** Compact / standard / roomy keyboard height, in percent of the base size. */
    public static final int[] KEYBOARD_PERCENTS = {88, 100, 115};

    /** 0 means "show every candidate the chain returns". */
    public static final int[] CANDIDATE_COUNTS = {0, 3, 5, 7, 9};

    private static final String FILE = "pime";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String theme(Context context) {
        return prefs(context).getString("theme", THEME_SYSTEM);
    }

    public static void setTheme(Context context, String value) {
        prefs(context).edit().putString("theme", value).apply();
    }

    public static int nightMode(Context context) {
        switch (theme(context)) {
            case THEME_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case THEME_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    public static int candidates(Context context) {
        return prefs(context).getInt("candidates", 0);
    }

    public static void setCandidates(Context context, int value) {
        prefs(context).edit().putInt("candidates", value).apply();
    }

    /** Display-only separator; the editor's composing text stays as typed. */
    public static String pinyinSeparator(Context context) {
        return prefs(context).getString("pinyin_separator", SEPARATOR_APOSTROPHE);
    }

    public static void setPinyinSeparator(Context context, String value) {
        prefs(context).edit().putString("pinyin_separator", value).apply();
    }

    public static boolean vibrate(Context context) {
        return prefs(context).getBoolean("vibrate", true);
    }

    public static void setVibrate(Context context, boolean value) {
        prefs(context).edit().putBoolean("vibrate", value).apply();
    }

    public static boolean sound(Context context) {
        return prefs(context).getBoolean("sound", false);
    }

    public static void setSound(Context context, boolean value) {
        prefs(context).edit().putBoolean("sound", value).apply();
    }

    public static int keyboardPercent(Context context) {
        return prefs(context).getInt("keyboard_percent", 100);
    }

    public static void setKeyboardPercent(Context context, int value) {
        prefs(context).edit().putInt("keyboard_percent", value).apply();
    }

    /**
     * Which side the 中/英 key shows. English uses the bundled English chain
     * instead of the Chinese plan; persisted so the choice survives the input
     * service restarting.
     */
    public static boolean english(Context context) {
        return prefs(context).getBoolean("english", false);
    }

    public static void setEnglish(Context context, boolean value) {
        prefs(context).edit().putBoolean("english", value).apply();
    }

    /** Name of the active chain file (e.g. "default.cbor"); "default.cbor" if unset. */
    public static String activeChain(Context context) {
        return prefs(context).getString("active_chain", "default.cbor");
    }

    public static void setActiveChain(Context context, String name) {
        prefs(context).edit().putString("active_chain", name).apply();
    }

    /**
     * APK update time that the bundled plugin packages were last copied for.
     * Zero means "never synced", so the first service start after install (or
     * after upgrading from a build without this bookkeeping) refreshes them.
     */
    public static long bundledStamp(Context context) {
        return prefs(context).getLong("bundled_stamp", 0L);
    }

    public static void setBundledStamp(Context context, long value) {
        prefs(context).edit().putLong("bundled_stamp", value).apply();
    }
}
