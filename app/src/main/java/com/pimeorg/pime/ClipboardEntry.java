package com.pimeorg.pime;

/**
 * One row of the clipboard history.
 *
 * <p>Text and media share the table: a text entry has an empty {@link #mediaPath}
 * (and its {@link #text} is the copied string); a media entry points at a file
 * under {@code filesDir/clipboard_*} and keeps a placeholder in {@link #text}.
 */
final class ClipboardEntry {
    /** Placeholder text a media entry shows in place of its content. */
    static final String IMAGE_PLACEHOLDER = "[图片]";
    static final String VIDEO_PLACEHOLDER = "[视频]";

    long id;
    String text = "";
    String mediaPath = "";
    String mime = "";
    long timestamp;
    boolean pinned;
    boolean quickSend;

    ClipboardEntry() {
    }

    ClipboardEntry(long id, String text, String mediaPath, String mime,
                   long timestamp, boolean pinned, boolean quickSend) {
        this.id = id;
        this.text = text == null ? "" : text;
        this.mediaPath = mediaPath == null ? "" : mediaPath;
        this.mime = mime == null ? "" : mime;
        this.timestamp = timestamp;
        this.pinned = pinned;
        this.quickSend = quickSend;
    }

    boolean isMedia() {
        return !mediaPath.isEmpty();
    }

    boolean isImage() {
        return isMedia() && mime.startsWith("image/");
    }

    boolean isVideo() {
        return isMedia() && mime.startsWith("video/");
    }
}