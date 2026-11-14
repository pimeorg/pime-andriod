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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        if (Build.VERSION.SDK_INT >= 34) {
            final InputMethodInfo info = imm.getCurrentInputMethodInfo();
            return info != null && getPackageName().equals(info.getPackageName());
        }
        final String current = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName() + "/");
    }

    private CharSequence pluginSummary() {
        final int count = pluginDirs().length;
        return count == 0 ? getString(R.string.no_plugins)
                : count + " 个插件包 · 拼音默认链";
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

    /** Reads id/version/role from a plugin manifest; display only. */
    private String[] manifestInfo(File dir) {
        String id = null;
        String version = null;
        String role = null;
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
            }
        } catch (IOException ignored) {
            // Falls back to the directory name below.
        }
        return new String[]{id != null ? id : dir.getName(), version,
                role != null ? role : getString(R.string.role_unknown)};
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
            addTextBlock(notesCard, getString(R.string.about_license));
        });
    }

    private void openPlugins() {
        enterDetail(R.string.page_plugins, parent -> {
            try {
                buildPluginsPage(parent);
            } catch (IOException error) {
                addTextBlock(parent, getString(R.string.plugin_setting_missing));
            }
        });
    }

    /** Builds the full chain + plugin management page for the active chain. */
    private void buildPluginsPage(LinearLayout parent) throws IOException {
        final String activeName = ChainStore.activeChainName(this);
        final File active = ChainStore.activeChain(this);

        /* --- chain plans --- */
        addGroupTitle(parent, R.string.section_chains);
        final LinearLayout chainCard = addCard(parent);
        final List<File> chains = ChainStore.listChains(this);
        if (chains.isEmpty()) {
            addRow(chainCard, R.drawable.ic_folder, getString(R.string.chain_name_empty),
                    null, 0, null);
        }
        for (int i = 0; i < chains.size(); i++) {
            final File chain = chains.get(i);
            final boolean isActive = chain.getName().equals(activeName);
            final String subtitle = isActive
                    ? getString(R.string.chain_active) : getString(R.string.plugins_chain_desc);
            addRow(chainCard, R.drawable.ic_folder, chain.getName(), subtitle, 0,
                    v -> switchChain(chain));
            if (i != chains.size() - 1) addDivider(chainCard);
        }
        addRow(chainCard, R.drawable.ic_extension, getString(R.string.action_new_chain),
                null, R.drawable.ic_chevron_right, v -> newChainDialog());
        addDivider(chainCard);
        addRow(chainCard, R.drawable.ic_folder, getString(R.string.action_import_chain),
                null, R.drawable.ic_chevron_right,
                v -> importChainLauncher.launch(new String[]{"*/*"}));

        /* --- active chain plugins --- */
        final List<ChainStore.PluginEntry> plugins = ChainStore.readChain(this, active);
        addGroupTitle(parent, getString(R.string.section_chain_plugins) + " · "
                + getString(R.string.plugins_count_value, plugins.size()));
        final LinearLayout pluginCard = addCard(parent);
        if (plugins.isEmpty()) {
            addRow(pluginCard, R.drawable.ic_extension, getString(R.string.no_plugins), null, 0, null);
        }
        for (int i = 0; i < plugins.size(); i++) {
            final ChainStore.PluginEntry entry = plugins.get(i);
            final String subtitle = entry.role + (entry.version.isEmpty()
                    ? "" : " · " + entry.version);
            addSwitchRow(pluginCard, R.drawable.ic_extension, entry.id, subtitle, true,
                    (button, checked) -> togglePlugin(active, entry, checked));
            if (i != plugins.size() - 1) addDivider(pluginCard);
        }

        /* --- per-plugin settings --- */
        final List<ChainStore.PluginEntry> withSettings = new ArrayList<>();
        for (ChainStore.PluginEntry entry : plugins) {
            if (!entry.settings.isEmpty()) withSettings.add(entry);
        }
        if (!withSettings.isEmpty()) {
            addGroupTitle(parent, R.string.section_plugin_settings);
            for (ChainStore.PluginEntry entry : withSettings) {
                final LinearLayout settingsCard = addCard(parent);
                for (Map.Entry<String, Object> setting : entry.settings.entrySet()) {
                    addSettingRow(settingsCard, active, entry, setting.getKey(),
                            setting.getValue());
                    addDivider(settingsCard);
                }
            }
        }

        addTextBlock(parent, getString(R.string.plugins_note));
    }

    private void switchChain(File chain) {
        ChainStore.setActiveChain(this, chain.getName());
        broadcastRebuild();
        snack(getString(R.string.chain_switched, chain.getName()));
        rebuildDetail();
    }

    private void togglePlugin(File chain, ChainStore.PluginEntry entry, boolean enabled) {
        try {
            if (enabled) {
                final ChainStore.PluginEntry fresh = pluginEntryFromDir(entry.id);
                if (fresh == null) {
                    snack(getString(R.string.plugin_not_in_chain));
                    rebuildDetail();
                    return;
                }
                ChainStore.addPlugin(this, chain, fresh);
                snack(getString(R.string.plugin_on, entry.id));
            } else {
                ChainStore.removePlugin(this, chain, entry.id);
                snack(getString(R.string.plugin_off, entry.id));
            }
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
        }
        broadcastRebuild();
        rebuildDetail();
    }

    /** Reads role/version from a plugin package's manifest to build a chain entry. */
    private ChainStore.PluginEntry pluginEntryFromDir(String id) {
        for (File dir : pluginDirs()) {
            final String[] info = manifestInfo(dir);
            if (info[0].equals(id)) {
                return new ChainStore.PluginEntry(id, info[2],
                        info[1] == null ? "" : info[1]);
            }
        }
        return null;
    }

    private void addSettingRow(LinearLayout parent, File chain,
                               ChainStore.PluginEntry entry, String key, Object value) {
        if (value instanceof Boolean) {
            addSwitchRow(parent, R.drawable.ic_tune, key,
                    getString(R.string.plugins_add_desc), (Boolean) value,
                    (button, checked) -> {
                        setSetting(chain, entry.id, key, checked);
                    });
        } else {
            final int number = (int) ((Number) value).longValue();
            addRow(parent, R.drawable.ic_tune, key,
                    getString(R.string.settings_int_value, number),
                    R.drawable.ic_chevron_right,
                    v -> chooseIntSetting(chain, entry, key, number));
        }
    }

    private void setSetting(File chain, String pluginId, String key, Object value) {
        try {
            ChainStore.setPluginSetting(this, chain, pluginId, key, value);
            snack(getString(R.string.plugin_setting_changed, pluginId, key));
        } catch (IOException error) {
            snack(getString(R.string.plugin_setting_missing));
        }
        broadcastRebuild();
        rebuildDetail();
    }

    private void chooseIntSetting(File chain, ChainStore.PluginEntry entry,
                                  String key, int current) {
        final int[] candidates = {Math.max(0, current - 1), current, current + 1};
        final CharSequence[] labels = new CharSequence[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            labels[i] = getString(R.string.settings_int_value, candidates[i]);
        }
        int selected = 1;
        choose(R.string.settings_int_title, labels, selected, (dialog, which) -> {
            setSetting(chain, entry.id, key, candidates[which]);
            dialog.dismiss();
        });
    }

    private void newChainDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.new_chain_hint);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_chain_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    try {
                        final File newChain = ChainStore.chainFile(this, name);
                        if (newChain.exists()) {
                            snack(getString(R.string.new_chain_exists));
                            return;
                        }
                        final File active = ChainStore.activeChain(this);
                        ChainStore.writeChain(this, newChain,
                                ChainStore.readChain(this, active));
                        switchChain(newChain);
                    } catch (IOException error) {
                        snack(getString(R.string.plugin_setting_missing));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importChain(Uri uri) {
        try {
            final String display = uri.getLastPathSegment() != null
                    ? uri.getLastPathSegment() : "imported.cbor";
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
        detailContent.removeAllViews();
        detailPage.build(detailContent);
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
}
