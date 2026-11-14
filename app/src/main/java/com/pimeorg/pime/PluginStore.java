package com.pimeorg.pime;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Installs plugin packages into files/plugins.
 *
 * <p>A package is the directory core loads: manifest.yaml, the runtime module
 * (plugin.wasm, or plugin.lua for the Lua runtime) and any assets. It arrives
 * either as a .zip of that directory or as the directory itself, both picked
 * through the system file chooser.
 *
 * <p>The copy lands in a hidden sibling directory and only then takes the
 * package's name, so a half-written package is never seen by the loader.
 */
public final class PluginStore {
    /** Entry modules core recognises; a package without one cannot be loaded. */
    private static final String[] ENTRY_FILES = {
            "plugin.wasm", "plugin.lua", "plugin.dll", "plugin.so", "plugin.dylib"};

    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 1L * 1024 * 1024;

    private PluginStore() {
    }

    /** The manifest fields the host needs; absent fields are empty. */
    public static final class Manifest {
        public final String id;
        public final String version;
        public final String role;
        /** Whether the manifest mentions this device's ABI at all. */
        public final boolean platformDeclared;

        Manifest(String id, String version, String role, boolean platformDeclared) {
            this.id = id;
            this.version = version;
            this.role = role;
            this.platformDeclared = platformDeclared;
        }

        /** Directory name for the installed package, matching the bundled ones. */
        public String packageName() {
            return "pime-plugin-" + sanitize(id) + "-" + sanitize(version);
        }

        @Override
        public String toString() {
            return id + " " + version;
        }
    }

    public static File dir(Context context) {
        return new File(context.getFilesDir(), "plugins");
    }

    /** This device's platform token, as manifests spell it. */
    public static String platformToken() {
        final String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        if (abi.startsWith("arm64")) return "android-arm64";
        if (abi.startsWith("x86_64")) return "android-x64";
        if (abi.startsWith("armeabi")) return "android-arm";
        if (abi.startsWith("x86")) return "android-x86";
        return "android-" + abi;
    }

    /**
     * Reads and validates the package behind a picked uri without copying it,
     * so the caller can confirm a replacement before moving any bytes.
     */
    public static Manifest readManifest(Context context, Uri picked) throws IOException {
        if (DocumentsContract.isTreeUri(picked)) return readTreeManifest(context, picked);
        final Archive archive = scanZip(context, picked);
        if (archive.manifestText == null) {
            throw new IOException(context.getString(R.string.plugin_import_no_manifest));
        }
        if (!archive.hasEntry) {
            throw new IOException(context.getString(R.string.plugin_import_no_entry));
        }
        return parseManifest(context, archive.manifestText);
    }

    /**
     * Copies the picked package into files/plugins under its manifest name.
     * With {@code replace} false an existing package is refused.
     */
    public static File install(Context context, Uri picked, boolean replace) throws IOException {
        final Manifest manifest = readManifest(context, picked);
        final File root = dir(context);
        final File target = new File(root, manifest.packageName());
        if (target.exists() && !replace) {
            throw new IOException(
                    context.getString(R.string.plugin_import_exists, target.getName()));
        }

        final File staging = new File(root, ".import-" + System.nanoTime());
        deleteTree(staging);
        if (!staging.mkdirs()) {
            throw new IOException(context.getString(R.string.plugin_import_io));
        }
        try {
            if (DocumentsContract.isTreeUri(picked)) {
                copyTree(context, picked, staging);
            } else {
                unzip(context, picked, scanZip(context, picked).root, staging);
            }
            if (target.exists()) {
                deleteTree(target);
                // Core keeps its own copy of an installed package at
                // <id>/<version>. A same-version replacement has a different
                // code hash, which core refuses rather than overwrite, so the
                // stale copy has to go too.
                final File installed = new File(new File(root, manifest.id), manifest.version);
                deleteTree(installed);
                final File parent = installed.getParentFile();
                final File[] siblings = parent != null ? parent.listFiles() : null;
                if (siblings != null && siblings.length == 0) parent.delete();
            }
            if (!staging.renameTo(target)) {
                throw new IOException(context.getString(R.string.plugin_import_io));
            }
        } catch (IOException error) {
            deleteTree(staging);
            throw error;
        }
        return target;
    }

    // ------------------------------------------------------------------ zip

    /** What one pass over an archive tells us. */
    private static final class Archive {
        String root = "";
        String manifestText;
        boolean hasEntry;
    }

    private static Archive scanZip(Context context, Uri zip) throws IOException {
        final List<String> names = new ArrayList<>();
        final Map<String, String> manifests = new LinkedHashMap<>();
        try (ZipInputStream in = openZip(context, zip)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                final String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.endsWith("/") || isJunk(name)) continue;
                checkPath(context, name);
                names.add(name);
                if (name.equals("manifest.yaml") || name.endsWith("/manifest.yaml")) {
                    manifests.put(name, new String(
                            readFully(context, in, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
                }
            }
        } catch (ZipException error) {
            throw new IOException(context.getString(R.string.plugin_import_bad), error);
        }
        final Archive archive = new Archive();
        archive.root = commonRoot(names);
        for (String name : names) {
            final String relative = stripPrefix(name, archive.root);
            if (isEntryFile(relative)) archive.hasEntry = true;
            if (relative.equals("manifest.yaml")) archive.manifestText = manifests.get(name);
        }
        return archive;
    }

    private static void unzip(Context context, Uri zip, String root, File target)
            throws IOException {
        long total = 0;
        try (ZipInputStream in = openZip(context, zip)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                final String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.endsWith("/") || isJunk(name)) continue;
                final String relative = stripPrefix(name, root);
                if (relative.isEmpty()) continue;
                final File out = resolve(context, target, relative);
                final File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException(context.getString(R.string.plugin_import_io));
                }
                total += writeFully(context, in, out, MAX_FILE_BYTES);
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IOException(context.getString(R.string.plugin_import_too_big));
                }
            }
        }
    }

    private static ZipInputStream openZip(Context context, Uri zip) throws IOException {
        final InputStream in = context.getContentResolver().openInputStream(zip);
        if (in == null) throw new IOException(context.getString(R.string.plugin_import_io));
        return new ZipInputStream(in, StandardCharsets.UTF_8);
    }

    /** The single top-level directory of an archive, or "" when it is flat. */
    private static String commonRoot(List<String> names) {
        final Set<String> tops = new LinkedHashSet<>();
        for (String name : names) {
            final int slash = name.indexOf('/');
            tops.add(slash < 0 ? "" : name.substring(0, slash));
        }
        return tops.size() == 1 && !tops.contains("") ? tops.iterator().next() : "";
    }

    private static String stripPrefix(String name, String root) {
        if (root.isEmpty()) return name;
        return name.startsWith(root + "/") ? name.substring(root.length() + 1) : name;
    }

    // ------------------------------------------------------------------ folder

    /** One child document of a picked tree. */
    private static final class Doc {
        String id;
        String name;
        boolean directory;
    }

    private static Manifest readTreeManifest(Context context, Uri tree) throws IOException {
        final String rootId = DocumentsContract.getTreeDocumentId(tree);
        String manifestId = null;
        boolean hasEntry = false;
        for (Doc child : children(context, tree, rootId)) {
            if (child.name.equals("manifest.yaml")) manifestId = child.id;
            else if (isEntryFile(child.name)) hasEntry = true;
        }
        if (manifestId == null) {
            throw new IOException(context.getString(R.string.plugin_import_no_manifest));
        }
        if (!hasEntry) {
            throw new IOException(context.getString(R.string.plugin_import_no_entry));
        }
        final String text = readDocument(context, tree, manifestId);
        return parseManifest(context, text);
    }

    private static void copyTree(Context context, Uri tree, File target) throws IOException {
        copyChildren(context, tree, DocumentsContract.getTreeDocumentId(tree), target, 0);
    }

    private static void copyChildren(Context context, Uri tree, String parentId, File target,
                                     long depth) throws IOException {
        if (depth > 8) throw new IOException(context.getString(R.string.plugin_import_io));
        for (Doc child : children(context, tree, parentId)) {
            if (isJunk(child.name)) continue;
            final String name = child.name.replace('\\', '/');
            checkPath(context, name);
            if (child.directory) {
                final File dir = resolve(context, target, name);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException(context.getString(R.string.plugin_import_io));
                }
                copyChildren(context, tree, child.id, dir, depth + 1);
            } else {
                final File out = resolve(context, target, name);
                final File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException(context.getString(R.string.plugin_import_io));
                }
                final InputStream in = openDocument(context, tree, child.id);
                try {
                    writeFully(context, in, out, MAX_FILE_BYTES);
                } finally {
                    in.close();
                }
            }
        }
    }

    private static List<Doc> children(Context context, Uri tree, String parentId)
            throws IOException {
        final Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        final List<Doc> out = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException(context.getString(R.string.plugin_import_io));
            while (cursor.moveToNext()) {
                final Doc doc = new Doc();
                doc.id = cursor.getString(0);
                doc.name = cursor.getString(1);
                doc.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                if (doc.name != null && !doc.name.isEmpty()) out.add(doc);
            }
        } catch (RuntimeException error) {
            throw new IOException(context.getString(R.string.plugin_import_io), error);
        }
        return out;
    }

    private static InputStream openDocument(Context context, Uri tree, String docId)
            throws IOException {
        final InputStream in = context.getContentResolver().openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId));
        if (in == null) throw new IOException(context.getString(R.string.plugin_import_io));
        return in;
    }

    private static String readDocument(Context context, Uri tree, String docId) throws IOException {
        final InputStream in = openDocument(context, tree, docId);
        try {
            return new String(readFully(context, in, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ manifest & paths

    private static Manifest parseManifest(Context context, String text) throws IOException {
        String id = "";
        String version = "";
        String role = "";
        for (String line : text.split("\n")) {
            final int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') continue;
            final String key = line.substring(0, colon).trim();
            final String value = unquote(line.substring(colon + 1).trim());
            if (key.equals("id")) id = value;
            else if (key.equals("version")) version = value;
            else if (key.equals("role")) role = value;
        }
        if (id.isEmpty() || version.isEmpty()) {
            throw new IOException(context.getString(R.string.plugin_import_bad_id));
        }
        return new Manifest(id, version, role, text.contains(platformToken()));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isEntryFile(String name) {
        for (String entry : ENTRY_FILES) {
            if (entry.equals(name)) return true;
        }
        return false;
    }

    /** Archive cruft and OS metadata that is never part of a package. */
    private static boolean isJunk(String name) {
        if (name.equals(".DS_Store") || name.startsWith("__MACOSX/")) return true;
        for (String part : name.split("/")) {
            if (part.startsWith("._") || part.equals(".DS_Store")) return true;
        }
        return false;
    }

    private static void checkPath(Context context, String path) throws IOException {
        if (path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            throw new IOException(context.getString(R.string.plugin_import_unsafe));
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException(context.getString(R.string.plugin_import_unsafe));
            }
        }
    }

    /** Resolves a package-relative path under the target, refusing to escape it. */
    private static File resolve(Context context, File target, String relative) throws IOException {
        checkPath(context, relative);
        final File out = new File(target, relative);
        if (!out.getAbsolutePath().startsWith(target.getAbsolutePath() + File.separator)) {
            throw new IOException(context.getString(R.string.plugin_import_unsafe));
        }
        return out;
    }

    private static String sanitize(String value) {
        final StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return out.length() == 0 ? "plugin" : out.toString();
    }

    // ------------------------------------------------------------------ bytes

    private static byte[] readFully(Context context, InputStream in, long limit)
            throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) > 0) {
            total += read;
            if (total > limit) throw new IOException(context.getString(R.string.plugin_import_too_big));
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static long writeFully(Context context, InputStream in, File out, long limit)
            throws IOException {
        long total = 0;
        try (FileOutputStream file = new FileOutputStream(out)) {
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > limit) {
                    throw new IOException(context.getString(R.string.plugin_import_too_big));
                }
                file.write(chunk, 0, read);
            }
        }
        return total;
    }

    /** Recursive delete; a missing tree is not an error. */
    public static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
