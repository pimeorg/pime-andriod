package com.pimeorg.pime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android host for pime-core.
 *
 * Lifecycle: one core per process, one session per focused editor. Keys are
 * offered to the core first; whatever the core does not handle is passed to
 * the editor, so the keyboard stays usable with no plugins installed.
 */
public final class PimeImeService extends InputMethodService
        implements PimeCore.Listener, KeyboardView.Listener {
    private static final String TAG = "PimeIme";

    private static final byte[] EMPTY_GENERATION = {
            (byte) 0xA1, 0x67, 'p', 'l', 'u', 'g', 'i', 'n', 's', (byte) 0x80
    };

    /** Bundled chain the 中/英 key switches to; the active chain stays Chinese. */
    private static final String ENGLISH_CHAIN = ChainStore.ENGLISH_CHAIN;

    /** Union of the capabilities declared by the bundled chain plugins. */
    private static final String[] CHAIN_CAPABILITIES = {
            "input.read", "workspace", "output.emit", "asset.read",
            "store.kv", "time", "context.read", "surrounding.read"
    };

    /** Local action: the settings page edited a chain and wants the kernel rebuilt. */
    public static final String ACTION_REBUILD_CHAIN = "com.pimeorg.pime.action.REBUILD_CHAIN";
    /** Local action: the settings page installed a plugin package. */
    public static final String ACTION_RELOAD_PLUGINS = "com.pimeorg.pime.action.RELOAD_PLUGINS";
    /** Local action: display preferences changed; rebuild the input view. */
    public static final String ACTION_DISPLAY_CHANGED = "com.pimeorg.pime.action.DISPLAY_CHANGED";

    private static final int TOP_BAR_HEIGHT_DP = 44;
    private static final int CANDIDATE_ROW_HEIGHT_DP = 48;
    /** The preedit gets its own line above the candidate strip. */
    private static final int COMPOSITION_ROW_HEIGHT_DP = 30;
    /** Keys are laid out inside the view; this is everything around the four rows. */
    private static final float ROW_CHROME_DP = 9f + 7f + 8f * 3;

    private PimeCore core;
    private boolean coreReady;
    private File pluginDir;
    private KeyboardView keyboard;
    private ImeToolbar toolbar;
    /** Palette the current view was built with; candidates must match it. */
    private KeyboardTheme inputPalette;
    private LinearLayout candidateRow;
    private LinearLayout candidateBar;
    private HorizontalScrollView candidateScroll;
    private TextView compositionView;
    private String composition = "";
    /** Syllable bounds in the composition, in UTF-16 units; empty when unknown. */
    private int[] compositionStarts = new int[0];
    private int[] compositionEnds = new int[0];
    private final List<String> candidates = new ArrayList<>();
    private BroadcastReceiver rebuildReceiver;

    /**
     * Two generations live side by side: the Chinese chain chosen in settings
     * and the bundled English chain. The 中/英 key only activates one or the
     * other, so no plugin is rebuilt while typing.
     */
    private long chineseGeneration;
    private long englishGeneration;
    private long activeGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        final File files = getFilesDir();
        pluginDir = new File(files, "plugins");
        final File dataDir = new File(files, "data");
        final File trustDir = new File(files, "trust");
        final File chainDir = new File(files, "chains");
        pluginDir.mkdirs();
        dataDir.mkdirs();
        trustDir.mkdirs();
        chainDir.mkdirs();

        installBundled(pluginDir, chainDir);

        try {
            core = new PimeCore();
            coreReady = core.start(pluginDir.getAbsolutePath(),
                    dataDir.getAbsolutePath(), trustDir.getAbsolutePath(),
                    PimeCore.FLAG_ALLOW_INSECURE, this);
        } catch (Throwable error) {
            Log.e(TAG, "native library unavailable", error);
            coreReady = false;
        }
        if (!coreReady) {
            Log.w(TAG, "core not running; the keyboard passes every key through");
            return;
        }
        Log.i(TAG, "pime-core " + core.version());

        grantChainCapabilities();
        loadPlugins(pluginDir);

        buildGenerations();
        registerRebuildReceiver();
    }

    /**
     * Builds both generations and activates the one for the current language.
     * Old generations are retired after the switch, never before it, so a
     * failed build cannot leave the keyboard without a working chain.
     */
    private void buildGenerations() {
        if (!coreReady) return;
        final long oldChinese = chineseGeneration;
        final long oldEnglish = englishGeneration;

        chineseGeneration = buildChain(ChainStore.activeChain(this), true);
        englishGeneration = buildChain(
                new File(ChainStore.chainsDir(this), ENGLISH_CHAIN), false);
        activeGeneration = 0;
        activateForLanguage(Prefs.english(this));

        retireGeneration(oldChinese);
        retireGeneration(oldEnglish);
    }

    /**
     * Reads and builds one chain file. A missing Chinese chain falls back to
     * an empty generation so the keyboard still works without plugins; a
     * missing English chain leaves English keys on the host path.
     */
    private long buildChain(File chain, boolean allowEmptyFallback) {
        final byte[] config = readBytes(chain);
        if (config == null) {
            Log.w(TAG, "chain " + chain.getName() + " missing");
            if (!allowEmptyFallback) return 0;
            final long empty = core.buildGeneration(EMPTY_GENERATION);
            if (empty == 0) Log.w(TAG, "empty generation not buildable");
            return empty;
        }
        final long built = core.buildGeneration(config);
        if (built == 0) {
            Log.w(TAG, "chain " + chain.getName() + " not buildable");
        } else {
            Log.i(TAG, "chain built: " + chain.getName());
        }
        return built;
    }

    /** Switches to the generation that belongs to the 中/英 side. */
    private void activateForLanguage(boolean english) {
        if (!coreReady) return;
        final long target = english ? englishGeneration : chineseGeneration;
        if (target == 0 || target == activeGeneration) return;
        if (core.activateGeneration(target) != 0) {
            Log.w(TAG, "generation " + target + " not activatable");
            return;
        }
        activeGeneration = target;
        Log.i(TAG, "active generation: " + (english ? "english" : "chinese"));
    }

    private void retireGeneration(long generation) {
        if (!coreReady || generation == 0 || generation == activeGeneration) return;
        final int status = core.retireGeneration(generation);
        if (status != 0) Log.w(TAG, "retire generation " + generation + ": "
                + core.statusString(status));
    }

    private void registerRebuildReceiver() {
        rebuildReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_DISPLAY_CHANGED.equals(intent.getAction())) {
                    refreshInputView();
                    return;
                }
                // A newly installed package has to be loaded before the chain
                // that references it can be built.
                if (ACTION_RELOAD_PLUGINS.equals(intent.getAction())) {
                    loadPlugins(pluginDir);
                }
                buildGenerations();
            }
        };
        final IntentFilter filter = new IntentFilter(ACTION_REBUILD_CHAIN);
        filter.addAction(ACTION_RELOAD_PLUGINS);
        filter.addAction(ACTION_DISPLAY_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rebuildReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rebuildReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        if (rebuildReceiver != null) {
            unregisterReceiver(rebuildReceiver);
            rebuildReceiver = null;
        }
        if (core != null) core.stop();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        final KeyboardTheme palette = KeyboardTheme.of(this);
        inputPalette = palette;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        /* Any slack the window gives us goes above the bar, so the keys keep
         * their place on screen when the candidate row comes and goes. */
        root.setGravity(Gravity.BOTTOM);
        root.setBackgroundColor(palette.board);

        /* Toolbar: the same evenly spread icon row WeaveText keeps above its
         * keys. It stays in place while composing, so settings and collapse
         * never move as the candidate row comes and goes. */
        toolbar = new ImeToolbar(this, palette);
        toolbar.setListener(this::onTool);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR_HEIGHT_DP)));

        /* Composition sits on its own line above the scrollable candidate
         * strip, so a long preedit never squeezes the candidates off screen. */
        candidateRow = new LinearLayout(this);
        candidateRow.setOrientation(LinearLayout.VERTICAL);
        candidateRow.setBackgroundColor(palette.bar);

        compositionView = new TextView(this);
        compositionView.setTextColor(palette.hint);
        compositionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        compositionView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        compositionView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        compositionView.setSingleLine(true);
        compositionView.setEllipsize(TextUtils.TruncateAt.END);
        compositionView.setPadding(dp(16), 0, dp(16), 0);
        compositionView.setVisibility(View.GONE);
        candidateRow.addView(compositionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(COMPOSITION_ROW_HEIGHT_DP)));

        candidateScroll = new HorizontalScrollView(this);
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setFillViewport(true);
        candidateBar = new LinearLayout(this);
        candidateBar.setOrientation(LinearLayout.HORIZONTAL);
        candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        candidateScroll.addView(candidateBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        candidateScroll.setVisibility(View.GONE);
        candidateRow.addView(candidateScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CANDIDATE_ROW_HEIGHT_DP)));
        candidateRow.setVisibility(View.GONE);
        root.addView(candidateRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final View divider = new View(this);
        divider.setBackgroundColor(palette.divider);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        final int keyboardHeight = keyboardHeight(palette);
        keyboard = new KeyboardView(this);
        keyboard.setKeyboardTheme(palette);
        keyboard.setEnglish(Prefs.english(this));
        keyboard.setListener(this);
        root.addView(keyboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));
        toolbar.setSymbolsActive(keyboard.isSymbolPage());

        root.setMinimumHeight(dp(TOP_BAR_HEIGHT_DP) + keyboardHeight);
        return root;
    }

    /**
     * Four rows of keys. Landscape screens get shorter rows so the keyboard does
     * not swallow the editor; portrait follows the screen height as well.
     */
    private int keyboardHeight(KeyboardTheme palette) {
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float screenHeightDp = metrics.heightPixels / metrics.density;
        final boolean landscape =
                getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
        float rowDp = landscape
                ? screenHeightDp * 0.105f
                : screenHeightDp * 0.072f;
        rowDp = Math.max(landscape ? 34f : 42f, Math.min(rowDp, landscape ? 48f : 60f));
        final int total = Math.round(rowDp * 4 + ROW_CHROME_DP);
        return dp(total * Prefs.keyboardPercent(this) / 100);
    }

    /** Host actions from the toolbar; none of them touch the plugin chain. */
    private void onTool(int tool) {
        switch (tool) {
            case ImeToolbar.TOOL_KEYBOARD:
                if (keyboard != null) keyboard.togglePage();
                break;
            case ImeToolbar.TOOL_PASTE:
                pasteClipboard();
                break;
            case ImeToolbar.TOOL_SETTINGS:
                openSettings();
                break;
            case ImeToolbar.TOOL_HIDE:
                requestHideSelf(0);
                break;
            default:
                break;
        }
    }

    @Override
    public void onPageChanged(boolean symbols) {
        if (toolbar != null) toolbar.setSymbolsActive(symbols);
    }

    /**
     * 中/英 switches the active generation: the Chinese chain chosen in
     * settings or the bundled English chain. A composition in flight is
     * committed as raw letters first (the same thing WeaveText does), so
     * switching does not eat what was already typed.
     */
    @Override
    public void onLanguageChanged(boolean english) {
        Prefs.setEnglish(this, english);
        final String raw = composition;
        if (coreReady) {
            /* `reset` is the chain contract for cancelling a composition: the
             * parse plugin drops its buffer and asks the host to finish the
             * preedit and hide the bar. */
            report(core.reset(), "reset");
            core.pump();
        }
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null && !raw.isEmpty()) {
            ic.setComposingText("", 1);
            ic.finishComposingText();
            ic.commitText(raw, 1);
        }
        activateForLanguage(english);
    }

    /**
     * Inserts the clipboard's text as plain editor text. Pasting is a host
     * action: the chain has no say in it, and a live composition is finished
     * first because commitText replaces the composing region.
     */
    private void pasteClipboard() {
        final android.content.ClipboardManager manager =
                (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
        final android.content.ClipData clip =
                manager == null ? null : manager.getPrimaryClip();
        CharSequence text = null;
        if (clip != null && clip.getItemCount() > 0) {
            text = clip.getItemAt(0).coerceToText(this);
        }
        if (text == null || text.length() == 0) {
            android.widget.Toast.makeText(this, R.string.ime_paste_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!composition.isEmpty()) {
            if (coreReady) {
                report(core.reset(), "reset");
                core.pump();
            }
            composition = "";
            if (compositionView != null) compositionView.setText("");
            /* reset() drops the chain's candidate list too; hiding the row by
             * hand keeps stale candidates from being tappable after a paste. */
            hideCandidates();
            ic.setComposingText("", 1);
            ic.finishComposingText();
        }
        ic.commitText(text, 1);
    }

    /** Rounded pill with a themed ripple; {@code fill} 0 keeps it transparent. */
    private Drawable ripple(Drawable fill, KeyboardTheme palette) {
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple), fill, null);
    }

    private void openSettings() {
        final Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * The palette is resolved once, when the view is built, and the key size
     * follows the screen. So a theme or orientation change rebuilds the view and
     * puts the composition and candidates back.
     */
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        refreshInputView();
    }

    /**
     * Rebuilds the keyboard with the current palette and size while keeping the
     * composition and candidates. Theme and height are host preferences, so a
     * change in settings must not wait for the next time the keyboard opens.
     */
    private void refreshInputView() {
        if (keyboard == null) return;
        setInputView(onCreateInputView());
        renderComposition();
        updateCandidateRow();
        if (!candidates.isEmpty()) onCandidates(candidates.toArray(new String[0]));
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        if (!coreReady) return;

        if (!core.hasSession() && !core.openSession()) {
            Log.w(TAG, "session create failed");
            return;
        }
        reportContext(attribute);
    }

    /** Context, secure flag and focus for the focused editor. */
    private void reportContext(EditorInfo attribute) {
        final String app = attribute.packageName == null
                ? "" : attribute.packageName;
        final boolean password = isPassword(attribute.inputType);
        final int hostCaps = PimeCore.HOST_CANDIDATES | PimeCore.HOST_TOUCH
                | PimeCore.HOST_SECURE;
        report(core.context(app, fieldType(attribute.inputType),
                PimeCore.INPUT_KEYBOARD, "zh_CN", 20, hostCaps), "context");
        report(core.secure(password), "secure");
        report(core.focus(true), "focus");
    }

    @Override
    public void onFinishInput() {
        if (coreReady) {
            report(core.focus(false), "focus");
            report(core.reset(), "reset");
            core.pump();
        }
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        super.onFinishInput();
    }

    @Override
    public void onKey(KeyboardView.Key key) {
        final boolean shifted = keyboard != null && keyboard.isShiftDown();
        final String text = key.kind == KeyboardView.KIND_CHAR
                || key.kind == KeyboardView.KIND_SPACE
                ? String.valueOf(textFor(key, shifted)) : null;
        final boolean english = keyboard != null && keyboard.isEnglish();

        /* English shares the chain path. The bundled English chain is only
         * skipped when it is not installed, so without it English still types
         * through the editor instead of falling back to pinyin. */
        if (coreReady && (!english || englishGeneration != 0)) {
            if (english && endsEnglishComposition(key)) {
                if (commitEnglishCompositionThen(text)) return;
            }
            final int status = core.key(key.code,
                    shifted ? PimeCore.MOD_SHIFT : 0, PimeCore.KEY_DOWN, text);
            core.pump();
            if (status == 1) {
                /* The chain confirms the typed word on space; the space the
                 * user pressed belongs in the editor, not in the candidate. */
                if (english && key.kind == KeyboardView.KIND_SPACE) appendSpace();
                return;
            }
            if (status < 0) {
                Log.w(TAG, "key " + key.code + ": "
                        + core.statusString(-status));
            }
        }
        passThrough(key, text);
    }

    /**
     * Digits 1-9 pick candidates while the English chain is composing, and
     * letters and the apostrophe extend the word. Every other symbol ends the
     * composition and is inserted after it, so punctuation never silently
     * replaces the typed word (and is never swallowed by the chain).
     */
    private boolean endsEnglishComposition(KeyboardView.Key key) {
        if (key.kind != KeyboardView.KIND_CHAR) return false;
        final char c = (char) key.code;
        if (Character.isLetter(c) || c == '\'') return false;
        return c < '1' || c > '9';
    }

    /** Commits the live English word, then the symbol the user pressed. */
    private boolean commitEnglishCompositionThen(String text) {
        if (composition.isEmpty()) return false;
        final String word = composition;
        if (coreReady) {
            report(core.reset(), "reset");
            core.pump();
        }
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return true;
        ic.setComposingText("", 1);
        ic.finishComposingText();
        ic.commitText(word + (text == null ? "" : text), 1);
        return true;
    }

    private void appendSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(" ", 1);
    }

    private void passThrough(KeyboardView.Key key, String text) {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (key.kind) {
            case KeyboardView.KIND_BACKSPACE:
                /* Soft keyboards cannot rely on sendKeyEvent reaching text
                 * fields; deleteSurroundingText is the supported path. */
                ic.deleteSurroundingText(1, 0);
                break;
            case KeyboardView.KIND_ENTER:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER));
                break;
            default:
                if (text != null && !text.isEmpty()) ic.commitText(text, 1);
                break;
        }
    }

    private char textFor(KeyboardView.Key key, boolean shifted) {
        if (key.kind == KeyboardView.KIND_SPACE) return ' ';
        final char value = (char) key.code;
        if (!shifted || !key.isLetter()) return value;
        return Character.toUpperCase(value);
    }

    private void selectCandidate(int index) {
        if (!coreReady || !core.hasSession()) return;
        final int status = core.selectCandidate(index);
        if (status != 0) Log.w(TAG, "candidate " + index + ": " + text(status));
        core.pump();
    }

    /* PimeCore.Listener */

    @Override
    public void onComposition(String text, int cursor, int[] starts, int[] ends) {
        composition = text == null ? "" : text;
        compositionStarts = starts == null ? new int[0] : starts;
        compositionEnds = ends == null ? new int[0] : ends;
        renderComposition();
        updateCandidateRow();
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (text == null || text.isEmpty()) {
            /* Clearing the composition must also drop the text it had placed
             * in the editor; finishComposingText would commit it instead. */
            ic.setComposingText("", 1);
            ic.finishComposingText();
        } else {
            ic.setComposingText(text, Math.max(1, cursor + 1));
        }
    }

    /**
     * The preedit above the candidates may separate syllables with the host
     * preference; the editor's composing text stays exactly as the chain sent
     * it, because separators there would become part of the actual input.
     */
    private void renderComposition() {
        if (compositionView == null) return;
        compositionView.setText(composition.isEmpty() ? "" : separatedComposition());
    }

    private String separatedComposition() {
        final int count = Math.min(compositionStarts.length, compositionEnds.length);
        if (count < 2) return composition;
        final boolean space = Prefs.SEPARATOR_SPACE.equals(Prefs.pinyinSeparator(this));
        final StringBuilder out = new StringBuilder(composition.length() + count);
        for (int i = 0; i < count; ++i) {
            final int start = compositionStarts[i];
            final int end = compositionEnds[i];
            if (start < 0 || end < start || end > composition.length()) {
                return composition;
            }
            if (i > 0) out.append(space ? ' ' : '\'');
            out.append(composition, start, end);
        }
        return out.toString();
    }

    @Override
    public void onCommit(String text) {
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null && text != null && !text.isEmpty()) ic.commitText(text, 1);
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
    }

    @Override
    public void onCandidates(String[] items) {
        if (candidateBar == null) return;
        candidates.clear();
        candidates.addAll(Arrays.asList(items));
        candidateBar.removeAllViews();
        if (items.length == 0) {
            hideCandidates();
            return;
        }
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        final int limit = Prefs.candidates(this);
        final int count = limit > 0 ? Math.min(limit, items.length) : items.length;
        for (int i = 0; i < count; ++i) {
            candidateBar.addView(candidateItem(items[i], i, palette));
        }
        candidateScroll.setVisibility(View.VISIBLE);
        updateCandidateRow();
    }

    /**
     * The stack under the toolbar carries the preedit line above the candidate
     * strip, either alone, or nothing; it only takes height when it has
     * something to say.
     */
    private void updateCandidateRow() {
        if (candidateRow == null) return;
        final boolean composing = !composition.isEmpty();
        if (compositionView != null) {
            compositionView.setVisibility(composing ? View.VISIBLE : View.GONE);
        }
        candidateRow.setVisibility(composing || !candidates.isEmpty()
                ? View.VISIBLE : View.GONE);
    }

    /**
     * The chain's first candidate gets the accent pill; the rest are plain, so
     * the eye lands on the one a tap on space would take.
     */
    private TextView candidateItem(String text, int index, KeyboardTheme palette) {
        final boolean first = index == 0;
        final TextView item = new TextView(this);
        item.setText(text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, first ? 20 : 19);
        item.setTextColor(first ? palette.accent : palette.candidateText);
        item.setTypeface(Typeface.create("sans-serif",
                first ? Typeface.BOLD : Typeface.NORMAL));
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setPadding(dp(16), 0, dp(16), 0);
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(18));
        pill.setColor(first ? palette.accentSoft : 0);
        item.setBackground(ripple(pill, palette));
        item.setOnClickListener(v -> selectCandidate(index));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(4), dp(7), first ? dp(4) : 0, dp(7));
        item.setLayoutParams(params);
        return item;
    }

    @Override
    public void onHideCandidates() {
        hideCandidates();
    }

    private void hideCandidates() {
        candidates.clear();
        if (candidateBar != null) candidateBar.removeAllViews();
        if (candidateScroll != null) candidateScroll.setVisibility(View.GONE);
        updateCandidateRow();
    }

    @Override
    public void onBeep() {
        if (keyboard != null) {
            keyboard.performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    @Override
    public void onNotify(int code) {
        Log.i(TAG, "notify: " + text(code));
    }

    /* host setup */

    /**
     * Grants the capabilities the bundled first-party chain declares. The core
     * fails closed: a declared but ungranted capability makes the plugin see
     * PIME_DENIED on use (the translate plugin's dictionary would not load).
     */
    private void grantChainCapabilities() {
        for (String capability : CHAIN_CAPABILITIES) {
            report(core.permission(capability, true), "grant " + capability);
        }
    }

    private void loadPlugins(File pluginDir) {
        final File[] entries = pluginDir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries);
        for (File entry : entries) {
            if (!entry.isDirectory()) continue;
            if (!new File(entry, "manifest.yaml").isFile()) continue;
            final int status = core.loadPlugin(entry.getAbsolutePath());
            if (status == 0) {
                Log.i(TAG, "plugin loaded: " + entry.getName());
            } else {
                Log.w(TAG, "plugin " + entry.getName() + ": " + text(status));
            }
        }
    }

    /** Copies assets/plugins/<id>/ and assets/chains/ on first run. */
    private void installBundled(File pluginDir, File chainDir) {
        final AssetManager assets = getAssets();
        try {
            for (String name : assets.list("plugins")) {
                final File target = new File(pluginDir, name);
                if (!target.exists()) copyAssetTree("plugins/" + name, target);
            }
            for (String name : assets.list("chains")) {
                if (!name.endsWith(".cbor")) continue;
                final File target = new File(chainDir, name);
                if (!target.exists()) copyAssetTree("chains/" + name, target);
            }
        } catch (IOException error) {
            Log.w(TAG, "bundled assets unavailable", error);
        }
    }

    private void copyAssetTree(String assetPath, File target) throws IOException {
        final String[] children = getAssets().list(assetPath);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("mkdir " + target);
            }
            for (String child : children) {
                copyAssetTree(assetPath + "/" + child, new File(target, child));
            }
            return;
        }
        final File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdir " + parent);
        }
        try (InputStream input = getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(target)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static byte[] readBytes(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new java.io.FileInputStream(file)) {
            final byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                final int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return offset == buffer.length ? buffer
                    : Arrays.copyOf(buffer, offset);
        } catch (IOException error) {
            Log.w(TAG, "read " + file, error);
            return null;
        }
    }

    private static int fieldType(int inputType) {
        if (isPassword(inputType)) return PimeCore.FIELD_PASSWORD;
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
                return PimeCore.FIELD_NUMBER;
            case InputType.TYPE_CLASS_PHONE:
                return PimeCore.FIELD_PHONE;
            case InputType.TYPE_CLASS_TEXT:
                return (inputType & InputType.TYPE_MASK_VARIATION)
                        == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        ? PimeCore.FIELD_EMAIL
                        : (inputType & InputType.TYPE_MASK_VARIATION)
                        == InputType.TYPE_TEXT_VARIATION_URI
                        ? PimeCore.FIELD_URL
                        : PimeCore.FIELD_TEXT;
            default:
                return PimeCore.FIELD_TEXT;
        }
    }

    private static boolean isPassword(int inputType) {
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if ((inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private void report(int status, String what) {
        if (status != 0) Log.w(TAG, what + ": " + text(status));
    }

    private String text(int status) {
        return coreReady ? core.statusString(status) : Integer.toString(status);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
