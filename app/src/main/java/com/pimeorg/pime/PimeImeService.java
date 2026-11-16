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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
    private TextView compositionView;
    private String composition = "";
    /** Password fields never hand their text to the chain. */
    private boolean secureField;
    /** Syllable bounds in the composition, in UTF-16 units; empty when unknown. */
    private int[] compositionStarts = new int[0];
    private int[] compositionEnds = new int[0];
    private final List<String> candidates = new ArrayList<>();
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
    /** True while the capture thread should keep reading the microphone. */
    private volatile boolean voiceCapturing;
    private boolean voiceReplay;
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
                    toggleVoice();
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
            if (!candidates.isEmpty()) pageCandidates(next ? 34 : 33);
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
            case ImeToolbar.TOOL_VOICE:
                toggleVoice();
                break;
            default:
                break;
        }
    }

    /* ------------------------------------------------------------- voice */

    /**
     * Mic tap: first starts listening, a second tap finalizes the utterance.
     * Recognition runs in the bundled native plugin; the host only owns the
     * permission, the AudioRecord and the generation switch.
     */
    private void toggleVoice() {
        if (!coreReady) return;
        if (voiceActive) {
            stopVoice();
            return;
        }
        if (secureField) {
            Toast.makeText(this, R.string.voice_unavailable_secure,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            final Intent intent = new Intent(this, VoicePermissionActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }
        if (speechGeneration == 0) {
            Toast.makeText(this, R.string.voice_unavailable_plugin,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!VoiceModels.isReady(this)) {
            Toast.makeText(this, R.string.voice_download_needed,
                    Toast.LENGTH_SHORT).show();
            VoiceModels.ensure(this, this::startVoice,
                    () -> toast(R.string.voice_download_failed));
            return;
        }
        startVoice();
    }

    private void startVoice() {
        if (voiceActive || !coreReady) return;
        /* Reached from the download callback too: the keyboard may be gone by
         * then, and opening the microphone without a visible field is wrong. */
        if (!isInputViewShown()) return;
        if (activeGeneration != speechGeneration) {
            if (core.activateGeneration(speechGeneration) != 0) {
                Toast.makeText(this, R.string.voice_unavailable_plugin,
                        Toast.LENGTH_SHORT).show();
                return;
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
            return;
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
            return;
        }
        voiceActive = true;
        if (toolbar != null) toolbar.setVoiceActive(true);
    }

    private void stopVoice() {
        stopCapture();
        if (coreReady) {
            report(core.voice(PimeCore.VOICE_STOP), "voice stop");
            core.pump();
        }
        voiceActive = false;
        if (toolbar != null) toolbar.setVoiceActive(false);
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
        if (toolbar != null) toolbar.setVoiceActive(false);
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
        cancelVoice();
        composition = "";
        secureField = isPassword(attribute.inputType);
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
                | PimeCore.HOST_SECURE | PimeCore.HOST_SURROUNDING
                | PimeCore.HOST_AUDIO_CAPTURE;
        report(core.context(app, fieldType(attribute.inputType),
                PimeCore.INPUT_KEYBOARD, "zh_CN", 20, hostCaps), "context");
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
        if (composition.isEmpty()) {
            commitPrediction(index);
            return;
        }
        final char digit = (char) ('1' + index);
        commitSeen = false;
        final int status = core.key(digit, 0, PimeCore.KEY_DOWN,
                String.valueOf(digit));
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

    private void pageCandidates(int keyCode) {
        if (!coreReady || !core.hasSession()) return;
        final int status = core.key(keyCode, 0, PimeCore.KEY_DOWN, null);
        core.pump();
        if (status < 0) {
            Log.w(TAG, "page " + keyCode + ": " + core.statusString(-status));
        }
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
        /* Every new page starts at its first candidate. */
        candidateScroll.scrollTo(0, 0);
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
