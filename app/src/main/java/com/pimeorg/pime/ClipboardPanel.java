package com.pimeorg.pime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * The clipboard sheet drawn in the keyboard's key area: quick-send chips over a
 * scrollable history list. Built fresh each time the panel opens; entries are
 * laid out from the {@link ClipboardHistory} cache (no DB access on the main
 * thread).
 *
 * <p>It is a plain view, not an overlay of its own: the host mounts it inside
 * the same key-area overlay the layout chooser uses, so the two sheets never
 * fight for the window.
 */
final class ClipboardPanel extends LinearLayout {

    interface Listener {
        /** The user picked an entry: insert it into the editor. */
        void onInsert(ClipboardEntry entry);

        /** The top "粘贴当前" button: paste the system clipboard's text as-is. */
        void onPasteCurrent();

        void onClearHistory();

        void onTogglePin(ClipboardEntry entry);

        void onAddToQuickSend(ClipboardEntry entry);

        void onRemoveFromQuickSend(ClipboardEntry entry);

        void onDelete(ClipboardEntry entry);
    }

    private final KeyboardTheme palette;
    private final Listener listener;

    ClipboardPanel(Context context, KeyboardTheme palette, Listener listener,
                   List<ClipboardEntry> quickSend, List<ClipboardEntry> history) {
        super(context);
        this.palette = palette;
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackground(panelBackground());
        setPadding(dp(8), dp(4), dp(8), dp(4));
        /* Empty sheet space belongs to the sheet: a tap on it must not fall
         * through to the dismiss-cover drawn behind the overlay. */
        setClickable(true);

        addView(buildHeader(), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        addView(buildQuickSend(quickSend), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(buildHistory(history), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ------------------------------------------------------------ header

    private View buildHeader() {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(getContext());
        title.setText(R.string.clipboard_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif", android.graphics.Typeface.BOLD));
        title.setTextColor(palette.candidateText);
        row.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(textAction(R.string.clipboard_paste_current, v -> {
            if (listener != null) listener.onPasteCurrent();
        }), new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(textAction(R.string.clipboard_clear_history, v -> {
            if (listener != null) listener.onClearHistory();
        }), new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    // ------------------------------------------------------------ quick send

    private View buildQuickSend(List<ClipboardEntry> quickSend) {
        final LinearLayout section = new LinearLayout(getContext());
        section.setOrientation(VERTICAL);

        section.addView(sectionLabel(R.string.clipboard_quick_send),
                new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        if (quickSend.isEmpty()) {
            final TextView hint = new TextView(getContext());
            hint.setText(R.string.clipboard_quick_send_empty);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setTextColor(palette.hint);
            hint.setPadding(dp(10), 0, dp(10), dp(6));
            section.addView(hint, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return section;
        }

        final HorizontalScrollView scroller = new HorizontalScrollView(getContext());
        scroller.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = new LinearLayout(getContext());
        chips.setOrientation(HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        for (ClipboardEntry entry : quickSend) {
            chips.addView(quickChip(entry), chipParams());
        }
        scroller.addView(chips, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        section.addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return section;
    }

    private View quickChip(ClipboardEntry entry) {
        final TextView chip = new TextView(getContext());
        chip.setText(chipLabel(entry));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTextColor(palette.keyText);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER);
        chip.setMaxWidth(dp(220));
        chip.setPadding(dp(12), dp(4), dp(12), dp(4));
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(14));
        fill.setColor(palette.rail);
        chip.setBackground(ripple(fill));
        chip.setOnClickListener(v -> {
            if (listener != null) listener.onInsert(entry);
        });
        chip.setOnLongClickListener(v -> {
            if (listener != null) listener.onRemoveFromQuickSend(entry);
            return true;
        });
        return chip;
    }

    private static String chipLabel(ClipboardEntry entry) {
        if (entry.isImage()) return ClipboardEntry.IMAGE_PLACEHOLDER;
        if (entry.isVideo()) return ClipboardEntry.VIDEO_PLACEHOLDER;
        final String text = entry.text.replace('\n', ' ').trim();
        return text.isEmpty() ? ClipboardEntry.IMAGE_PLACEHOLDER : text;
    }

    // ------------------------------------------------------------ history

    private View buildHistory(List<ClipboardEntry> history) {
        final LinearLayout section = new LinearLayout(getContext());
        section.setOrientation(VERTICAL);
        section.addView(sectionLabel(R.string.clipboard_history),
                new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        if (history.isEmpty()) {
            final TextView empty = new TextView(getContext());
            empty.setText(R.string.clipboard_empty);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(palette.hint);
            empty.setGravity(Gravity.CENTER);
            section.addView(empty, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return section;
        }

        final ScrollView scroller = new ScrollView(getContext());
        scroller.setVerticalScrollBarEnabled(false);
        final LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(VERTICAL);
        for (ClipboardEntry entry : history) {
            list.addView(historyRow(entry), new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroller.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(scroller, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return section;
    }

    private View historyRow(ClipboardEntry entry) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(4), dp(8));
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(10));
        fill.setColor(entry.pinned ? palette.accentSoft : 0);
        row.setBackground(ripple(fill));
        row.setOnClickListener(v -> {
            if (listener != null) listener.onInsert(entry);
        });

        if (entry.isMedia()) {
            row.addView(mediaThumb(entry), thumbParams());
        }
        final LinearLayout lines = new LinearLayout(getContext());
        lines.setOrientation(VERTICAL);
        lines.setGravity(Gravity.CENTER_VERTICAL);

        final TextView primary = new TextView(getContext());
        primary.setText(rowLabel(entry));
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        primary.setTextColor(palette.candidateText);
        primary.setSingleLine(!entry.isMedia());
        primary.setMaxLines(entry.isMedia() ? 1 : 2);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(primary, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView secondary = new TextView(getContext());
        secondary.setText(secondaryLabel(entry));
        secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        secondary.setTextColor(palette.hint);
        secondary.setSingleLine(true);
        secondary.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(secondary, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(lines, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(rowAction(entry.pinned ? R.drawable.ic_check : R.drawable.ic_unfold_more,
                entry.pinned ? R.string.clipboard_unpin : R.string.clipboard_pin,
                entry.pinned ? palette.accent : palette.hint, v -> {
                    if (listener != null) listener.onTogglePin(entry);
                }));
        row.addView(rowAction(R.drawable.ic_add, R.string.clipboard_add_quick_send,
                palette.hint, v -> {
                    if (listener != null) listener.onAddToQuickSend(entry);
                }));
        row.addView(rowAction(R.drawable.ic_trash, R.string.clipboard_delete,
                palette.hint, v -> {
                    if (listener != null) listener.onDelete(entry);
                }));
        return row;
    }

    private static String rowLabel(ClipboardEntry entry) {
        if (entry.isImage()) return ClipboardEntry.IMAGE_PLACEHOLDER;
        if (entry.isVideo()) return ClipboardEntry.VIDEO_PLACEHOLDER;
        return entry.text;
    }

    private String secondaryLabel(ClipboardEntry entry) {
        final String time = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.timestamp));
        if (entry.pinned) return getContext().getString(R.string.clipboard_pinned) + " · " + time;
        return time;
    }

    private View mediaThumb(ClipboardEntry entry) {
        if (entry.isImage()) {
            final ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(palette.rail);
            image.setImageBitmap(decodeThumb(entry.mediaPath, dp(44)));
            return image;
        }
        /* Video: an icon tile in the same slot the image thumbnail takes. */
        final ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_view_list);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageTintList(ColorStateList.valueOf(palette.hint));
        icon.setBackgroundColor(palette.rail);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        return icon;
    }

    /** Decodes a small square thumbnail, honouring {@code inSampleSize} for speed. */
    private static Bitmap decodeThumb(String path, int targetPx) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetPx
                || bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        if (!new File(path).exists()) return null;
        return BitmapFactory.decodeFile(path, options);
    }

    // ------------------------------------------------------------ small views

    private TextView sectionLabel(int res) {
        final TextView label = new TextView(getContext());
        label.setText(res);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(android.graphics.Typeface.create(
                "sans-serif", android.graphics.Typeface.BOLD));
        label.setTextColor(palette.hint);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(10), 0, dp(10), 0);
        return label;
    }

    private TextView textAction(int res, OnClickListener click) {
        final TextView action = new TextView(getContext());
        action.setText(res);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        action.setTextColor(palette.accent);
        action.setGravity(Gravity.CENTER);
        action.setSingleLine(true);
        action.setPadding(dp(10), 0, dp(10), 0);
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(8));
        fill.setColor(0);
        action.setBackground(ripple(fill));
        action.setOnClickListener(click);
        action.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return action;
    }

    private ImageButton rowAction(int iconRes, int descriptionRes, int tint,
                                  OnClickListener click) {
        final ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconRes);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        final GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(0);
        button.setBackground(ripple(fill));
        button.setContentDescription(getContext().getString(descriptionRes));
        button.setOnClickListener(click);
        button.setLayoutParams(new LayoutParams(dp(36),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    // ------------------------------------------------------------ helpers

    private Drawable panelBackground() {
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(palette.panel);
        fill.setCornerRadius(dp(16));
        fill.setStroke(Math.max(1, dp(1) / 2), palette.divider);
        return fill;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple), content, null);
    }

    private LinearLayout.LayoutParams chipParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        params.rightMargin = dp(8);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private LinearLayout.LayoutParams thumbParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.rightMargin = dp(10);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}