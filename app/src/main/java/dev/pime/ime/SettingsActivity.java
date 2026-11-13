package dev.pime.ime;

import android.content.Intent;
import android.content.res.ColorStateList;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Arrays;

/** Launcher entry: enable the IME, pick it, and show where plugins live. */
public final class SettingsActivity extends AppCompatActivity {
    private TextView heroStatus;
    private MaterialButton heroAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        final View content = findViewById(R.id.content);
        final int side = Math.max(0, (getResources().getConfiguration().screenWidthDp - 640) / 2);
        content.setPadding(dp(side), content.getPaddingTop(), dp(side), content.getPaddingBottom());

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        final MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_home);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                showAbout();
                return true;
            }
            return false;
        });

        heroStatus = findViewById(R.id.hero_status);
        heroAction = findViewById(R.id.hero_action);

        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildRows() {
        final LinearLayout keyboard = findViewById(R.id.keyboard_rows);
        addRow(keyboard, R.drawable.ic_keyboard, R.string.row_ime,
                getString(R.string.row_ime_desc),
                true, v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_extension, R.string.row_plugins, pluginSummary(),
                true, v -> showPlugins());
        addDivider(keyboard);
        addRow(keyboard, R.drawable.ic_folder, R.string.row_storage, "files/plugins · files/chains",
                true, v -> showStorage());

        final LinearLayout about = findViewById(R.id.about_rows);
        addRow(about, R.drawable.ic_info, R.string.row_version,
                BuildConfig.VERSION_NAME + (BuildConfig.DEBUG ? "（debug）" : ""),
                false, null);
        addDivider(about);
        addRow(about, R.drawable.ic_keyboard, R.string.row_core,
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

        final int onContainer = attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer);
        if (current) {
            heroStatus.setText(R.string.status_ready);
            heroStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
            heroAction.setVisibility(View.GONE);
        } else {
            heroStatus.setText(enabled ? R.string.status_enabled : R.string.status_disabled);
            heroStatus.setTextColor(onContainer);
            heroAction.setText(enabled ? R.string.action_switch : R.string.action_enable);
            heroAction.setVisibility(View.VISIBLE);
            heroAction.setOnClickListener(v -> {
                if (!enabled) {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                } else {
                    imm.showInputMethodPicker();
                }
            });
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
        final java.util.List<File> packages = new java.util.ArrayList<>();
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

    private void showPlugins() {
        final File[] dirs = pluginDirs();
        final File chain = new File(getFilesDir(), "chains/default.cbor");
        final StringBuilder text = new StringBuilder();
        for (File dir : dirs) {
            text.append("• ").append(dir.getName()).append('\n');
        }
        if (dirs.length == 0) text.append(getString(R.string.no_plugins)).append('\n');
        text.append("\n链：").append(chain.getAbsolutePath())
                .append(chain.isFile() ? "（拼音默认链）" : "（缺失，使用空链）");
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_plugins_title)
                .setMessage(text)
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }

    private void showStorage() {
        final String plugins = new File(getFilesDir(), "plugins").getAbsolutePath();
        final String chains = new File(getFilesDir(), "chains").getAbsolutePath();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_storage_title)
                .setMessage("插件：" + plugins + "\n\n链：" + chains + "/default.cbor")
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_about_title)
                .setMessage(getString(R.string.dialog_about_text, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }

    private void addRow(LinearLayout parent, int iconRes, int titleRes, CharSequence subtitle,
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

        final TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface));
        lines.addView(title);

        if (subtitle != null) {
            final TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sub.setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
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

    private int attrColor(int attr) {
        final TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
