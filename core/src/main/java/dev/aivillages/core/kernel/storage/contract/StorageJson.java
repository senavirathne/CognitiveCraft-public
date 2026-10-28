package dev.aivillages.core.kernel.storage.contract;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Bounded integer-only JSON, including explicit null; independent of peer codecs. */
final class StorageJson {
    static final long MAX_INTEGER = 9_007_199_254_740_991L;
    static final int MAX_BYTES = 65_536;
    static Object parse(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("wire byte limit");
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = read(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing JSON");
            return value;
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("invalid JSON", e);
        }
    }
    private static Object read(JsonReader reader, int depth, int[] tokens) throws IOException {
        if (depth > 48 || ++tokens[0] > 16_384) throw new IllegalArgumentException("JSON limits");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var value = new LinkedHashMap<String, Object>();
                while (reader.hasNext()) {
                    String key = string(reader.nextName());
                    if (++tokens[0] > 16_384 || value.containsKey(key)) throw new IllegalArgumentException("duplicate or limit");
                    value.put(key, read(reader, depth + 1, tokens));
                }
                reader.endObject(); yield value;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var value = new ArrayList<Object>();
                while (reader.hasNext()) value.add(read(reader, depth + 1, tokens));
                reader.endArray(); yield value;
            }
            case STRING -> string(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (!number.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException("integer only");
                long value = Long.parseLong(number);
                if (value < -MAX_INTEGER || value > MAX_INTEGER) throw new IllegalArgumentException("integer range");
                yield value;
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield null; }
            default -> throw new IllegalArgumentException("JSON value required");
        };
    }
    private static String string(String value) {
        if (value.length() > 32_768 || !Normalizer.isNormalized(value, Normalizer.Form.NFC))
            throw new IllegalArgumentException("NFC/string limit");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("unpaired surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("unpaired surrogate");
        }
        return value;
    }
    static String canonical(Object value) {
        var out = new StringBuilder(); append(value, out); return out.append('\n').toString();
    }
    private static void append(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof Map<?, ?> map) {
            out.append('{'); var keys = new ArrayList<String>();
            map.keySet().forEach(key -> keys.add((String) key)); Collections.sort(keys);
            boolean first = true;
            for (String key : keys) {
                if (!first) out.append(','); first = false;
                quote(key, out); out.append(':'); append(map.get(key), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) { if (i != 0) out.append(','); append(list.get(i), out); }
            out.append(']');
        } else if (value instanceof String s) quote(s, out);
        else if (value instanceof Long || value instanceof Integer || value instanceof Boolean) out.append(value);
        else throw new IllegalArgumentException("unsupported JSON value");
    }
    private static void quote(String value, StringBuilder out) {
        string(value); out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        out.append('"');
    }
    private StorageJson() { }
}
