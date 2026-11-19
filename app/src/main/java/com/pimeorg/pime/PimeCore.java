package com.pimeorg.pime;

import android.util.Log;

/**
 * Thin JNI face of pime-core. Holds no input-method logic: it forwards host
 * events into the kernel and the kernel's sink commands back to a listener.
 *
 * <p>Core lives on the thread that started it: its plugins and the WASM
 * backend are bound to that thread, and driving them from another one makes
 * every plugin answer decode as garbage. The rest of the host does not have to
 * care, though — the voice capture thread, the plugin wake callback and the UI
 * all reach this object — so every call that touches core state is
 * {@code synchronized} on it. That makes "one call at a time" hold whichever
 * thread asks, which is also how a captured audio frame can be handed over
 * while the main thread is still building a chain.
 *
 * <p>The only methods left unlocked are the ones that touch no core state:
 * {@link #hasSession()}, {@link #isRunning()}, {@link #version()} and
 * {@link #statusString(int)}.
 *
 * <p>Sink callbacks run on whatever thread made the call and never call back
 * into core, so holding the monitor across them cannot deadlock.
 */
public final class PimeCore {
    private static final String TAG = "PimeCore";

    static {
        System.loadLibrary("pime_jni");
    }

    /* core flags */
    public static final int FLAG_ALLOW_INSECURE = 1;
    public static final int FLAG_STRICT_SIGNATURE = 2;

    /* modifiers */
    public static final int MOD_SHIFT = 1 << 2;

    /* key actions */
    public static final int KEY_DOWN = 0;
    public static final int KEY_UP = 1;

    /* keycodes */
    public static final int KEY_BACKSPACE = 0x01000001;
    public static final int KEY_ENTER = 0x01000003;
    public static final int KEY_ESCAPE = 0x01000004;

    /* context */
    public static final int FIELD_TEXT = 0;
    public static final int FIELD_PASSWORD = 1;
    public static final int FIELD_NUMBER = 2;
    public static final int FIELD_EMAIL = 3;
    public static final int FIELD_URL = 4;
    public static final int FIELD_PHONE = 5;
    public static final int FIELD_SEARCH = 6;
    public static final int FIELD_UNKNOWN = 0xFFFFFFFF;

    public static final int INPUT_KEYBOARD = 0;

    /* host capabilities */
    public static final int HOST_SURROUNDING = 1;
    public static final int HOST_SECURE = 1 << 1;
    public static final int HOST_AUDIO_CAPTURE = 1 << 2;
    public static final int HOST_CANDIDATES = 1 << 4;
    public static final int HOST_TOUCH = 1 << 5;

    /* voice actions */
    public static final int VOICE_START = 0;
    public static final int VOICE_STOP = 1;
    public static final int VOICE_CANCEL = 2;

    public interface Listener {
        /**
         * @param starts syllable start offsets, in UTF-16 units, or null when
         *               the chain reported no segments
         * @param ends   syllable end offsets, in UTF-16 units
         */
        void onComposition(String text, int cursor, int[] starts, int[] ends);

        void onCommit(String text);

        void onCandidates(String[] items);

        void onHideCandidates();

        void onBeep();

        void onNotify(int code);

        /** Called from a plugin thread: post {@link #pump()} to the owner thread. */
        default void onWake() {
        }
    }

    private long core;
    private long session;
    private long generation;
    private Listener listener;
    private String version = "";

    public synchronized boolean start(String pluginDir, String dataDir,
                                      String trustDir, int flags,
                                      Listener listener) {
        if (core != 0) return true;
        this.listener = listener;
        core = nativeStart(pluginDir, dataDir, trustDir, flags);
        if (core == 0) return false;
        version = nativeVersion();
        return true;
    }

    public synchronized void stop() {
        closeSession();
        if (core != 0) {
            nativeStop(core);
            core = 0;
        }
        generation = 0;
        listener = null;
    }

    public boolean isRunning() {
        return core != 0;
    }

    public String version() {
        return version;
    }

    public synchronized int loadPlugin(String packageDir) {
        if (core == 0) return -1;
        return nativePluginLoad(core, packageDir);
    }

    /**
     * Host policy: grants or revokes a capability before plugins are loaded.
     * Capabilities a plugin declares but the host does not grant fail closed
     * with PIME_DENIED once the plugin uses them.
     */
    public synchronized int permission(String capability, boolean granted) {
        if (core == 0 || capability == null) return -1;
        return nativePermission(core, capability, granted);
    }

    /**
     * Builds a generation from chain CBOR without activating it. A generation
     * with an empty plugin list is valid; it simply leaves every key unhandled.
     *
     * @return the generation id, or 0 when it could not be built
     */
    public synchronized long buildGeneration(byte[] config) {
        if (core == 0 || config == null) return 0;
        return nativeBuildGeneration(core, config);
    }

    /** Activates a generation built earlier. @return 0 on success. */
    public synchronized int activateGeneration(long generation) {
        if (core == 0 || generation == 0) return -1;
        final int status = nativeActivateGeneration(core, generation);
        if (status != 0) {
            android.util.Log.w(TAG, "activate generation: " + statusString(status));
            return status;
        }
        this.generation = generation;
        return 0;
    }

    /**
     * Frees a generation that is no longer active. The core refuses to retire
     * the active generation, so callers switch first and retire afterwards.
     */
    public synchronized int retireGeneration(long generation) {
        if (core == 0 || generation == 0) return -1;
        return nativeRetireGeneration(core, generation);
    }

    /** Builds and activates a generation from chain CBOR. */
    public boolean useGeneration(byte[] config) {
        final long built = buildGeneration(config);
        return built != 0 && activateGeneration(built) == 0;
    }

    public synchronized boolean openSession() {
        if (core == 0) return false;
        if (session == 0) session = nativeSessionCreate(core);
        return session != 0;
    }

    public synchronized void closeSession() {
        if (session != 0) {
            nativeSessionDestroy(session);
            session = 0;
        }
    }

    public boolean hasSession() {
        return session != 0;
    }

    /** @return 1 handled, 0 pass through to the editor, negative on error. */
    public synchronized int key(int keycode, int modifiers, int action,
                                String text) {
        if (session == 0) return -1;
        return nativeKey(session, keycode, modifiers, action, text);
    }

    public synchronized int text(String text) {
        if (session == 0) return -1;
        return nativeText(session, text);
    }

    public synchronized int selectCandidate(int index) {
        if (session == 0) return -1;
        return nativeCandidate(session, index);
    }

    public synchronized int context(String appId, int fieldType, int inputType,
                                    String locale, int pageSize, int hostCaps,
                                    int maxCandidates) {
        if (session == 0) return -1;
        return nativeContext(session, appId, fieldType, inputType, locale,
                pageSize, hostCaps, maxCandidates);
    }

    /**
     * Sets how many candidates translate plugins build for the next chain run.
     * 0 restores the plugin's configured maximum. Does not dispatch an event,
     * so toggling the candidate table stays free of an extra chain run.
     */
    public synchronized int setCandidateBudget(int maxCandidates) {
        if (session == 0) return -1;
        return nativeCandidateBudget(session, maxCandidates);
    }

    public synchronized int focus(boolean focused) {
        if (session == 0) return -1;
        return nativeFocus(session, focused);
    }

    public synchronized int secure(boolean secure) {
        if (session == 0) return -1;
        return nativeSecure(session, secure);
    }

    public synchronized int reset() {
        if (session == 0) return -1;
        return nativeReset(session);
    }

    /** Tells the active chain's recognize plugins to start, stop or cancel voice input. */
    public synchronized int voice(int action) {
        if (session == 0) return -1;
        return nativeVoice(session, action);
    }

    /**
     * Feeds one chunk of 16 kHz mono s16le PCM captured by the host. Safe to
     * call from the audio thread; the core writes it into its capture ring.
     */
    public synchronized int audioIn(byte[] pcm, int length, long timeMs) {
        if (session == 0) return -1;
        return nativeAudioIn(session, pcm, length, timeMs);
    }

    /**
     * Reports the text around the cursor. Predict/association plugins prefer
     * it over their own last-committed-word state, so the host must send it
     * whenever the editor text changes outside the chain (delete, paste).
     */
    public synchronized int surrounding(String before, String after) {
        if (session == 0) return -1;
        return nativeSurrounding(session, before, after);
    }

    public synchronized void pump() {
        if (core != 0) nativePump(core);
    }

    public synchronized void paused(boolean paused) {
        if (core != 0) nativePause(core, paused);
    }

    public String statusString(int status) {
        return nativeStatusString(status);
    }

    /* Called from native on the owner thread. */

    private void onNativeComposition(String text, int cursor, int[] starts,
                                     int[] ends) {
        if (listener != null) listener.onComposition(text, cursor, starts, ends);
    }

    private void onNativeCommit(String text) {
        if (listener != null) listener.onCommit(text);
    }

    private void onNativeCandidates(String[] items) {
        if (listener != null) listener.onCandidates(items);
    }

    private void onNativeHideCandidates() {
        if (listener != null) listener.onHideCandidates();
    }

    private void onNativeBeep() {
        if (listener != null) listener.onBeep();
    }

    private void onNativeNotify(int code) {
        if (listener != null) listener.onNotify(code);
    }

    /* Called from a plugin thread. The listener must post pump() to the
     * owner thread; core input calls are not thread-safe. */
    private void onNativeWake() {
        if (listener != null) listener.onWake();
    }

    /* Core and plugin log records, already level-filtered by core. */
    private void onNativeLog(int level, String tag, String message) {
        switch (level) {
            case 0: /* PIME_LOG_ERROR */
                Log.e(tag, message);
                break;
            case 1: /* PIME_LOG_WARN */
                Log.w(tag, message);
                break;
            case 3: /* PIME_LOG_DEBUG */
                Log.d(tag, message);
                break;
            case 4: /* PIME_LOG_TRACE */
                Log.v(tag, message);
                break;
            default:
                Log.i(tag, message);
                break;
        }
    }

    private native long nativeStart(String pluginDir, String dataDir,
                                    String trustDir, int flags);

    private native void nativeStop(long core);

    private native int nativePluginLoad(long core, String packageDir);

    private native int nativePermission(long core, String capability,
                                        boolean granted);

    private native long nativeBuildGeneration(long core, byte[] config);

    private native int nativeActivateGeneration(long core, long generation);

    private native int nativeRetireGeneration(long core, long generation);

    private native long nativeSessionCreate(long core);

    private native void nativeSessionDestroy(long session);

    private native int nativeKey(long session, int keycode, int modifiers,
                                 int action, String text);

    private native int nativeText(long session, String text);

    private native int nativeCandidate(long session, int index);

    private native int nativeContext(long session, String appId, int fieldType,
                                     int inputType, String locale, int pageSize,
                                     int hostCaps, int maxCandidates);

    private native int nativeCandidateBudget(long session, int maxCandidates);

    private native int nativeFocus(long session, boolean focused);

    private native int nativeSecure(long session, boolean secure);

    private native int nativeReset(long session);

    private native int nativeVoice(long session, int action);

    private native int nativeAudioIn(long session, byte[] pcm, int length,
                                     long timeMs);

    private native int nativeSurrounding(long session, String before,
                                         String after);

    private native void nativePump(long core);

    private native void nativePause(long core, boolean paused);

    private native String nativeStatusString(int status);

    private native String nativeVersion();
}
