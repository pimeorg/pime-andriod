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
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
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
import android.widget.PopupWindow;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Android host for pime-core.
 *
 * Lifecycle: one core per process, one session per focused editor. Keys are
 * offered to the core first; whatever the core does not handle is passed to
 * the editor, so the keyboard stays usable with no plugins installed.
 */
public class PimeImeService extends InputMethodService
        implements PimeCore.Listener, KeyboardView.Listener, ImeToolbar.Listener {
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
     * Candidates built for the folded strip. Typing pays for every candidate
     * the chain produces (building, ranking, CBOR, and re-decoding in the
     * fourteen downstream plugins), so the strip asks for a display-sized
     * list; opening the table asks for {@link #CANDIDATE_PAGE_SIZE} again.
     */
    private static final int CANDIDATE_FOLDED_SIZE = 64;
    /**
     * Loop bound while collecting pages / restoring the strip. The real stop
     * condition is "the chain stopped moving or repeated a page"; this only
     * keeps a misbehaving chain from spinning forever. It is not a candidate
     * limit.
     */
    private static final int GRID_PAGE_GUARD = 1000;
    private static final int GRID_ROW_HEIGHT_DP = 48;
    /** Table cell text size; the cell width is measured from it. */
    private static final float GRID_TEXT_SIZE_SP = 20f;
    /** Floor for a word wider than the whole row; it shrinks, never truncates. */
    private static final float GRID_MIN_TEXT_SIZE_SP = 10f;
    /** Horizontal breathing room inside a table cell, each side. */
    private static final int GRID_CELL_PADDING_DP = 6;
    /**
     * A run of deletes stays open for one swipe-down undo this long, in ms: a
     * pause or any other key ends it, so undo walks back whole runs rather
     * than one character at a time.
     */
    private static final long UNDO_GROUP_MS = 1200;
    /** Delete runs kept for undo; the oldest is dropped first. */
    private static final int UNDO_RUNS = 8;
    /** Characters read per call while a swipe-up clear walks the field. */
    private static final int CLEAR_CHUNK = 1000;
    /** Upper bound on those calls, so a misbehaving editor cannot spin us. */
    private static final int CLEAR_CHUNKS = 100;
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
    /**
     * The key area is showing the handwriting layout instead of the keys.
     * Persisted through {@link Prefs#handwritingLayout}; picking any chain
     * clears it. The layout view below is a sibling of the keyboard, the same
     * way the candidate panel takes the rectangle over.
     */
    private boolean handwritingMode;
    private LinearLayout handwritingLayoutView;
    private HandwritingView handwritingPad;
    private HorizontalScrollView handwritingPadScroll;
    private LinearLayout handwritingPadBoard;
    private final List<HandwritingRecognizer.Match> handwritingPadMatches =
            new ArrayList<>();
    /** The layout chooser; non-focusable so it does not end the input session. */
    private PopupWindow layoutPopup;
    /** Hairline between the candidate row and the key area. */
    private View keyboardDivider;
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
     * Runs of text ⌫ removed, newest last, restored one per swipe down on ⌫.
     * Purely host-side: the chain has no undo, and a swipe-down must put back
     * what the editor actually lost.
     */
    private final List<DeleteRun> undoRuns = new ArrayList<>();
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
    /** Measures candidate text so a table cell can be as wide as its word. */
    private Paint gridMeasurePaint;
    /** Width the current rows were laid out for; a layout change re-renders. */
    private int gridRenderedWidth;
    private boolean candidatesExpanded;
    /** Height of the key rectangle the panel takes over when unfolded. */
    private int keyAreaHeight;

    /* ---- unfolded panel: tabs, syllable rail, handwriting ---- */

    private static final int TAB_FREQUENCY = 0;
    private static final int TAB_SINGLE = 1;
    private static final int TAB_HANDWRITING = 2;
    private static final int RAIL_SYLLABLES = 0;
    private static final int RAIL_STROKES = 1;
    private static final int TAB_ROW_HEIGHT_DP = 38;
    /** Height of the rounded category track inside the tab row. */
    private static final int TAB_HEIGHT_DP = 34;
    /** Width the category track asks for; narrow screens shrink it. */
    private static final int TAB_WIDTH_DP = 250;
    private static final int RAIL_WIDTH_DP = 64;
    private static final int HANDWRITING_RESULT_HEIGHT_DP = 46;

    private int panelTab = TAB_FREQUENCY;
    private int railMode = RAIL_SYLLABLES;
    /** Syllable the table is filtered to; null means every candidate. */
    private String railSyllable;
    /** First-stroke bucket the table is filtered to; -1 means no filter. */
    private int railStroke = -1;
    private final TextView[] tabViews = new TextView[3];
    /** Collapse chevron on the category line; the folded strip has its own. */
    private ImageButton panelChevron;
    /** Bottom cell of the rail column: 首笔画, or the way back from it. */
    private TextView railFootItem;
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
    /** Candidate count the next chain run builds; see CANDIDATE_FOLDED_SIZE. */
    private int candidateBudget = CANDIDATE_FOLDED_SIZE;
    /** Long-press candidate menu; non-focusable so the IME keeps the editor. */
    private PopupWindow candidatePopup;
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
        dismissCandidateMenu();
        dismissLayoutMenu();
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
         * keys. It shares one band with the candidate row rather than stacking
         * above it: while a preedit or candidates are showing, the row owns the
         * band and the toolbar steps aside, so the keys never sit under two
         * bars at once. Its tools and their order come from Prefs; removing all
         * of them gives the room to keys. */
        toolbar = new ImeToolbar(this, palette, Prefs.toolbarTools(this));
        toolbar.setListener(this);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight()));

        /* Composition sits on its own line above the scrollable candidate
         * strip, so a long preedit never squeezes the candidates off screen. */
        candidateRow = new LinearLayout(this);
        candidateRow.setOrientation(LinearLayout.VERTICAL);
        candidateRow.setBackgroundColor(palette.bar);

        compositionView = new TextView(this);
        compositionView.setTextColor(palette.hint);
        compositionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
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
        keyboardDivider = divider;
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        final int keyboardHeight = keyboardHeight(palette);
        keyAreaHeight = keyboardHeight;
        keyboard = new KeyboardView(this);
        keyboard.setKeyboardTheme(palette);
        keyboard.setEnglish(Prefs.english(this));
        keyboard.setListener(this);
        root.addView(keyboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));
        handwritingMode = Prefs.handwritingLayout(this);
        toolbar.setLayoutActive(handwritingMode);
        toolbar.setLanguageActive(keyboard.isEnglish());

        /* The table sits where the keys are, with their exact height: unfolding
         * swaps one for the other, so the editor above keeps its size and the
         * candidates get the whole key area. Nothing hangs over the editor and
         * nothing has to be re-laid out. */
        candidatePanel = buildCandidatePanel(palette);
        root.addView(candidatePanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));

        /* The handwriting layout takes the same rectangle as the keys, so
         * switching layouts never changes the IME height or reflows the
         * editor. */
        handwritingLayoutView = buildHandwritingLayout(palette);
        root.addView(handwritingLayoutView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));

        /* The toolbar and the candidate row swap within one band, so reserve
         * the taller of the two: the slack sits above the bar and the keys do
         * not move as the row comes and goes. */
        final int barBand = toolbar.hasTools()
                ? Math.max(toolbarHeight(), dp(CANDIDATE_ROW_HEIGHT_DP)) : 0;
        root.setMinimumHeight(barBand + keyboardHeight);
        /* First paint: hide the surface that the persisted layout does not use. */
        updateCandidateRow();
        return root;
    }

    /** Zero when the user kept no toolbar tools; the keys get the room instead. */
    private int toolbarHeight() {
        return toolbar != null && toolbar.hasTools() ? dp(TOP_BAR_HEIGHT_DP) : 0;
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
        panel.setBackgroundColor(palette.panel);
        panel.setVisibility(View.GONE);

        /* Category line, the way WeChat separates the categories: one rounded
         * track holding three equal tabs, the active one a lighter pill inside
         * the track, and the collapse chevron on the same line at the right,
         * outside the track. */
        final FrameLayout tabRow = new FrameLayout(this);
        tabRow.setBackgroundColor(palette.panel);
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        final GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(10));
        track.setColor(palette.tabTrack);
        tabs.setBackground(track);
        tabViews[TAB_FREQUENCY] = panelTabView(R.string.ime_tab_frequency, TAB_FREQUENCY);
        tabViews[TAB_SINGLE] = panelTabView(R.string.ime_tab_single, TAB_SINGLE);
        tabViews[TAB_HANDWRITING] = panelTabView(R.string.ime_tab_handwriting,
                TAB_HANDWRITING);
        for (TextView tab : tabViews) {
            tabs.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        final FrameLayout.LayoutParams tabsParams = new FrameLayout.LayoutParams(
                dp(TAB_WIDTH_DP), dp(TAB_HEIGHT_DP), Gravity.CENTER);
        tabRow.addView(tabs, tabsParams);
        /* The track is a fixed size and stays screen-centred; only a screen
         * too narrow to hold track plus chevron makes it give way. */
        tabRow.addOnLayoutChangeListener((v, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            /* 62% of the width is what the reference's track measures on a
             * 411dp screen; the cap keeps it from ballooning on tablets and the
             * floor keeps the three labels readable on narrow phones. */
            final int width = Math.min(dp(TAB_WIDTH_DP),
                    Math.max(dp(180), Math.min(v.getWidth() - dp(108),
                            Math.round(v.getWidth() * 0.62f))));
            if (width > 0 && tabsParams.width != width) {
                tabsParams.width = width;
                tabs.setLayoutParams(tabsParams);
            }
        });
        panelChevron = chevronButton(palette);
        panelChevron.setRotation(180f);
        panelChevron.setContentDescription(getString(R.string.ime_action_collapse));
        panelChevron.setOnClickListener(v -> toggleCandidatePanel());
        tabRow.addView(panelChevron, new FrameLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL));
        panel.addView(tabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_ROW_HEIGHT_DP)));
        final View tabRule = new View(this);
        tabRule.setBackgroundColor(palette.rowLine);
        panel.addView(tabRule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        final FrameLayout content = new FrameLayout(this);
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* Table and handwriting share the content rectangle. */
        gridArea = new LinearLayout(this);
        gridArea.setOrientation(LinearLayout.HORIZONTAL);
        gridArea.setBackgroundColor(palette.panel);
        content.addView(gridArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* Left rail: syllables of the candidates' first characters, or the
         * first-stroke buckets once the stroke item is tapped. Its items are
         * exactly as tall as the table's rows, so the two columns line up. */
        railScroll = new ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setClipToPadding(false);
        railScroll.setBackgroundColor(palette.rail);
        /* Mirrors the table: the last syllable can scroll above the rail foot. */
        railScroll.setPadding(0, 0, 0, dp(GRID_ROW_HEIGHT_DP));
        railList = new LinearLayout(this);
        railList.setOrientation(LinearLayout.VERTICAL);
        railScroll.addView(railList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridArea.addView(railScroll, new LinearLayout.LayoutParams(
                dp(RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        final View railDivider = new View(this);
        railDivider.setBackgroundColor(palette.rowLine);
        gridArea.addView(railDivider, new LinearLayout.LayoutParams(
                Math.max(1, dp(1) / 2), ViewGroup.LayoutParams.MATCH_PARENT));

        gridScroll = new ScrollView(this);
        gridScroll.setFillViewport(true);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipToPadding(false);
        gridScroll.setBackgroundColor(palette.panel);
        /* The last row can scroll clear of the pinned delete / 首笔画 cells. */
        gridScroll.setPadding(0, 0, 0, dp(GRID_ROW_HEIGHT_DP));
        gridBoard = new LinearLayout(this);
        gridBoard.setOrientation(LinearLayout.VERTICAL);
        gridScroll.addView(gridBoard, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridArea.addView(gridScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        /* Rows are sized from the table's measured width; if the first render
         * happened before layout (or the window width changed), redo it once
         * the real width is known. */
        gridScroll.addOnLayoutChangeListener((v, left, top, right, bottom,
                                              oldLeft, oldTop, oldRight, oldBottom) -> {
            final int width = right - left;
            if (width > 0 && width != gridRenderedWidth
                    && candidatesExpanded && panelTab != TAB_HANDWRITING) {
                renderGrid();
            }
        });

        handwritingArea = new LinearLayout(this);
        handwritingArea.setOrientation(LinearLayout.VERTICAL);
        handwritingArea.setVisibility(View.GONE);
        /* Keep the ink out from under the pinned corner cells. */
        handwritingArea.setPadding(0, 0, 0, dp(GRID_ROW_HEIGHT_DP));
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
        /* The bar is the table's last row band, not a bar under it: the four
         * cells the delete key does not use stay transparent, so the row's
         * candidates show through and the table scrolls beneath both corner
         * cells, exactly like the reference. */
        final LinearLayout railFoot = new LinearLayout(this);
        railFoot.setOrientation(LinearLayout.VERTICAL);
        railFoot.setBackgroundColor(palette.rail);
        railFootItem = new TextView(this);
        railFootItem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        railFootItem.setGravity(Gravity.CENTER);
        railFootItem.setSingleLine(true);
        railFootItem.setEllipsize(TextUtils.TruncateAt.END);
        railFootItem.setTextColor(palette.railText);
        railFoot.addView(railFootItem, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final View railFootRule = new View(this);
        railFootRule.setBackgroundColor(palette.railLine);
        railFoot.addView(railFootRule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        bar.addView(railFoot, new LinearLayout.LayoutParams(
                dp(RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        final View railFootLine = new View(this);
        railFootLine.setBackgroundColor(palette.rowLine);
        bar.addView(railFootLine, new LinearLayout.LayoutParams(
                Math.max(1, dp(1) / 2), ViewGroup.LayoutParams.MATCH_PARENT));

        final FrameLayout barBody = new FrameLayout(this);
        bar.addView(barBody, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        final LinearLayout barCells = new LinearLayout(this);
        barCells.setOrientation(LinearLayout.HORIZONTAL);
        barBody.addView(barCells, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        /* One column of the grid: the delete key lands where the fifth
         * candidate of the last row would be, as in WeChat's table. */
        for (int column = 1; column < CANDIDATE_GRID_COLUMNS; ++column) {
            barCells.addView(new View(this), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        final ImageButton delete = new ImageButton(this);
        delete.setImageResource(R.drawable.ic_backspace);
        delete.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        delete.setPadding(dp(17), dp(11), dp(17), dp(11));
        delete.setImageTintList(ColorStateList.valueOf(palette.keyText));
        delete.setContentDescription(getString(R.string.ime_action_delete));
        /* Flat fill, no pill: in the table the key is a cell, not a key face. */
        final GradientDrawable deleteFill = new GradientDrawable();
        deleteFill.setColor(palette.keyCap);
        delete.setBackground(ripple(deleteFill, palette));
        delete.setOnClickListener(v -> gridDelete());
        barCells.addView(delete, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        /* Handwriting actions sit on the empty cells left of the delete key. */
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(4), 0, 0, 0);
        handwritingUndo = barAction(R.string.ime_handwriting_undo, palette,
                v -> handwritingView.undo());
        handwritingClear = barAction(R.string.ime_handwriting_clear, palette,
                v -> handwritingView.clear());
        handwritingUndo.setVisibility(View.GONE);
        handwritingClear.setVisibility(View.GONE);
        actions.addView(handwritingUndo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        actions.addView(handwritingClear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        barBody.addView(actions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        /* Pinned to the bottom band of the table: the delete cell takes the last
         * row's fifth column, the rail foot its first, and the row's other four
         * candidates show through between them. */
        content.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_ROW_HEIGHT_DP),
                Gravity.BOTTOM));
        renderTabs(palette);
        return panel;
    }

    /** Fold chevron: the strip's and the category line's are the same control. */
    private ImageButton chevronButton(KeyboardTheme palette) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_expand_more);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(candidateChevronBackground(false, palette));
        button.setImageTintList(ColorStateList.valueOf(palette.hint));
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        button.setContentDescription(getString(R.string.ime_action_expand));
        return button;
    }


    private TextView panelTabView(int label, int tab) {
        final TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setOnClickListener(v -> selectPanelTab(tab));
        return view;
    }

    /** Handwriting actions: plain text, because the delete cell owns the fill. */
    private TextView barAction(int label, KeyboardTheme palette,
                               View.OnClickListener click) {
        final TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(palette.keyFuncText);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dp(14), 0, dp(14), 0);
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(10));
        fill.setColor(0);
        view.setBackground(ripple(fill, palette));
        view.setOnClickListener(click);
        return view;
    }

    /** Paints the category line; the active tab is the lighter pill in the track. */
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
            tab.setTextColor(active ? palette.tabActiveText : palette.tabText);
            tab.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            /* The pill is inset inside the tab's share of the track, so the
             * track still reads as one control with a moving highlight. */
            final GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(8));
            pill.setColor(active ? palette.tabActive : 0);
            final int inset = dp(3);
            tab.setBackground(ripple(new InsetDrawable(
                    pill, inset, inset, inset, inset), palette));
            tab.setSelected(active);
        }
        if (panelChevron != null) {
            panelChevron.setImageTintList(ColorStateList.valueOf(palette.hint));
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
        /* 首笔画 filters the table; it has nothing to say to the ink surface. */
        if (railFootItem != null) {
            railFootItem.setVisibility(handwriting ? View.GONE : View.VISIBLE);
        }
    }

    /** Host actions from the toolbar; none of them touch the plugin chain. */
    @Override
    public void onTool(String tool) {
        switch (tool) {
            case ImeToolbar.TOOL_LAYOUT:
                showLayoutMenu();
                break;
            case ImeToolbar.TOOL_LANGUAGE:
                if (keyboard != null) keyboard.toggleLanguage();
                break;
            case ImeToolbar.TOOL_PASTE:
                pasteClipboard();
                break;
            case ImeToolbar.TOOL_SETTINGS:
                /* The gear sits on the toolbar, so it configures the toolbar
                 * itself rather than the whole keyboard settings app. */
                openToolbarSettings();
                break;
            case ImeToolbar.TOOL_HIDE:
                requestHideSelf(0);
                break;
            default:
                break;
        }
    }

    /** Long-pressing a toolbar icon is the shortcut to the customization page. */
    @Override
    public void onCustomize() {
        openToolbarSettings();
    }

    /**
     * Opening the settings activity while the keyboard window is still up left
     * the IME drawn over the page, and on the way back the server believed it
     * was already visible so tapping a field no longer brought it up. Hide the
     * keyboard first; the next tap on a field shows it again as usual.
     */
    private void openToolbarSettings() {
        requestHideSelf(0);
        openSettings(SettingsActivity.PAGE_TOOLBAR);
    }

    /* ------------------------------------------------------------- layout */

    /**
     * The keyboard tool's menu: the built-in handwriting layout plus every
     * installed chain except the reserved English one. A chain is an input
     * layout (pinyin, wubi, shuangpin, ...), so this is the layout switch.
     * 中/英 stays on its own key and toolbar tool.
     */
    private void showLayoutMenu() {
        if (toolbar == null) return;
        dismissCandidateMenu();
        dismissLayoutMenu();
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        final LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(palette.panel);
        fill.setCornerRadius(dp(12));
        fill.setStroke(Math.max(1, dp(1) / 2), palette.divider);
        menu.setBackground(fill);
        menu.setPadding(0, dp(4), 0, dp(4));

        final String active = ChainStore.activeChainName(this);
        boolean first = true;
        for (File chain : ChainStore.listChains(this)) {
            if (chain.getName().equals(ChainStore.ENGLISH_CHAIN)) continue;
            if (!first) menu.addView(menuDivider(palette));
            first = false;
            final File target = chain;
            final boolean checked = !handwritingMode
                    && chain.getName().equals(active);
            menu.addView(layoutMenuRow(layoutLabel(chain), checked, palette,
                    v -> {
                        dismissLayoutMenu();
                        selectLayoutChain(target);
                    }), layoutMenuRowParams());
        }
        if (!first) menu.addView(menuDivider(palette));
        menu.addView(layoutMenuRow(getString(R.string.layout_handwriting),
                handwritingMode, palette, v -> {
                    dismissLayoutMenu();
                    setHandwritingLayout(true);
                }), layoutMenuRowParams());

        final PopupWindow popup = new PopupWindow(menu, dp(200),
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setBackgroundDrawable(new ColorDrawable(0));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setOnDismissListener(() -> {
            if (layoutPopup == popup) layoutPopup = null;
        });
        layoutPopup = popup;
        final View anchor = toolbar.toolButton(ImeToolbar.TOOL_LAYOUT);
        popup.showAsDropDown(anchor != null ? anchor : toolbar, 0, dp(4));
    }

    private LinearLayout.LayoutParams layoutMenuRowParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
    }

    private View menuDivider(KeyboardTheme palette) {
        final View line = new View(this);
        line.setBackgroundColor(palette.divider);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return line;
    }

    private View layoutMenuRow(String label, boolean checked,
                               KeyboardTheme palette, View.OnClickListener click) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(14), 0);
        row.setBackground(ripple(new GradientDrawable(), palette));
        final TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setTextColor(checked ? palette.accent : palette.candidateText);
        text.setTypeface(Typeface.create("sans-serif",
                checked ? Typeface.BOLD : Typeface.NORMAL));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        if (checked) {
            final ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(palette.accent));
            row.addView(check, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        row.setOnClickListener(click);
        return row;
    }

    private String layoutLabel(File chain) {
        final String name = ChainStore.displayName(chain);
        switch (name) {
            case "default":
                return getString(R.string.layout_pinyin);
            case "shuangpin":
                return getString(R.string.layout_shuangpin);
            case "wubi":
                return getString(R.string.layout_wubi);
            case "t9":
                return getString(R.string.layout_t9);
            case "cangjie":
                return getString(R.string.layout_cangjie);
            default:
                return name;
        }
    }

    private void dismissLayoutMenu() {
        if (layoutPopup == null) return;
        final PopupWindow popup = layoutPopup;
        layoutPopup = null;
        popup.dismiss();
    }

    /**
     * Switches the Chinese chain and rebuilds the generations. The old chain is
     * reset first so the raw letters are committed, not reparsed by the new
     * one. A chain that cannot build is rolled back: core would otherwise
     * leave the keyboard on an empty generation.
     */
    private void selectLayoutChain(File chain) {
        if (chain == null || !chain.isFile()) {
            toast(R.string.layout_unavailable);
            return;
        }
        try {
            ChainStore.readChain(this, chain);
        } catch (IOException error) {
            toast(R.string.layout_unavailable);
            return;
        }
        if (handwritingMode) setHandwritingLayout(false);
        final String name = chain.getName();
        if (name.equals(ChainStore.activeChainName(this))) return;
        if (voiceActive) stopVoice();
        finishCompositionAsRaw();
        undoRuns.clear();
        candidatesExpanded = false;
        final String previous = ChainStore.activeChainName(this);
        ChainStore.setActiveChain(this, name);
        buildGenerations();
        if (coreReady && chineseGeneration == 0 && !previous.isEmpty()) {
            ChainStore.setActiveChain(this, previous);
            buildGenerations();
            toast(R.string.layout_unavailable);
            return;
        }
        if (toolbar != null) toolbar.setLayoutActive(false);
        updateCandidateRow();
        Toast.makeText(this, getString(R.string.layout_switched,
                layoutLabel(chain)), Toast.LENGTH_SHORT).show();
    }

    /**
     * Shows the handwriting surface in the key area. The persisted flag makes
     * the choice survive a field switch and a view rebuild; the in-layout
     * 键盘 button is the way back even when the toolbar has no tools.
     */
    private void setHandwritingLayout(boolean on) {
        if (handwritingMode == on) return;
        /* Set the mode first: the resets below already call
         * updateCandidateRow(), and it must not flash the keys. */
        handwritingMode = on;
        Prefs.setHandwritingLayout(this, on);
        if (toolbar != null) toolbar.setLayoutActive(on);
        if (on) {
            candidatesExpanded = false;
            panelTab = TAB_FREQUENCY;
            finishCompositionAsRaw();
        }
        clearHandwritingPad();
        hideCandidates();
        updateCandidateRow();
    }

    /**
     * The handwriting layout: a result strip and its actions on top, the ink
     * surface below. It replaces the keys in the same rectangle, so the editor
     * never reflows when the layout is chosen.
     */
    private LinearLayout buildHandwritingLayout(KeyboardTheme palette) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(palette.board);
        layout.setVisibility(View.GONE);

        final LinearLayout resultRow = new LinearLayout(this);
        resultRow.setOrientation(LinearLayout.HORIZONTAL);
        resultRow.setGravity(Gravity.CENTER_VERTICAL);
        resultRow.setBackgroundColor(palette.bar);
        handwritingPadScroll = new HorizontalScrollView(this);
        handwritingPadScroll.setHorizontalScrollBarEnabled(false);
        handwritingPadBoard = new LinearLayout(this);
        handwritingPadBoard.setOrientation(LinearLayout.HORIZONTAL);
        handwritingPadScroll.addView(handwritingPadBoard,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        resultRow.addView(handwritingPadScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        resultRow.addView(padAction(R.string.ime_handwriting_undo, palette,
                v -> handwritingPad.undo()), padActionParams());
        resultRow.addView(padAction(R.string.ime_handwriting_clear, palette,
                v -> handwritingPad.clear()), padActionParams());
        resultRow.addView(padAction(R.string.ime_handwriting_delete, palette,
                v -> passThrough(backspaceKey, null)), padActionParams());
        /* Mandatory exit: the toolbar may be empty or the layout tool removed. */
        resultRow.addView(padAction(R.string.layout_keyboard, palette,
                v -> setHandwritingLayout(false)), padActionParams());
        layout.addView(resultRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(HANDWRITING_RESULT_HEIGHT_DP)));

        handwritingPad = new HandwritingView(this);
        handwritingPad.setPalette(palette);
        handwritingPad.setListener(this::onPadInkChanged);
        layout.addView(handwritingPad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return layout;
    }

    private TextView padAction(int label, KeyboardTheme palette,
                               View.OnClickListener click) {
        final TextView action = barAction(label, palette, click);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        action.setPadding(dp(8), 0, dp(8), 0);
        return action;
    }

    private LinearLayout.LayoutParams padActionParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void onPadInkChanged(List<int[][]> strokes) {
        handwritingPadMatches.clear();
        if (!strokes.isEmpty() && strokes.get(0).length > 0) {
            if (recognizer == null) {
                recognizer = new HandwritingRecognizer(HanziData.get(this).templates());
            }
            handwritingPadMatches.addAll(recognizer.lookup(strokes, 20));
        }
        renderPadResults();
    }

    private void renderPadResults() {
        if (handwritingPadBoard == null) return;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        handwritingPadBoard.removeAllViews();
        for (int at = 0; at < handwritingPadMatches.size(); ++at) {
            final char hanzi = handwritingPadMatches.get(at).hanzi;
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
            item.setOnClickListener(v -> commitPadChar(hanzi));
            handwritingPadBoard.addView(item, new LinearLayout.LayoutParams(
                    dp(56), ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (handwritingPadScroll != null) handwritingPadScroll.scrollTo(0, 0);
    }

    /**
     * A recognized character is committed directly: it has no index in the
     * chain. The ink clears and the pad stays up for the next character.
     * No refreshSurrounding(): association candidates would be generated
     * behind the ink surface and pop up when the keys come back.
     */
    private void commitPadChar(char hanzi) {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(String.valueOf(hanzi), 1);
        clearHandwritingPad();
    }

    private void clearHandwritingPad() {
        handwritingPadMatches.clear();
        if (handwritingPad != null) handwritingPad.clear();
        if (handwritingPadBoard != null) handwritingPadBoard.removeAllViews();
    }

    @Override
    public boolean onToolHold(String tool) {
        return ImeToolbar.TOOL_VOICE.equals(tool) && onVoiceHold();
    }

    @Override
    public void onToolRelease(String tool, boolean cancelled) {
        if (ImeToolbar.TOOL_VOICE.equals(tool)) onVoiceRelease(cancelled);
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

    /**
     * 中/英 switches the active generation: the Chinese chain chosen in
     * settings or the bundled English chain. A composition in flight is
     * committed as raw letters first (the same thing WeaveText does), so
     * switching does not eat what was already typed.
     */
    @Override
    public void onLanguageChanged(boolean english) {
        Prefs.setEnglish(this, english);
        if (toolbar != null) toolbar.setLanguageActive(english);
        finishCompositionAsRaw();
        activateForLanguage(english);
    }

    /**
     * Cancels the live composition and commits the raw letters to the editor,
     * then hides the candidate row. Used whenever the chain underneath the
     * preedit changes (中/英, layout switch): what was typed is never eaten.
     */
    private void finishCompositionAsRaw() {
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

    /** Opens the launcher straight on a detail page ("toolbar"), or home. */
    private void openSettings(String page) {
        final Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (page != null) intent.putExtra(SettingsActivity.EXTRA_PAGE, page);
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
        /* The toolbar is rebuilt below, so a menu anchored to it must go. */
        dismissLayoutMenu();
        setInputView(onCreateInputView());
        renderComposition();
        updateCandidateRow();
        if (!candidates.isEmpty()) onCandidates(candidates.toArray(new String[0]));
        /* A rebuild starts with fresh views; when the table was open, put its
         * tabs, rail and rows back instead of leaving keyboard and panel
         * disagreeing about the rectangle. Ink does not survive a rebuild, so
         * old handwriting results are dropped with it. The handwriting layout
         * already owns the rectangle and must not restore the panel. */
        handwritingMatches.clear();
        handwritingPadMatches.clear();
        if (candidatesExpanded && !handwritingMode) {
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
        dismissLayoutMenu();
        cancelVoice();
        composition = "";
        undoRuns.clear();
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
        clearHandwritingPad();
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
                PimeCore.INPUT_KEYBOARD, "zh_CN", CANDIDATE_PAGE_SIZE, hostCaps,
                candidateBudget), "context");
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
        dismissLayoutMenu();
        cancelVoice();
        clearHandwritingPad();
        if (coreReady) {
            report(core.focus(false), "focus");
            report(core.reset(), "reset");
            core.pump();
        }
        composition = "";
        undoRuns.clear();
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        super.onFinishInput();
    }

    @Override
    public void onKey(KeyboardView.Key key) {
        final boolean backspace = key.kind == KeyboardView.KIND_BACKSPACE;
        /* Swipe-down undo only restores what a run of deletes just removed.
         * Any other key moves the cursor context, so the journal ends here. */
        if (!backspace) undoRuns.clear();
        final boolean shifted = keyboard != null && keyboard.isShiftDown();
        final String text = key.kind == KeyboardView.KIND_CHAR
                || key.kind == KeyboardView.KIND_SPACE
                ? String.valueOf(textFor(key, shifted)) : null;
        final boolean english = keyboard != null && keyboard.isEnglish();

        /* With a display window (3/5/7/9), space picks the first candidate of
         * that window instead of the hidden head of the chain's single page. */
        if (!english && coreReady && core.hasSession()
                && key.kind == KeyboardView.KIND_SPACE
                && stripOffset > 0 && !composition.isEmpty()) {
            final CandidateEntry entry = stripCandidateAt(stripOffset);
            if (entry != null) {
                selectStripCandidate(entry);
                return;
            }
        }

        /* English shares the chain path. The bundled English chain is only
         * skipped when it is not installed, so without it English still types
         * through the editor instead of falling back to pinyin. */
        if (coreReady && (!english || englishGeneration != 0)) {
            if (english && endsEnglishComposition(key)) {
                if (commitEnglishCompositionThen(text)) return;
            }
            commitSeen = false;
            /* The preedit the chain is about to shorten; captured before the
             * key runs, because pump() reports the new one back. */
            final String beforeComposition = composition;
            final int status = core.key(key.code,
                    shifted ? PimeCore.MOD_SHIFT : 0, PimeCore.KEY_DOWN, text);
            core.pump();
            if (status == 1) {
                if (backspace) noteCompositionDeletion(beforeComposition);
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
            case KeyboardView.KIND_BACKSPACE: {
                /* Soft keyboards cannot rely on sendKeyEvent reaching text
                 * fields; deleteSurroundingText is the supported path. */
                final CharSequence before = ic.getTextBeforeCursor(1, 0);
                ic.deleteSurroundingText(1, 0);
                noteEditorDeletion(before == null ? "" : before.toString());
                break;
            }
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
     * Records one character ⌫ removed from the editor. Consecutive deletes
     * group into a run (newest first, because the text goes back before the
     * cursor in the order it was typed); a pause or another key starts a new
     * run. Secure fields are never journalled — the undo buffer would outlive
     * the field and keep a password in memory.
     */
    private void noteEditorDeletion(String removed) {
        if (removed.isEmpty() || secureField) return;
        final long now = SystemClock.uptimeMillis();
        DeleteRun run = undoRuns.isEmpty() ? null : undoRuns.get(undoRuns.size() - 1);
        if (run == null || !run.composition.isEmpty()
                || now - run.at > UNDO_GROUP_MS) {
            run = new DeleteRun();
            undoRuns.add(run);
            if (undoRuns.size() > UNDO_RUNS) undoRuns.remove(0);
        }
        run.text.insert(0, removed);
        run.at = now;
    }

    /**
     * Records the preedit ⌫ is about to shorten. Only the run's first snapshot
     * is kept, so one undo puts the whole composition back instead of one
     * letter per swipe.
     */
    private void noteCompositionDeletion(String before) {
        if (before.isEmpty() || secureField) return;
        final long now = SystemClock.uptimeMillis();
        DeleteRun run = undoRuns.isEmpty() ? null : undoRuns.get(undoRuns.size() - 1);
        if (run != null && run.text.length() == 0
                && !run.composition.isEmpty()
                && now - run.at <= UNDO_GROUP_MS) {
            run.at = now;
            return;
        }
        run = new DeleteRun();
        run.composition = before;
        undoRuns.add(run);
        if (undoRuns.size() > UNDO_RUNS) undoRuns.remove(0);
    }

    /** ⌫ swiped down: put back the last run of deletes. */
    @Override
    public void onUndo() {
        if (undoRuns.isEmpty()) {
            toast(R.string.undo_nothing);
            return;
        }
        final DeleteRun run = undoRuns.remove(undoRuns.size() - 1);
        if (run.text.length() > 0) {
            final InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            /* Editor text sat before the preedit (a clear removes both), so it
             * goes back first and the preedit is then rebuilt on top. */
            ic.commitText(run.text.toString(), 1);
        }
        if (!run.composition.isEmpty()) {
            restoreComposition(run.composition);
        } else if (run.text.length() > 0) {
            refreshSurrounding();
        }
    }

    /**
     * ⌫ swiped up: clear the preedit and everything before the cursor in one
     * go. This is the point of the gesture — a long press deletes one key at a
     * time and would take a while on a filled field. The whole clear is one
     * undo entry, so a single swipe down puts it all back.
     */
    @Override
    public void onDeleteAll() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        final String preedit = composition;
        if (!preedit.isEmpty()) {
            if (coreReady && core.hasSession()) {
                report(core.reset(), "reset");
                core.pump();
            }
            composition = "";
            if (compositionView != null) compositionView.setText("");
            hideCandidates();
        }
        /* Read and delete in chunks: editors cap how much one call may remove,
         * and every chunk has to land in the same undo entry. */
        final StringBuilder removed = new StringBuilder();
        for (int guard = 0; guard < CLEAR_CHUNKS; ++guard) {
            final CharSequence before = ic.getTextBeforeCursor(CLEAR_CHUNK, 0);
            if (before == null || before.length() == 0) break;
            ic.deleteSurroundingText(before.length(), 0);
            removed.insert(0, before);
        }
        if (!secureField && (removed.length() > 0 || !preedit.isEmpty())) {
            final DeleteRun run = new DeleteRun();
            run.text.append(removed);
            run.composition = preedit;
            undoRuns.add(run);
            if (undoRuns.size() > UNDO_RUNS) undoRuns.remove(0);
        }
        if (composition.isEmpty() && !candidates.isEmpty()) refreshSurrounding();
    }

    /**
     * Puts a preedit back that ⌫ had shortened. {@code reset} is the chain's
     * cancel contract, and the letters are then fed back as keys so the parse
     * and rank plugins rebuild the same composition and candidate list as
     * before the deletes (the chain keeps no undo of its own).
     */
    private void restoreComposition(String text) {
        if (!coreReady || !core.hasSession()) return;
        report(core.reset(), "reset");
        core.pump();
        composition = "";
        if (compositionView != null) compositionView.setText("");
        hideCandidates();
        for (int i = 0; i < text.length(); ++i) {
            final char c = text.charAt(i);
            final int status = core.key(c, 0, PimeCore.KEY_DOWN, String.valueOf(c));
            if (status < 0) {
                Log.w(TAG, "undo key " + c + ": " + core.statusString(-status));
            }
            core.pump();
        }
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
        if (candidatesExpanded || collecting) return;
        final int limit = Prefs.candidates(this);
        if (limit <= 0) return;
        final int size = candidateEntries().size();
        if (size == 0) return;
        final int next = stripOffset + (forward ? limit : -limit);
        if (next < 0 || next >= size) return;
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
        /* Text landed at the cursor, so an old delete run no longer sits where
         * undo would put it back. */
        undoRuns.clear();
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
        final List<CandidateEntry> entries = candidateEntries();
        final int size = entries.size();
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
            bindCandidateItem(item, entries.get(i), i == start, palette);
            item.setVisibility(View.VISIBLE);
            ++used;
        }
        for (int i = used; i < stripViews.size(); ++i) {
            stripViews.get(i).setVisibility(View.GONE);
        }
        candidateScroll.scrollTo(0, 0);
    }

    /**
     * The code a candidate list belongs to. Pins and deletions are remembered
     * per code (and per language), so promoting 你好 for {@code nihao} does not
     * reorder every other composition. With no composition the list is the
     * predictor's, and the scope is the shared prediction list.
     */
    private String candidateScope() {
        final boolean english = keyboard != null
                ? keyboard.isEnglish() : Prefs.english(this);
        final String language = english ? "en:" : "zh:";
        if (composition.isEmpty()) return language + "predict";
        return language + composition.toLowerCase(Locale.ROOT);
    }

    /**
     * The chain's candidates with the user's long-press overrides applied.
     * {@link CandidateEntry#rawIndex} still points into {@code candidates}, so
     * a tap selects exactly the word the chain knows by that index even after
     * a pin moved it to the front or a deletion removed entries around it.
     */
    private List<CandidateEntry> candidateEntries() {
        final List<CandidateEntry> entries = new ArrayList<>(candidates.size());
        if (candidates.isEmpty()) return entries;
        final String scope = candidateScope();
        final Set<String> hidden = Prefs.candidateHidden(this, scope);
        final String pinned = Prefs.candidatePin(this, scope);
        CandidateEntry pinnedEntry = null;
        for (int i = 0; i < candidates.size(); ++i) {
            final String word = candidates.get(i);
            if (hidden.contains(word)) continue;
            final CandidateEntry entry = new CandidateEntry(word, i);
            if (pinnedEntry == null && pinned != null && pinned.equals(word)) {
                pinnedEntry = entry;
            } else {
                entries.add(entry);
            }
        }
        if (pinnedEntry != null) entries.add(0, pinnedEntry);
        return entries;
    }

    /** One visible strip slot, or null when the window moved past the list. */
    private CandidateEntry stripCandidateAt(int position) {
        final List<CandidateEntry> entries = candidateEntries();
        return position >= 0 && position < entries.size()
                ? entries.get(position) : null;
    }

    private void selectStripCandidate(CandidateEntry entry) {
        if (entry == null) return;
        if (composition.isEmpty()) commitPrediction(entry.rawIndex);
        else selectCandidate(entry.rawIndex);
    }

    /**
     * Long-press actions on one candidate: keep it at the head of this code's
     * list, or take it out of the list. Both are local preferences, so the
     * chain and its dictionaries are left untouched.
     *
     * <p>A normal PopupMenu is focusable, and opening one takes focus from the
     * IME: the editor finishes the composition and the candidate row vanishes
     * before the action runs. This menu is a non-focusable PopupWindow, so the
     * input session stays put while it is open.
     */
    private void showCandidateMenu(View anchor, String word) {
        if (anchor == null || word == null || word.isEmpty()) return;
        dismissCandidateMenu();
        dismissLayoutMenu();
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        final LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(palette.panel);
        fill.setCornerRadius(dp(12));
        fill.setStroke(Math.max(1, dp(1) / 2), palette.divider);
        menu.setBackground(fill);
        menu.setPadding(0, dp(4), 0, dp(4));

        final TextView pin = candidateMenuRow(R.string.candidate_pin_first, palette);
        final String scope = candidateScope();
        pin.setOnClickListener(v -> {
            dismissCandidateMenu();
            pinCandidate(word, scope);
        });
        menu.addView(pin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        final View line = new View(this);
        line.setBackgroundColor(palette.divider);
        menu.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        final TextView delete = candidateMenuRow(R.string.candidate_delete, palette);
        delete.setOnClickListener(v -> {
            dismissCandidateMenu();
            deleteCandidate(word, scope);
        });
        menu.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        final PopupWindow popup = new PopupWindow(menu,
                dp(176), ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setBackgroundDrawable(new ColorDrawable(0));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setOnDismissListener(() -> {
            if (candidatePopup == popup) candidatePopup = null;
        });
        candidatePopup = popup;
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private TextView candidateMenuRow(int text, KeyboardTheme palette) {
        final TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setTextColor(palette.candidateText);
        row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setBackground(ripple(new GradientDrawable(), palette));
        return row;
    }

    private void dismissCandidateMenu() {
        if (candidatePopup == null) return;
        final PopupWindow popup = candidatePopup;
        candidatePopup = null;
        popup.dismiss();
    }

    private void pinCandidate(String word, String scope) {
        if (word == null || word.isEmpty() || scope == null) return;
        Prefs.setCandidatePin(this, scope, word);
        /* The pinned word belongs at the head of the whole list, not just the
         * display window the finger was in. */
        stripOffset = 0;
        toast(R.string.candidate_pinned);
        refreshCandidateOverrides();
    }

    private void deleteCandidate(String word, String scope) {
        if (word == null || word.isEmpty() || scope == null) return;
        Prefs.hideCandidate(this, scope, word);
        if (word.equals(Prefs.candidatePin(this, scope))) {
            Prefs.clearCandidatePin(this, scope);
        }
        toast(R.string.candidate_deleted);
        refreshCandidateOverrides();
    }

    /** Redraws the list that is currently on screen after a long-press action. */
    private void refreshCandidateOverrides() {
        if (candidatesExpanded && panelTab != TAB_HANDWRITING) {
            renderStrip();
            renderGrid();
        } else {
            renderStrip();
        }
        updateCandidateRow();
    }

    /**
     * The band above the keys carries the preedit line above the candidate
     * strip, either alone, or nothing; it only takes height when it has
     * something to say, and while it does the toolbar is folded away into the
     * same band. The table and the keys are mutually exclusive views of the
     * same rectangle, so unfolding swaps one for the other without moving the
     * keys off their row.
     */
    private void updateCandidateRow() {
        if (candidateRow == null) return;
        /* The handwriting layout owns the whole key area: no keys, no strip,
         * no table. The toolbar stays, because the band above the pad is
         * otherwise empty. */
        if (handwritingMode) {
            candidateRow.setVisibility(View.GONE);
            if (candidatePanel != null) candidatePanel.setVisibility(View.GONE);
            if (keyboardDivider != null) keyboardDivider.setVisibility(View.GONE);
            if (keyboard != null) keyboard.setVisibility(View.GONE);
            if (handwritingLayoutView != null) {
                handwritingLayoutView.setVisibility(View.VISIBLE);
            }
            if (toolbar != null) {
                toolbar.setVisibility(toolbar.hasTools()
                        ? View.VISIBLE : View.GONE);
            }
            return;
        }
        if (handwritingLayoutView != null) {
            handwritingLayoutView.setVisibility(View.GONE);
        }
        final boolean composing = !composition.isEmpty();
        if (compositionView != null) {
            compositionView.setVisibility(composing ? View.VISIBLE : View.GONE);
        }
        final boolean hasCandidates = !candidateEntries().isEmpty();
        /* The chevron also opens the handwriting tab, which works with no
         * candidate list at all; association-only candidates still justify the
         * table (they commit by text, without a chain index). */
        final boolean expandable = composing || hasCandidates;
        if (candidatesExpanded && !expandable && panelTab != TAB_HANDWRITING) {
            candidatesExpanded = false;
        }
        final boolean unfolded = candidatesExpanded;
        /* The row and the toolbar share one band: whichever has something to
         * say gets it. A preedit or candidates mean the row is up, so the
         * toolbar gives the band away until typing stops. */
        final boolean rowVisible = composing || hasCandidates || unfolded;
        if (expandButton != null) {
            /* The open table has its own chevron on the category line, so the
             * strip's goes away with the strip. */
            expandButton.setVisibility(expandable && !unfolded
                    ? View.VISIBLE : View.GONE);
        }
        if (panelChevron != null) {
            panelChevron.setVisibility(unfolded ? View.VISIBLE : View.GONE);
        }
        if (candidateScroll != null) {
            candidateScroll.setVisibility(
                    hasCandidates && !unfolded ? View.VISIBLE : View.GONE);
        }
        if (candidateLine != null) {
            /* The open table keeps the whole rectangle, including the strip's
             * line: that is where its category track goes, as in WeChat. */
            candidateLine.setVisibility(unfolded ? View.GONE : View.VISIBLE);
        }
        if (candidatePanel != null) {
            candidatePanel.setVisibility(unfolded ? View.VISIBLE : View.GONE);
            final ViewGroup.LayoutParams params = candidatePanel.getLayoutParams();
            /* The key area and the candidate strip's line are what the table
             * takes over; the toolbar is already gone whenever the row is up,
             * and the hairline goes with the keys. The panel then holds just
             * the preedit line, the category track and candidates, as WeChat
             * draws it. */
            final int room = keyAreaHeight + dp(CANDIDATE_ROW_HEIGHT_DP)
                    + Math.max(1, dp(1) / 2);
            final int tall = dp(GRID_ROW_HEIGHT_DP);
            final int chrome = dp(TAB_ROW_HEIGHT_DP) + Math.max(1, dp(1) / 2);
            /* Whole rows only: the pinned last band (delete + 首笔画) has to sit
             * on a row boundary, otherwise it would slice the row above it. The
             * leftover dp simply stays with the editor. */
            final int rows = Math.max(2, (room - chrome) / tall);
            final int wanted = unfolded ? chrome + rows * tall : room;
            if (params != null && params.height != wanted) {
                params.height = wanted;
                candidatePanel.setLayoutParams(params);
            }
        }
        if (toolbar != null) {
            toolbar.setVisibility(rowVisible || !toolbar.hasTools()
                    ? View.GONE : View.VISIBLE);
        }
        if (keyboardDivider != null) {
            keyboardDivider.setVisibility(unfolded ? View.GONE : View.VISIBLE);
        }
        if (keyboard != null) {
            keyboard.setVisibility(unfolded ? View.GONE : View.VISIBLE);
        }
        candidateRow.setVisibility(rowVisible ? View.VISIBLE : View.GONE);
    }

    /**
     * The whole point of WeChat's chevron: hand the key area to the candidates.
     * The keys are swapped out for a table of every candidate the chain has, so
     * one screen shows several pages at once and no sideways paging is needed;
     * the same rectangle also hosts the tabs and the handwriting surface.
     */
    private void toggleCandidatePanel() {
        if (handwritingMode) return;
        if (!candidatesExpanded && candidates.isEmpty() && composition.isEmpty()) return;
        candidatesExpanded = !candidatesExpanded;
        if (candidatesExpanded) {
            /* The table shows everything the chain can produce. */
            applyCandidateBudget(CANDIDATE_PAGE_SIZE);
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
            /* Back to typing: the next chain run is display-sized again. */
            applyCandidateBudget(CANDIDATE_FOLDED_SIZE);
        }
        updateExpandButton();
        updateCandidateRow();
    }

    /**
     * Changes the candidate count translate plugins build on the next chain
     * run without dispatching an event of its own: only the frame context of
     * the following key/page event changes.
     */
    private void applyCandidateBudget(int budget) {
        if (candidateBudget == budget) return;
        candidateBudget = budget;
        if (coreReady && core.hasSession())
            report(core.setCandidateBudget(budget), "budget");
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

    /** What one undo unit removed: editor text, a preedit, or both. */
    private static final class DeleteRun {
        /** Editor characters in restore order (newest delete first). */
        final StringBuilder text = new StringBuilder();
        /** Composition as it stood before the run; empty when it had none. */
        String composition = "";
        long at = SystemClock.uptimeMillis();
    }

    /** One folded-strip slot and the index that still selects it in the chain. */
    private static final class CandidateEntry {
        final String text;
        /** Offset in {@code candidates}; predictions commit by text instead. */
        final int rawIndex;

        CandidateEntry(String text, int rawIndex) {
            this.text = text;
            this.rawIndex = rawIndex;
        }
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
        final String scope = candidateScope();
        final Set<String> hidden = Prefs.candidateHidden(this, scope);
        final String pinned = Prefs.candidatePin(this, scope);
        GridEntry pinnedEntry = null;
        int index = 0;
        for (List<String> page : gridPages) {
            for (String text : page) {
                final int global = index++;
                if (hidden.contains(text)) continue;
                if (panelTab == TAB_SINGLE && !isSingleHanzi(text)) continue;
                final GridEntry entry = new GridEntry(text, global);
                if (pinnedEntry == null && pinned != null && pinned.equals(text)) {
                    pinnedEntry = entry;
                } else {
                    entries.add(entry);
                }
            }
        }
        if (pinnedEntry != null) entries.add(0, pinnedEntry);
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
        renderRailFoot(palette);
        if (railMode == RAIL_STROKES) {
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

        /* Unfiltered, the rail starts at the first syllable, like WeChat's;
         * "全部" only appears once there is a filter to clear. */
        if (railSyllable != null) {
            addRailItem(getString(R.string.ime_rail_all), false, () -> {
                railSyllable = null;
                railStroke = -1;
                renderRail(palette);
                renderGrid();
            });
        }
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
    }

    /**
     * The rail's bottom cell is the 首笔画 door: it opens the stroke buckets, and
     * while they are open it is the way back. Keeping it out of the scrolling
     * list means it never scrolls away, as in WeChat's table.
     */
    private void renderRailFoot(KeyboardTheme palette) {
        if (railFootItem == null) return;
        if (railMode == RAIL_STROKES) {
            final SpannableStringBuilder text = new SpannableStringBuilder("‹ ");
            text.setSpan(new ForegroundColorSpan(palette.hint), 0, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(getString(R.string.ime_rail_first_stroke));
            railFootItem.setText(text);
            railFootItem.setTextColor(palette.railText);
            railFootItem.setOnClickListener(v -> {
                railMode = RAIL_SYLLABLES;
                railStroke = -1;
                renderRail(palette);
                renderGrid();
            });
        } else {
            railFootItem.setText(strokeRailLabel(palette));
            railFootItem.setTextColor(palette.railText);
            railFootItem.setOnClickListener(v -> {
                railMode = RAIL_STROKES;
                renderRail(palette);
            });
        }
    }

    /** 首笔画 with the second-level arrow, the arrow a step dimmer. */
    private CharSequence strokeRailLabel(KeyboardTheme palette) {
        final SpannableStringBuilder text = new SpannableStringBuilder(
                getString(R.string.ime_rail_first_stroke));
        final int from = text.length();
        text.append(" ›");
        text.setSpan(new ForegroundColorSpan(palette.hint), from, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /**
     * One rail cell. It is as tall as a table row and carries the same kind of
     * hairline at its foot, so the syllable column lines up with the candidates
     * beside it instead of being a separate list.
     */
    private void addRailItem(CharSequence label, boolean active, Runnable click) {
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        final LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        final TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setTextColor(active ? palette.tabActiveText : palette.railText);
        cell.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final View line = new View(this);
        line.setBackgroundColor(palette.railLine);
        cell.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(active ? palette.tabActive : 0);
        cell.setBackground(ripple(fill, palette));
        cell.setOnClickListener(v -> click.run());
        railList.addView(cell, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_ROW_HEIGHT_DP)));
    }

    /**
     * Lays the visible candidates out in rows of at most
     * {@link #CANDIDATE_GRID_COLUMNS}, in chain order. Each row is a block:
     * the cells, then a hairline.
     *
     * <p>The table is a column grid. Every cell is at least one column wide
     * ({@code available / CANDIDATE_GRID_COLUMNS}), so a short row — the last
     * page of the list, or one broken because the next word did not fit —
     * still lines its cells up with the rows above instead of stretching one
     * word across the line or packing them to the left. A word wider than a
     * column takes the width it needs and pushes its neighbours, and a word
     * wider than the whole row gets a line to itself at a reduced size.
     * Nothing is ever ellipsized: every candidate the table shows is readable
     * in full.
     */
    private void renderGrid() {
        if (gridBoard == null) return;
        final KeyboardTheme palette = inputPalette != null
                ? inputPalette : KeyboardTheme.of(this);
        gridBoard.removeAllViews();
        gridRenderedWidth = gridContentWidth();
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
        final int available = gridRenderedWidth;
        final int columns = CANDIDATE_GRID_COLUMNS;
        /* One column of the grid. Every cell is at least this wide, so the
         * cells of a short row sit on the same column boundaries as the rows
         * above it. */
        final int columnWidth = Math.max(1, available / columns);
        final int[] cellWidths = new int[columns];
        final int line = Math.max(1, dp(1) / 2);
        int at = 0;
        while (at < entries.size()) {
            int count = 0;
            int used = 0;
            /* Fill the row with as many whole words as fit; the first one is
             * always taken, even when it is wider than the row. */
            while (at + count < entries.size() && count < columns) {
                final int natural = (int) Math.ceil(
                        gridCellWidth(entries.get(at + count).text));
                final int width = Math.max(columnWidth, natural);
                if (count > 0 && used + width > available) break;
                cellWidths[count] = width;
                used += width;
                ++count;
            }
            final LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int column = 0; column < count; ++column) {
                final GridEntry entry = entries.get(at + column);
                int width = cellWidths[column];
                float textSize = GRID_TEXT_SIZE_SP;
                if (width > available) {
                    /* One word wider than the row: shrink the text instead of
                     * cutting the word off. */
                    textSize = Math.max(GRID_MIN_TEXT_SIZE_SP,
                            GRID_TEXT_SIZE_SP * available / width);
                    width = available;
                }
                row.addView(gridCell(entry, at + column, palette, textSize),
                        new LinearLayout.LayoutParams(
                                width, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            at += count;
            block.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            final View foot = new View(this);
            foot.setBackgroundColor(palette.rowLine);
            block.addView(foot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, line));
            gridBoard.addView(block, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_ROW_HEIGHT_DP)));
        }
        if (gridScroll != null) gridScroll.scrollTo(0, 0);
    }

    /**
     * Width one table cell needs for its word, padding included. The row
     * builder uses it to decide how many candidates fit side by side.
     */
    private float gridCellWidth(String text) {
        if (gridMeasurePaint == null) {
            gridMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gridMeasurePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        gridMeasurePaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, GRID_TEXT_SIZE_SP,
                getResources().getDisplayMetrics()));
        return gridMeasurePaint.measureText(text) + dp(2 * GRID_CELL_PADDING_DP);
    }

    /**
     * Width a table row has to work with. Before the first layout the scroll
     * view has no width yet, so the screen less the fixed rail stands in for
     * it; {@link #gridScroll}'s layout listener re-renders if the two differ.
     */
    private int gridContentWidth() {
        if (gridScroll != null && gridScroll.getWidth() > 0) return gridScroll.getWidth();
        return Math.max(dp(1), getResources().getDisplayMetrics().widthPixels
                - dp(RAIL_WIDTH_DP) - Math.max(1, dp(1) / 2));
    }

    /**
     * Table cell: one candidate, centred in the width its word earned. The
     * table is a list to tap, not a place where the first row is pre-selected,
     * so no cell is painted as the answer until it is picked.
     */
    private View gridCell(GridEntry entry, int position, KeyboardTheme palette,
                          float textSizeSp) {
        final TextView item = new TextView(this);
        item.setText(entry.text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        item.setTextColor(palette.candidateText);
        item.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        /* No ellipsis, ever: renderGrid measured the word and gave the cell
         * that width (shrinking the text when a single word outgrew the row). */
        item.setEllipsize(null);
        item.setPadding(dp(GRID_CELL_PADDING_DP), 0, dp(GRID_CELL_PADDING_DP), 0);
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(0);
        item.setBackground(ripple(fill, palette));
        item.setOnClickListener(v -> pickEntry(entry));
        item.setOnLongClickListener(v -> {
            showCandidateMenu(v, entry.text);
            return true;
        });
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
        dismissCandidateMenu();
        candidates.clear();
        stripOffset = 0;
        /* The handwriting surface is not tied to a candidate list: a chain
         * "hide" while it is up must not fold the tab away. */
        if (panelTab != TAB_HANDWRITING) candidatesExpanded = false;
        applyCandidateBudget(CANDIDATE_FOLDED_SIZE);
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
        /* One line, but never shortened: the strip scrolls sideways, so a long
         * word keeps its full width and stays readable end to end. */
        item.setSingleLine(true);
        item.setEllipsize(null);
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
    private void bindCandidateItem(TextView item, CandidateEntry entry,
                                   boolean first, KeyboardTheme palette) {
        item.setText(entry.text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, first ? 20 : 19);
        item.setTextColor(first ? palette.accent : palette.candidateText);
        item.setTypeface(Typeface.create("sans-serif",
                first ? Typeface.BOLD : Typeface.NORMAL));
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(18));
        pill.setColor(first ? palette.accentSoft : 0);
        item.setBackground(ripple(pill, palette));
        item.setOnClickListener(v -> selectStripCandidate(entry));
        item.setOnLongClickListener(v -> {
            showCandidateMenu(v, entry.text);
            return true;
        });
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
