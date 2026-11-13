package dev.pime.ime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher entry: enable the IME, pick it, and show where plugins live. */
public final class SettingsActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ScrollView scroll = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = new TextView(this);
        title.setText("PIME 输入法");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        final TextView subtitle = new TextView(this);
        subtitle.setText("基于 pime-core 的 Android 宿主外壳\n"
                + "版本 " + BuildConfig.VERSION_NAME);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(button("启用输入法", v -> startActivity(
                new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button("选择输入法", v -> {
            final InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }));

        final TextView paths = new TextView(this);
        paths.setText("插件目录：\n" + new java.io.File(getFilesDir(), "plugins")
                + "\n\n链配置：\n" + new java.io.File(getFilesDir(), "chains/default.cbor")
                + "\n\n外壳只负责宿主：按键先交给 pime-core，未被处理时直接交给编辑器。"
                + "分词、词典、候选等业务全部由插件提供。");
        paths.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        paths.setPadding(0, dp(24), 0, 0);
        paths.setTextIsSelectable(true);
        root.addView(paths);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        // Do not read Settings.Secure.ENABLED_INPUT_METHODS: Android 15 only
        // allows that to apps targeting API 33 or lower. The InputMethodManager
        // list is the public, targetSdk-independent equivalent.
        final InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        boolean on = false;
        if (imm != null) {
            final String self = getPackageName();
            for (android.view.inputmethod.InputMethodInfo info
                    : imm.getEnabledInputMethodList()) {
                if (self.equals(info.getPackageName())) {
                    on = true;
                    break;
                }
            }
        }
        status.setText(on ? "状态：已启用" : "状态：未启用");
        status.setTextColor(on ? 0xFF7BD88F : 0xFFE8A33D);
        status.setBackgroundColor(0xFF26282C);
    }

    private Button button(String label, android.view.View.OnClickListener action) {
        final Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(action);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
