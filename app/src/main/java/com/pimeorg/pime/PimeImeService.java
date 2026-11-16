package com.pimeorg.pime;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            "store.kv", "time", "context.read", "surrounding.read",
            "audio.capture", "audio.pcm", "log"
    };

    /** Native speech plugin bundled with the shell (see assets/plugins). */
    private static final String SPEECH_PLUGIN_ID = "pime-speech-sherpa";
    private static final String SPEECH_PLUGIN_LIB = "libpime_speech_sherpa.so";
    private static final String VOICE_MODEL = "asr-stream-small";
    private static final int REQUEST_RECORD_AUDIO = 81;

    /** Local action: the settings page edited a chain and wants the kernel rebuilt. */
    public static final String ACTION_REBUILD_CHAIN = "com.pimeorg.pime.action.REBUILD_CHAIN";
    /** Local action: the settings page installed a plugin package. */
    public static final String ACTION_RELOAD_PLUGINS = "com.pimeorg.pime.action.RELOAD_PLUGINS";
    /** Local action: display preferences changed; rebuild the input view. */
    public static final String ACTION_DISPLAY_CHANGED = "com.pimeorg.pime.action.DISPLAY_CHANGED";
    /** Local action: the user just granted the microphone permission. */
    public static final String ACTION_VOICE_READY = "com.pimeorg.pime.action.VOICE_READY";

    private static final int TOP_BAR_HEIGHT_DP = 44;
    private static final int CANDIDATE_ROW_HEIGHT_DP = 48;
    /** Cells per row in the unfolded table. */
    private static final int CANDIDATE_GRID_COLUMNS = 5;
    /**
     * Most candidates one chain call may hand back. This is the chain's page
     * size, not a display preference: "候选数量 = 全部" means the whole list,
     * so the host asks for everything the plugins can produce and only slices
     * it locally for the 3/5/7/9 choices.
     */
    private static final int CANDIDATE_PAGE_SIZE = 640;
    /**
     * Loop bound while collecting pages / restoring the strip. The real stop
     * condition is "the chain stopped moving or repeated a page"; this only
     * keeps a misbehaving chain from spinning forever. It is not a candidate
     * limit.
     */
    private static final int GRID_PAGE_GUARD = 1000;
    private static final int GRID_ROW_HEIGHT_DP = 46;
    /** The table's bottom line: the delete key where the keys used to be. */
    private static final int GRID_BAR_HEIGHT_DP = 52;
    /**
     * pime key codes the keymap plugin turns into page moves: `home` rewinds to
     * the first page, which lets the table be collected in one sweep.
     */
    private static final int KEY_PAGE_UP = 33;
    private static final int KEY_PAGE_DOWN = 34;
    private static final int KEY_HOME = 36;
    /** Characters of editor context reported to the predictor. */
    private static final int SURROUNDING_BEFORE = 64;
    private static final int SURROUNDING_AFTER = 32;
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
    private CandidateStrip candidateScroll;
    /** Strip plus its chevron cell; hides and shows as one row. */
    private LinearLayout candidateLine;
    private ImageButton expandButton;
    private TextView compositionView;
    private String composition = "";
    /**
     * The table that takes over the key area while candidates are unfolded.
     *
     * <p>It replaces the keys instead of hanging above them: the input view has
     * exactly the same height either way, so the editor never reflows and the
     * whole key area goes to candidates — the reason WeChat's chevron is worth
     * tapping at all.
     */
    private LinearLayout candidatePanel;
    /** Rows of the table; the ScrollView above it does the scrolling. */
    private LinearLayout gridBoard;
    private ScrollView gridScroll;
    private boolean candidatesExpanded;

    /* ---- unfolded panel: tabs, syllable rail, handwriting ---- */

    private static final int TAB_FREQUENCY = 0;
    private static final int TAB_SINGLE = 1;
    private static final int TAB_HANDWRITING = 2;
    private static final int RAIL_SYLLABLES = 0;
    private static final int RAIL_STROKES = 1;
    private static final int TAB_ROW_HEIGHT_DP = 38;
    private static final int RAIL_WIDTH_DP = 60;
    private static final int RAIL_ITEM_HEIGHT_DP = 42;
    private static final int HANDWRITING_RESULT_HEIGHT_DP = 46;

    private int panelTab = TAB_FREQUENCY;
    private int railMode = RAIL_SYLLABLES;
    /** Syllable the table is filtered to; null means every candidate. */
    private String railSyllable;
    /** First-stroke bucket the table is filtered to; -1 means no filter. */
    private int railStroke = -1;
    private final TextView[] tabViews = new TextView[3];
    private LinearLayout gridArea;
    private LinearLayout railList;
    private ScrollView railScroll;
    private LinearLayout handwritingArea;
    private HandwritingView handwritingView;
    private LinearLayout handwritingBoard;
    private HorizontalScrollView handwritingScroll;
    private TextView handwritingUndo;
    private TextView handwritingClear;
    private HandwritingRecognizer recognizer;
    private final List<HandwritingRecognizer.Match> handwritingMatches = new ArrayList<>();
    /**
     * Pages the table shows, {@code gridPages.get(0)} being the chain's first
     * page. Collected with home + pagedown, so a candidate's offset in this
     * list is also its <em>global</em> index — the number the chain's
     * {@code candidate} event takes, which means picking row 23 needs no page
     * bookkeeping at all.
     */
    private final List<List<String>> gridPages = new ArrayList<>();
    /** One page as the chain emits it; the collector reads it, nothing draws it. */
    private final List<String> collectedPage = new ArrayList<>();
    /** True while the host walks the chain: emitted pages are collected, not drawn. */
    private boolean collecting;
    /** Page the strip showed when the table opened; restored when it folds. */
    private int gridReturnPage;
    /** Chain page the strip is on; mirrors the format plugin's own cursor. */
    private int chainPage;
    /** Syllable bounds in the composition, in UTF-16 units; empty when unknown. */
    private int[] compositionStarts = new int[0];
    private int[] compositionEnds = new int[0];
    private final List<String> candidates = new ArrayList<>();
    /**
     * First candidate the folded strip draws when the preference caps it at
     * 3/5/7/9. The chain hands over its whole list in one page, so paging the
     * strip is a local window move; "全部" keeps this at zero.
     */
    private int stripOffset;
    /** Strip item views are reused: a whole-dictionary list is not a view list. */
    private final List<TextView> stripViews = new ArrayList<>();
    /**
     * Set by {@link #onCommit} while a chain key runs. A digit that picks a
     * candidate commits without the parsers' commit phase, so the host has to
     * tell them the composition ended once the key returns.
     */
    private boolean commitSeen;
    private BroadcastReceiver rebuildReceiver;

    /**
     * Two generations live side by side: the Chinese chain chosen in settings
     * and the bundled English chain. The 中/英 key only activates one or the
     * other, so no plugin is rebuilt while typing.
     */
    private long chineseGeneration;
    private long englishGeneration;
    private long activeGeneration;

    /** Generation with only the native speech plugin, used while listening. */
    private long speechGeneration;
    private boolean voiceActive;
    /** Password fields never hand their text to the chain. */
    private boolean secureField;
    /** Passed through the table's delete key while it takes over the keys. */
    private final KeyboardView.Key backspaceKey =
            new KeyboardView.Key("\u232b", 8, 1.5f, KeyboardView.KIND_BACKSPACE);
    private boolean voiceReplay;
    /** True while the capture thread should keep reading the microphone. */
    private volatile boolean voiceCapturing;
    private AudioRecord voiceRecord;
    private Thread voiceThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        final long oldSpeech = speechGeneration;
        speechGeneration = buildSpeechGeneration();
        activeGeneration = 0;
        activateForLanguage(Prefs.english(this));

        retireGeneration(oldChinese);
        retireGeneration(oldEnglish);
        retireGeneration(oldSpeech);
    }

    /** Directory the voice model is downloaded into. */
    private File voiceModelDir() {
        return new File(new File(getFilesDir(), "models"), VOICE_MODEL);
    }

    /**
     * Builds the one-plugin generation used while the microphone is open. The
     * plugin does not live in the typing chains: every key stays on the normal
     * path, and the host only activates this generation around a voice turn.
     */
    private long buildSpeechGeneration() {
        if (!coreReady) return 0;
        final File modelDir = voiceModelDir();
        final Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("runtime_dir", getApplicationInfo().nativeLibraryDir);
        settings.put("model_dir", modelDir.getAbsolutePath());
        if (BuildConfig.DEBUG) {
            /* Debug-only replay hook: drop a 16 kHz mono wav into the app's
             * files directory to exercise recognition without a microphone. */
            final File testWav = new File(getFilesDir(), "voice-test.wav");
            if (testWav.isFile()) {
                settings.put("wav_file", testWav.getAbsolutePath());
                Log.i(TAG, "voice: replaying " + testWav);
            }
        }

        final Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("id", SPEECH_PLUGIN_ID);
        plugin.put("role", "recognize");
        plugin.put("settings", settings);

        final List<Object> plugins = new ArrayList<>();
        plugins.add(plugin);
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("plugins", plugins);

        final long built = core.buildGeneration(Cbor.write(root));
        if (built == 0) Log.w(TAG, "speech generation not buildable");
        else Log.i(TAG, "speech generation built");
        return built;
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
                if (ACTION_VOICE_READY.equals(intent.getAction())) {
                    /* The permission arrived after the hold ended: say how to
                     * start now, rather than opening the microphone without
                     * a finger on the key. */
                    toast(R.string.voice_permission_granted);
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
        filter.addAction(ACTION_VOICE_READY);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rebuildReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rebuildReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        cancelVoice();
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

        candidateScroll = new CandidateStrip(this, next -> {
            if (!candidates.isEmpty()) pageCandidates(next);
        });
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setFillViewport(true);
        candidateBar = new LinearLayout(this);
        candidateBar.setOrientation(LinearLayout.HORIZONTAL);
        candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        /* WRAP_CONTENT, not MATCH_PARENT: the mixed Chinese/English view can
         * be wider than the screen, and a viewport-wide bar has no scroll
         * range to drag. */
        candidateScroll.addView(candidateBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        candidateScroll.setVisibility(View.GONE);
        /* The strip keeps the chevron cell at the end: expanding shows the
         * whole chain page at once instead of scrolling one row sideways. */
        candidateLine = new LinearLayout(this);
        candidateLine.setOrientation(LinearLayout.HORIZONTAL);
        /* END: with the strip gone the chevron alone must still sit on the
         * right, where the folded row puts it. */
        candidateLine.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        candidateLine.addView(candidateScroll, new LinearLayout.LayoutParams(
                0, dp(CANDIDATE_ROW_HEIGHT_DP), 1f));
        expandButton = new ImageButton(this);
        expandButton.setImageResource(R.drawable.ic_expand_more);
        expandButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        expandButton.setBackground(candidateChevronBackground(false, palette));
        expandButton.setImageTintList(ColorStateList.valueOf(palette.hint));
        expandButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        expandButton.setContentDescription(
                getString(R.string.ime_action_expand));
        expandButton.setOnClickListener(v -> toggleCandidatePanel());
        expandButton.setVisibility(View.GONE);
        candidateLine.addView(expandButton, new LinearLayout.LayoutParams(
                dp(CANDIDATE_ROW_HEIGHT_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        candidateRow.addView(candidateLine, new LinearLayout.LayoutParams(
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

        /* The table sits where the keys are, with their exact height: unfolding
         * swaps one for the other, so the editor above keeps its size and the
         * candidates get the whole key area. Nothing hangs over the editor and
         * nothing has to be re-laid out. */
        candidatePanel = buildCandidatePanel(palette);
        root.addView(candidatePanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));

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

    /**
     * The unfolded candidate table: scrolling rows of candidates, with the
     * delete key kept on the bottom line where the keys used to be (so a wrong
     * letter can be fixed without folding the table away first).
     */
    private LinearLayout buildCandidatePanel(KeyboardTheme palette) {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(palette.board);
        panel.setVisibility(View.GONE);

        /* Category line, the way WeChat separates \u8bcd\u9891 / \u5355\u5b57 / \u624b\u5199\u627e\u5b57:
         * equal thirds, the active one a soft accent pill. */
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(6), 0, dp(6), 0);
        tabViews[TAB_FREQUENCY] = panelTabView(R.string.ime_tab_frequency, TAB_FREQUENCY);
        tabViews[TAB_SINGLE] = panelTabView(R.string.ime_tab_single, TAB_SINGLE);
        tabViews[TAB_HANDWRITING] = panelTabView(R.string.ime_tab_handwriting,
                TAB_HANDWRITING);
        for (TextView tab : tabViews) {
            tabs.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        panel.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_ROW_HEIGHT_DP)));

        final FrameLayout content = new FrameLayout(this);
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* Table and handwriting share the content rectangle. */
        gridArea = new LinearLayout(this);
        gridArea.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(gridArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* Left rail: syllables of the candidates' first characters, or the
         * first-stroke buckets once \u9996\u7b14\u753b is tapped. */
        railScroll = new ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setBackgroundColor(palette.bar);
        railList = new LinearLayout(this);
        railList.setOrientation(LinearLayout.VERTICAL);
        railScroll.addView(railList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridArea.addView(railScroll, new LinearLayout.LayoutParams(
                dp(RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        final View railDivider = new View(this);
        railDivider.setBackgroundColor(palette.divider);
        gridArea.addView(railDivider, new LinearLayout.LayoutParams(
                Math.max(1, dp(1) / 2), ViewGroup.LayoutParams.MATCH_PARENT));

        gridScroll = new ScrollView(this);
        gridScroll.setFillViewport(true);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipToPadding(false);
        gridBoard = new LinearLayout(this);
        gridBoard.setOrientation(LinearLayout.VERTICAL);
        gridScroll.addView(gridBoard, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridArea.addView(gridScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        handwritingArea = new LinearLayout(this);
        handwritingArea.setOrientation(LinearLayout.VERTICAL);
        handwritingArea.setVisibility(View.GONE);
        handwritingView = new HandwritingView(this);
        handwritingView.setPalette(palette);
        handwritingView.setListener(this::onInkChanged);
        handwritingArea.addView(handwritingView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        /* Results are one horizontal row, not a grid: every dp saved here
         * goes to the writing surface above, which is the scarce resource. */
        handwritingScroll = new HorizontalScrollView(this);
        handwritingScroll.setHorizontalScrollBarEnabled(false);
        handwritingBoard = new LinearLayout(this);
        handwritingBoard.setOrientation(LinearLayout.HORIZONTAL);
        handwritingScroll.addView(handwritingBoard, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        handwritingArea.addView(handwritingScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(HANDWRITING_RESULT_HEIGHT_DP)));
        content.addView(handwritingArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        handwritingUndo = barAction(R.string.ime_handwriting_undo, palette,
                v -> handwritingView.undo());
        handwritingClear = barAction(R.string.ime_handwriting_clear, palette,
                v -> handwritingView.clear());
        handwritingUndo.setVisibility(View.GONE);
        handwritingClear.setVisibility(View.GONE);
        bar.addView(handwritingUndo, new LinearLayout.LayoutParams(
                dp(56), dp(GRID_ROW_HEIGHT_DP)));
        bar.addView(handwritingClear, new LinearLayout.LayoutParams(
                dp(56), dp(GRID_ROW_HEIGHT_DP)));
        /* One column of the grid: the delete key lands where the fifth
         * candidate of the next row would be, as in WeChat's table. */
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1,
                CANDIDATE_GRID_COLUMNS - 1));
        final TextView delete = new TextView(this);
        delete.setText("\u232b");
        delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        delete.setTextColor(palette.keyFuncText);
        delete.setGravity(Gravity.CENTER);
        delete.setContentDescription(getString(R.string.ime_action_delete));
        final GradientDrawable deleteFill = new GradientDrawable();
        deleteFill.setCornerRadius(dp(14));
        deleteFill.setColor(palette.keyFunc);
        delete.setBackground(ripple(deleteFill, palette));
        delete.setOnClickListener(v -> gridDelete());
        bar.addView(delete, new LinearLayout.LayoutParams(0, dp(GRID_ROW_HEIGHT_DP), 1f));
        panel.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_BAR_HEIGHT_DP)));
        renderTabs(palette);
        return panel;
    }

    private TextView panelTabView(int label, int tab) {
        final TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setOnClickListener(v -> selectPanelTab(tab));
        return view;
    }

    private TextView barAction(int label, KeyboardTheme palette,
                               View.OnClickListener click) {
        final TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(palette.keyFuncText);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(12));
        fill.setColor(palette.keyFunc);
        view.setBackground(ripple(fill, palette));
        view.setOnClickListener(click);
        return view;
    }

    /** Paints the category line; the active tab is the only accent pill. */
    private void renderTabs(KeyboardTheme palette) {
        final int[] labels = {
                R.string.ime_tab_frequency,
                R.string.ime_tab_single,
                R.string.ime_tab_handwriting,
        };
        for (int i = 0; i < tabViews.length; ++i) {
            final TextView tab = tabViews[i];
            if (tab == null) continue;
            final boolean active = panelTab == i;
            tab.setText(labels[i]);
            tab.setTextColor(active ? palette.accent : palette.hint);
            tab.setTypeface(Typeface.create("sans-serif",
                    active ? Typeface.BOLD : Typeface.NORMAL));
            final GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(13));
            pill.setColor(active ? palette.accentSoft : 0);
            tab.setBackground(ripple(pill, palette));
            tab.setSelected(active);
        }
    }

    private void selectPanelTab(int tab) {
        if (panelTab == tab) return;
        panelTab = tab;
        railMode = RAIL_SYLLABLES;
        railSyllable = null;
        railStroke = -1;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        renderTabs(palette);
        updatePanelContent();
        if (tab == TAB_HANDWRITING) {
            renderHandwritingGrid();
        } else if (gridPages.isEmpty()) {
            /* Opened on the handwriting tab last time: the chain's pages were
             * never collected, so switching back gathers them now. */
            buildGrid();
        } else {
            renderRail(palette);
            renderGrid();
        }
    }

    /** Table or handwriting surface: only the tab decides. */
    private void updatePanelContent() {
        final boolean handwriting = panelTab == TAB_HANDWRITING;
        if (gridArea != null) {
            gridArea.setVisibility(handwriting ? View.GONE : View.VISIBLE);
        }
        if (handwritingArea != null) {
            handwritingArea.setVisibility(handwriting ? View.VISIBLE : View.GONE);
        }
        if (handwritingUndo != null) {
            handwritingUndo.setVisibility(handwriting ? View.VISIBLE : View.GONE);
        }
        if (handwritingClear != null) {
            handwritingClear.setVisibility(handwriting ? View.VISIBLE : View.GONE);
        }
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

    /* ------------------------------------------------------------- voice */

    /**
     * Space held: the only way into ASR. Recognition runs in the bundled
     * native plugin; the host only owns the permission, the AudioRecord and
     * the generation switch. Returns false when voice cannot start, so the
     * release still types a plain space and the user is never left without
     * the key they pressed.
     */
    @Override
    public boolean onVoiceHold() {
        if (!coreReady) return false;
        if (voiceActive) {
            /* A turn that outlived its release (touch cancelled without an
             * event, view rebuilt): the next hold closes it. */
            stopVoice();
            return true;
        }
        if (secureField) {
            Toast.makeText(this, R.string.voice_unavailable_secure,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.voice_permission_needed,
                    Toast.LENGTH_SHORT).show();
            final Intent intent = new Intent(this, VoicePermissionActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return false;
        }
        if (speechGeneration == 0) {
            Toast.makeText(this, R.string.voice_unavailable_plugin,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!VoiceModels.isReady(this)) {
            /* The download takes about a minute; the finger is long gone by
             * then, so the turn is not opened behind the user's back. */
            Toast.makeText(this, R.string.voice_download_needed,
                    Toast.LENGTH_SHORT).show();
            VoiceModels.ensure(this, () -> toast(R.string.voice_ready),
                    () -> toast(R.string.voice_download_failed));
            return false;
        }
        return startVoice();
    }

    /** Space released: finalize and commit the utterance, or drop it. */
    @Override
    public void onVoiceRelease(boolean cancelled) {
        if (!voiceActive) return;
        if (cancelled) cancelVoice();
        else stopVoice();
    }

    private boolean startVoice() {
        if (voiceActive || !coreReady) return false;
        /* The microphone only opens while the keyboard and the user's finger
         * are both there. */
        if (!isInputViewShown()) return false;
        if (activeGeneration != speechGeneration) {
            if (core.activateGeneration(speechGeneration) != 0) {
                Toast.makeText(this, R.string.voice_unavailable_plugin,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            activeGeneration = speechGeneration;
        }
        report(core.reset(), "reset");
        final int status = core.voice(PimeCore.VOICE_START);
        core.pump();
        if (status != 0) {
            Log.w(TAG, "voice start: " + text(status));
            toast(R.string.voice_unavailable_plugin);
            restoreLanguageGeneration();
            return false;
        }
        /* The debug replay hook feeds the plugin from a wav, so leaving the
         * microphone closed keeps the capture ring out of the picture. */
        voiceReplay = BuildConfig.DEBUG
                && new File(getFilesDir(), "voice-test.wav").isFile();
        if (!voiceReplay && !startCapture()) {
            core.voice(PimeCore.VOICE_CANCEL);
            core.pump();
            restoreLanguageGeneration();
            toast(R.string.voice_unavailable_mic);
            return false;
        }
        voiceActive = true;
        return true;
    }

    private void stopVoice() {
        stopCapture();
        if (coreReady) {
            report(core.voice(PimeCore.VOICE_STOP), "voice stop");
            core.pump();
        }
        voiceActive = false;
        restoreLanguageGeneration();
    }

    private void cancelVoice() {
        if (!voiceActive) return;
        stopCapture();
        if (coreReady) {
            report(core.voice(PimeCore.VOICE_CANCEL), "voice cancel");
            core.pump();
        }
        voiceActive = false;
        restoreLanguageGeneration();
    }

    /** Returns to the generation for the current 中/英 side. */
    private void restoreLanguageGeneration() {
        if (!coreReady) return;
        final long target = Prefs.english(this) ? englishGeneration : chineseGeneration;
        if (target != 0 && core.activateGeneration(target) == 0) {
            activeGeneration = target;
        }
    }

    /** Opens the 16 kHz mono microphone and feeds PCM into the core ring. */
    private boolean startCapture() {
        final int minimum = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return false;
        final int buffer = Math.max(minimum * 2, 32000);
        try {
            voiceRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buffer);
        } catch (RuntimeException error) {
            Log.w(TAG, "AudioRecord unavailable", error);
            voiceRecord = null;
            return false;
        }
        if (voiceRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            voiceRecord.release();
            voiceRecord = null;
            return false;
        }
        voiceCapturing = true;
        final AudioRecord record = voiceRecord;
        voiceRecord.startRecording();
        voiceThread = new Thread(() -> {
            final byte[] pcm = new byte[3200]; /* 100 ms */
            final long started = SystemClock.elapsedRealtime();
            int reads = 0;
            int failures = 0;
            while (voiceCapturing) {
                final int read = record.read(pcm, 0, pcm.length);
                if (read > 0) {
                    if (reads++ == 0) {
                        Log.i(TAG, "voice: capture running");
                    }
                    if (coreReady) {
                        final int status = core.audioIn(pcm, read,
                                SystemClock.elapsedRealtime() - started);
                        if (status != 0 && failures++ < 3) {
                            Log.w(TAG, "voice: audio in " + text(status));
                        }
                    }
                } else if (read < 0 && failures++ < 3) {
                    Log.w(TAG, "voice: read " + read);
                }
            }
        }, "pime-voice");
        voiceThread.start();
        return true;
    }

    private void stopCapture() {
        voiceCapturing = false;
        if (voiceRecord != null) {
            try {
                voiceRecord.stop();
            } catch (IllegalStateException ignored) {
                /* Already stopped. */
            }
            voiceRecord.release();
            voiceRecord = null;
        }
        if (voiceThread != null) {
            try {
                voiceThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            voiceThread = null;
        }
    }

    private void toast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_SHORT).show();
    }

    /** Plugin threads call this through the sink; pump must run on the owner. */
    @Override
    public void onWake() {
        mainHandler.post(() -> {
            if (coreReady) core.pump();
        });
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
        refreshSurrounding();
    }

    /** Rounded pill with a themed ripple; {@code fill} 0 keeps it transparent. */
    private Drawable ripple(Drawable fill, KeyboardTheme palette) {
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple), fill, null);
    }

    /** Transparent cell with a rounded ripple: the chevron reads as part of the row. */
    private Drawable candidateChevronBackground(boolean active, KeyboardTheme palette) {
        final GradientDrawable cell = new GradientDrawable();
        cell.setCornerRadius(dp(14));
        cell.setColor(active ? palette.accentSoft : 0);
        return ripple(cell, palette);
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
        /* A rebuild starts with fresh views; when the table was open, put its
         * tabs, rail and rows back instead of leaving keyboard and panel
         * disagreeing about the rectangle. Ink does not survive a rebuild, so
         * old handwriting results are dropped with it. */
        handwritingMatches.clear();
        if (candidatesExpanded) {
            keyboard.setVisibility(View.GONE);
            renderPanelGrid();
        }
        updateCandidateRow();
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
        cancelVoice();
        composition = "";
        secureField = isPassword(attribute.inputType);
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        /* A new editor starts folded on 词频, with a clean ink surface. */
        candidatesExpanded = false;
        panelTab = TAB_FREQUENCY;
        railMode = RAIL_SYLLABLES;
        railSyllable = null;
        railStroke = -1;
        handwritingMatches.clear();
        if (handwritingView != null) handwritingView.clear();
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
                | PimeCore.HOST_SECURE | PimeCore.HOST_SURROUNDING
                | PimeCore.HOST_AUDIO_CAPTURE;
        report(core.context(app, fieldType(attribute.inputType),
                PimeCore.INPUT_KEYBOARD, "zh_CN", CANDIDATE_PAGE_SIZE, hostCaps),
                "context");
        report(core.secure(password), "secure");
        report(core.focus(true), "focus");
    }

    /**
     * The view exists by the time this runs, so predictions computed from the
     * field's existing text can be shown immediately instead of waiting for
     * the first key.
     */
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (info != null) secureField = isPassword(info.inputType);
        refreshSurrounding();
    }

    @Override
    public void onFinishInput() {
        cancelVoice();
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

        /* With a display window (3/5/7/9), space picks the first candidate of
         * that window instead of the hidden head of the chain's single page. */
        if (!english && coreReady && core.hasSession()
                && key.kind == KeyboardView.KIND_SPACE
                && stripOffset > 0 && !composition.isEmpty()
                && !candidates.isEmpty()) {
            selectCandidate(stripOffset);
            return;
        }

        /* English shares the chain path. The bundled English chain is only
         * skipped when it is not installed, so without it English still types
         * through the editor instead of falling back to pinyin. */
        if (coreReady && (!english || englishGeneration != 0)) {
            if (english && endsEnglishComposition(key)) {
                if (commitEnglishCompositionThen(text)) return;
            }
            commitSeen = false;
            final int status = core.key(key.code,
                    shifted ? PimeCore.MOD_SHIFT : 0, PimeCore.KEY_DOWN, text);
            core.pump();
            if (status == 1) {
                /* The chain confirms the typed word on space; the space the
                 * user pressed belongs in the editor, not in the candidate. */
                if (english && key.kind == KeyboardView.KIND_SPACE) appendSpace();
                if (isCandidateDigit(key)) resetAfterCommit();
                return;
            }
            if (status < 0) {
                Log.w(TAG, "key " + key.code + ": "
                        + core.statusString(-status));
            }
        }
        passThrough(key, text);
        /* The editor text changed without the chain seeing a commit
         * (backspace, punctuation, enter): association candidates must follow
         * what is actually before the cursor, not the last committed word.
         * Only a visible strip can go stale, so plain deleting costs no
         * extra chain round trip. */
        if (composition.isEmpty() && !candidates.isEmpty()) refreshSurrounding();
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

    /**
     * Re-derives association candidates from the text actually in the editor.
     *
     * <p>The predict plugins fall back to their own "last committed word"
     * state whenever no surrounding text arrives, so after a delete that word
     * is still offered as context even though it is gone from the field
     * (`你好` deleted to empty still showed 吗/啊/的). Reporting the real text
     * makes the chain prefer it; an empty field has no context to predict
     * from, so the old strip is dropped instead.
     */
    private void refreshSurrounding() {
        if (!coreReady || !core.hasSession() || secureField) return;
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        final CharSequence before = ic.getTextBeforeCursor(SURROUNDING_BEFORE, 0);
        final CharSequence after = ic.getTextAfterCursor(SURROUNDING_AFTER, 0);
        /* Predictions only describe the cursor's left context. Whatever the
         * bar holds now belongs to the text before the edit. */
        hideCandidates();
        if (before == null || before.length() == 0) return;
        report(core.surrounding(before.toString(),
                after == null ? "" : after.toString()), "surrounding");
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

    /**
     * A tap picks by digit, not by candidate index: the chain pages its list,
     * so {@code index} is only the slot on the page currently shown, while the
     * digit is resolved against the page the format plugin believes it is on.
     *
     * <p>With no composition the strip is fed by predict-role plugins, which
     * run after format and never enter its page: there is no digit slot to
     * resolve, so the tapped text is inserted directly.
     */
    private void selectCandidate(int index) {
        if (!coreReady || !core.hasSession()) return;
        if (index < 0 || index >= candidates.size()) return;
        if (composition.isEmpty()) {
            commitPrediction(index);
            return;
        }
        /* The `candidate` event addresses the whole list, not a digit slot on
         * a nine-wide page: "全部" can show hundreds, and one tap still has to
         * commit exactly the word under the finger. */
        commitSeen = false;
        final int status = core.selectCandidate(index);
        core.pump();
        if (status < 0) Log.w(TAG, "candidate " + index + ": " + text(-status));
        resetAfterCommit();
    }

    /**
     * Association candidates are plain text, not a formatting selection: the
     * chain has no composition to commit, so the host inserts the word and
     * rebuilds the next suggestions from the new cursor context.
     */
    private void commitPrediction(int index) {
        if (index < 0 || index >= candidates.size()) return;
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        final String text = candidates.get(index);
        hideCandidates();
        ic.commitText(text, 1);
        refreshSurrounding();
    }

    /** True for the digits the chain uses to pick a candidate by slot. */
    private static boolean isCandidateDigit(KeyboardView.Key key) {
        return key.kind == KeyboardView.KIND_CHAR
                && key.code >= '1' && key.code <= '9';
    }

    /**
     * A digit selection commits in candidates-format, which never sends the
     * parsers a commit phase: their session state would still hold the word
     * that was just committed, and the next key would resume it (pick
     * `Google` for `goog`, then type `l` and get `googl`). `reset` is the
     * chain contract for "the composition is gone", so the buffers are
     * dropped the same way the old `candidate` host event dropped them.
     */
    private void resetAfterCommit() {
        if (!commitSeen) return;
        commitSeen = false;
        if (!coreReady || !core.hasSession()) return;
        report(core.reset(), "reset");
        core.pump();
    }

    /**
     * Sends a page move and captures the page the chain replies with.
     *
     * <p>The chain keeps its own page cursor; the host only mirrors it in
     * {@link #chainPage} so the strip can be put back where it was. `home`
     * rewinds to the first page, which is what makes the table collectable in
     * one sweep.
     */
    private boolean sendPageKey(int keyCode) {
        if (!coreReady || !core.hasSession()) return false;
        collectedPage.clear();
        final int status = core.key(keyCode, 0, PimeCore.KEY_DOWN, null);
        core.pump();
        if (status < 0) {
            Log.w(TAG, "page " + keyCode + ": " + core.statusString(-status));
            return false;
        }
        if (keyCode == KEY_HOME) chainPage = 0;
        else if (keyCode == KEY_PAGE_DOWN) ++chainPage;
        else if (keyCode == KEY_PAGE_UP) chainPage = Math.max(0, chainPage - 1);
        return true;
    }

    /**
     * Swipe on the folded strip: move the local window one display page.
     *
     * <p>With "全部" the whole list is already on screen, so there is nothing
     * to page; with 3/5/7/9 the chain's single page is sliced here. This also
     * keeps the chain's own page cursor at zero, which is what the table's
     * global indexes and space/digit selection rely on.
     */
    private void pageCandidates(boolean forward) {
        if (candidatesExpanded || collecting || candidates.isEmpty()) return;
        final int limit = Prefs.candidates(this);
        if (limit <= 0) return;
        final int next = stripOffset + (forward ? limit : -limit);
        if (next < 0 || next >= candidates.size()) return;
        stripOffset = next;
        renderStrip();
        updateCandidateRow();
    }

    /* PimeCore.Listener */

    @Override
    public void onComposition(String text, int cursor, int[] starts, int[] ends) {
        final String next = text == null ? "" : text;
        if (!next.equals(composition)) {
            /* A new composition restarts the format plugin's paging and the
             * strip's local window. */
            chainPage = 0;
            gridReturnPage = 0;
            stripOffset = 0;
        }
        composition = next;
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
            if (i > 0) out.append(space ? ' ' : (char) 39);
            out.append(composition, start, end);
        }
        return out.toString();
    }

    @Override
    public void onCommit(String text) {
        commitSeen = true;
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null && text != null && !text.isEmpty()) ic.commitText(text, 1);
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
    }

    @Override
    public void onCandidates(String[] items) {
        if (candidateBar == null) return;
        /* A page the host walked to while filling the table is read by the
         * collector; drawing it would flash the strip under the table. */
        if (collecting) {
            collectedPage.clear();
            collectedPage.addAll(Arrays.asList(items));
            return;
        }
        candidates.clear();
        candidates.addAll(Arrays.asList(items));
        /* A new list is a new first page, whether it came from typing or from
         * the chain's own paging. */
        stripOffset = 0;
        if (BuildConfig.DEBUG) Log.d(TAG, "candidates=" + items.length);
        if (items.length == 0) {
            hideCandidates();
            return;
        }
        if (candidatesExpanded) {
            /* The table owns the visible list while it is open. */
            return;
        }
        renderStrip();
        updateCandidateRow();
    }

    /**
     * Draws the folded strip from {@link #candidates}.
     *
     * <p>The chain now delivers its whole candidate list in one page, so the
     * 3/5/7/9 preference is a window over that list rather than a request for
     * fewer candidates. "全部" draws every one of them. Views are recycled
     * because a dictionary reading can fill hundreds of slots ({@code shi}
     * has 260, a bare {@code s} the full 640), and rebuilding that many
     * TextViews on every key would be visible.
     */
    private void renderStrip() {
        if (candidateBar == null) return;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        final int size = candidates.size();
        final int limit = Prefs.candidates(this);
        if (stripOffset < 0 || stripOffset >= size) stripOffset = 0;
        final int start = limit > 0 ? stripOffset : 0;
        final int end = Math.min(size, start + (limit > 0 ? limit : size));
        int used = 0;
        for (int i = start; i < end; ++i) {
            TextView item;
            if (used < stripViews.size()) {
                item = stripViews.get(used);
            } else {
                item = candidateItem(palette);
                stripViews.add(item);
                candidateBar.addView(item);
            }
            bindCandidateItem(item, candidates.get(i), i, i == start, palette);
            item.setVisibility(View.VISIBLE);
            ++used;
        }
        for (int i = used; i < stripViews.size(); ++i) {
            stripViews.get(i).setVisibility(View.GONE);
        }
        candidateScroll.scrollTo(0, 0);
    }

    /**
     * The stack under the toolbar carries the preedit line above the candidate
     * strip, either alone, or nothing; it only takes height when it has
     * something to say. The table and the keys are mutually exclusive views of
     * the same rectangle, so nothing here changes the input view's height.
     */
    private void updateCandidateRow() {
        if (candidateRow == null) return;
        final boolean composing = !composition.isEmpty();
        if (compositionView != null) {
            compositionView.setVisibility(composing ? View.VISIBLE : View.GONE);
        }
        final boolean hasCandidates = !candidates.isEmpty();
        /* The chevron also opens the handwriting tab, which works with no
         * candidate list at all; association-only candidates still justify the
         * table (they commit by text, without a chain index). */
        final boolean expandable = composing || hasCandidates;
        if (candidatesExpanded && !expandable && panelTab != TAB_HANDWRITING) {
            candidatesExpanded = false;
        }
        final boolean unfolded = candidatesExpanded;
        if (expandButton != null) {
            expandButton.setVisibility(expandable || unfolded
                    ? View.VISIBLE : View.GONE);
        }
        if (candidateScroll != null) {
            candidateScroll.setVisibility(
                    hasCandidates && !unfolded ? View.VISIBLE : View.GONE);
        }
        if (candidatePanel != null) {
            candidatePanel.setVisibility(unfolded ? View.VISIBLE : View.GONE);
        }
        if (keyboard != null) {
            keyboard.setVisibility(unfolded ? View.GONE : View.VISIBLE);
        }
        candidateRow.setVisibility(composing || hasCandidates || unfolded
                ? View.VISIBLE : View.GONE);
    }

    /**
     * The whole point of WeChat's chevron: hand the key area to the candidates.
     * The keys are swapped out for a table of every candidate the chain has, so
     * one screen shows several pages at once and no sideways paging is needed;
     * the same rectangle also hosts the tabs and the handwriting surface.
     */
    private void toggleCandidatePanel() {
        if (!candidatesExpanded && candidates.isEmpty() && composition.isEmpty()) return;
        candidatesExpanded = !candidatesExpanded;
        if (candidatesExpanded) {
            gridReturnPage = chainPage;
            final KeyboardTheme palette = inputPalette != null
                    ? inputPalette : KeyboardTheme.of(this);
            renderTabs(palette);
            updatePanelContent();
            if (panelTab == TAB_HANDWRITING) {
                renderHandwritingGrid();
            } else {
                buildGrid();
            }
        } else {
            foldGrid();
        }
        updateExpandButton();
        updateCandidateRow();
    }

    /**
     * Collects the chain's pages into the table.
     *
     * <p>The chain hands out one page at a time, so the table walks it: `home`
     * rewinds to the first page (which becomes the first row) and `pagedown`
     * collects from there until the chain stops moving or the table is full.
     * Because the walk starts at the first page, a candidate's offset in the
     * collected list <em>is</em> its global index, which is exactly what the
     * chain's `candidate` event takes — so picking a row never has to page the
     * chain again, and there is no per-page slot arithmetic to get wrong.
     */
    private void buildGrid() {
        gridPages.clear();
        if (composition.isEmpty()) {
            /* Association candidates come from predict plugins after format,
             * so there is no page cursor to walk: the shown list is the table,
             * and a tap inserts its text. */
            if (!candidates.isEmpty()) gridPages.add(new ArrayList<>(candidates));
            renderPanelGrid();
            return;
        }
        if (!coreReady || !core.hasSession()) {
            renderPanelGrid();
            return;
        }
        collecting = true;
        try {
            if (sendPageKey(KEY_HOME) && !collectedPage.isEmpty()) {
                gridPages.add(new ArrayList<>(collectedPage));
                int guard = 0;
                while (guard++ < GRID_PAGE_GUARD) {
                    if (!sendPageKey(KEY_PAGE_DOWN)) break;
                    if (collectedPage.isEmpty()) break;
                    final List<String> last = gridPages.get(gridPages.size() - 1);
                    /* The format plugin clamps at its last page and re-sends
                     * it, which is how "there is nothing further" looks. */
                    if (collectedPage.equals(last)) break;
                    gridPages.add(new ArrayList<>(collectedPage));
                }
            }
            chainPage = Math.max(0, gridPages.size() - 1);
            restoreStripPage();
        } finally {
            collecting = false;
        }
        renderPanelGrid();
    }

    /** A collected page or an open rail changes the table, never the rail's own shape. */
    private void renderPanelGrid() {
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        renderTabs(palette);
        updatePanelContent();
        if (panelTab == TAB_HANDWRITING) {
            renderHandwritingGrid();
            return;
        }
        renderRail(palette);
        renderGrid();
    }

    /**
     * Walks the chain back to the page the strip was showing, and keeps
     * {@link #candidates} in step so folding shows that page again.
     */
    private void restoreStripPage() {
        if (gridPages.isEmpty()) return;
        final int target = Math.min(Math.max(gridReturnPage, 0), gridPages.size() - 1);
        int guard = 0;
        while (chainPage > target && guard++ < GRID_PAGE_GUARD) {
            if (!sendPageKey(KEY_PAGE_UP)) break;
        }
        while (chainPage < target && guard++ < GRID_PAGE_GUARD) {
            if (!sendPageKey(KEY_PAGE_DOWN)) break;
        }
        candidates.clear();
        candidates.addAll(gridPages.get(target));
        stripOffset = 0;
    }

    /** One visible cell and the index that picks it. */
    private static final class GridEntry {
        final String text;
        /** Offset in the collected list = the chain's global candidate index. */
        final int index;

        GridEntry(String text, int index) {
            this.text = text;
            this.index = index;
        }
    }

    /**
     * Candidates of the active category, before the rail narrows them down.
     *
     * <p>The index is the offset in the <em>whole</em> collected list, so
     * filtering never renumbers what {@code core.selectCandidate} takes.
     */
    private List<GridEntry> baseEntries() {
        final List<GridEntry> entries = new ArrayList<>();
        int index = 0;
        for (List<String> page : gridPages) {
            for (String text : page) {
                final int global = index++;
                if (panelTab == TAB_SINGLE && !isSingleHanzi(text)) continue;
                entries.add(new GridEntry(text, global));
            }
        }
        return entries;
    }

    private List<GridEntry> visibleEntries() {
        final List<GridEntry> base = baseEntries();
        if (railSyllable == null && railStroke < 0) return base;
        final HanziData data = HanziData.get(this);
        final List<GridEntry> filtered = new ArrayList<>(base.size());
        for (GridEntry entry : base) {
            final char first = firstHanzi(entry.text);
            if (first == 0) continue;
            if (railSyllable != null) {
                final String syllable = data.pinyinOf(first);
                if (!railSyllable.equals(syllable)) continue;
            }
            if (railStroke >= 0 && data.firstStroke(first) != railStroke) continue;
            filtered.add(entry);
        }
        return filtered;
    }

    /** True for exactly one Han character (the 单字 tab's filter). */
    private static boolean isSingleHanzi(String text) {
        return text != null && text.codePointCount(0, text.length()) == 1
                && firstHanzi(text) != 0;
    }

    /** First character when it is Han, otherwise 0 (nothing to look up). */
    private static char firstHanzi(String text) {
        if (text == null || text.isEmpty()) return 0;
        final char first = text.charAt(0);
        return Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN
                ? first : 0;
    }

    /**
     * The left rail: syllables of the candidates' first characters (the way
     * WeChat groups the table), with 首笔画 opening a second-level list. The
     * rail only narrows what is already collected; it never asks the chain for
     * a different page.
     */
    private void renderRail(KeyboardTheme palette) {
        if (railList == null) return;
        railList.removeAllViews();
        if (railMode == RAIL_STROKES) {
            addRailItem("‹", false, () -> {
                railMode = RAIL_SYLLABLES;
                renderRail(palette);
            });
            addRailItem(getString(R.string.ime_rail_all),
                    railStroke < 0, () -> {
                        railStroke = -1;
                        railMode = RAIL_SYLLABLES;
                        renderRail(palette);
                        renderGrid();
                    });
            final int[] labels = {
                    R.string.ime_stroke_heng,
                    R.string.ime_stroke_shu,
                    R.string.ime_stroke_pie,
                    R.string.ime_stroke_dian,
                    R.string.ime_stroke_zhe,
            };
            for (int i = 0; i < labels.length; ++i) {
                final int bucket = i;
                addRailItem(getString(labels[i]), railStroke == i, () -> {
                    railStroke = bucket;
                    railSyllable = null;
                    renderRail(palette);
                    renderGrid();
                });
            }
            return;
        }

        addRailItem(getString(R.string.ime_rail_all),
                railSyllable == null && railStroke < 0, () -> {
                    railSyllable = null;
                    railStroke = -1;
                    renderRail(palette);
                    renderGrid();
                });
        final HanziData data = HanziData.get(this);
        final java.util.LinkedHashSet<String> syllables = new java.util.LinkedHashSet<>();
        for (GridEntry entry : baseEntries()) {
            final char first = firstHanzi(entry.text);
            if (first == 0) continue;
            final String syllable = data.pinyinOf(first);
            if (syllable != null) syllables.add(syllable);
        }
        for (String syllable : syllables) {
            addRailItem(syllable, syllable.equals(railSyllable), () -> {
                railSyllable = syllable;
                railStroke = -1;
                renderRail(palette);
                renderGrid();
            });
        }
        addRailItem(getString(R.string.ime_rail_first_stroke) + " ›",
                railStroke >= 0, () -> {
                    railMode = RAIL_STROKES;
                    renderRail(palette);
                });
    }

    private void addRailItem(String label, boolean active, Runnable click) {
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        final TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setTextColor(active ? palette.accent : palette.hint);
        item.setTypeface(Typeface.create("sans-serif",
                active ? Typeface.BOLD : Typeface.NORMAL));
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(10));
        pill.setColor(active ? palette.accentSoft : 0);
        item.setBackground(ripple(pill, palette));
        item.setOnClickListener(v -> click.run());
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(RAIL_ITEM_HEIGHT_DP));
        params.setMargins(dp(4), dp(2), dp(4), dp(2));
        railList.addView(item, params);
    }

    /** Lays the visible candidates out five to a row, in chain order. */
    private void renderGrid() {
        if (gridBoard == null) return;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        gridBoard.removeAllViews();
        final List<GridEntry> entries = visibleEntries();
        if (entries.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.ime_candidates_empty);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setTextColor(palette.hint);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            gridBoard.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (gridScroll != null) gridScroll.scrollTo(0, 0);
            return;
        }
        final int columns = CANDIDATE_GRID_COLUMNS;
        for (int start = 0; start < entries.size(); start += columns) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int column = 0; column < columns; ++column) {
                final int at = start + column;
                final View cell = at < entries.size()
                        ? gridCell(entries.get(at), at, palette) : new View(this);
                row.addView(cell, new LinearLayout.LayoutParams(
                        0, dp(GRID_ROW_HEIGHT_DP), 1f));
            }
            gridBoard.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_ROW_HEIGHT_DP)));
        }
        if (gridScroll != null) gridScroll.scrollTo(0, 0);
    }

    /** Table cell: same emphasis as the strip's first item, evenly spread. */
    private View gridCell(GridEntry entry, int position, KeyboardTheme palette) {
        final TextView item = new TextView(this);
        final boolean first = position == 0;
        item.setText(entry.text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, first ? 19 : 18);
        item.setTextColor(first ? palette.accent : palette.candidateText);
        item.setTypeface(Typeface.create("sans-serif",
                first ? Typeface.BOLD : Typeface.NORMAL));
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setPadding(dp(4), 0, dp(4), 0);
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(16));
        pill.setColor(first ? palette.accentSoft : 0);
        item.setBackground(ripple(pill, palette));
        item.setOnClickListener(v -> pickEntry(entry));
        return item;
    }

    private void pickEntry(GridEntry entry) {
        if (composition.isEmpty()) {
            commitPrediction(entry.index);
        } else {
            selectGridCandidate(entry.index);
        }
    }

    /**
     * Picks a row of the table by its global index.
     *
     * <p>The format plugin resolves `candidate` over the whole list
     * (`commit = index < total`), so row 23 of a five-page table commits
     * straight away: no paging, no digit, and no way to land on the wrong word
     * because a page count drifted.
     */
    private void selectGridCandidate(int index) {
        if (!coreReady || !core.hasSession()) return;
        if (composition.isEmpty()) return;
        commitSeen = false;
        final int status = core.selectCandidate(index);
        core.pump();
        if (status < 0) {
            Log.w(TAG, "candidate " + index + ": " + core.statusString(-status));
        }
        resetAfterCommit();
    }

    /** The table's delete key: fix a letter without folding the table away. */
    private void gridDelete() {
        onKey(backspaceKey);
        if (!candidatesExpanded) return;
        if (composition.isEmpty()) {
            /* Nothing left to unfold: fall back to the keys, except while
             * handwriting, whose surface stays for the next character. */
            if (panelTab != TAB_HANDWRITING) toggleCandidatePanel();
            return;
        }
        buildGrid();
    }

    /* ---- handwriting tab ---- */

    private void onInkChanged(List<int[][]> strokes) {
        handwritingMatches.clear();
        if (!strokes.isEmpty() && strokes.get(0).length > 0) {
            if (recognizer == null) {
                recognizer = new HandwritingRecognizer(HanziData.get(this).templates());
            }
            handwritingMatches.addAll(recognizer.lookup(strokes, 20));
        }
        renderHandwritingGrid();
    }

    private void renderHandwritingGrid() {
        if (handwritingBoard == null) return;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        handwritingBoard.removeAllViews();
        for (int at = 0; at < handwritingMatches.size(); ++at) {
            final char hanzi = handwritingMatches.get(at).hanzi;
            final TextView item = new TextView(this);
            item.setText(String.valueOf(hanzi));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, at == 0 ? 20 : 19);
            item.setTextColor(at == 0 ? palette.accent : palette.candidateText);
            item.setTypeface(Typeface.create("sans-serif",
                    at == 0 ? Typeface.BOLD : Typeface.NORMAL));
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            final GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(16));
            pill.setColor(at == 0 ? palette.accentSoft : 0);
            item.setBackground(ripple(pill, palette));
            item.setOnClickListener(v -> commitHandwriting(hanzi));
            handwritingBoard.addView(item, new LinearLayout.LayoutParams(
                    dp(56), ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (handwritingScroll != null) handwritingScroll.scrollTo(0, 0);
    }

    /**
     * Handwriting is not a formatting candidate: it has no index in the chain,
     * so the character is inserted directly. A live composition is dropped
     * first — the drawn character is the user's answer to that preedit.
     */
    private void commitHandwriting(char hanzi) {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        final boolean stayOpen = candidatesExpanded;
        if (!composition.isEmpty()) {
            ic.setComposingText("", 1);
            composition = "";
            renderComposition();
            if (coreReady && core.hasSession()) {
                report(core.reset(), "reset");
                core.pump();
            }
        }
        ic.commitText(String.valueOf(hanzi), 1);
        candidates.clear();
        if (handwritingView != null) handwritingView.clear();
        if (stayOpen) {
            /* The chain's reset may have folded the panel; the handwriting
             * surface stays up for the next character. */
            candidatesExpanded = true;
            panelTab = TAB_HANDWRITING;
            final KeyboardTheme palette = inputPalette != null
                    ? inputPalette : KeyboardTheme.of(this);
            renderTabs(palette);
            updatePanelContent();
            renderHandwritingGrid();
            updateCandidateRow();
        }
        refreshSurrounding();
    }

    /** Folds the table back into the strip, on the page it was showing. */
    private void foldGrid() {
        if (gridBoard != null) gridBoard.removeAllViews();
        gridPages.clear();
        renderStrip();
    }

    /** The chevron points down while folded and up while the table is open. */
    private void updateExpandButton() {
        if (expandButton == null) return;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        expandButton.setRotation(candidatesExpanded ? 180f : 0f);
        expandButton.setImageTintList(ColorStateList.valueOf(
                candidatesExpanded ? palette.accent : palette.hint));
        expandButton.setContentDescription(getString(candidatesExpanded
                ? R.string.ime_action_collapse : R.string.ime_action_expand));
    }

    private void hideCandidates() {
        candidates.clear();
        stripOffset = 0;
        /* The handwriting surface is not tied to a candidate list: a chain
         * "hide" while it is up must not fold the tab away. */
        if (panelTab != TAB_HANDWRITING) candidatesExpanded = false;
        chainPage = 0;
        gridReturnPage = 0;
        gridPages.clear();
        if (candidateBar != null) {
            candidateBar.removeAllViews();
            stripViews.clear();
        }
        if (gridBoard != null) gridBoard.removeAllViews();
        if (candidateScroll != null) candidateScroll.setVisibility(View.GONE);
        if (expandButton != null) expandButton.setVisibility(View.GONE);
        updateExpandButton();
        updateCandidateRow();
    }

    /** An empty strip item; {@link #bindCandidateItem} fills it each redraw. */
    private TextView candidateItem(KeyboardTheme palette) {
        final TextView item = new TextView(this);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return item;
    }

    /**
     * The first visible candidate gets the accent pill; the rest are plain, so
     * the eye lands on the one a tap on space would take.
     */
    private void bindCandidateItem(TextView item, String text, int index,
                                   boolean first, KeyboardTheme palette) {
        item.setText(text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, first ? 20 : 19);
        item.setTextColor(first ? palette.accent : palette.candidateText);
        item.setTypeface(Typeface.create("sans-serif",
                first ? Typeface.BOLD : Typeface.NORMAL));
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(18));
        pill.setColor(first ? palette.accentSoft : 0);
        item.setBackground(ripple(pill, palette));
        item.setOnClickListener(v -> selectCandidate(index));
        final LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) item.getLayoutParams();
        params.setMargins(dp(4), dp(7), first ? dp(4) : 0, dp(7));
        item.setLayoutParams(params);
    }

    @Override
    public void onHideCandidates() {
        hideCandidates();
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
        makeNativeEntryReadOnly(pluginDir);
    }

    /** Core's installed copy is what gets dlopen'ed; mark it non-writable. */
    private void makeNativeEntryReadOnly(File pluginDir) {
        final File idDir = new File(pluginDir, SPEECH_PLUGIN_ID);
        final File[] versions = idDir.listFiles();
        if (versions == null) return;
        for (File version : versions) {
            final File entry = new File(version, "plugin.so");
            if (entry.isFile() && entry.canWrite() && !entry.setReadOnly()) {
                Log.w(TAG, "cannot make " + entry + " read-only");
            }
        }
    }

    /**
     * Copies assets/plugins/&lt;package&gt;/ and assets/chains/ into files/.
     *
     * <p>Bundled plugin packages are refreshed whenever the APK changes: an
     * upgrade may fix a package (assets, manifest) on an existing install, and
     * a same-version rebuild would otherwise stay invisible because core's
     * own installed copy refuses a different code hash. Bundled chains refresh
     * too, but only while they still match what this app last wrote, so user
     * edits survive upgrades.
     */
    private void installBundled(File pluginDir, File chainDir) {
        final AssetManager assets = getAssets();
        final long stamp = packageStamp();
        final boolean refresh = Prefs.bundledStamp(this) != stamp;
        boolean complete = true;
        try {
            for (String name : assets.list("plugins")) {
                final File target = new File(pluginDir, name);
                if (target.exists() && !refresh) {
                    ensureNativeEntry(target);
                    continue;
                }
                if (target.exists()) {
                    PluginStore.deleteTree(target);
                    removeInstalledCopy(pluginDir, "plugins/" + name);
                }
                copyAssetTree("plugins/" + name, target);
                ensureNativeEntry(target);
            }
            syncBundledChains(chainDir, refresh);
        } catch (IOException error) {
            complete = false;
            Log.w(TAG, "bundled assets unavailable", error);
        }
        if (complete && refresh) Prefs.setBundledStamp(this, stamp);
    }

    /**
     * Installs the bundled chains, replacing an upgrade's stale copy unless the
     * user edited it. A chain from a build without baselines cannot be checked,
     * so its old content is kept as {@code <name>.bak} before the refresh.
     */
    private void syncBundledChains(File chainDir, boolean refresh)
            throws IOException {
        for (String name : getAssets().list("chains")) {
            if (!name.endsWith(".cbor")) continue;
            final File target = new File(chainDir, name);
            final byte[] bundled = readAssetBytes("chains/" + name);
            final String bundledHash = sha256(bundled);
            if (!target.exists()) {
                copyAssetTree("chains/" + name, target);
                Prefs.setChainBaseline(this, name, bundledHash);
                continue;
            }
            if (!refresh) continue;
            final byte[] current = readBytes(target);
            if (current == null) continue;
            final String baseline = Prefs.chainBaseline(this, name);
            if (baseline != null && !baseline.equals(sha256(current))) continue;
            if (Arrays.equals(bundled, current)) {
                Prefs.setChainBaseline(this, name, bundledHash);
                continue;
            }
            if (baseline == null) writeFile(new File(chainDir, name + ".bak"),
                    current);
            copyAssetTree("chains/" + name, target);
            Prefs.setChainBaseline(this, name, bundledHash);
        }
    }

    /**
     * The speech plugin's binary is built into the APK, not shipped in
     * assets; copy the ABI-correct library next to its manifest and make it
     * read-only (Android 10+ refuses to dlopen writable code from the app's
     * home directory).
     */
    private void ensureNativeEntry(File packageDir) throws IOException {
        final File manifest = new File(packageDir, "manifest.yaml");
        if (!manifest.isFile()) return;
        final byte[] bytes = readBytes(manifest);
        if (bytes == null) return;
        final String text = new String(bytes, "UTF-8");
        if (!text.contains("runtime: native")) return;
        final File entry = new File(packageDir, "plugin.so");
        if (entry.isFile()) {
            entry.setReadOnly();
            return;
        }
        final File library = new File(getApplicationInfo().nativeLibraryDir,
                SPEECH_PLUGIN_LIB);
        if (!library.isFile()) {
            Log.w(TAG, "native plugin library missing: " + library);
            return;
        }
        try (InputStream input = new FileInputStream(library)) {
            writeFile(entry, readAll(input));
        }
        if (!entry.setReadOnly()) Log.w(TAG, "cannot make " + entry + " read-only");
    }

    private static byte[] readAll(InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            final byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data);
            final StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xf, 16));
                text.append(Character.forDigit(value & 0xf, 16));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            return "";
        }
    }

    private static void writeFile(File target, byte[] data) throws IOException {
        final File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdir " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(data);
        }
    }

    private byte[] readAssetBytes(String assetPath) throws IOException {
        try (InputStream input = getAssets().open(assetPath)) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /** The APK's last update time, used as the bundled-package sync stamp. */
    private long packageStamp() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException error) {
            return 0L;
        }
    }

    /**
     * Deletes core's installed copy of a bundled package at
     * {@code <pluginDir>/<id>/<version>}. Core refuses to replace a
     * same-version package whose code hash changed, so the copy has to go
     * before the refreshed bundle is loaded again.
     */
    private void removeInstalledCopy(File pluginDir, String assetPath)
            throws IOException {
        final String text = readAssetText(assetPath + "/manifest.yaml");
        final PluginStore.Manifest manifest =
                PluginStore.parseManifestText(this, text);
        final File installed =
                new File(new File(pluginDir, manifest.id), manifest.version);
        PluginStore.deleteTree(installed);
        final File parent = installed.getParentFile();
        final String[] siblings = parent != null ? parent.list() : null;
        if (parent != null && siblings != null && siblings.length == 0) {
            parent.delete();
        }
    }

    private String readAssetText(String assetPath) throws IOException {
        try (InputStream input = getAssets().open(assetPath)) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
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

    /**
     * Candidate strip: scrolls horizontally while the page is wider than the
     * screen, and turns a swipe into a page request at the edge (or whenever
     * there is nothing to scroll). A chain page holds only page_size
     * candidates, so without paging the sixth candidate — e.g. the Chinese
     * half of a mixed view — would be unreachable.
     */
    private static final class CandidateStrip extends HorizontalScrollView {
        interface Pager {
            /** {@code next} is true when the user swiped towards later pages. */
            void page(boolean next);
        }

        private final Pager pager;
        private final int slop;
        private float downX;
        private float downY;
        private boolean paging;

        CandidateStrip(Context context, Pager pager) {
            super(context);
            this.pager = pager;
            this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                paging = false;
            } else if (action == MotionEvent.ACTION_MOVE && !paging
                    && startsPage(event)) {
                paging = true;
                return true;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if (paging) {
                if (action == MotionEvent.ACTION_UP) {
                    paging = false;
                    pager.page(downX > event.getX());
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    paging = false;
                }
                return true;
            }
            /* No item took the touch (the blank tail of the strip): keep the
             * same bookkeeping here, or the scroll view would swallow a swipe
             * that has nothing left to scroll. */
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
            } else if (action == MotionEvent.ACTION_MOVE && startsPage(event)) {
                paging = true;
                return true;
            }
            return super.onTouchEvent(event);
        }

        /** True when this move begins a horizontal swipe the strip cannot scroll. */
        private boolean startsPage(MotionEvent event) {
            final float dx = event.getX() - downX;
            final float dy = event.getY() - downY;
            if (Math.abs(dx) <= slop || Math.abs(dx) <= Math.abs(dy)) return false;
            return !canScrollHorizontally(dx < 0 ? 1 : -1);
        }
    }
}
