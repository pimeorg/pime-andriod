package com.pimeorg.pime;

import android.content.Context;
import android.content.Intent;
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
import android.widget.ImageButton;
import android.widget.ImageView;
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

    /** Union of the capabilities declared by the bundled chain plugins. */
    private static final String[] CHAIN_CAPABILITIES = {
            "input.read", "workspace", "output.emit", "asset.read"
    };

    private static final int TOP_BAR_HEIGHT_DP = 44;
    private static final int CANDIDATE_ROW_HEIGHT_DP = 48;
    /** Keys are laid out inside the view; this is everything around the four rows. */
    private static final float ROW_CHROME_DP = 9f + 7f + 8f * 3;

    private PimeCore core;
    private boolean coreReady;
    private KeyboardView keyboard;
    private LinearLayout candidateBar;
    private HorizontalScrollView candidateScroll;
    private TextView compositionView;
    private String composition = "";
    private final List<String> candidates = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        final File files = getFilesDir();
        final File pluginDir = new File(files, "plugins");
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

        final byte[] config = readBytes(new File(chainDir, "default.cbor"));
        if (!core.useGeneration(config != null ? config : EMPTY_GENERATION)) {
            Log.w(TAG, "no active generation; keys stay unhandled");
        }
    }

    @Override
    public void onDestroy() {
        if (core != null) core.stop();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        final KeyboardTheme palette = KeyboardTheme.of(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        /* Any slack the window gives us goes above the bar, so the keys keep
         * their place on screen when the candidate row comes and goes. */
        root.setGravity(Gravity.BOTTOM);
        root.setBackgroundColor(palette.board);

        /* Top bar: settings on the left, the composition string filling the
         * middle, and the collapse key on the right. */
        final LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(palette.bar);

        topBar.addView(iconButton(R.drawable.ic_settings, palette,
                R.string.ime_action_settings, v -> openSettings()));

        compositionView = new TextView(this);
        compositionView.setTextColor(palette.onBar);
        compositionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        compositionView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        compositionView.setGravity(Gravity.CENTER_VERTICAL);
        compositionView.setSingleLine(true);
        compositionView.setEllipsize(TextUtils.TruncateAt.START);
        compositionView.setPadding(dp(8), 0, dp(8), 0);
        topBar.addView(compositionView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        topBar.addView(iconButton(R.drawable.ic_expand_more, palette,
                R.string.ime_action_hide, v -> requestHideSelf(0)));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR_HEIGHT_DP)));

        candidateScroll = new HorizontalScrollView(this);
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setBackgroundColor(palette.bar);
        candidateScroll.setFillViewport(true);
        candidateBar = new LinearLayout(this);
        candidateBar.setOrientation(LinearLayout.HORIZONTAL);
        candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        candidateScroll.addView(candidateBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        candidateScroll.setVisibility(View.GONE);
        root.addView(candidateScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CANDIDATE_ROW_HEIGHT_DP)));

        final View divider = new View(this);
        divider.setBackgroundColor(palette.divider);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        final int keyboardHeight = keyboardHeight(palette);
        keyboard = new KeyboardView(this);
        keyboard.setKeyboardTheme(palette);
        keyboard.setListener(this);
        root.addView(keyboard, new LinearLayout.LayoutParams(
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

    private ImageButton iconButton(int iconRes, KeyboardTheme palette, int descRes,
                                   View.OnClickListener action) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(palette.hint);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getString(descRes));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(ripple(null, palette));
        button.setOnClickListener(action);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return button;
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
        if (keyboard == null) return;
        setInputView(onCreateInputView());
        compositionView.setText(composition);
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
        hideCandidates();
        /* The composition view owns the fill weight, so it stays in the layout
         * even when there is nothing to show; dropping it (GONE) would let the
         * two action buttons collapse to the left and the collapse key would
         * no longer sit at the far right. */
        if (compositionView != null) compositionView.setText("");
        if (!coreReady) return;

        if (!core.hasSession() && !core.openSession()) {
            Log.w(TAG, "session create failed");
            return;
        }
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
        hideCandidates();
        composition = "";
        if (compositionView != null) compositionView.setText("");
        super.onFinishInput();
    }

    @Override
    public void onKey(KeyboardView.Key key) {
        final boolean shifted = keyboard != null && keyboard.isShiftDown();
        final String text = key.kind == KeyboardView.KIND_CHAR
                || key.kind == KeyboardView.KIND_SPACE
                ? String.valueOf(textFor(key, shifted)) : null;

        if (coreReady) {
            final int status = core.key(key.code,
                    shifted ? PimeCore.MOD_SHIFT : 0, PimeCore.KEY_DOWN, text);
            core.pump();
            if (status == 1) return;
            if (status < 0) {
                Log.w(TAG, "key " + key.code + ": "
                        + core.statusString(-status));
            }
        }
        passThrough(key, text);
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
    public void onComposition(String text, int cursor) {
        composition = text == null ? "" : text;
        if (compositionView == null) return;
        compositionView.setText(text);
        /* Keep the view in the layout with an empty string so the fill weight it
         * carries still pushes the action buttons to the right edge; visibility
         * toggling would let the buttons jump left. */
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
        final KeyboardTheme palette = KeyboardTheme.of(this);
        final int limit = Prefs.candidates(this);
        final int count = limit > 0 ? Math.min(limit, items.length) : items.length;
        for (int i = 0; i < count; ++i) {
            candidateBar.addView(candidateItem(items[i], i, palette));
        }
        candidateScroll.setVisibility(View.VISIBLE);
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
