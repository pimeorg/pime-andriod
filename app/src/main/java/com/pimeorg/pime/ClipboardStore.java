package com.pimeorg.pime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Clipboard history storage: one {@code clipboard_entries} table, plain
 * {@link SQLiteOpenHelper} (the host has no Room/annotation processing).
 *
 * <p>History rows and quick-send rows share the table, told apart by
 * {@code is_quick_send}: a promoted quick item is a <em>copy</em> of the history
 * row (like the reference), so clearing history never takes a quick item with
 * it. Every call is {@code synchronized}: the store is touched from the
 * history's single-thread executor and from settings, and SQLite on one
 * connection does not want two writers at once.
 */
final class ClipboardStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "clipboard.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "clipboard_entries";

    private static volatile ClipboardStore instance;

    static ClipboardStore getInstance(Context context) {
        if (instance == null) {
            synchronized (ClipboardStore.class) {
                if (instance == null) {
                    instance = new ClipboardStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ClipboardStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "text TEXT NOT NULL DEFAULT '',"
                + "media_path TEXT NOT NULL DEFAULT '',"
                + "mime TEXT NOT NULL DEFAULT '',"
                + "timestamp INTEGER NOT NULL DEFAULT 0,"
                + "is_pinned INTEGER NOT NULL DEFAULT 0,"
                + "is_quick_send INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_clipboard_ts ON " + TABLE + " (timestamp)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        /* v1 is the first schema; nothing to migrate yet. */
    }

    // ------------------------------------------------------------ inserts

    /**
     * Stores copied text. The same text (history only, media excluded) is
     * bumped to the top instead of duplicated, then the history is trimmed to
     * {@code maxItems} (0 = no limit).
     */
    synchronized void upsertText(String text, long now, int maxItems) {
        final SQLiteDatabase db = getWritableDatabase();
        final long existing = findHistoryIdByText(db, text);
        if (existing > 0) {
            updateTimestamp(db, existing, now);
        } else {
            final ContentValues values = new ContentValues();
            values.put("text", text);
            values.put("timestamp", now);
            db.insert(TABLE, null, values);
            trimHistory(db, maxItems);
        }
    }

    /** Stores an image/video entry, keyed by its local path (content-addressed). */
    synchronized void upsertMedia(String mediaPath, String mime, String placeholder,
                                  long now, int maxItems) {
        final SQLiteDatabase db = getWritableDatabase();
        final long existing = findHistoryIdByPath(db, mediaPath);
        if (existing > 0) {
            updateTimestamp(db, existing, now);
        } else {
            final ContentValues values = new ContentValues();
            values.put("text", placeholder);
            values.put("media_path", mediaPath);
            values.put("mime", mime);
            values.put("timestamp", now);
            db.insert(TABLE, null, values);
            trimHistory(db, maxItems);
        }
    }

    // ------------------------------------------------------------ queries

    synchronized List<ClipboardEntry> queryHistory() {
        return query("is_quick_send = 0 ORDER BY is_pinned DESC, timestamp DESC", null);
    }

    synchronized List<ClipboardEntry> queryQuickSend() {
        return query("is_quick_send = 1 ORDER BY timestamp DESC", null);
    }

    private List<ClipboardEntry> query(String where, String[] args) {
        final List<ClipboardEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, where, args,
                null, null, null)) {
            while (cursor.moveToNext()) {
                entries.add(read(cursor));
            }
        }
        return entries;
    }

    private static ClipboardEntry read(Cursor cursor) {
        return new ClipboardEntry(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("text")),
                cursor.getString(cursor.getColumnIndexOrThrow("media_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("mime")),
                cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")) != 0,
                cursor.getInt(cursor.getColumnIndexOrThrow("is_quick_send")) != 0);
    }

    // ------------------------------------------------------------ mutations

    synchronized void delete(long id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Deletes several rows (multi-select in the panel). */
    synchronized void delete(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        final StringBuilder where = new StringBuilder("id IN (");
        final String[] args = new String[ids.size()];
        int i = 0;
        for (Long id : ids) {
            if (i > 0) where.append(',');
            where.append('?');
            args[i++] = String.valueOf(id);
        }
        where.append(')');
        getWritableDatabase().delete(TABLE, where.toString(), args);
    }

    /** Clears the history but keeps quick-send items. */
    synchronized void deleteHistory() {
        getWritableDatabase().delete(TABLE, "is_quick_send = 0", null);
    }

    /** Clears history and quick-send items alike. */
    synchronized void deleteAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    synchronized void togglePin(long id) {
        getWritableDatabase().execSQL("UPDATE " + TABLE
                + " SET is_pinned = 1 - is_pinned WHERE id = ? AND is_quick_send = 0",
                new Object[]{id});
    }

    /**
     * Promotes a history entry to quick send by copying it into a new row
     * (pinned, is_quick_send = 1). A text/path already present in quick send is
     * just bumped to the top; the list is capped at {@code maxQuick}.
     */
    synchronized void addToQuickSend(long id, long now, int maxQuick) {
        final SQLiteDatabase db = getWritableDatabase();
        final ClipboardEntry source = findById(db, id);
        if (source == null || source.quickSend) return;
        final long existing = source.isMedia()
                ? findQuickSendIdByPath(db, source.mediaPath)
                : findQuickSendIdByText(db, source.text);
        if (existing > 0) {
            updateTimestamp(db, existing, now);
        } else {
            final ContentValues values = new ContentValues();
            values.put("text", source.text);
            values.put("media_path", source.mediaPath);
            values.put("mime", source.mime);
            values.put("timestamp", now);
            values.put("is_pinned", 1);
            values.put("is_quick_send", 1);
            db.insert(TABLE, null, values);
            trimQuickSend(db, maxQuick);
        }
    }

    synchronized void removeFromQuickSend(long id) {
        getWritableDatabase().delete(TABLE, "id = ? AND is_quick_send = 1",
                new String[]{String.valueOf(id)});
    }

    /** Bumps a quick-send row to the top (the list is ordered by timestamp). */
    synchronized void touch(long id) {
        final ContentValues values = new ContentValues();
        values.put("timestamp", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, "id = ? AND is_quick_send = 1",
                new String[]{String.valueOf(id)});
    }

    // ------------------------------------------------------------ cleanup

    /** Re-trims the history to {@code maxItems} now (a policy change in settings). */
    synchronized void trimHistoryTo(int maxItems) {
        trimHistory(getWritableDatabase(), maxItems);
    }

    /** Deletes the oldest auto-cleanable rows (unpinned, non-quick-send) beyond the limit. */
    private void trimHistory(SQLiteDatabase db, int maxItems) {
        if (maxItems <= 0) return;
        final int cleanable = countAutoCleanable(db);
        if (cleanable <= maxItems) return;
        db.execSQL("DELETE FROM " + TABLE + " WHERE id IN ("
                + "SELECT id FROM " + TABLE + " WHERE " + CLEANABLE_WHERE
                + " ORDER BY timestamp ASC LIMIT ?)",
                new Object[]{cleanable - maxItems});
    }

    private void trimQuickSend(SQLiteDatabase db, int maxQuick) {
        if (maxQuick <= 0) return;
        final int count = countQuickSend(db);
        if (count <= maxQuick) return;
        db.execSQL("DELETE FROM " + TABLE + " WHERE id IN ("
                + "SELECT id FROM " + TABLE + " WHERE is_quick_send = 1"
                + " ORDER BY timestamp ASC LIMIT ?)",
                new Object[]{count - maxQuick});
    }

    /** Rows eligible for automatic cleanup: history only, not pinned. */
    private static final String CLEANABLE_WHERE = "is_quick_send = 0 AND is_pinned = 0";

    private static int countAutoCleanable(SQLiteDatabase db) {
        return count(db, "SELECT COUNT(*) FROM " + TABLE + " WHERE " + CLEANABLE_WHERE, null);
    }

    private static int countQuickSend(SQLiteDatabase db) {
        return count(db, "SELECT COUNT(*) FROM " + TABLE + " WHERE is_quick_send = 1", null);
    }

    private static int count(SQLiteDatabase db, String sql, Object[] args) {
        try (Cursor cursor = db.rawQuery(sql, args == null ? null
                : toStringArgs(args))) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Deletes history rows older than {@code cutoff}; pinned and quick-send survive. */
    synchronized int deleteOlderThan(long cutoff) {
        return getWritableDatabase().delete(TABLE,
                CLEANABLE_WHERE + " AND timestamp < ?", new String[]{String.valueOf(cutoff)});
    }

    // ------------------------------------------------------------ media files

    synchronized List<String> allMediaPaths() {
        final List<String> paths = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT media_path FROM " + TABLE + " WHERE media_path != ''", null)) {
            while (cursor.moveToNext()) paths.add(cursor.getString(0));
        }
        return paths;
    }

    synchronized int countByPath(String path) {
        return count(getReadableDatabase(),
                "SELECT COUNT(*) FROM " + TABLE + " WHERE media_path = ?",
                new Object[]{path});
    }

    /** Unpinned history media entries, oldest first: candidates for the size budget. */
    synchronized List<ClipboardEntry> oldestUnpinnedMedia() {
        return query("media_path != '' AND is_quick_send = 0 AND is_pinned = 0"
                + " ORDER BY timestamp ASC", null);
    }

    // ------------------------------------------------------------ helpers

    private static long findHistoryIdByText(SQLiteDatabase db, String text) {
        return firstId(db, "SELECT id FROM " + TABLE
                + " WHERE text = ? AND media_path = '' AND is_quick_send = 0 LIMIT 1", text);
    }

    private static long findHistoryIdByPath(SQLiteDatabase db, String path) {
        return firstId(db, "SELECT id FROM " + TABLE
                + " WHERE media_path = ? AND is_quick_send = 0 LIMIT 1", path);
    }

    private static long findQuickSendIdByText(SQLiteDatabase db, String text) {
        return firstId(db, "SELECT id FROM " + TABLE
                + " WHERE text = ? AND media_path = '' AND is_quick_send = 1 LIMIT 1", text);
    }

    private static long findQuickSendIdByPath(SQLiteDatabase db, String path) {
        return firstId(db, "SELECT id FROM " + TABLE
                + " WHERE media_path = ? AND is_quick_send = 1 LIMIT 1", path);
    }

    private static long firstId(SQLiteDatabase db, String sql, String arg) {
        try (Cursor cursor = db.rawQuery(sql, new String[]{arg})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static ClipboardEntry findById(SQLiteDatabase db, long id) {
        try (Cursor cursor = db.query(TABLE, null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    private static void updateTimestamp(SQLiteDatabase db, long id, long now) {
        final ContentValues values = new ContentValues();
        values.put("timestamp", now);
        db.update(TABLE, values, "id = ?", new String[]{String.valueOf(id)});
    }

    private static String[] toStringArgs(Object[] args) {
        final String[] result = new String[args.length];
        for (int i = 0; i < args.length; ++i) result[i] = String.valueOf(args[i]);
        return result;
    }
}