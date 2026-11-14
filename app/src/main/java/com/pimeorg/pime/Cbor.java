package com.pimeorg.pime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CBOR (RFC 7049) codec for the chain document shape:
 * a map holding a "plugins" array of maps whose values are text, integers and
 * booleans. Only the subset the chain format actually uses is implemented, so
 * no external dependency is needed and encoding is deterministic.
 *
 * The native pime-core parses these bytes with a standard CBOR reader, so the
 * encoding here stays byte-compatible with the bundled default chain.
 */
final class Cbor {

    private Cbor() {
    }

    /* ------------------------------------------------------------------ decode */

    /** Reads a CBOR document into a Map/List/String/Long/Boolean tree. */
    static Object read(InputStream in) throws IOException {
        final int first = in.read();
        if (first < 0) throw new IOException("empty CBOR");
        return decode(first, in);
    }

    private static Object decode(int first, InputStream in) throws IOException {
        final int major = first >> 5;
        final int info = first & 0x1F;
        final long arg = readLength(info, in);
        switch (major) {
            case 0: // unsigned integer
                return arg;
            case 1: // negative integer
                return arg - 1;
            case 2: // text string
                return readText(arg, in);
            case 3: // byte string
                return readBytes(arg, in);
            case 4: // array
                return readArray(arg, in);
            case 5: // map
                return readMap(arg, in);
            case 7:
                if (info == 20) return false;
                if (info == 21) return true;
                if (info == 22) throw new IOException("CBOR null is not used");
                if (info == 23) return null; // simple value (undefined)
                throw new IOException("unsupported CBOR simple value " + info);
            default:
                throw new IOException("unsupported CBOR major type " + major);
        }
    }

    private static long readLength(int info, InputStream in) throws IOException {
        if (info < 24) return info;
        final int bytes = 1 << (info - 24);
        long value = 0;
        for (int i = 0; i < bytes; ++i) {
            final int b = in.read();
            if (b < 0) throw new IOException("truncated CBOR length");
            value = (value << 8) | b;
        }
        return value;
    }

    private static String readText(long length, InputStream in) throws IOException {
        final byte[] buffer = new byte[(int) length];
        readFully(buffer, in);
        return new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(long length, InputStream in) throws IOException {
        final byte[] buffer = new byte[(int) length];
        readFully(buffer, in);
        return buffer;
    }

    private static List<Object> readArray(long length, InputStream in) throws IOException {
        final List<Object> list = new ArrayList<>();
        for (long i = 0; i < length; ++i) {
            final int first = in.read();
            if (first < 0) throw new IOException("truncated CBOR array");
            list.add(decode(first, in));
        }
        return list;
    }

    private static Map<String, Object> readMap(long length, InputStream in) throws IOException {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (long i = 0; i < length; ++i) {
            final int first = in.read();
            if (first < 0) throw new IOException("truncated CBOR map");
            final Object key = decode(first, in);
            final int second = in.read();
            if (second < 0) throw new IOException("truncated CBOR map");
            final Object value = decode(second, in);
            map.put(String.valueOf(key), value);
        }
        return map;
    }

    private static void readFully(byte[] buffer, InputStream in) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            final int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("truncated CBOR string");
            offset += read;
        }
    }

    /* ------------------------------------------------------------------ encode */

    static byte[] write(Object value) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        encode(value, out);
        return out.toByteArray();
    }

    private static void encode(Object value, ByteArrayOutputStream out) {
        if (value == null) {
            out.write(0xF6); // undefined
        } else if (value instanceof Boolean) {
            out.write((Boolean) value ? 0xF5 : 0xF4);
        } else if (value instanceof Number) {
            writeInt((((Number) value).longValue()), out);
        } else if (value instanceof String) {
            writeText((String) value, out);
        } else if (value instanceof List) {
            writeArray((List<?>) value, out);
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value, out);
        } else if (value instanceof byte[]) {
            writeBytes((byte[]) value, out);
        } else {
            throw new IllegalArgumentException("cannot encode " + value.getClass());
        }
    }

    private static void writeInt(long value, ByteArrayOutputStream out) {
        if (value >= 0) {
            writeHeader(0, value, out);
        } else {
            writeHeader(1, -1 - value, out);
        }
    }

    private static void writeText(String value, ByteArrayOutputStream out) {
        final byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeHeader(2, bytes.length, out);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeBytes(byte[] value, ByteArrayOutputStream out) {
        writeHeader(3, value.length, out);
        out.write(value, 0, value.length);
    }

    private static void writeArray(List<?> value, ByteArrayOutputStream out) {
        writeHeader(4, value.size(), out);
        for (Object item : value) encode(item, out);
    }

    private static void writeMap(Map<?, ?> value, ByteArrayOutputStream out) {
        writeHeader(5, value.size(), out);
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            encode(String.valueOf(entry.getKey()), out);
            encode(entry.getValue(), out);
        }
    }

    private static void writeHeader(int major, long arg, ByteArrayOutputStream out) {
        if (arg < 24) {
            out.write((major << 5) | (int) arg);
        } else if (arg < 0x100) {
            out.write((major << 5) | 24);
            out.write((int) (arg & 0xFF));
        } else if (arg < 0x10000) {
            out.write((major << 5) | 25);
            out.write((int) ((arg >> 8) & 0xFF));
            out.write((int) (arg & 0xFF));
        } else if (arg < 0x100000000L) {
            out.write((major << 5) | 26);
            out.write((int) ((arg >> 24) & 0xFF));
            out.write((int) ((arg >> 16) & 0xFF));
            out.write((int) ((arg >> 8) & 0xFF));
            out.write((int) (arg & 0xFF));
        } else {
            out.write((major << 5) | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((arg >> shift) & 0xFF));
            }
        }
    }
}
