package com.pimeorg.pime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The clipboard sheet drawn in the keyboard's key area: a segmented
 * 「剪切板 / 快捷输入」 switch over a two-column grid, in the shape of the
 * reference implementation (Xime).
 *
 * <p>Cards act on tap (insert) and long-press (a menu: pin / add to quick
 * send / multi-select / delete — quick-send cards get pin / remove), so a card
 * is a preview and nothing else. Multi-select batches deletes from a bottom
 * bar; clearing the history asks for confirmation first.
 *
 * <p>A magnifier left of the clear-history button turns the header into a
 * search field (hint text, blinking caret, 取消 on the right) that filters the
 * current tab's cards as the search text changes. The field is a plain
 * text view, not an edit control: the keys under the sheet belong to the IME,
 * so the service routes them here while the field is open (see
 * {@code PimeImeService#onKey}) and nothing is committed to the editor.
 *
 * <p>It is a plain view, not an overlay of its own: the sheet is mounted in
 * the same key-area overlay the layout chooser uses, while the search mode is
 * mounted above the keyboard so the keys stay usable — the window grows for
 * it instead of covering them.
 */
final class ClipboardPanel extends FrameLayout {

    interface Listener {
        /** The user picked an entry: insert it into the editor. */
        void onInsert(ClipboardEntry entry);

        void onClearHistory();

        void onTogglePin(ClipboardEntry entry);

        void onAddToQuickSend(ClipboardEntry entry);

        void onRemoveFromQuickSend(ClipboardEntry entry);

        /** Moves a quick-send entry to the top of its list. */
        void onPinQuickSend(ClipboardEntry entry);

        void onDelete(ClipboardEntry entry);

        /** Multi-select confirmed: delete these entries in one go. */
        void onDeleteEntries(List<ClipboardEntry> entries);

        /** The sheet switched tabs; remembered across rebuilds. */
        void onTabChanged(int tab);

        /** The magnifier was tapped: the host swaps the sheet for a search field. */
        void onSearchOpen();

        /** 取消 in the field: back to the tabbed sheet. */
        void onSearchClose();

        /** The query changed; kept so a rebuild (history edit) restores it. */
        void onSearchQueryChanged(String query);

        /** The result list was dragged: the host folds the keys away. */
        void onSearchResultsDragged();

        /** The field was tapped: bring the keys back to type. */
        void onSearchFieldTapped();
    }

    private static final int TAB_CLIPBOARD = 0;
    private static final int TAB_QUICK_SEND = 1;

    private static final int CARD_HEIGHT_DP = 62;
    /** Search results are single-line rows, one per match, like the reference. */
    private static final int SEARCH_ROW_HEIGHT_DP = 48;
    private static final long CARET_BLINK_MS = 500;
    /** Decode box for grid thumbnails; also bounds the bitmap cache. */
    private static final int THUMB_MAX_PX = 384;

    /**
     * Thumbnails survive panel rebuilds (the panel is recreated on every
     * history change). Media files are content-addressed and immutable, so the
     * path alone is a safe key.
     */
    private static final LruCache<String, Bitmap> THUMBNAILS =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private final KeyboardTheme palette;
    private final Listener listener;
    private final List<ClipboardEntry> history;
    private final List<ClipboardEntry> quickSend;
    private final Set<Long> selected = new HashSet<>();

    private final LinearLayout header;
    private final LinearLayout bottomBar;
    private final RecyclerView grid;
    private final TextView emptyView;

    /** Search mode is fixed per instance: opening/closing rebuilds the panel. */
    private final boolean searching;
    private final Handler caretHandler = new Handler(Looper.getMainLooper());
    private final Runnable caretBlink = new Runnable() {
        @Override
        public void run() {
            caretOn = !caretOn;
            renderSearchField();
            caretHandler.postDelayed(this, CARET_BLINK_MS);
        }
    };
    private TextView searchField;
    private String query;
    private boolean caretOn = true;

    private int tab;
    private boolean multiSelect;
    private HistoryAdapter adapter;

    ClipboardPanel(Context context, KeyboardTheme palette, Listener listener,
                   List<ClipboardEntry> quickSend, List<ClipboardEntry> history,
                   int initialTab, boolean searchMode, String initialQuery) {
        super(context);
        this.palette = palette;
        this.listener = listener;
        this.history = history;
        this.quickSend = quickSend;
        this.searching = searchMode;
        this.query = initialQuery == null ? "" : initialQuery;
        setBackground(panelBackground());
        /* Empty sheet space belongs to the sheet: a tap on it must not fall
         * through to the dismiss-cover drawn behind the overlay. */
        setClickable(true);

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        /* Padding lives on the content, not the panel, so menu scrims cover
         * the full sheet instead of stopping at an inset border. */
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        final FrameLayout body = new FrameLayout(context);
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new RecyclerView(context);
        grid.setLayoutManager(new GridLayoutManager(context, searching ? 1 : 2));
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        /* A completed swipe over the results folds the keys away: the list
         * takes the room instead of scrolling under a keyboard. Folding on
         * release, not mid-drag, keeps the gesture from being cancelled by
         * the window resize. */
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private boolean dragging;

            @Override
            public void onScrollStateChanged(RecyclerView view, int state) {
                if (!searching) return;
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    dragging = true;
                } else if (state == RecyclerView.SCROLL_STATE_IDLE && dragging) {
                    dragging = false;
                    if (listener != null) listener.onSearchResultsDragged();
                }
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = label("", 13, false, palette.hint);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setMaxLines(2);
        emptyView.setPadding(dp(24), 0, dp(24), 0);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(10), 0, dp(10), 0);
        content.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        showTab(initialTab);
        if (searching) {
            renderSearchField();
            caretHandler.postDelayed(caretBlink, CARET_BLINK_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        caretHandler.removeCallbacks(caretBlink);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ search

    /** Appends typed text (a committed character, a word, a space). */
    void appendSearchText(String text) {
        if (text == null || text.isEmpty()) return;
        query += text;
        caretOn = true;
        renderSearchField();
        refreshGrid();
        if (listener != null) listener.onSearchQueryChanged(query);
    }

    /** Deletes one character (a code point) before the caret. */
    void deleteSearchChar() {
        if (query.isEmpty()) return;
        final int cut = query.offsetByCodePoints(query.length(), -1);
        query = query.substring(0, cut);
        caretOn = true;
        renderSearchField();
        refreshGrid();
        if (listener != null) listener.onSearchQueryChanged(query);
    }

    /** Clears the query (the keyboard's clear swipe while searching). */
    void clearSearchText() {
        if (query.isEmpty()) return;
        query = "";
        caretOn = true;
        renderSearchField();
        refreshGrid();
        if (listener != null) listener.onSearchQueryChanged(query);
    }

    /**
     * An empty field reads as a caret with the placeholder after it, so the
     * hint never looks like text the user typed and the caret sits where
     * typing would start. The caret slot is always in the string — blinking
     * only swaps its colour, so the text never reflows under the finger.
     */
    private void renderSearchField() {
        if (searchField == null) return;
        final String hint = getContext().getString(R.string.clipboard_search_hint);
        final boolean empty = query.isEmpty();
        final String body = empty ? "|" + hint : query + "|";
        final SpannableString text = new SpannableString(body);
        if (empty) {
            text.setSpan(new ForegroundColorSpan(palette.hint), 1, body.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final int caret = empty ? 0 : body.length() - 1;
        text.setSpan(new ForegroundColorSpan(caretOn ? palette.accent : Color.TRANSPARENT),
                caret, caret + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        searchField.setTextColor(palette.candidateText);
        searchField.setText(text);
    }

    // ------------------------------------------------------------ tabs

    /** Switches tabs: swaps the grid's data and the header's actions. */
    private void showTab(int newTab) {
        tab = newTab == TAB_QUICK_SEND ? TAB_QUICK_SEND : TAB_CLIPBOARD;
        multiSelect = false;
        selected.clear();
        fillHeader();
        refreshGrid();
        updateBottomBar();
        if (listener != null) listener.onTabChanged(tab);
    }

    /** The current tab's entries, narrowed by the query while searching. */
    private void refreshGrid() {
        final List<ClipboardEntry> source = tab == TAB_CLIPBOARD ? history : quickSend;
        List<ClipboardEntry> items = source;
        if (searching) {
            items = new ArrayList<>();
            if (!query.isEmpty()) {
                final String needle = query.toLowerCase(Locale.ROOT);
                for (ClipboardEntry entry : source) {
                    if (entryLabel(entry).toLowerCase(Locale.ROOT).contains(needle)) {
                        items.add(entry);
                    }
                }
            }
        }
        adapter = new HistoryAdapter(items);
        grid.setAdapter(adapter);
        final boolean empty = items.isEmpty();
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        /* No keyword yet means an empty result area, not the whole history. */
        final boolean noQuery = searching && query.isEmpty();
        emptyView.setText(searching
                ? R.string.clipboard_search_empty
                : tab == TAB_CLIPBOARD
                        ? R.string.clipboard_empty : R.string.clipboard_quick_send_empty);
        emptyView.setVisibility(empty && !noQuery ? View.VISIBLE : View.GONE);
    }

    private void fillHeader() {
        header.removeAllViews();

        if (searching) {
            final LinearLayout pill = new LinearLayout(getContext());
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(14), 0, dp(8), 0);
            final GradientDrawable pillFill = new GradientDrawable();
            pillFill.setCornerRadius(dp(17));
            pillFill.setColor(palette.rail);
            pill.setBackground(pillFill);

            searchField = label("", 14, false, palette.candidateText);
            searchField.setSingleLine(true);
            searchField.setEllipsize(TextUtils.TruncateAt.END);
            searchField.setGravity(Gravity.CENTER_VERTICAL);
            pill.addView(searchField, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            /* Tapping the field asks for the keys back after a result swipe
             * folded them away. */
            pill.setOnClickListener(v -> {
                if (listener != null) listener.onSearchFieldTapped();
            });

            final LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    0, dp(34), 1f);
            pillParams.gravity = Gravity.CENTER_VERTICAL;
            header.addView(pill, pillParams);

            final TextView cancel = label(getContext().getString(R.string.clipboard_cancel),
                    13, false, palette.candidateText);
            cancel.setGravity(Gravity.CENTER);
            cancel.setSingleLine(true);
            cancel.setPadding(dp(12), 0, dp(6), 0);
            cancel.setBackground(transparentRounded(8));
            cancel.setOnClickListener(v -> {
                if (listener != null) listener.onSearchClose();
            });
            final LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            cancelParams.gravity = Gravity.CENTER_VERTICAL;
            header.addView(cancel, cancelParams);
            return;
        }

        /* Segmented switch: a soft-accent track with the active tab in the
         * accent colour (same shape as the reference implementation). */
        final LinearLayout switchTrack = new LinearLayout(getContext());
        switchTrack.setOrientation(LinearLayout.HORIZONTAL);
        switchTrack.setPadding(dp(2), dp(2), dp(2), dp(2));
        final GradientDrawable trackFill = new GradientDrawable();
        trackFill.setCornerRadius(dp(13));
        trackFill.setColor(palette.accentSoft);
        switchTrack.setBackground(trackFill);
        switchTrack.addView(tabButton(R.string.clipboard_title, TAB_CLIPBOARD));
        switchTrack.addView(tabButton(R.string.clipboard_quick_send, TAB_QUICK_SEND));
        final LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        trackParams.gravity = Gravity.CENTER_VERTICAL;
        header.addView(switchTrack, trackParams);

        header.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 0, 1f));

        if (!multiSelect) {
            header.addView(iconAction(R.drawable.ic_search, R.string.clipboard_search,
                    v -> {
                        if (listener != null) listener.onSearchOpen();
                    }));
        }
        if (tab == TAB_CLIPBOARD && !multiSelect) {
            header.addView(iconAction(R.drawable.ic_trash, R.string.clipboard_clear_history,
                    v -> showClearConfirm()));
        } else if (multiSelect) {
            header.addView(textAction(R.string.clipboard_cancel, v -> exitMultiSelect()));
        }
    }

    private View tabButton(int labelRes, int index) {
        final boolean active = tab == index;
        final TextView button = label(getContext().getString(labelRes), 11,
                active, active ? palette.onAccent : palette.candidateText);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setPadding(dp(12), 0, dp(12), 0);
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(11));
        fill.setColor(active ? palette.accent : 0);
        button.setBackground(ripple(fill));
        button.setOnClickListener(v -> {
            if (tab != index) showTab(index);
        });
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return button;
    }

    private void enterMultiSelect(ClipboardEntry entry) {
        multiSelect = true;
        selected.clear();
        if (entry != null) selected.add(entry.id);
        fillHeader();
        updateBottomBar();
        notifyCardsChanged();
    }

    private void exitMultiSelect() {
        multiSelect = false;
        selected.clear();
        fillHeader();
        updateBottomBar();
        notifyCardsChanged();
    }

    private void deleteSelected() {
        final List<ClipboardEntry> doomed = new ArrayList<>();
        for (ClipboardEntry entry : history) {
            if (selected.contains(entry.id)) doomed.add(entry);
        }
        exitMultiSelect();
        if (!doomed.isEmpty() && listener != null) listener.onDeleteEntries(doomed);
    }

    private void notifyCardsChanged() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** Bottom bar in multi-select: how many are selected, and the delete button. */
    private void updateBottomBar() {
        bottomBar.removeAllViews();
        final boolean show = multiSelect && tab == TAB_CLIPBOARD;
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        final TextView count = label(getContext().getString(
                R.string.clipboard_selected_count, selected.size()), 13, false,
                palette.candidateText);
        bottomBar.addView(count, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView delete = label(getContext().getString(R.string.clipboard_delete),
                13, true, palette.onAccent);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(16), dp(7), dp(16), dp(7));
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(16));
        fill.setColor(palette.accent);
        delete.setBackground(ripple(fill));
        delete.setOnClickListener(v -> deleteSelected());
        bottomBar.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------ cards

    private void onCardClick(ClipboardEntry entry) {
        if (multiSelect && tab == TAB_CLIPBOARD) {
            if (!selected.remove(entry.id)) selected.add(entry.id);
            updateBottomBar();
            notifyCardsChanged();
            return;
        }
        if (listener != null) listener.onInsert(entry);
    }

    private void onCardLongPress(ClipboardEntry entry) {
        if (multiSelect) {
            exitMultiSelect();
            return;
        }
        showItemMenu(entry);
    }

    /** One card: text preview, or an image/video tile. */
    private final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {
        private static final int TYPE_TEXT = 0;
        private static final int TYPE_IMAGE = 1;
        private static final int TYPE_VIDEO = 2;

        private final List<ClipboardEntry> entries;

        HistoryAdapter(List<ClipboardEntry> entries) {
            this.entries = entries;
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public int getItemViewType(int position) {
            /* Search results are text rows, media included: only the searchable
             * text is shown, not a tile. */
            if (searching) return TYPE_TEXT;
            final ClipboardEntry entry = entries.get(position);
            return entry.isImage() ? TYPE_IMAGE : entry.isVideo() ? TYPE_VIDEO : TYPE_TEXT;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            final FrameLayout card = new FrameLayout(parent.getContext());
            final GridLayoutManager.LayoutParams params = new GridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(searching ? SEARCH_ROW_HEIGHT_DP : CARD_HEIGHT_DP));
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            card.setLayoutParams(params);
            /* Ripple in the foreground: the image tile would hide a background ripple. */
            card.setForeground(cardRipple());

            final Holder holder;
            if (viewType == TYPE_TEXT) {
                final TextView text = label("", 14, false, palette.candidateText);
                text.setMaxLines(searching ? 1 : 2);
                text.setEllipsize(TextUtils.TruncateAt.END);
                text.setPadding(dp(10), dp(9), dp(10), dp(9));
                text.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(text, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                holder = new Holder(card, text, null, null, null);
            } else {
                final FrameLayout tile = new FrameLayout(parent.getContext());
                card.addView(tile, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                final ImageView image = new ImageView(parent.getContext());
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(palette.rail);
                tile.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                final TextView chip = mediaLabel();
                final FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START);
                chipParams.setMargins(dp(4), 0, 0, dp(4));
                tile.addView(chip, chipParams);

                final TextView placeholder = label("", 13, false, palette.hint);
                placeholder.setGravity(Gravity.CENTER);
                tile.addView(placeholder, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                holder = new Holder(card, null, image, chip, placeholder);
            }

            card.setOnClickListener(v -> {
                if (holder.entry != null) onCardClick(holder.entry);
            });
            card.setOnLongClickListener(v -> {
                if (holder.entry != null) onCardLongPress(holder.entry);
                return true;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            final ClipboardEntry entry = entries.get(position);
            holder.entry = entry;
            holder.itemView.setBackground(cardBackground(entry));

            if (holder.text != null) {
                if (!searching || query.isEmpty()) {
                    holder.text.setText(entryLabel(entry));
                    return;
                }
                /* Paint every hit in the accent colour, like the reference. */
                final String source = entryLabel(entry);
                final String haystack = source.toLowerCase(Locale.ROOT);
                final String needle = query.toLowerCase(Locale.ROOT);
                final SpannableString highlighted = new SpannableString(source);
                int from = 0;
                while (true) {
                    final int at = haystack.indexOf(needle, from);
                    if (at < 0) break;
                    highlighted.setSpan(new ForegroundColorSpan(palette.accent),
                            at, at + needle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    from = at + needle.length();
                }
                holder.text.setText(highlighted);
                return;
            }
            if (entry.isImage()) {
                final Bitmap thumb = thumbFor(entry);
                if (thumb != null) {
                    holder.image.setVisibility(View.VISIBLE);
                    holder.image.setImageBitmap(thumb);
                    holder.placeholder.setVisibility(View.GONE);
                    holder.chip.setVisibility(View.VISIBLE);
                    holder.chip.setText(entryLabel(entry));
                } else {
                    holder.image.setVisibility(View.GONE);
                    holder.chip.setVisibility(View.GONE);
                    holder.placeholder.setVisibility(View.VISIBLE);
                    holder.placeholder.setText(entryLabel(entry));
                }
            } else {
                holder.image.setVisibility(View.GONE);
                holder.chip.setVisibility(View.GONE);
                holder.placeholder.setVisibility(View.VISIBLE);
                holder.placeholder.setText(entryLabel(entry));
            }
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView text;
            final ImageView image;
            final TextView chip;
            final TextView placeholder;
            ClipboardEntry entry;

            Holder(View itemView, TextView text, ImageView image, TextView chip,
                   TextView placeholder) {
                super(itemView);
                this.text = text;
                this.image = image;
                this.chip = chip;
                this.placeholder = placeholder;
            }
        }
    }

    private static String entryLabel(ClipboardEntry entry) {
        if (entry.isImage()) return ClipboardEntry.IMAGE_PLACEHOLDER;
        if (entry.isVideo()) return ClipboardEntry.VIDEO_PLACEHOLDER;
        return entry.text;
    }

    private TextView mediaLabel() {
        final TextView label = new TextView(getContext());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTextColor(0xFFFFFFFF);
        label.setSingleLine(true);
        label.setPadding(dp(5), dp(1), dp(5), dp(1));
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(5));
        fill.setColor(0x66000000);
        label.setBackground(fill);
        return label;
    }

    private Drawable cardBackground(ClipboardEntry entry) {
        final boolean isSelected = multiSelect && selected.contains(entry.id);
        /* Quick-send rows are stored pinned, so the pin tint is a history-only cue. */
        final boolean isPinned = tab == TAB_CLIPBOARD && entry.pinned;
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(8));
        fill.setColor(isSelected || isPinned ? palette.accentSoft : palette.rail);
        if (isSelected) fill.setStroke(Math.max(1, dp(1)), palette.accent);
        return fill;
    }

    private Drawable cardRipple() {
        final GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(8));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, mask);
    }

    /** Decodes a small square thumbnail, honouring {@code inSampleSize} for speed. */
    private static Bitmap thumbFor(ClipboardEntry entry) {
        final Bitmap cached = THUMBNAILS.get(entry.mediaPath);
        if (cached != null) return cached;
        if (!new File(entry.mediaPath).exists()) return null;
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(entry.mediaPath, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= THUMB_MAX_PX
                || bounds.outHeight / (sample * 2) >= THUMB_MAX_PX) {
            sample *= 2;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        final Bitmap bitmap = BitmapFactory.decodeFile(entry.mediaPath, options);
        if (bitmap != null) THUMBNAILS.put(entry.mediaPath, bitmap);
        return bitmap;
    }

    // ------------------------------------------------------------ long-press menu

    private void showItemMenu(ClipboardEntry entry) {
        final FrameLayout scrim = scrim();

        /* Side by side: the preview takes the slack width, the menu keeps a
         * fixed column. Stacked, a preview plus the menu rows is taller than
         * the key area and the sheet would clip it. */
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout previewCard = new LinearLayout(getContext());
        previewCard.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable previewFill = new GradientDrawable();
        previewFill.setCornerRadius(dp(10));
        previewFill.setColor(palette.rail);
        previewCard.setBackground(previewFill);
        previewCard.setPadding(dp(8), dp(8), dp(8), dp(8));

        final Bitmap thumb = entry.isImage() ? thumbFor(entry) : null;
        if (thumb != null) {
            final ImageView image = new ImageView(getContext());
            image.setImageBitmap(thumb);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            previewCard.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
        }

        final TextView preview = label(entryLabel(entry), 13, false, palette.candidateText);
        preview.setMaxLines(thumb != null ? 2 : 6);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (thumb != null) previewParams.topMargin = dp(6);
        previewCard.addView(preview, previewParams);
        row.addView(previewCard, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout menu = dialogCard();
        menu.setPadding(0, dp(4), 0, dp(4));
        if (tab == TAB_QUICK_SEND) {
            menu.addView(menuItem(R.drawable.ic_unfold_more, R.string.clipboard_pin, v -> {
                removeView(scrim);
                if (listener != null) listener.onPinQuickSend(entry);
            }));
            menu.addView(menuItem(R.drawable.ic_trash, R.string.clipboard_delete, v -> {
                removeView(scrim);
                if (listener != null) listener.onRemoveFromQuickSend(entry);
            }));
        } else {
            menu.addView(menuItem(entry.pinned ? R.drawable.ic_check : R.drawable.ic_unfold_more,
                    entry.pinned ? R.string.clipboard_unpin : R.string.clipboard_pin, v -> {
                        removeView(scrim);
                        if (listener != null) listener.onTogglePin(entry);
                    }));
            menu.addView(menuItem(R.drawable.ic_add, R.string.clipboard_add_quick_send, v -> {
                removeView(scrim);
                if (listener != null) listener.onAddToQuickSend(entry);
            }));
            /* Search rows are a flat result list; batching deletes stays a
             * gesture of the tabbed sheet. */
            if (!searching) {
                menu.addView(menuItem(R.drawable.ic_view_list,
                        R.string.clipboard_multi_select, v -> {
                            removeView(scrim);
                            enterMultiSelect(entry);
                        }));
            }
            menu.addView(menuItem(R.drawable.ic_trash, R.string.clipboard_delete, v -> {
                removeView(scrim);
                if (listener != null) listener.onDelete(entry);
            }));
        }
        final LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                dp(156), ViewGroup.LayoutParams.WRAP_CONTENT);
        menuParams.setMarginStart(dp(8));
        row.addView(menu, menuParams);

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        scrim.addView(row, params);
        addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View menuItem(int iconRes, int labelRes, OnClickListener click) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setBackground(transparentRounded(8));
        row.setOnClickListener(click);

        final ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(palette.hint));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        final TextView text = label(getContext().getString(labelRes), 15, false,
                palette.candidateText);
        final LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.setMarginStart(dp(12));
        row.addView(text, textParams);
        return row;
    }

    // ------------------------------------------------------------ clear confirm

    private void showClearConfirm() {
        final FrameLayout scrim = scrim();
        final LinearLayout card = dialogCard();
        card.setPadding(dp(18), dp(16), dp(18), dp(10));

        final TextView title = label(getContext().getString(R.string.clipboard_clear_history),
                16, true, palette.candidateText);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView message = label(getContext().getString(
                        R.string.clipboard_clear_confirm_message, history.size()),
                13, false, palette.hint);
        final LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(6);
        card.addView(message, messageParams);

        final LinearLayout buttons = new LinearLayout(getContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        final LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(12);
        buttons.addView(dialogButton(R.string.clipboard_cancel, false, v -> removeView(scrim)));
        buttons.addView(dialogButton(R.string.clipboard_clear_confirm_action, true, v -> {
            removeView(scrim);
            if (listener != null) listener.onClearHistory();
        }));
        card.addView(buttons, buttonsParams);

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        params.leftMargin = dp(24);
        params.rightMargin = dp(24);
        scrim.addView(card, params);
        addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView dialogButton(int labelRes, boolean primary, OnClickListener click) {
        final TextView button = label(getContext().getString(labelRes), 14, primary,
                primary ? palette.onAccent : palette.candidateText);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(16));
        fill.setColor(primary ? palette.accent : 0);
        button.setBackground(ripple(fill));
        button.setOnClickListener(click);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        button.setLayoutParams(params);
        return button;
    }

    private FrameLayout scrim() {
        final FrameLayout scrim = new FrameLayout(getContext());
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> removeView(scrim));
        return scrim;
    }

    private LinearLayout dialogCard() {
        final LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(14));
        fill.setColor(palette.panel);
        card.setBackground(fill);
        card.setClickable(true);
        return card;
    }

    // ------------------------------------------------------------ small views

    private TextView label(CharSequence text, int sp, boolean bold, int color) {
        final TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            label.setTypeface(android.graphics.Typeface.create(
                    "sans-serif", android.graphics.Typeface.BOLD));
        }
        label.setTextColor(color);
        return label;
    }

    private TextView textAction(int res, OnClickListener click) {
        final TextView action = label(getContext().getString(res), 13, false, palette.accent);
        action.setGravity(Gravity.CENTER);
        action.setSingleLine(true);
        action.setPadding(dp(10), 0, dp(10), 0);
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(8));
        fill.setColor(0);
        action.setBackground(ripple(fill));
        action.setOnClickListener(click);
        action.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return action;
    }

    private View iconAction(int iconRes, int descriptionRes, OnClickListener click) {
        final ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconRes);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setImageTintList(ColorStateList.valueOf(palette.hint));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(transparentRounded(16));
        button.setContentDescription(getContext().getString(descriptionRes));
        button.setOnClickListener(click);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(36), dp(36));
        params.gravity = Gravity.CENTER_VERTICAL;
        button.setLayoutParams(params);
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

    private Drawable transparentRounded(int radiusDp) {
        final GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(radiusDp));
        fill.setColor(0);
        return ripple(fill);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
