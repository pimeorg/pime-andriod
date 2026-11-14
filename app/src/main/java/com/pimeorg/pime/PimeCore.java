package com.pimeorg.pime;

/**
 * Thin JNI face of pime-core. Holds no input-method logic: it forwards host
 * events into the kernel and the kernel's sink commands back to a listener.
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
    public static final int HOST_CANDIDATES = 1 << 4;
    public static final int HOST_TOUCH = 1 << 5;

    public interface Listener {
        void onComposition(String text, int cursor);

        void onCommit(String text);

        void onCandidates(String[] items);

        void onHideCandidates();

        void onBeep();

        void onNotify(int code);
    }

    private long core;
    private long session;
    private long generation;
    private Listener listener;
    private String version = "";

    public boolean start(String pluginDir, String dataDir, String trustDir,
                         int flags, Listener listener) {
        if (core != 0) return true;
        this.listener = listener;
        core = nativeStart(pluginDir, dataDir, trustDir, flags);
        if (core == 0) return false;
        version = nativeVersion();
        return true;
    }

    public void stop() {
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

    public int loadPlugin(String packageDir) {
        if (core == 0) return -1;
        return nativePluginLoad(core, packageDir);
    }

    /**
     * Host policy: grants or revokes a capability before plugins are loaded.
     * Capabilities a plugin declares but the host does not grant fail closed
     * with PIME_DENIED once the plugin uses them.
     */
    public int permission(String capability, boolean granted) {
        if (core == 0 || capability == null) return -1;
        return nativePermission(core, capability, granted);
    }

    /**
     * Builds and activates a generation from chain CBOR. A generation with an
     * empty plugin list is valid; it simply leaves every key unhandled.
     */
    public boolean useGeneration(byte[] config) {
        if (core == 0 || config == null) return false;
        long built = nativeBuildGeneration(core, config);
        if (built == 0) return false;
        int status = nativeActivateGeneration(core, built);
        if (status != 0) {
            android.util.Log.w(TAG, "activate generation: " + statusString(status));
            return false;
        }
        generation = built;
        return true;
    }

    public boolean openSession() {
        if (core == 0) return false;
        if (session == 0) session = nativeSessionCreate(core);
        return session != 0;
    }

    public void closeSession() {
        if (session != 0) {
            nativeSessionDestroy(session);
            session = 0;
        }
    }

    public boolean hasSession() {
        return session != 0;
    }

    /** @return 1 handled, 0 pass through to the editor, negative on error. */
    public int key(int keycode, int modifiers, int action, String text) {
        if (session == 0) return -1;
        return nativeKey(session, keycode, modifiers, action, text);
    }

    public int text(String text) {
        if (session == 0) return -1;
        return nativeText(session, text);
    }

    public int selectCandidate(int index) {
        if (session == 0) return -1;
        return nativeCandidate(session, index);
    }

    public int context(String appId, int fieldType, int inputType, String locale,
                       int pageSize, int hostCaps) {
        if (session == 0) return -1;
        return nativeContext(session, appId, fieldType, inputType, locale,
                pageSize, hostCaps);
    }

    public int focus(boolean focused) {
        if (session == 0) return -1;
        return nativeFocus(session, focused);
    }

    public int secure(boolean secure) {
        if (session == 0) return -1;
        return nativeSecure(session, secure);
    }

    public int reset() {
        if (session == 0) return -1;
        return nativeReset(session);
    }

    public void pump() {
        if (core != 0) nativePump(core);
    }

    public void paused(boolean paused) {
        if (core != 0) nativePause(core, paused);
    }

    public String statusString(int status) {
        return nativeStatusString(status);
    }

    /* Called from native on the owner thread. */

    private void onNativeComposition(String text, int cursor) {
        if (listener != null) listener.onComposition(text, cursor);
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

    private native long nativeStart(String pluginDir, String dataDir,
                                    String trustDir, int flags);

    private native void nativeStop(long core);

    private native int nativePluginLoad(long core, String packageDir);

    private native int nativePermission(long core, String capability,
                                        boolean granted);

    private native long nativeBuildGeneration(long core, byte[] config);

    private native int nativeActivateGeneration(long core, long generation);

    private native long nativeSessionCreate(long core);

    private native void nativeSessionDestroy(long session);

    private native int nativeKey(long session, int keycode, int modifiers,
                                 int action, String text);

    private native int nativeText(long session, String text);

    private native int nativeCandidate(long session, int index);

    private native int nativeContext(long session, String appId, int fieldType,
                                     int inputType, String locale, int pageSize,
                                     int hostCaps);

    private native int nativeFocus(long session, boolean focused);

    private native int nativeSecure(long session, boolean secure);

    private native int nativeReset(long session);

    private native void nativePump(long core);

    private native void nativePause(long core, boolean paused);

    private native String nativeStatusString(int status);

    private native String nativeVersion();
}
