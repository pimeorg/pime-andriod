package com.pimeorg.pime;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Milliseconds the ink stays after a stroke before it fades away; 0 keeps
     * it on the pad until the user clears or commits it.
     */
    public static final int[] HANDWRITING_FADE_DELAYS = {0, 1500, 2500, 4000};

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

    public static int handwritingFadeDelay(Context context) {
        return prefs(context).getInt("handwriting_fade_delay", 2500);
    }

    public static void setHandwritingFadeDelay(Context context, int value) {
        prefs(context).edit().putInt("handwriting_fade_delay", value).apply();
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

    /**
     * Swipe up on the delete key to clear the input in one go, swipe down to
     * undo the last run of deletes. Read by the key surface on each press, so
     * no rebuild.
     */
    public static boolean swipeDelete(Context context) {
        return prefs(context).getBoolean("swipe_delete", true);
    }

    public static void setSwipeDelete(Context context, boolean value) {
        prefs(context).edit().putBoolean("swipe_delete", value).apply();
    }

    /**
     * Ordered toolbar tools, left to right. An empty list is a valid choice:
     * the bar hides and the keys keep the room. Unknown ids are dropped so a
     * renamed tool cannot leave an invisible dead cell behind.
     */
    public static List<String> toolbarTools(Context context) {
        final String raw = prefs(context).getString("toolbar_tools", null);
        if (raw == null) {
            return new ArrayList<>(Arrays.asList(ImeToolbar.DEFAULT_TOOLS));
        }
        final List<String> tools = new ArrayList<>();
        for (String stored : raw.split(",")) {
            /* The keyboard tool used to be "symbols"; it now opens the layout
             * chooser. Mapping on read keeps existing orders and positions. */
            final String id = "symbols".equals(stored)
                    ? ImeToolbar.TOOL_LAYOUT : stored;
            if (ImeToolbar.isTool(id) && !tools.contains(id)) tools.add(id);
        }
        return tools;
    }

    public static void setToolbarTools(Context context, List<String> tools) {
        prefs(context).edit()
                .putString("toolbar_tools", TextUtils.join(",", tools))
                .apply();
    }

    /** Back to the install default: the built-in four, in their built-in order. */
    public static void resetToolbarTools(Context context) {
        prefs(context).edit().remove("toolbar_tools").apply();
    }

    public static int keyboardPercent(Context context) {
        return prefs(context).getInt("keyboard_percent", 100);
    }

    public static void setKeyboardPercent(Context context, int value) {
        prefs(context).edit().putInt("keyboard_percent", value).apply();
    }

    /**
     * Candidate overrides recorded by long-pressing a word: the pinned word
     * (shown first) and the words hidden from that code's list. They are keyed
     * by the host's candidate scope, which is the composition (or the
     * prediction list), and deliberately live outside the plugin chain: they
     * are per-device user choices about the visible list, not language data.
     */
    public static String candidatePin(Context context, String scope) {
        return prefs(context).getString("candidate_pin/" + scope, null);
    }

    public static void setCandidatePin(Context context, String scope, String word) {
        prefs(context).edit().putString("candidate_pin/" + scope, word).apply();
    }

    public static void clearCandidatePin(Context context, String scope) {
        prefs(context).edit().remove("candidate_pin/" + scope).apply();
    }

    public static Set<String> candidateHidden(Context context, String scope) {
        final Set<String> stored = prefs(context).getStringSet(
                "candidate_hidden/" + scope, null);
        return stored == null ? Collections.emptySet() : new HashSet<>(stored);
    }

    public static void hideCandidate(Context context, String scope, String word) {
        final Set<String> hidden = new HashSet<>(candidateHidden(context, scope));
        if (!hidden.add(word)) return;
        prefs(context).edit()
                .putStringSet("candidate_hidden/" + scope, hidden)
                .apply();
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
     * True while the key area shows the handwriting layout instead of the
     * keys. Persisted so the chosen layout survives a field switch or a view
     * rebuild; picking any chain clears it.
     */
    public static boolean handwritingLayout(Context context) {
        return prefs(context).getBoolean("handwriting_layout", false);
    }

    public static void setHandwritingLayout(Context context, boolean value) {
        prefs(context).edit().putBoolean("handwriting_layout", value).apply();
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

    /**
     * SHA-256 of the bundled chain this app last wrote as {@code name}. A chain
     * whose file no longer matches it was edited by the user and must survive
     * upgrades; {@code null} means the build predates this bookkeeping.
     */
    public static String chainBaseline(Context context, String name) {
        return prefs(context).getString("chain_baseline/" + name, null);
    }

    public static void setChainBaseline(Context context, String name, String hash) {
        prefs(context).edit().putString("chain_baseline/" + name, hash).apply();
    }
}
