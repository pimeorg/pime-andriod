package com.pimeorg.pime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Downloads and extracts the streaming speech model used by the native
 * {@code pime-speech-sherpa} plugin.
 *
 * <p>WeaveText ships no weights in the APK either: the first voice turn fetches
 * a pinned archive, verifies its SHA-256 and extracts only the two files the
 * plugin opens. Mirrors are tried in order because GitHub is not reachable
 * everywhere.
 */
final class VoiceModels {
    private static final String TAG = "PimeVoiceModels";

    private static final String ARCHIVE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2";
    private static final String ARCHIVE_SHA256 =
            "b3b309f7ce4a737195fcc6963ea19b0653a7d3401580af5ae0d3e284cbb71f0b";
    private static final long ARCHIVE_BYTES = 21264113L;

    /* Ordered mirrors; the first entry is the original URL. */
    private static final String[] MIRRORS = {
            "{url}",
            "https://ghproxy.net/{url}",
            "https://gh-proxy.com/{url}",
            "https://ghfast.top/{url}",
            "https://gh.llkk.cc/{url}",
    };

    private static final String MODEL_NAME = "model.int8.onnx";
    private static final String TOKENS_NAME = "tokens.txt";
    private static final long MODEL_BYTES = 26342340L;
    private static final long TOKENS_BYTES = 13366L;

    private static final Object LOCK = new Object();
    private static boolean downloading;

    private VoiceModels() {
    }

    static File dir(Context context) {
        return new File(new File(context.getFilesDir(), "models"), "asr-stream-small");
    }

    static boolean isReady(Context context) {
        final File dir = dir(context);
        return fileHasSize(new File(dir, MODEL_NAME), MODEL_BYTES)
                && fileHasSize(new File(dir, TOKENS_NAME), TOKENS_BYTES);
    }

    /**
     * Fetches the model if needed, then runs {@code onReady} on the main
     * thread. A failed download leaves the model absent; the next mic tap
     * retries.
     */
    static void ensure(Context context, Runnable onReady, Runnable onFailed) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (downloading) return;
            if (isReady(app)) {
                new Handler(Looper.getMainLooper()).post(onReady);
                return;
            }
            downloading = true;
        }
        new Thread(() -> {
            boolean ok = false;
            try {
                install(app);
                ok = isReady(app);
            } catch (Exception error) {
                Log.w(TAG, "voice model download failed", error);
            } finally {
                synchronized (LOCK) {
                    downloading = false;
                }
            }
            final Runnable callback = ok ? onReady : onFailed;
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }, "pime-voice-model").start();
    }

    private static void install(Context context) throws IOException {
        final File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        final File cache = new File(context.getCacheDir(), "asr-stream-small.tar.bz2");
        if (!fileHasSize(cache, ARCHIVE_BYTES)
                || !ARCHIVE_SHA256.equals(sha256(cache))) {
            download(cache);
        }
        extract(cache, dir);
    }

    private static void download(File target) throws IOException {
        IOException last = null;
        for (String template : MIRRORS) {
            final String url = template.replace("{url}", ARCHIVE);
            final File part = new File(target.getPath() + ".part");
            try {
                copy(url, part);
                final String got = sha256(part);
                if (!ARCHIVE_SHA256.equals(got)) {
                    throw new IOException("sha256 mismatch: " + got);
                }
                if (!part.renameTo(target)) throw new IOException("rename failed");
                Log.i(TAG, "voice model downloaded via " + url);
                return;
            } catch (IOException error) {
                last = error;
                part.delete();
                Log.w(TAG, url + ": " + error.getMessage());
            }
        }
        throw last != null ? last : new IOException("no mirror");
    }

    private static void copy(String url, File target) throws IOException {
        final HttpURLConnection connection =
                (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "pime-android");
        try {
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(target)) {
                final byte[] buffer = new byte[65536];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) > 0) {
                    total += read;
                    if (total > ARCHIVE_BYTES * 2) {
                        throw new IOException("archive too large");
                    }
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Extracts only the model and token file; paths in the archive are ignored. */
    private static void extract(File archive, File dir) throws IOException {
        final File modelTmp = new File(dir, MODEL_NAME + ".tmp");
        final File tokensTmp = new File(dir, TOKENS_NAME + ".tmp");
        boolean model = false;
        boolean tokens = false;
        try (InputStream raw = new FileInputStream(archive);
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                final String name = entry.getName();
                final boolean isModel = name.endsWith("/" + MODEL_NAME);
                final boolean isTokens = name.endsWith("/" + TOKENS_NAME);
                if (!isModel && !isTokens) continue;
                final long limit = isModel ? MODEL_BYTES : TOKENS_BYTES;
                if (entry.getSize() != limit) {
                    throw new IOException("unexpected size for " + name);
                }
                final File target = isModel ? modelTmp : tokensTmp;
                copyEntry(tar, target, limit);
                if (isModel) model = true;
                else tokens = true;
            }
        }
        if (!model || !tokens) throw new IOException("archive lacks model files");
        if (!modelTmp.renameTo(new File(dir, MODEL_NAME))) {
            throw new IOException("cannot install model");
        }
        if (!tokensTmp.renameTo(new File(dir, TOKENS_NAME))) {
            throw new IOException("cannot install tokens");
        }
        archive.delete();
    }

    private static void copyEntry(InputStream input, File target, long limit)
            throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            final byte[] buffer = new byte[65536];
            long total = 0;
            while (total < limit) {
                final int want = (int) Math.min(buffer.length, limit - total);
                final int read = input.read(buffer, 0, want);
                if (read <= 0) throw new IOException("truncated archive");
                output.write(buffer, 0, read);
                total += read;
            }
        }
    }

    private static boolean fileHasSize(File file, long size) {
        return file.isFile() && file.length() == size;
    }

    private static String sha256(File file) {
        try (InputStream input = new FileInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            final StringBuilder text = new StringBuilder();
            for (byte value : digest.digest()) {
                text.append(Character.forDigit((value >> 4) & 0xf, 16));
                text.append(Character.forDigit(value & 0xf, 16));
            }
            return text.toString();
        } catch (IOException | NoSuchAlgorithmException error) {
            return "";
        }
    }
}
