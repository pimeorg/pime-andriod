package com.pimeorg.pime;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and edits the chain documents (CBOR files under files/chains) that tell
 * pime-core which plugins are used and in what order.
 *
 * A chain is one map, {@code {"plugins": [entry, ...]}}, where each entry is
 * {@code {id, role, version}} plus an optional {@code settings} map. The array
 * order is the processing stage order. All plugins in files/plugins are loaded
 * by the service; the chain decides which are active.
 */
public final class ChainStore {

    /** One plugin as it appears inside a chain document. */
    public static final class PluginEntry {
        public String id;
        public String role;
        public String version;
        public final Map<String, Object> settings = new LinkedHashMap<>();

        public PluginEntry(String id, String role, String version) {
            this.id = id;
            this.role = role;
            this.version = version;
        }
    }

    private static final String PLUGINS_KEY = "plugins";

    private ChainStore() {
    }

    /* ---------------------------------------------------------------- paths */

    public static File chainsDir(Context context) {
        return new File(context.getFilesDir(), "chains");
    }

    public static File pluginsDir(Context context) {
        return new File(context.getFilesDir(), "plugins");
    }

    public static File defaultChain(Context context) {
        return new File(chainsDir(context), "default.cbor");
    }

    public static File chainFile(Context context, String name) {
        return new File(chainsDir(context), sanitizeName(name));
    }

    /** Lists every chain file, sorted by name. */
    public static List<File> listChains(Context context) {
        final File[] files = chainsDir(context).listFiles(
                f -> f.isFile() && f.getName().endsWith(".cbor"));
        final List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) result.add(file);
        }
        result.sort((a, b) -> a.getName().compareTo(b.getName()));
        return result;
    }

    private static String sanitizeName(String name) {
        // Keep only characters that are safe for a file name, dropping extensions
        // so callers can pass either "mychain" or "mychain.cbor".
        String base = name;
        final int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return base + ".cbor";
    }

    /* ------------------------------------------------------------ active chain */

    public static String activeChainName(Context context) {
        final String name = Prefs.activeChain(context);
        return name == null ? defaultChain(context).getName() : name;
    }

    public static File activeChain(Context context) {
        return chainFile(context, activeChainName(context));
    }

    public static void setActiveChain(Context context, String name) {
        Prefs.setActiveChain(context, sanitizeName(name));
    }

    /* ---------------------------------------------------------------- read */

    public static List<PluginEntry> readChain(Context context, File file) throws IOException {
        final Map<String, Object> root = parse(readBytes(file));
        final Object plugins = root.get(PLUGINS_KEY);
        final List<PluginEntry> result = new ArrayList<>();
        if (plugins instanceof List) {
            for (Object item : (List<?>) plugins) {
                if (item instanceof Map) result.add(toEntry((Map<?, ?>) item));
            }
        }
        return result;
    }

    /* ---------------------------------------------------------------- write */

    public static void writeChain(Context context, File file, List<PluginEntry> plugins)
            throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        final List<Object> list = new ArrayList<>();
        for (PluginEntry entry : plugins) list.add(toMap(entry));
        root.put(PLUGINS_KEY, list);
        atomicWrite(file, Cbor.write(root));
    }

    /** Appends a plugin to the chain, keeping a stable stage order. */
    public static void addPlugin(Context context, File file, PluginEntry entry)
            throws IOException {
        final List<PluginEntry> plugins = readChain(context, file);
        for (PluginEntry existing : plugins) {
            if (existing.id.equals(entry.id)) return; // already present
        }
        plugins.add(entry);
        writeChain(context, file, plugins);
    }

    public static void removePlugin(Context context, File file, String id) throws IOException {
        final List<PluginEntry> plugins = readChain(context, file);
        final List<PluginEntry> kept = new ArrayList<>();
        for (PluginEntry entry : plugins) {
            if (!entry.id.equals(id)) kept.add(entry);
        }
        writeChain(context, file, kept);
    }

    /** Sets one setting on one plugin; a null value deletes the key. */
    public static void setPluginSetting(Context context, File file, String id,
                                        String key, Object value) throws IOException {
        final List<PluginEntry> plugins = readChain(context, file);
        for (PluginEntry entry : plugins) {
            if (entry.id.equals(id)) {
                if (value == null) entry.settings.remove(key);
                else entry.settings.put(key, value);
                writeChain(context, file, plugins);
                return;
            }
        }
    }

    /** Inserts a plugin entry right before the one with the given id, or at the end. */
    public static void insertBefore(Context context, File file, PluginEntry entry,
                                    String beforeId) throws IOException {
        final List<PluginEntry> plugins = readChain(context, file);
        for (PluginEntry existing : plugins) {
            if (existing.id.equals(entry.id)) return;
        }
        final List<PluginEntry> result = new ArrayList<>();
        boolean inserted = false;
        for (PluginEntry e : plugins) {
            if (!inserted && beforeId != null && e.id.equals(beforeId)) {
                result.add(entry);
                inserted = true;
            }
            result.add(e);
        }
        if (!inserted) result.add(entry);
        writeChain(context, file, result);
    }

    /* --------------------------------------------------------------- helpers */

    private static Map<String, Object> parse(byte[] bytes) throws IOException {
        final Object root = Cbor.read(new ByteArrayInputStream(bytes));
        if (!(root instanceof Map)) {
            throw new IOException("chain is not a CBOR map");
        }
        return castMap(root);
    }

    private static PluginEntry toEntry(Map<?, ?> map) {
        final PluginEntry entry = new PluginEntry(
                text(map.get("id"), ""),
                text(map.get("role"), ""),
                text(map.get("version"), ""));
        final Object settings = map.get("settings");
        if (settings instanceof Map) {
            for (Map.Entry<?, ?> e : castMap(settings).entrySet()) {
                entry.settings.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
        }
        return entry;
    }

    private static Map<String, Object> toMap(PluginEntry entry) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id);
        map.put("role", entry.role);
        map.put("version", entry.version);
        if (!entry.settings.isEmpty()) map.put("settings", entry.settings);
        return map;
    }

    /** Normalizes decoded values into the shapes the encoder understands. */
    private static Object normalize(Object value) {
        if (value instanceof Long) {
            // Keep integers within int range as int; the encoder handles both.
            return value;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    /** Writes through a temp file then renames, so a crash never leaves a torn chain. */
    private static void atomicWrite(File file, byte[] bytes) throws IOException {
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        final File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
        }
        if (!temp.renameTo(file)) {
            throw new IOException("cannot replace " + file);
        }
    }
}
