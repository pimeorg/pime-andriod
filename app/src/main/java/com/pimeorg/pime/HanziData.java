package com.pimeorg.pime;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Characters the host knows about beyond the plugin chain: their reading and
 * their stroke templates.
 *
 * <p>Two bundled files back this:
 *
 * <ul>
 *   <li>{@code hanzi/pinyin.tsv} — {@code char<TAB>syllable}, generated from the
 *       pinyin plugin's dictionary. It groups the unfolded candidates by the
 *       syllable of their first character (the left rail).</li>
 *   <li>{@code hanzi/mmah.bin} — stroke templates derived from Make Me a Hanzi
 *       (9,507 characters). The first substroke tells the first-stroke filter
 *       what a candidate starts with; the whole template lets the handwriting
 *       tab recognise a drawn character.</li>
 * </ul>
 *
 * <p>Both files are loaded once, on first use, so typing without ever opening
 * the candidate table pays nothing.
 */
final class HanziData {

    /** First-stroke buckets, in the order the filter row shows them. */
    static final int STROKE_HENG = 0;
    static final int STROKE_SHU = 1;
    static final int STROKE_PIE = 2;
    static final int STROKE_DIAN = 3;
    static final int STROKE_ZHE = 4;

    /** One character's stroke template, kept as the 3-byte substroke triples. */
    static final class Template {
        final char hanzi;
        final int strokeCount;
        /** dir, length, center — three bytes per substroke, in stroke order. */
        final byte[] subStrokes;

        Template(char hanzi, int strokeCount, byte[] subStrokes) {
            this.hanzi = hanzi;
            this.strokeCount = strokeCount;
            this.subStrokes = subStrokes;
        }

        int subStrokeCount() {
            return subStrokes.length / 3;
        }
    }

    private static HanziData instance;

    private final Context context;
    private Map<Character, String> pinyin;
    private List<Template> templates;
    private Map<Character, Template> templateByChar;

    private HanziData(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized HanziData get(Context context) {
        if (instance == null) instance = new HanziData(context);
        return instance;
    }

    /** Reading of {@code ch}, or {@code null} when the dictionary lacks it. */
    synchronized String pinyinOf(char ch) {
        if (pinyin == null) pinyin = loadPinyin(context.getAssets());
        return pinyin.get(ch);
    }

    /**
     * First-stroke bucket of {@code ch}, or -1 when there is no template.
     *
     * <p>The data records substrokes, not stroke boundaries, so this is the
     * direction of the first meaningful substroke. That is exact for 横/竖/撇/
     * 点; a 折 character usually starts with the horizontal or vertical leg of
     * its first stroke, so 折 only claims directions that fit none of the four
     * (a clean diagonal start) — an approximation, documented in PROGRESS.
     */
    int firstStroke(char ch) {
        Template template = templateOf(ch);
        if (template == null) return -1;
        int count = template.subStrokeCount();
        if (count == 0) return -1;
        int pick = 0;
        /* The very first substroke is often a 2–3 px artifact of the font
         * outline. Ignore those and take the first substroke with some body,
         * otherwise the first one. */
        for (int i = 0; i < count && i < 4; ++i) {
            if ((template.subStrokes[i * 3 + 1] & 0xFF) >= 20) {
                pick = i;
                break;
            }
        }
        return strokeBucket(template.subStrokes[pick * 3] & 0xFF);
    }

    /** Maps the analyser's direction (0..255 = 0..2π) to a first-stroke bucket. */
    static int strokeBucket(int direction) {
        final double degrees = direction * 360.0 / 256.0;
        /* 0 = right, 90 = up, 180 = left, 270 = down. */
        final double[][] centres = {
                {0, 180},   // 横
                {90, 270},  // 竖
                {225},      // 撇
                {315},      // 点/捺
        };
        double best = 45.0;
        int bucket = STROKE_ZHE;
        for (int i = 0; i < centres.length; ++i) {
            for (double centre : centres[i]) {
                double delta = Math.abs(degrees - centre);
                delta = Math.min(delta, 360.0 - delta);
                if (delta < best) {
                    best = delta;
                    bucket = i;
                }
            }
        }
        return bucket;
    }

    synchronized Template templateOf(char ch) {
        if (templateByChar == null) {
            List<Template> loaded = templates();
            Map<Character, Template> map = new HashMap<>(loaded.size() * 2);
            for (Template template : loaded) map.put(template.hanzi, template);
            templateByChar = map;
        }
        return templateByChar.get(ch);
    }

    synchronized List<Template> templates() {
        if (templates == null) {
            try {
                templates = parseTemplates(readAll(context.getAssets(), "hanzi/mmah.bin"));
            } catch (IOException error) {
                templates = Collections.emptyList();
            }
        }
        return templates;
    }

    private static Map<Character, String> loadPinyin(AssetManager assets) {
        Map<Character, String> map = new HashMap<>(4096);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("hanzi/pinyin.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab != 1) continue;
                map.put(line.charAt(0), line.substring(tab + 1));
            }
        } catch (IOException ignored) {
            /* No table: the rail simply has no syllables to offer. */
        }
        return map;
    }

    private static byte[] readAll(AssetManager assets, String name) throws IOException {
        try (InputStream in = new BufferedInputStream(assets.open(name))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    /**
     * Parses the serialized template file.
     *
     * <p>Layout (little endian, bincode): {@code u64 count}, then per character
     * a UTF-8 code point (1–4 bytes), {@code u16 strokeCount}, {@code u64
     * substrokeCount} and {@code substrokeCount} triples. Exposed for the unit
     * test, which feeds the bundled file straight in.
     */
    static List<Template> parseTemplates(byte[] data) {
        List<Template> list = new ArrayList<>(9600);
        int offset = 0;
        if (data.length < 8) return list;
        long count = readU64(data, 0);
        offset = 8;
        for (long i = 0; i < count; ++i) {
            if (offset >= data.length) break;
            int first = data[offset] & 0xFF;
            int length = first < 0x80 ? 1
                    : (first & 0xE0) == 0xC0 ? 2
                    : (first & 0xF0) == 0xE0 ? 3 : 4;
            if (offset + length > data.length) break;
            String text = new String(data, offset, length, StandardCharsets.UTF_8);
            offset += length;
            if (text.isEmpty()) break;
            int strokeCount = readU16(data, offset);
            offset += 2;
            long subCount = readU64(data, offset);
            offset += 8;
            int bytes = (int) Math.min(subCount * 3L, data.length - offset);
            byte[] subs = new byte[bytes];
            System.arraycopy(data, offset, subs, 0, bytes);
            offset += bytes;
            list.add(new Template(text.charAt(0), strokeCount, subs));
        }
        return list;
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long readU64(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; --i) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }
}
