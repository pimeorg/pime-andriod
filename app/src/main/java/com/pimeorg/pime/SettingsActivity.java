package com.pimeorg.pime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Launcher entry: enable the IME, pick it, and show where plugins live. */
public final class SettingsActivity extends AppCompatActivity {
    private interface Page {
        void build(LinearLayout parent);
    }

    private MaterialToolbar toolbar;
    private View homeScroll;
    private View detailScroll;
    private LinearLayout detailContent;
    private TextView heroStatus;
    private LinearLayout heroSteps;
    private android.widget.EditText testInput;
    private com.google.android.material.textfield.TextInputLayout testInputLayout;
    private boolean pimeIsCurrent;
    private boolean warnedAboutOtherIme;
    private boolean inDetail;
    private Boolean lastEnabled;
    private Boolean lastCurrent;
    private Page detailPage;
    private int detailTitleRes;
    private ActivityResultLauncher<String[]> importChainLauncher;
    private ActivityResultLauncher<String[]> importPluginLauncher;
    private ActivityResultLauncher<Uri> importFolderLauncher;
    /** Copies imported packages off the main thread; a package can carry a model. */
    private final java.util.concurrent.ExecutorService importExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Plugin whose settings panel is open on the chain page; null when none. */
    private String expandedPluginId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Edge-to-edge turns off the window's own resize, so the input method
            // inset has to be applied by hand or the keyboard covers the field.
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        testInput = findViewById(R.id.test_input);
        testInputLayout = findViewById(R.id.test_input_layout);
        // An EditText swallows taps itself, so an OnClickListener never fires;
        // the focus change is what a tap actually produces.
        testInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) showKeyboard(view);
        });

        importChainLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), result -> {
                    if (result != null) importChain(result);
                });
        importPluginLauncher = registerForActivityResult(
                new OpenDocumentInDownloads(), result -> {
                    if (result != null) importPlugin(result);
                });
        importFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), result -> {
                    if (result != null) importPlugin(result);
                });

        // Wide screens (tablets, landscape) keep a 640dp reading column, matching
        // the reference settings app.
        final int side = Math.max(0, (getResources().getConfiguration().screenWidthDp - 640) / 2);
        final View content = findViewById(R.id.content);
        content.setPadding(dp(side), content.getPaddingTop(), dp(side), content.getPaddingBottom());
        detailContent = findViewById(R.id.detail_content);
        detailContent.setPadding(dp(side), detailContent.getPaddingTop(), dp(side),
                detailContent.getPaddingBottom());

        toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_home);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                openAbout();
                return true;
            }
            return false;
        });

        homeScroll = findViewById(R.id.home_scroll);
        detailScroll = findViewById(R.id.detail_scroll);
        heroStatus = findViewById(R.id.hero_status);
        heroSteps = findViewById(R.id.hero_steps);

        buildHomeRows();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (inDetail) {
                    leaveDetail();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Switching the input method from the picker dialog never pauses the
        // activity, so onResume alone would leave the status stale.
        if (hasFocus) refreshStatus();
    }

    @Override
    protected void onDestroy() {
        importExecutor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ home

    private void buildHomeRows() {
        final LinearLayout keyboard = findViewById(R.id.keyboard_rows);
        addRow(keyboard, R.drawable.ic_keyboard, getString(R.string.row_ime),
                getString(R.string.row_ime_desc), R.drawable.ic_chevron_right,
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_tune, getString(R.string.row_settings),
                getString(R.string.row_settings_desc), R.drawable.ic_chevron_right,
                v -> openSettings());
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_extension, getString(R.string.row_plugins),
                pluginSummary(), R.drawable.ic_chevron_right, v -> openPlugins());
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_folder, getString(R.string.row_storage),
                "files/plugins · files/chains", R.drawable.ic_chevron_right, v -> openStorage());

        final LinearLayout about = findViewById(R.id.about_rows);
        addRow(about, R.drawable.ic_info, getString(R.string.row_about),
                BuildConfig.VERSION_NAME, R.drawable.ic_chevron_right, v -> openAbout());
        addDivider(about);
        addRow(about, R.drawable.ic_memory, getString(R.string.row_core),
                getString(R.string.row_core_desc), 0, null);
    }

    private void refreshStatus() {
        // Do not read Settings.Secure.ENABLED_INPUT_METHODS: Android 15 only
        // allows that to apps targeting API 33 or lower. The InputMethodManager
        // list is the public, targetSdk-independent equivalent.
        final InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        final boolean enabled = isInputMethodEnabled(imm);
        final boolean current = enabled && isCurrentInputMethod(imm);

        final int color;
        if (current) {
            heroStatus.setText(R.string.status_pill_ready);
            color = ContextCompat.getColor(this, R.color.success);
        } else if (enabled) {
            heroStatus.setText(R.string.status_pill_enabled);
            color = ContextCompat.getColor(this, R.color.warning);
        } else {
            heroStatus.setText(R.string.status_pill_disabled);
            color = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        }
        heroStatus.setTextColor(color);
        heroStatus.setBackground(pill(color));

        // The test field types through whichever input method is current, so say
        // so plainly instead of letting it look broken.
        pimeIsCurrent = current;
        testInputLayout.setHelperText(current
                ? getString(R.string.hero_test_helper)
                : getString(R.string.hero_test_helper_other_ime));

        buildSteps(imm, enabled, current);

        // Confirm the change when the user comes back from the system pages.
        if (lastEnabled != null && lastEnabled != enabled) {
            snack(enabled ? R.string.snack_enabled : R.string.snack_disabled);
        } else if (lastCurrent != null && !lastCurrent && current) {
            snack(R.string.snack_default);
        }
        lastEnabled = enabled;
        lastCurrent = current;
    }

    /**
     * Tapping the field usually shows the input method on its own; asking
     * explicitly is what makes it work when the system decides otherwise (a
     * hardware keyboard being attached, for one).
     */
    private void showKeyboard(View view) {
        view.requestFocus();
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        // Typing here can only ever go through the current input method, so once
        // per visit offer to switch to PIME instead of quietly doing nothing for
        // anyone who has not set it as the default yet.
        if (!pimeIsCurrent && !warnedAboutOtherIme) {
            warnedAboutOtherIme = true;
            Snackbar.make(findViewById(R.id.root), R.string.hero_test_other_ime_snack,
                            Snackbar.LENGTH_LONG)
                    .setAction(R.string.step2_action, v -> imm.showInputMethodPicker())
                    .show();
        }
    }

    private void snack(int textRes) {
        Snackbar.make(findViewById(R.id.root), textRes, Snackbar.LENGTH_SHORT).show();
    }

    private void snack(CharSequence message) {
        Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_SHORT).show();
    }

    private void buildSteps(InputMethodManager imm, boolean enabled, boolean current) {
        heroSteps.removeAllViews();
        if (enabled && current) {
            heroSteps.setVisibility(View.GONE);
            return;
        }
        heroSteps.setVisibility(View.VISIBLE);
        if (!enabled) {
            addStep(heroSteps, 1, getString(R.string.step1_title), getString(R.string.step1_desc),
                    getString(R.string.step1_note), false, true, getString(R.string.step1_action),
                    v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        } else {
            addStep(heroSteps, 1, getString(R.string.step1_title), getString(R.string.step1_desc),
                    null, true, false, null, null);
            addStep(heroSteps, 2, getString(R.string.step2_title), getString(R.string.step2_desc),
                    null, false, true, getString(R.string.step2_action),
                    v -> imm.showInputMethodPicker());
        }
    }

    private boolean isInputMethodEnabled(InputMethodManager imm) {
        if (imm == null) return false;
        final String self = getPackageName();
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (self.equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean isCurrentInputMethod(InputMethodManager imm) {
        if (imm != null && Build.VERSION.SDK_INT >= 34) {
            /* Ask the framework first, but do not trust a negative on its own:
             * on some builds getCurrentInputMethodInfo() still reports the
             * previously bound method after a switch, and the settings page
             * would claim "not switched" while the keyboard is already ours. */
            final InputMethodInfo info = imm.getCurrentInputMethodInfo();
            if (info != null && getPackageName().equals(info.getPackageName())) {
                return true;
            }
        }
        final String current = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName() + "/");
    }

    private CharSequence pluginSummary() {
        final int count = pluginDirs().length;
        if (count == 0) return getString(R.string.no_plugins);
        return getString(R.string.plugins_summary, count,
                ChainStore.displayName(ChainStore.activeChain(this)));
    }

    private File[] pluginDirs() {
        final File dir = new File(getFilesDir(), "plugins");
        final File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs == null) return new File[0];
        // Only complete packages are loadable; older layouts kept the manifest
        // under a version subdirectory and are not part of the active set.
        final List<File> packages = new ArrayList<>();
        for (File candidate : dirs) {
            if (new File(candidate, "manifest.yaml").isFile()
                    && new File(candidate, "plugin.wasm").isFile()) {
                packages.add(candidate);
            }
        }
        final File[] result = packages.toArray(new File[0]);
        Arrays.sort(result);
        return result;
    }

    /** Reads id/version/role/description from a plugin manifest; display only. */
    private String[] manifestInfo(File dir) {
        String id = null;
        String version = null;
        String role = null;
        String description = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(dir, "manifest.yaml")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final int colon = line.indexOf(':');
                if (colon <= 0) continue;
                final String key = line.substring(0, colon).trim();
                final String value = line.substring(colon + 1).trim();
                if (key.equals("id")) id = value;
                else if (key.equals("version")) version = value;
                else if (key.equals("role")) role = value;
                else if (key.equals("description")) description = unquote(value);
            }
        } catch (IOException ignored) {
            // Falls back to the directory name below.
        }
        return new String[]{id != null ? id : dir.getName(), version,
                role != null ? role : getString(R.string.role_unknown),
                description != null ? description : ""};
    }

    /** Strips one pair of surrounding YAML quotes, matching PluginStore. */
    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ------------------------------------------------------------------ detail pages

    private void openAbout() {
        enterDetail(R.string.page_about, parent -> {
            final MaterialCardView brand = new MaterialCardView(this);
            brand.setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorPrimaryContainer));
            brand.setRadius(dp(28));
            brand.setCardElevation(0f);
            brand.setStrokeWidth(0);
            final LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brandParams.setMargins(dp(16), dp(4), dp(16), 0);

            final LinearLayout brandRow = new LinearLayout(this);
            brandRow.setOrientation(LinearLayout.HORIZONTAL);
            brandRow.setGravity(Gravity.CENTER_VERTICAL);
            brandRow.setPadding(dp(20), dp(20), dp(20), dp(20));

            final ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.pime_logo);
            logo.setBackgroundResource(R.drawable.bg_logo_circle);
            logo.setPadding(dp(10), dp(10), dp(10), dp(10));
            logo.setImageTintList(ColorStateList.valueOf(
                    attrColor(com.google.android.material.R.attr.colorOnPrimary)));
            brandRow.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

            final LinearLayout brandLines = new LinearLayout(this);
            brandLines.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout.LayoutParams lineParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lineParams.setMarginStart(dp(14));
            brandRow.addView(brandLines, lineParams);
            brandLines.addView(text(getString(R.string.hero_title), 22, true,
                    attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer)));
            brandLines.addView(text(getString(R.string.hero_tagline), 13, false,
                    attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer)));

            brand.addView(brandRow);
            parent.addView(brand, brandParams);

            addGroupTitle(parent, R.string.section_version);
            final LinearLayout versionCard = addCard(parent);
            addRow(versionCard, R.drawable.ic_info, getString(R.string.row_version),
                    BuildConfig.VERSION_NAME + (BuildConfig.DEBUG ? "（debug）" : ""), 0, null);
            addDivider(versionCard);
            addRow(versionCard, R.drawable.ic_extension, getString(R.string.row_plugins_count),
                    getString(R.string.plugins_count_value, pluginDirs().length), 0, null);
            addDivider(versionCard);
            addRow(versionCard, R.drawable.ic_memory, getString(R.string.row_core),
                    getString(R.string.row_core_desc), 0, null);

            addGroupTitle(parent, R.string.section_notes);
            final LinearLayout notesCard = addCard(parent);
            addTextBlock(notesCard, getString(R.string.about_intro));
            addDivider(notesCard, false);
            addTextBlock(notesCard, getString(R.string.about_data));
            addDivider(notesCard, false);
            addTextBlock(notesCard, getString(R.string.about_handwriting));
            addDivider(notesCard, false);
            addTextBlock(notesCard, getString(R.string.about_license));
        });
    }

    private void openPlugins() {
        expandedPluginId = null;
        // Every read is guarded inside, so a corrupt chain file shows a warning
        // row instead of replacing the whole page with an error.
        enterDetail(R.string.page_plugins, this::buildPluginsPage);
    }

    /**
     * One row per plugin, in processing order, with an inline switch and reorder
     * arrows. Disabling a plugin moves it to the "not enabled" card rather than
     * hiding it, so the same plugin never appears twice.
     */
    private void buildPluginsPage(LinearLayout parent) {
        final File active = ChainStore.activeChain(this);
        final String activeName = ChainStore.activeChainName(this);
        final List<ChainStore.PluginEntry> chainPlugins = readChainSafe(active);
        final File[] installed = pluginDirs();
        final Set<String> installedIds = new HashSet<>();
        final Map<String, String[]> infoById = new LinkedHashMap<>();
        for (File dir : installed) {
            final String[] info = manifestInfo(dir);
            installedIds.add(info[0]);
            infoById.put(info[0], info);
        }

        /* --- chain plans --- */
        addGroupTitle(parent, R.string.section_chains);
        final LinearLayout chainCard = addCard(parent);
        final List<File> chains = ChainStore.listChains(this);
        for (File chain : chains) {
            /* The English chain is bound to the 中/英 key, not a user plan. */
            if (chain.getName().equals(ChainStore.ENGLISH_CHAIN)) continue;
            final boolean isActive = chain.getName().equals(activeName);
            final int count = isActive ? chainPlugins.size() : readChainSafe(chain).size();
            addChainRow(chainCard, chain, isActive, count, v -> switchChain(chain));
            addDivider(chainCard);
        }
        addRow(chainCard, R.drawable.ic_add, getString(R.string.action_new_chain),
                getString(R.string.action_new_chain_desc), 0, v -> newChainDialog());
        addDivider(chainCard);
        addRow(chainCard, R.drawable.ic_folder, getString(R.string.action_import_chain),
                getString(R.string.action_import_chain_desc), 0,
                v -> importChainLauncher.launch(new String[]{"*/*"}));

        /* --- processing order of the active chain --- */
        addGroupTitle(parent, getString(R.string.section_chain_order) + " · "
                + ChainStore.displayName(active));
        final LinearLayout orderCard = addCard(parent);
        if (chainPlugins.isEmpty()) {
            addTextBlock(orderCard, getString(R.string.chain_empty_hint));
        }
        for (int i = 0; i < chainPlugins.size(); i++) {
            final ChainStore.PluginEntry entry = chainPlugins.get(i);
            final String[] info = infoById.get(entry.id);
            addChainPluginRow(orderCard, active, entry, i, chainPlugins.size(),
                    installedIds.contains(entry.id), info != null ? info[3] : "");
            if (i != chainPlugins.size() - 1) addDivider(orderCard);
        }

        /* --- installed but not in the active chain --- */
        final List<File> disabled = new ArrayList<>();
        for (File dir : installed) {
            final String id = manifestInfo(dir)[0];
            boolean inChain = false;
            for (ChainStore.PluginEntry entry : chainPlugins) {
                if (entry.id.equals(id)) { inChain = true; break; }
            }
            if (!inChain) disabled.add(dir);
        }
        if (!disabled.isEmpty()) {
            addGroupTitle(parent, getString(R.string.section_disabled_plugins) + " · "
                    + getString(R.string.plugins_count_value, disabled.size()));
            final LinearLayout disabledCard = addCard(parent);
            for (int i = 0; i < disabled.size(); i++) {
                final String[] info = manifestInfo(disabled.get(i));
                addDisabledPluginRow(disabledCard, active, info[0], info[2], info[1], info[3]);
                if (i != disabled.size() - 1) addDivider(disabledCard);
            }
        }

        /* --- installing more packages --- */
        addGroupTitle(parent, getString(R.string.section_packages) + " · "
                + getString(R.string.plugins_count_value, installed.length));
        final LinearLayout packageCard = addCard(parent);
        addRow(packageCard, R.drawable.ic_add, getString(R.string.action_import_plugin),
                getString(R.string.action_import_plugin_desc), 0,
                v -> importPluginLauncher.launch(new String[]{"*/*"}));
        addDivider(packageCard);
        addRow(packageCard, R.drawable.ic_folder, getString(R.string.action_import_folder),
                getString(R.string.action_import_folder_desc), 0,
                v -> importFolderLauncher.launch(downloadsUri()));

        addTextBlock(parent, getString(R.string.plugin_section_note));
        addTextBlock(parent, getString(R.string.plugins_note));
    }

    /** Reads a chain, treating an unreadable file as an empty one. */
    private List<ChainStore.PluginEntry> readChainSafe(File chain) {
        try {
            return ChainStore.readChain(this, chain);
        } catch (IOException error) {
            return new ArrayList<>();
        }
    }

    /**
     * One plugin in the active chain: order number, friendly name, reorder
     * arrows, an enable switch, and a settings panel that opens on tap.
     */
    private void addChainPluginRow(LinearLayout parent, File chain,
                                   ChainStore.PluginEntry entry, int index, int count,
                                   boolean installed, String description) {
        final boolean expanded = entry.id.equals(expandedPluginId);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(8), dp(8), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        final TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> toggleExpanded(entry.id));

        // The disclosure caret leads the row: next to the reorder arrows it read
        // as a third arrow.
        final ImageView caret = new ImageView(this);
        caret.setImageResource(R.drawable.ic_expand_more);
        caret.setImageTintList(ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        caret.setContentDescription(getString(R.string.expand_plugin_settings));
        caret.setScaleY(expanded ? -1f : 1f);
        final LinearLayout.LayoutParams caretParams =
                new LinearLayout.LayoutParams(dp(20), dp(20));
        caretParams.setMarginEnd(dp(10));
        row.addView(caret, caretParams);

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(lines, lineParams);

        // "3 · 拼音候选" — the step number carries the pipeline order.
        final SpannableStringBuilder title = new SpannableStringBuilder();
        title.append(String.valueOf(index + 1));
        title.setSpan(new ForegroundColorSpan(
                        attrColor(com.google.android.material.R.attr.colorPrimary)),
                0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setSpan(new TypefaceSpan("sans-serif-medium"), 0, title.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.append("  ·  ").append(pluginLabel(entry.id));
        final TextView titleView = text(title, 15, false,
                attrColor(com.google.android.material.R.attr.colorOnSurface));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(titleView);

        final StringBuilder subtitle = new StringBuilder();
        if (!entry.role.isEmpty()) subtitle.append(entry.role);
        if (!entry.version.isEmpty()) {
            if (subtitle.length() > 0) subtitle.append(" · ");
            subtitle.append(entry.version);
        }
        final TextView sub = text(subtitle, 12, false, installed
                ? attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                : ContextCompat.getColor(this, R.color.warning));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        if (!installed) sub.setText(getString(R.string.plugin_missing, subtitle));
        lines.addView(sub);

        if (installed && !description.isEmpty()) {
            final TextView desc = text(description, 12, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            desc.setMaxLines(2);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setPadding(0, dp(2), 0, 0);
            lines.addView(desc);
        }
        row.setContentDescription(installed && !description.isEmpty()
                ? getString(R.string.plugin_row_desc_described,
                        pluginLabel(entry.id), subtitle, description, index + 1)
                : getString(R.string.plugin_row_desc,
                        pluginLabel(entry.id), subtitle, index + 1));

        final ImageView up = arrowButton(R.drawable.ic_arrow_up,
                getString(R.string.action_move_up_named, pluginLabel(entry.id)), index != 0,
                () -> movePlugin(chain, entry, -1));
        final ImageView down = arrowButton(R.drawable.ic_arrow_down,
                getString(R.string.action_move_down_named, pluginLabel(entry.id)),
                index != count - 1, () -> movePlugin(chain, entry, 1));
        row.addView(up, new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(down, new LinearLayout.LayoutParams(dp(36), dp(36)));

        final MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(true);
        // The switch announces its own state; the description only names it.
        toggle.setContentDescription(pluginLabel(entry.id));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) togglePlugin(chain, entry.id, entry.role, entry.version, false);
        });
        final LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMarginStart(dp(4));
        row.addView(toggle, toggleParams);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (expanded) addPluginPanel(parent, chain, entry, installed, description);
    }

    /** The expanded part of a plugin row: identity plus its chain settings. */
    private void addPluginPanel(LinearLayout parent, File chain, ChainStore.PluginEntry entry,
                                boolean installed, String description) {
        addDivider(parent);
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(attrColor(
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));

        if (installed && !description.isEmpty()) {
            final TextView desc = text(description, 13, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurface));
            desc.setPadding(0, 0, 0, dp(2));
            panel.addView(desc);
        }
        panel.addView(text(entry.id, 12, false,
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));

        if (!installed) {
            panel.addView(text(getString(R.string.plugin_missing_note), 12, false,
                    ContextCompat.getColor(this, R.color.warning)));
        } else if (entry.settings.isEmpty()) {
            final TextView empty = text(getString(R.string.plugin_no_settings), 13, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            empty.setPadding(0, dp(6), 0, 0);
            panel.addView(empty);
        } else {
            for (Map.Entry<String, Object> setting : entry.settings.entrySet()) {
                addPanelSettingRow(panel, chain, entry, setting.getKey(), setting.getValue());
            }
        }
        parent.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A compact settings row inside an expanded plugin panel. */
    private void addPanelSettingRow(LinearLayout parent, File chain,
                                    ChainStore.PluginEntry entry, String key, Object value) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lines.setPadding(0, dp(8), dp(8), dp(8));
        row.addView(lines, lineParams);
        lines.addView(text(settingLabel(key), 14, false,
                attrColor(com.google.android.material.R.attr.colorOnSurface)));

        if (value instanceof Boolean) {
            lines.addView(text(getString((Boolean) value
                            ? R.string.settings_bool_true : R.string.settings_bool_false),
                    12, false, attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            final MaterialSwitch toggle = new MaterialSwitch(this);
            toggle.setChecked((Boolean) value);
            toggle.setContentDescription(settingLabel(key));
            toggle.setOnCheckedChangeListener((button, checked) ->
                    setSetting(chain, entry.id, key, checked));
            row.addView(toggle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            final int number = (int) ((Number) value).longValue();
            lines.addView(text(getString(R.string.settings_int_value, number), 12, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            row.setClickable(true);
            row.setFocusable(true);
            final TypedValue ripple = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> editIntSetting(chain, entry, key, number));
            final ImageView edit = new ImageView(this);
            edit.setImageResource(R.drawable.ic_tune);
            edit.setImageTintList(ColorStateList.valueOf(
                    attrColor(com.google.android.material.R.attr.colorPrimary)));
            edit.setContentDescription(settingLabel(key));
            row.addView(edit, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** An installed plugin that is not part of the active chain; the switch adds it. */
    private void addDisabledPluginRow(LinearLayout parent, File chain, String id, String role,
                                      String version, String description) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        // Start where the chain rows' text starts (caret + gap), so both cards
        // line up.
        row.setPadding(dp(46), dp(8), dp(16), dp(8));

        final MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(false);
        toggle.setContentDescription(pluginLabel(id));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) togglePlugin(chain, id, role, version, true);
        });
        row.setOnClickListener(v -> toggle.toggle());
        row.setFocusable(true);
        final TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(lines, lineParams);
        lines.addView(text(pluginLabel(id), 15, false,
                attrColor(com.google.android.material.R.attr.colorOnSurface)));

        final StringBuilder subtitle = new StringBuilder();
        if (role != null && !role.isEmpty()) subtitle.append(role);
        if (version != null && !version.isEmpty()) {
            if (subtitle.length() > 0) subtitle.append(" · ");
            subtitle.append(version);
        }
        final TextView sub = text(subtitle, 12, false,
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(sub);

        if (description != null && !description.isEmpty()) {
            final TextView desc = text(description, 12, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            desc.setMaxLines(2);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setPadding(0, dp(2), 0, 0);
            lines.addView(desc);
        }

        final LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMarginStart(dp(8));
        row.addView(toggle, toggleParams);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void toggleExpanded(String id) {
        expandedPluginId = id.equals(expandedPluginId) ? null : id;
        rebuildDetail();
    }

    private ImageView arrowButton(int iconRes, String contentDesc, boolean enabled,
                                  Runnable onClick) {
        final ImageView view = new ImageView(this);
        view.setImageResource(iconRes);
        view.setImageTintList(ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        view.setContentDescription(contentDesc);
        view.setClickable(true);
        view.setFocusable(true);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.35f);
        final TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        view.setOnClickListener(v -> onClick.run());
        return view;
    }

    private void movePlugin(File chain, ChainStore.PluginEntry entry, int direction) {
        try {
            ChainStore.movePlugin(this, chain, entry.id, direction);
            snack(getString(R.string.plugin_order_changed, pluginLabel(entry.id)));
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
        }
        broadcastRebuild();
        rebuildDetail();
    }

    private void switchChain(File chain) {
        ChainStore.setActiveChain(this, chain.getName());
        expandedPluginId = null;
        broadcastRebuild();
        snack(getString(R.string.chain_switched, ChainStore.displayName(chain)));
        rebuildDetail();
    }

    private void showChainMenu(View anchor, File chain, boolean isActive) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.action_rename_chain);
        final android.view.MenuItem delete =
                menu.getMenu().add(0, 2, 1, R.string.action_delete_chain);
        delete.setEnabled(!isActive);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                renameChainDialog(chain);
                return true;
            }
            if (item.getItemId() == 2) {
                deleteChain(chain);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void renameChainDialog(File chain) {
        final String current = ChainStore.displayName(chain);
        promptForName(R.string.action_rename_chain, current, getString(R.string.chain_name_hint),
                name -> {
                    if (name.isEmpty()) return getString(R.string.chain_name_invalid);
                    if (ChainStore.isReservedName(name)) {
                        return getString(R.string.chain_name_reserved);
                    }
                    if (!name.equals(current) && ChainStore.chainFile(this, name).exists()) {
                        return getString(R.string.new_chain_exists);
                    }
                    return null;
                },
                name -> {
                    if (name.equals(current)) return;
                    try {
                        ChainStore.renameChain(this, chain, name);
                        snack(getString(R.string.chain_renamed, name));
                        broadcastRebuild();
                    } catch (IOException error) {
                        snack(getString(R.string.plugin_setting_missing));
                    }
                    rebuildDetail();
                });
    }

    /**
     * A one-field prompt. Validation errors are shown on the field itself: a
     * Snackbar would be hidden behind the dialog window.
     */
    private void promptForName(int titleRes, String initial, String hint,
                               java.util.function.Function<String, CharSequence> validate,
                               java.util.function.Consumer<String> onAccept) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(initial);
        input.setSelection(input.getText().length());
        final TextInputLayout box = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        box.setHint(hint);
        box.addView(input);
        final android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(box);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(holder)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            focusAndShowKeyboard(dialog, input);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String value = input.getText().toString().trim();
                final CharSequence error = validate.apply(value);
                if (error != null) {
                    box.setError(error);
                    return;
                }
                dialog.dismiss();
                onAccept.accept(value);
            });
        });
        dialog.show();
    }

    /**
     * Puts the caret in the field and asks for the keyboard: an alert dialog
     * with a text field otherwise opens with the buttons focused, so the first
     * thing a user types goes nowhere.
     */
    private void focusAndShowKeyboard(AlertDialog dialog, android.widget.EditText input) {
        input.requestFocus();
        input.setSelection(0, input.getText().length());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void deleteChain(File chain) {
        final String name = ChainStore.displayName(chain);
        if (chain.getName().equals(ChainStore.activeChainName(this))) {
            snack(getString(R.string.chain_in_use_note));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete_chain)
                .setMessage(getString(R.string.chain_delete_confirm, name))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (chain.delete()) {
                        snack(getString(R.string.chain_deleted, name));
                    } else {
                        snack(getString(R.string.plugin_setting_missing));
                    }
                    broadcastRebuild();
                    rebuildDetail();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Adds or removes a plugin, remembering the chain as it was so the Snackbar
     * can put it back — reordering is a whole-file rewrite either way.
     */
    private void togglePlugin(File chain, String id, String role, String version,
                              boolean enabled) {
        final List<ChainStore.PluginEntry> before = readChainSafe(chain);
        try {
            if (enabled) {
                ChainStore.addPlugin(this, chain, new ChainStore.PluginEntry(id, role, version));
            } else {
                ChainStore.removePlugin(this, chain, id);
            }
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
            rebuildDetail();
            return;
        }
        broadcastRebuild();
        Snackbar.make(findViewById(R.id.root),
                        getString(enabled ? R.string.plugin_on : R.string.plugin_off,
                                pluginLabel(id)), Snackbar.LENGTH_LONG)
                .setAction(R.string.snack_undo, v -> restoreChain(chain, before))
                .show();
        rebuildDetail();
    }

    private void restoreChain(File chain, List<ChainStore.PluginEntry> entries) {
        try {
            ChainStore.writeChain(this, chain, entries);
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
        }
        broadcastRebuild();
        rebuildDetail();
    }

    private void setSetting(File chain, String pluginId, String key, Object value) {
        try {
            ChainStore.setPluginSetting(this, chain, pluginId, key, value);
            snack(getString(R.string.plugin_setting_changed, settingLabel(key)));
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
        }
        broadcastRebuild();
        rebuildDetail();
    }

    /** Free-form number entry; the previous ±1 choices were too coarse. */
    private void editIntSetting(File chain, ChainStore.PluginEntry entry, String key, int current) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelection(input.getText().length());
        final TextInputLayout box = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        box.setHint(getString(R.string.settings_int_hint));
        box.addView(input);
        final android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(box);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.settings_int_title, pluginLabel(entry.id),
                        settingLabel(key)))
                .setView(holder)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            focusAndShowKeyboard(dialog, input);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                final int value;
                try {
                    value = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException error) {
                    box.setError(getString(R.string.settings_int_invalid));
                    return;
                }
                if (value < 0) {
                    box.setError(getString(R.string.settings_int_invalid));
                    return;
                }
                dialog.dismiss();
                setSetting(chain, entry.id, key, value);
            });
        });
        dialog.show();
    }

    /** Friendly name for a known plugin id; unknown ids stay as they are. */
    private CharSequence pluginLabel(String id) {
        switch (id) {
            case "pime-keymap-default":
                return getString(R.string.plugin_label_keymap);
            case "pime-pinyin-parse":
                return getString(R.string.plugin_label_parse);
            case "pime-pinyin-translate":
                return getString(R.string.plugin_label_translate);
            case "pime-pinyin-rank":
                return getString(R.string.plugin_label_rank);
            case "pime-candidates-format":
                return getString(R.string.plugin_label_format);
            default:
                return id;
        }
    }

    private CharSequence settingLabel(String key) {
        switch (key) {
            case "max_candidates":
                return getString(R.string.setting_label_max_candidates);
            case "page_size":
                return getString(R.string.setting_label_page_size);
            default:
                return key;
        }
    }

    private void newChainDialog() {
        promptForName(R.string.new_chain_title, "", getString(R.string.new_chain_hint),
                name -> {
                    if (name.isEmpty()) return getString(R.string.chain_name_invalid);
                    if (ChainStore.isReservedName(name)) {
                        return getString(R.string.chain_name_reserved);
                    }
                    if (ChainStore.chainFile(this, name).exists()) {
                        return getString(R.string.new_chain_exists);
                    }
                    return null;
                },
                name -> {
                    try {
                        final File created = ChainStore.chainFile(this, name);
                        ChainStore.writeChain(this, created,
                                ChainStore.readChain(this, ChainStore.activeChain(this)));
                        switchChain(created);
                    } catch (IOException error) {
                        snack(getString(R.string.plugin_setting_missing));
                    }
                });
    }

    /**
     * A picked .zip or folder. The manifest is read first and only the copy is
     * deferred, so a bad package is rejected before anything is written and a
     * same-name package can be confirmed before it is replaced.
     */
    private void importPlugin(Uri picked) {
        final PluginStore.Manifest manifest;
        try {
            manifest = PluginStore.readManifest(this, picked);
        } catch (IOException error) {
            snack(getString(R.string.plugin_import_failed, error.getMessage()));
            return;
        }
        final File target = new File(PluginStore.dir(this), manifest.packageName());
        final boolean replace = target.exists();
        final List<CharSequence> warnings = new ArrayList<>();
        if (replace) {
            warnings.add(getString(R.string.plugin_import_exists, target.getName()));
        }
        if (!manifest.platformDeclared) {
            warnings.add(getString(R.string.plugin_import_platform,
                    PluginStore.platformToken()));
        }
        if (warnings.isEmpty()) {
            startInstall(picked, false);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(replace ? R.string.plugin_import_replace : R.string.plugin_import_confirm)
                .setMessage(TextUtils.join("\n\n", warnings))
                .setPositiveButton(replace ? R.string.plugin_import_replace
                                : R.string.plugin_import_continue,
                        (dialog, which) -> startInstall(picked, replace))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startInstall(Uri picked, boolean replace) {
        snack(getString(R.string.plugin_import_running));
        importExecutor.execute(() -> {
            File installed = null;
            CharSequence failure = null;
            try {
                installed = PluginStore.install(this, picked, replace);
            } catch (IOException error) {
                failure = getString(R.string.plugin_import_failed, error.getMessage());
            }
            final File result = installed;
            final CharSequence problem = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (problem != null) {
                    snack(problem);
                    return;
                }
                broadcastReloadPlugins();
                snack(getString(R.string.plugin_imported, pluginLabel(manifestInfo(result)[0])));
                rebuildDetail();
            });
        });
    }

    /** Tells the running input service to rescan the plugin directory. */
    private void broadcastReloadPlugins() {
        final Intent intent = new Intent(PimeImeService.ACTION_RELOAD_PLUGINS);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void importChain(Uri uri) {
        try {
            final String display = uri.getLastPathSegment() != null
                    ? uri.getLastPathSegment() : "imported.cbor";
            if (ChainStore.isReservedName(display)) {
                snack(getString(R.string.chain_name_reserved));
                return;
            }
            final File target = ChainStore.chainFile(this, display);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    snack(getString(R.string.import_no_file));
                    return;
                }
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            snack(getString(R.string.chain_imported, target.getName()));
            switchChain(target);
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
        }
    }

    /** Tells the running input service to rebuild the active chain. */
    private void broadcastRebuild() {
        final Intent intent = new Intent(PimeImeService.ACTION_REBUILD_CHAIN);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** Tells the running input service that the palette or key size changed. */
    private void broadcastDisplayChanged() {
        final Intent intent = new Intent(PimeImeService.ACTION_DISPLAY_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** Host-only display preferences; nothing here feeds the plugin chain. */
    private void openSettings() {
        enterDetail(R.string.page_settings, parent -> {
            addGroupTitle(parent, R.string.section_feel);
            final LinearLayout feel = addCard(parent);
            addRow(feel, R.drawable.ic_unfold_more, getString(R.string.row_height),
                    heightLabel(Prefs.keyboardPercent(this)),
                    R.drawable.ic_chevron_right, v -> chooseHeight());
            addDivider(feel);
            addSwitchRow(feel, R.drawable.ic_vibration, getString(R.string.row_vibrate),
                    getString(R.string.row_vibrate_desc), Prefs.vibrate(this),
                    (button, checked) -> {
                        Prefs.setVibrate(this, checked);
                        if (checked) {
                            findViewById(R.id.root).performHapticFeedback(
                                    android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        }
                    });
            addDivider(feel);
            addSwitchRow(feel, R.drawable.ic_volume_up, getString(R.string.row_sound),
                    getString(R.string.row_sound_desc), Prefs.sound(this),
                    (button, checked) -> Prefs.setSound(this, checked));

            addGroupTitle(parent, R.string.section_display);
            final LinearLayout display = addCard(parent);
            addRow(display, R.drawable.ic_view_list, getString(R.string.row_candidates),
                    candidatesLabel(Prefs.candidates(this)),
                    R.drawable.ic_chevron_right, v -> chooseCandidates());
            addDivider(display);
            addRow(display, R.drawable.ic_separator,
                    getString(R.string.row_pinyin_separator),
                    separatorLabel(Prefs.pinyinSeparator(this)),
                    R.drawable.ic_chevron_right, v -> chooseSeparator());
            addDivider(display);
            addRow(display, R.drawable.ic_contrast, getString(R.string.row_theme),
                    themeLabel(Prefs.theme(this)), R.drawable.ic_chevron_right,
                    v -> chooseTheme());

            addTextBlock(parent, getString(R.string.settings_note));
        });
    }

    private CharSequence heightLabel(int percent) {
        final int name;
        if (percent < 100) {
            name = R.string.height_compact;
        } else if (percent > 100) {
            name = R.string.height_roomy;
        } else {
            name = R.string.height_standard;
        }
        return getString(R.string.height_value, getString(name), percent);
    }

    private CharSequence candidatesLabel(int count) {
        return count <= 0 ? getString(R.string.candidates_all)
                : getString(R.string.candidates_value, count);
    }

    private CharSequence separatorLabel(String separator) {
        return getString(Prefs.SEPARATOR_SPACE.equals(separator)
                ? R.string.separator_space : R.string.separator_apostrophe);
    }

    private CharSequence themeLabel(String theme) {
        switch (theme) {
            case Prefs.THEME_LIGHT:
                return getString(R.string.theme_light);
            case Prefs.THEME_DARK:
                return getString(R.string.theme_dark);
            default:
                return getString(R.string.theme_system);
        }
    }

    private void chooseHeight() {
        final int[] values = Prefs.KEYBOARD_PERCENTS;
        final CharSequence[] labels = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = heightLabel(values[i]);
        choose(R.string.row_height, labels, indexOf(values, Prefs.keyboardPercent(this)),
                (dialog, which) -> {
                    Prefs.setKeyboardPercent(this, values[which]);
                    broadcastDisplayChanged();
                    dialog.dismiss();
                    rebuildDetail();
                });
    }

    private void chooseCandidates() {
        final int[] values = Prefs.CANDIDATE_COUNTS;
        final CharSequence[] labels = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = candidatesLabel(values[i]);
        choose(R.string.row_candidates, labels, indexOf(values, Prefs.candidates(this)),
                (dialog, which) -> {
                    Prefs.setCandidates(this, values[which]);
                    broadcastDisplayChanged();
                    dialog.dismiss();
                    rebuildDetail();
                });
    }

    private void chooseSeparator() {
        final String[] values = {
                Prefs.SEPARATOR_APOSTROPHE, Prefs.SEPARATOR_SPACE
        };
        final CharSequence[] labels = {
                getString(R.string.separator_apostrophe),
                getString(R.string.separator_space)
        };
        final int selected = Prefs.SEPARATOR_SPACE.equals(Prefs.pinyinSeparator(this))
                ? 1 : 0;
        choose(R.string.row_pinyin_separator, labels, selected, (dialog, which) -> {
            Prefs.setPinyinSeparator(this, values[which]);
            broadcastDisplayChanged();
            dialog.dismiss();
            rebuildDetail();
        });
    }

    private void chooseTheme() {
        final String[] values = {
                Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK
        };
        final CharSequence[] labels = {
                getString(R.string.theme_system), getString(R.string.theme_light),
                getString(R.string.theme_dark)
        };
        int selected = 0;
        final String current = Prefs.theme(this);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) selected = i;
        }
        choose(R.string.row_theme, labels, selected, (dialog, which) -> {
            Prefs.setTheme(this, values[which]);
            broadcastDisplayChanged();
            dialog.dismiss();
            // Recreates the activity (and any open input view) with the new
            // night mode, so there is nothing left to rebuild here.
            AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this));
        });
    }

    private void choose(int titleRes, CharSequence[] labels, int selected,
                        DialogInterface.OnClickListener onPick) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, selected, onPick)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return 0;
    }

    private void openStorage() {
        enterDetail(R.string.page_storage, parent -> {
            final File plugins = new File(getFilesDir(), "plugins");
            final File chains = new File(getFilesDir(), "chains");
            addGroupTitle(parent, R.string.section_dirs);
            final LinearLayout card = addCard(parent);
            addRow(card, R.drawable.ic_folder, getString(R.string.row_plugins),
                    plugins.getAbsolutePath() + "\n" + getString(R.string.storage_plugins_desc),
                    R.drawable.ic_copy, v -> copyPath(plugins.getAbsolutePath(), getString(R.string.row_plugins)));
            addDivider(card);
            addRow(card, R.drawable.ic_folder, getString(R.string.section_chain),
                    chains.getAbsolutePath() + "\n" + getString(R.string.storage_chains_desc),
                    R.drawable.ic_copy, v -> copyPath(chains.getAbsolutePath(), chains.getName()));

            addTextBlock(parent, getString(R.string.storage_note));
        });
    }

    private void enterDetail(int titleRes, Page page) {
        detailPage = page;
        detailTitleRes = titleRes;
        TransitionManager.beginDelayedTransition((ViewGroup) findViewById(R.id.root), new Fade());
        detailContent.removeAllViews();
        page.build(detailContent);
        toolbar.setTitle(titleRes);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
        toolbar.setNavigationOnClickListener(v -> leaveDetail());
        final android.view.MenuItem about = toolbar.getMenu().findItem(R.id.action_about);
        if (about != null) about.setVisible(false);
        homeScroll.setVisibility(View.GONE);
        detailScroll.setVisibility(View.VISIBLE);
        ((androidx.core.widget.NestedScrollView) detailScroll).scrollTo(0, 0);
        inDetail = true;
    }

    /** Rebuilds the open detail page so it shows freshly changed preferences. */
    private void rebuildDetail() {
        if (!inDetail || detailPage == null) return;
        final androidx.core.widget.NestedScrollView scroll =
                (androidx.core.widget.NestedScrollView) detailScroll;
        final int position = scroll.getScrollY();
        detailContent.removeAllViews();
        detailPage.build(detailContent);
        // Toggling a switch rebuilds the page; without this the list would jump
        // back to the top and the row you just changed would scroll away.
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void leaveDetail() {
        if (!inDetail) return;
        inDetail = false;
        detailPage = null;
        TransitionManager.beginDelayedTransition((ViewGroup) findViewById(R.id.root), new Fade());
        detailScroll.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        toolbar.setTitle(R.string.home_title);
        toolbar.setNavigationIcon(null);
        final android.view.MenuItem about = toolbar.getMenu().findItem(R.id.action_about);
        if (about != null) about.setVisible(true);
    }

    // ------------------------------------------------------------------ building blocks

    private void addStep(LinearLayout parent, int number, CharSequence title, CharSequence desc,
                         CharSequence note, boolean done, boolean active, CharSequence action,
                         View.OnClickListener actionListener) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(2));

        final TextView mark = new TextView(this);
        mark.setGravity(Gravity.CENTER);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setText(done ? getString(R.string.step_done) : String.valueOf(number));
        final int circleColor = done
                ? ContextCompat.getColor(this, R.color.success)
                : active ? attrColor(com.google.android.material.R.attr.colorPrimary)
                : attrColor(com.google.android.material.R.attr.colorSurfaceContainerHighest);
        mark.setTextColor(done || active
                ? attrColor(com.google.android.material.R.attr.colorOnPrimary)
                : attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(circleColor);
        mark.setBackground(circle);
        row.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lineParams.setMarginStart(dp(14));
        row.addView(lines, lineParams);

        final TextView titleView = text(title, 15, active,
                done || active ? attrColor(com.google.android.material.R.attr.colorOnSurface)
                        : attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        lines.addView(titleView);

        if (!done && desc != null) {
            lines.addView(text(desc, 13, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        }
        if (active && note != null) {
            lines.addView(text(note, 12, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        }
        if (active && action != null) {
            final MaterialButton button = new MaterialButton(this,
                    null, com.google.android.material.R.attr.materialButtonStyle);
            button.setText(action);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setAllCaps(false);
            final LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonParams.topMargin = dp(8);
            button.setOnClickListener(actionListener);
            lines.addView(button, buttonParams);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addGroupTitle(LinearLayout parent, int titleRes) {
        addGroupTitle(parent, getString(titleRes));
    }

    private void addGroupTitle(LinearLayout parent, CharSequence title) {
        final TextView view = text(title, 12, true,
                attrColor(com.google.android.material.R.attr.colorPrimary));
        view.setPadding(dp(24), dp(20), dp(24), dp(8));
        parent.addView(view);
    }

    private LinearLayout addCard(LinearLayout parent) {
        final MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainer));
        card.setRadius(dp(16));
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        final LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(16), 0, dp(16), 0);
        parent.addView(card, params);
        return column;
    }

    private void addTextBlock(LinearLayout parent, CharSequence text) {
        final TextView view = text(text, 13, false,
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setLineSpacing(0f, 1.25f);
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addRow(LinearLayout parent, int iconRes, CharSequence title, CharSequence subtitle,
                        int trailingIconRes, View.OnClickListener click) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (click != null) {
            row.setClickable(true);
            row.setFocusable(true);
            final TypedValue value = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            row.setBackgroundResource(value.resourceId);
            row.setOnClickListener(click);
        }

        final ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        icon.setContentDescription(null);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lineParams.setMarginStart(dp(16));
        row.addView(lines, lineParams);

        lines.addView(text(title, 16, false,
                attrColor(com.google.android.material.R.attr.colorOnSurface)));

        if (subtitle != null) {
            final TextView sub = text(subtitle, 14, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(2);
            lines.addView(sub, subParams);
        }

        if (trailingIconRes != 0) {
            final ImageView arrow = new ImageView(this);
            arrow.setImageResource(trailingIconRes);
            arrow.setImageTintList(ColorStateList.valueOf(
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            arrow.setContentDescription(trailingIconRes == R.drawable.ic_copy
                    ? getString(R.string.copy_path) : null);
            final LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            arrowParams.setMarginStart(dp(8));
            row.addView(arrow, arrowParams);
        }

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * One chain plan: tap to make it active, overflow for rename/delete. A check
     * mark (rather than bold text alone) is what says "this one is in use".
     */
    private void addChainRow(LinearLayout parent, File chain, boolean isActive, int count,
                             View.OnClickListener onSwitch) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(16), dp(8), dp(8), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        final TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(onSwitch);

        final ImageView mark = new ImageView(this);
        mark.setImageResource(isActive ? R.drawable.ic_check : R.drawable.ic_link);
        mark.setImageTintList(ColorStateList.valueOf(isActive
                ? attrColor(com.google.android.material.R.attr.colorPrimary)
                : attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        mark.setContentDescription(null);
        row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lineParams.setMarginStart(dp(14));
        row.addView(lines, lineParams);

        final TextView name = text(ChainStore.displayName(chain), 16, isActive,
                attrColor(com.google.android.material.R.attr.colorOnSurface));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(name);

        final LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(2);
        lines.addView(text(getString(R.string.chain_summary, count, getString(isActive
                ? R.string.chain_current : R.string.chain_switch)), 13, false,
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)), subParams);

        final ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_vert);
        more.setImageTintList(ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        more.setContentDescription(getString(R.string.action_chain_more));
        more.setClickable(true);
        more.setFocusable(true);
        final TypedValue moreRipple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                moreRipple, true);
        more.setBackgroundResource(moreRipple.resourceId);
        more.setOnClickListener(v -> showChainMenu(v, chain, isActive));
        final LinearLayout.LayoutParams moreParams =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        moreParams.setMarginStart(dp(4));
        row.addView(more, moreParams);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSwitchRow(LinearLayout parent, int iconRes, CharSequence title,
                              CharSequence subtitle, boolean checked,
                              android.widget.CompoundButton.OnCheckedChangeListener listener) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));

        final MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setOnCheckedChangeListener(listener);
        // Tapping anywhere on the row flips the switch.
        row.setOnClickListener(v -> toggle.toggle());
        row.setFocusable(true);
        final TypedValue rowRipple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rowRipple, true);
        row.setBackgroundResource(rowRipple.resourceId);

        final ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lineParams.setMarginStart(dp(16));
        row.addView(lines, lineParams);

        lines.addView(text(title, 16, false,
                attrColor(com.google.android.material.R.attr.colorOnSurface)));
        if (subtitle != null) {
            final TextView sub = text(subtitle, 14, false,
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            sub.setMaxLines(2);
            final LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(2);
            lines.addView(sub, subParams);
        }

        final LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMarginStart(dp(8));
        row.addView(toggle, toggleParams);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addDivider(LinearLayout parent) {
        addDivider(parent, true);
    }

    private void addDivider(LinearLayout parent, boolean indent) {
        final View divider = new View(this);
        divider.setBackgroundColor(attrColor(com.google.android.material.R.attr.colorOutlineVariant));
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMarginStart(dp(indent ? 56 : 16));
        params.setMarginEnd(dp(16));
        parent.addView(divider, params);
    }

    private TextView text(CharSequence value, int sizeSp, boolean bold, int color) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable pill(int color) {
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(999));
        background.setColor(withAlpha(color, 0.16f));
        return background;
    }

    private int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }

    private void copyPath(String path, CharSequence label) {
        final ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("pime", path));
        }
        Snackbar.make(findViewById(R.id.root), getString(R.string.copied, label),
                Snackbar.LENGTH_SHORT).show();
    }

    private int attrColor(int attr) {
        final TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Downloads, where a plugin .zip usually lands. The picker takes this as a
     * hint and falls back to its own default when it cannot resolve it.
     */
    private static Uri downloadsUri() {
        if (Build.VERSION.SDK_INT < 26) return null;
        return android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download");
    }

    /**
     * OpenDocument that starts in Downloads: that is where a plugin .zip lands,
     * and the picker otherwise opens wherever it was last left.
     */
    private static final class OpenDocumentInDownloads
            extends ActivityResultContracts.OpenDocument {
        @Override
        public Intent createIntent(Context context, String[] input) {
            final Intent intent = super.createIntent(context, input);
            if (Build.VERSION.SDK_INT >= 26) {
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                        downloadsUri());
            }
            return intent;
        }
    }
}
