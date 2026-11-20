package com.pimeorg.pime;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clipboard history manager.
 *
 * <p>Listens to the system clipboard, stores text or image/video entries in
 * {@link ClipboardStore}, and keeps media files under {@code filesDir/clipboard_images}
 * and {@code filesDir/clipboard_videos} named by content sha256 (so the same bytes
 * copied twice land on one file and bump one entry).
 *
 * <p>Threading: the listener callback runs on the main thread and only decides what
 * the clip <em>is</em>; the copy-into-history work (file reads, SQLite) runs on a
 * single-thread executor. Refreshed history/quick-send lists are cached in volatile
 * fields so the panel can read them without touching the DB on the main thread, and
 * the {@link Listener} is told to redraw on the main thread.
 */
final class ClipboardHistory {
    interface Listener {
        void onClipboardChanged();
    }

    private static final int MAX_QUICK_SEND = 20;
    /** Per-image byte cap. The whole image is read into memory, so gate it. */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** Per-video byte cap; videos are copied whole too, just with a larger ceiling. */
    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;
    /** Files dropped this recently are not pruned: a write and its DB row are not atomic. */
    private static final long ORPHAN_GRACE_MS = 60_000L;

    private static final String IMAGE_DIR = "clipboard_images";
    private static final String VIDEO_DIR = "clipboard_videos";

    private static volatile ClipboardHistory instance;

    static ClipboardHistory getInstance(Context context) {
        if (instance == null) {
            synchronized (ClipboardHistory.class) {
                if (instance == null) {
                    instance = new ClipboardHistory(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context context;
    private final ClipboardStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClipboardManager systemClipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            this::onSystemClipboardChanged;

    private volatile List<ClipboardEntry> historyCache = Collections.emptyList();
    private volatile List<ClipboardEntry> quickSendCache = Collections.emptyList();
    private volatile Listener listener;

    private ClipboardHistory(Context context) {
        this.context = context;
        this.store = ClipboardStore.getInstance(context);
        this.systemClipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        systemClipboard.addPrimaryClipChangedListener(clipListener);
        /* Apply the stored cleanup policy once at startup: "keep 7 days" must
         * not wait for the next copy to take effect. */
        executor.execute(() -> {
            trimToPolicy();
            refresh();
        });
    }

    List<ClipboardEntry> history() {
        return historyCache;
    }

    List<ClipboardEntry> quickSendItems() {
        return quickSendCache;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------ system clipboard

    private void onSystemClipboardChanged() {
        captureClip(3);
    }

    /**
     * Captures the primary clip. Some apps publish the clip asynchronously, so
     * the change callback can fire before the data is readable; a null clip is
     * retried a few times before giving up.
     */
    private void captureClip(int retries) {
        if (systemClipboard == null) return;
        final ClipData clip = systemClipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            if (retries > 0) main.postDelayed(() -> captureClip(retries - 1), 100);
            return;
        }
        /* API 33+ lets the producer mark password-manager output as sensitive;
         * history keeps those out unless the user opts in. */
        if (isSensitiveClip(clip) && !Prefs.clipboardCaptureSensitive(context)) return;
        final ClipData.Item item = clip.getItemAt(0);
        final Uri uri = item.getUri();
        if (uri != null) {
            final String mime = mimeOf(uri);
            if (mime == null) {
                /* Undeclared type (some apps omit it): sniff the first bytes,
                 * off the main thread. Media is stored as media; anything else
                 * falls back to the item's text — never the content:// string,
                 * which could not be pasted as text. */
                final CharSequence text = item.getText();
                executor.execute(() -> {
                    final String sniffed = sniffMediaMime(uri);
                    if (sniffed != null) {
                        addMediaFromUri(uri, sniffed, sniffed.startsWith("video/")
                                ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES);
                    } else if (text != null && text.length() > 0) {
                        addText(text.toString());
                    }
                });
                return;
            }
            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                final long cap = mime.startsWith("video/") ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
                executor.execute(() -> addMediaFromUri(uri, mime, cap));
                return;
            }
        }
        final CharSequence text = item.getText() != null
                ? item.getText()
                : uri == null && item.getIntent() != null ? item.getIntent().toUri(0) : null;
        if (text == null || text.length() == 0) return;
        final String value = text.toString();
        executor.execute(() -> addText(value));
    }

    private void addText(String value) {
        store.upsertText(value, System.currentTimeMillis(), countLimit());
        trimToPolicy();
        refresh();
    }

    private static boolean isSensitiveClip(ClipData clip) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        final PersistableBundle extras = clip.getDescription().getExtras();
        return extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false);
    }

    private String mimeOf(Uri uri) {
        try {
            return context.getContentResolver().getType(uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * MIME from the first 16 bytes, for clips whose producer declared no type.
     * Only image and video signatures are recognised; anything else is null.
     */
    private String sniffMediaMime(Uri uri) {
        final byte[] head = new byte[16];
        int count = 0;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            while (count < head.length) {
                final int read = input.read(head, count, head.length - count);
                if (read <= 0) break;
                count += read;
            }
        } catch (Exception ignored) {
            return null;
        }
        if (count < 12) return null;
        if (u8(head, 0) == 0x89 && u8(head, 1) == 0x50 && u8(head, 2) == 0x4E
                && u8(head, 3) == 0x47) return "image/png";
        if (u8(head, 0) == 0xFF && u8(head, 1) == 0xD8 && u8(head, 2) == 0xFF) return "image/jpeg";
        if (u8(head, 0) == 0x47 && u8(head, 1) == 0x49 && u8(head, 2) == 0x46
                && u8(head, 3) == 0x38) return "image/gif";
        if (u8(head, 0) == 0x42 && u8(head, 1) == 0x4D) return "image/bmp";
        if (u8(head, 0) == 0x1A && u8(head, 1) == 0x45 && u8(head, 2) == 0xDF
                && u8(head, 3) == 0xA3) return "video/webm";
        if (u8(head, 0) == 0x52 && u8(head, 1) == 0x49 && u8(head, 2) == 0x46
                && u8(head, 3) == 0x46) {
            if (u8(head, 8) == 0x57 && u8(head, 9) == 0x45 && u8(head, 10) == 0x42
                    && u8(head, 11) == 0x50) return "image/webp";
            if (u8(head, 8) == 0x41 && u8(head, 9) == 0x56 && u8(head, 10) == 0x49
                    && u8(head, 11) == 0x20) return "video/x-msvideo";
            return null;
        }
        /* ISO base media: brand at offset 8 says which family. */
        if (u8(head, 4) == 0x66 && u8(head, 5) == 0x74 && u8(head, 6) == 0x79
                && u8(head, 7) == 0x70) {
            final String brand = new String(head, 8, 4,
                    java.nio.charset.StandardCharsets.US_ASCII);
            switch (brand) {
                case "heic":
                case "heix":
                case "hevc":
                case "hevx":
                case "heim":
                case "heis":
                case "mif1":
                case "msf1":
                    return "image/heic";
                case "avif":
                case "avis":
                    return "image/avif";
                case "qt  ":
                    return "video/quicktime";
                case "3gp4":
                case "3gp5":
                case "3gp6":
                case "3gs7":
                    return "video/3gpp";
                case "isom":
                case "iso2":
                case "mp41":
                case "mp42":
                case "avc1":
                case "M4V":
                case "M4VH":
                case "M4VP":
                case "dash":
                    return "video/mp4";
                default:
                    return null;
            }
        }
        return null;
    }

    private static int u8(byte[] bytes, int index) {
        return bytes[index] & 0xFF;
    }

    private void addMediaFromUri(Uri uri, String mime, long maxBytes) {
        final byte[] bytes = readBytesCapped(uri, maxBytes);
        if (bytes == null) {
            main.post(() -> toast(R.string.clipboard_media_unreadable));
            return;
        }
        final File file = saveMedia(bytes, mime);
        if (file == null) return;
        final String placeholder = mime.startsWith("video/")
                ? ClipboardEntry.VIDEO_PLACEHOLDER : ClipboardEntry.IMAGE_PLACEHOLDER;
        store.upsertMedia(file.getAbsolutePath(), mime, placeholder,
                System.currentTimeMillis(), countLimit());
        trimToPolicy();
        refresh();
    }

    // ------------------------------------------------------------ mutations (UI)

    void remove(long id) {
        remove(Collections.singletonList(id));
    }

    /** Deletes several entries at once (the panel's multi-select). */
    void remove(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        executor.execute(() -> {
            store.delete(ids);
            purgeOrphanMedia();
            refresh();
        });
    }

    void clearHistory() {
        executor.execute(() -> {
            store.deleteHistory();
            purgeOrphanMedia();
            refresh();
        });
    }

    void clearAll() {
        executor.execute(() -> {
            store.deleteAll();
            purgeOrphanMedia();
            refresh();
        });
    }

    /** Deletes media files no entry references, now (ignores the grace window). */
    void clearMediaCache() {
        executor.execute(() -> {
            final java.util.Set<String> live =
                    new java.util.HashSet<>(store.allMediaPaths());
            deleteOrphans(new File(context.getFilesDir(), IMAGE_DIR), live, Long.MAX_VALUE);
            deleteOrphans(new File(context.getFilesDir(), VIDEO_DIR), live, Long.MAX_VALUE);
            refresh();
        });
    }

    void togglePin(long id) {
        executor.execute(() -> {
            store.togglePin(id);
            refresh();
        });
    }

    void addToQuickSend(long id) {
        executor.execute(() -> {
            store.addToQuickSend(id, System.currentTimeMillis(), MAX_QUICK_SEND);
            refresh();
        });
    }

    void removeFromQuickSend(long id) {
        executor.execute(() -> {
            store.removeFromQuickSend(id);
            refresh();
        });
    }

    /** Moves a quick-send entry to the top of the list. */
    void pinQuickSend(long id) {
        executor.execute(() -> {
            store.touch(id);
            refresh();
        });
    }

    /** Re-applies the cleanup policy (called by settings right after it changes). */
    void applyCleanupPolicy() {
        executor.execute(() -> {
            trimToPolicy();
            refresh();
        });
    }

    // ------------------------------------------------------------ media files

    /**
     * A FileProvider content URI for a stored media file, or null if it cannot be
     * exposed. Used to hand images/videos to the editor via {@code commitContent}.
     */
    Uri contentUriFor(File file) {
        try {
            return FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private File saveMedia(byte[] bytes, String mime) {
        try {
            final String dirName = mime.startsWith("video/") ? VIDEO_DIR : IMAGE_DIR;
            final File dir = new File(context.getFilesDir(), dirName);
            if (!dir.exists() && !dir.mkdirs()) return null;
            final File file = new File(dir, sha256Hex(bytes) + extensionFor(mime));
            if (file.exists() && file.length() == bytes.length) return file;
            /* Write beside the target and rename: a kill mid-write must not
             * leave a truncated file that later copies would reuse as
             * "already present" (names are content hashes). */
            final File tmp = new File(dir, file.getName() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return file;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extensionFor(String mime) {
        switch (mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/bmp":
            case "image/x-ms-bmp": return ".bmp";
            case "image/heic":
            case "image/heif": return ".heic";
            case "image/avif": return ".avif";
            case "video/mp4": return ".mp4";
            case "video/quicktime": return ".mov";
            case "video/webm": return ".webm";
            case "video/3gpp": return ".3gp";
            case "video/x-matroska": return ".mkv";
            case "video/x-msvideo": return ".avi";
            default: return mime != null && mime.startsWith("video/") ? ".mp4" : ".jpg";
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads a URI into memory, refusing anything over {@code maxBytes}. Checks the
     * declared length first when available, then counts while reading so an
     * oversized stream is never fully buffered.
     */
    private byte[] readBytesCapped(Uri uri, long maxBytes) {
        try {
            final android.content.res.AssetFileDescriptor descriptor =
                    context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (descriptor != null) {
                final long declared = descriptor.getLength();
                descriptor.close();
                if (declared > maxBytes) return null;
            }
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return null;
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) return null;
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------ cleanup

    /** History row cap for the insert path: 0 (unlimited) unless cleanup is by count. */
    private int countLimit() {
        return Prefs.CLIPBOARD_CLEANUP_COUNT.equals(Prefs.clipboardCleanupMode(context))
                ? Prefs.clipboardMaxItems(context) : 0;
    }

    /** Trims by count/days and enforces the media budget, per the current prefs. */
    private void trimToPolicy() {
        final int maxItems = Prefs.clipboardMaxItems(context);
        final String mode = Prefs.clipboardCleanupMode(context);
        if (Prefs.CLIPBOARD_CLEANUP_DAYS.equals(mode)) {
            final long days = Math.max(1, Prefs.clipboardRetainDays(context));
            store.deleteOlderThan(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000);
        } else if (Prefs.CLIPBOARD_CLEANUP_COUNT.equals(mode)) {
            /* upsertText/upsertMedia already trim by count; run once more so a
             * policy change through settings takes effect immediately. */
            store.trimHistoryTo(maxItems);
        }
        purgeOrphanMedia();
        enforceBudget(IMAGE_DIR, Prefs.clipboardImageBudgetMb(context));
        enforceBudget(VIDEO_DIR, Prefs.clipboardVideoBudgetMb(context));
    }

    /** Deletes media files no entry points at. Recent writes are skipped (see grace). */
    private void purgeOrphanMedia() {
        final List<String> referenced = store.allMediaPaths();
        final java.util.Set<String> live = new java.util.HashSet<>(referenced);
        final long cutoff = System.currentTimeMillis() - ORPHAN_GRACE_MS;
        deleteOrphans(new File(context.getFilesDir(), IMAGE_DIR), live, cutoff);
        deleteOrphans(new File(context.getFilesDir(), VIDEO_DIR), live, cutoff);
    }

    private static void deleteOrphans(File dir, java.util.Set<String> live, long cutoff) {
        final File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (!live.contains(file.getAbsolutePath()) && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** Deletes the oldest unpinned media entries until the directory fits the budget. */
    private void enforceBudget(String dirName, int budgetMb) {
        if (budgetMb <= 0) return;
        final long budget = budgetMb * 1024L * 1024;
        final File dir = new File(context.getFilesDir(), dirName);
        final File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= budget) return;
        for (ClipboardEntry entry : store.oldestUnpinnedMedia()) {
            if (total <= budget) break;
            final String prefix = new File(context.getFilesDir(), dirName).getAbsolutePath()
                    + File.separator;
            if (!entry.mediaPath.startsWith(prefix)) continue;
            final File file = new File(entry.mediaPath);
            final long size = file.length();
            store.delete(entry.id);
            /* Content-addressed: another entry may still point at this file. */
            if (store.countByPath(entry.mediaPath) == 0 && file.delete()) total -= size;
        }
    }

    // ------------------------------------------------------------ cache

    private void refresh() {
        historyCache = Collections.unmodifiableList(store.queryHistory());
        quickSendCache = Collections.unmodifiableList(store.queryQuickSend());
        final Listener current = listener;
        if (current != null) main.post(() -> current.onClipboardChanged());
    }

    private void toast(int res) {
        android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show();
    }
}