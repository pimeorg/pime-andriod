package dev.pime.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private boolean inDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
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

    // ------------------------------------------------------------------ home

    private void buildHomeRows() {
        final LinearLayout keyboard = findViewById(R.id.keyboard_rows);
        addRow(keyboard, R.drawable.ic_keyboard, getString(R.string.row_ime),
                getString(R.string.row_ime_desc), true,
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_extension, getString(R.string.row_plugins),
                pluginSummary(), true, v -> openPlugins());
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_folder, getString(R.string.row_storage),
                "files/plugins · files/chains", true, v -> openStorage());

        final LinearLayout about = findViewById(R.id.about_rows);
        addRow(about, R.drawable.ic_info, getString(R.string.row_about),
                BuildConfig.VERSION_NAME, true, v -> openAbout());
        addDivider(about);
        addRow(about, R.drawable.ic_extension, getString(R.string.row_core),
                getString(R.string.row_core_desc), false, null);
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

        buildSteps(imm, enabled, current);
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
                    BuildConfig.VERSION_NAME + (BuildConfig.DEBUG ? "（debug）" : ""), false, null);
            addDivider(versionCard);
            addRow(versionCard, R.drawable.ic_extension, getString(R.string.row_plugins_count),
                    getString(R.string.plugins_count_value, pluginDirs().length), false, null);
            addDivider(versionCard);
            addRow(versionCard, R.drawable.ic_keyboard, getString(R.string.row_core),
                    getString(R.string.row_core_desc), false, null);

            addGroupTitle(parent, R.string.section_notes);
            final LinearLayout notesCard = addCard(parent);
            addTextBlock(notesCard, getString(R.string.about_intro));
            addDivider(notesCard);
            addTextBlock(notesCard, getString(R.string.about_data));
            addDivider(notesCard);
            addTextBlock(notesCard, getString(R.string.about_license));
        });
    }

    private void openPlugins() {
        enterDetail(R.string.page_plugins, parent -> {
            final File[] packages = pluginDirs();
            addGroupTitle(parent, R.string.section_packages);
            final LinearLayout card = addCard(parent);
            if (packages.length == 0) {
                addRow(card, R.drawable.ic_extension, getString(R.string.no_plugins), null, false, null);
            }
            for (int i = 0; i < packages.length; i++) {
                final File dir = packages[i];
                final String[] info = manifestInfo(dir);
                final String subtitle = info[2] + (info[1] == null ? "" : " · " + info[1]);
                addRow(card, R.drawable.ic_extension, info[0], subtitle, true,
                        v -> copyPath(dir.getAbsolutePath()));
                if (i != packages.length - 1) addDivider(card);
            }

            final File chain = new File(getFilesDir(), "chains/default.cbor");
            addGroupTitle(parent, R.string.section_chain);
            final LinearLayout chainCard = addCard(parent);
            addRow(chainCard, R.drawable.ic_folder, chain.getName(),
                    getString(R.string.plugins_chain_desc), true,
                    v -> copyPath(chain.getAbsolutePath()));

            addTextBlock(parent, getString(R.string.plugins_note));
        });
    }

    private void openStorage() {
        enterDetail(R.string.page_storage, parent -> {
            final File plugins = new File(getFilesDir(), "plugins");
            final File chains = new File(getFilesDir(), "chains");
            addGroupTitle(parent, R.string.section_dirs);
            final LinearLayout card = addCard(parent);
            addRow(card, R.drawable.ic_folder, getString(R.string.row_plugins),
                    plugins.getAbsolutePath() + "\n" + getString(R.string.storage_plugins_desc),
                    true, v -> copyPath(plugins.getAbsolutePath()));
            addDivider(card);
            addRow(card, R.drawable.ic_folder, getString(R.string.section_chain),
                    chains.getAbsolutePath() + "\n" + getString(R.string.storage_chains_desc),
                    true, v -> copyPath(chains.getAbsolutePath()));

            addTextBlock(parent, getString(R.string.storage_note));
        });
    }

    private void enterDetail(int titleRes, Page page) {
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

    private void leaveDetail() {
        if (!inDetail) return;
        inDetail = false;
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
        final TextView title = text(getString(titleRes), 12, true,
                attrColor(com.google.android.material.R.attr.colorPrimary));
        title.setPadding(dp(24), dp(20), dp(24), dp(8));
        parent.addView(title);
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
                        boolean chevron, View.OnClickListener click) {
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

        if (chevron) {
            final ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_chevron_right);
            arrow.setImageTintList(ColorStateList.valueOf(
                    attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            arrow.setContentDescription(null);
            final LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            arrowParams.setMarginStart(dp(8));
            row.addView(arrow, arrowParams);
        }

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addDivider(LinearLayout parent) {
        final View divider = new View(this);
        divider.setBackgroundColor(attrColor(com.google.android.material.R.attr.colorOutlineVariant));
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMarginStart(dp(56));
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

    private void copyPath(String path) {
        final ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("pime", path));
        }
        Snackbar.make(findViewById(R.id.root), getString(R.string.copied, path),
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
