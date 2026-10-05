package dev.aivillages.core;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;

/** Strict, size-bounded JSON. Rejects duplicate fields, trailing data and excessive nesting. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Json() { }
    public static JsonObject object(String text, int max) {
        if (text == null || text.length() > max) throw new IllegalArgumentException("JSON too large");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected one JSON object");
            return value.getAsJsonObject();
        } catch (IOException | IllegalStateException ex) { throw new IllegalArgumentException("Invalid JSON", ex); }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IllegalArgumentException("JSON nesting too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); Set<String> keys = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new IllegalArgumentException("Duplicate JSON field");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token");
        };
    }
    public static void keys(JsonObject object, String... fields) {
        if (!object.keySet().equals(Set.of(fields))) throw new IllegalArgumentException("Unexpected or missing fields");
    }
    public static String string(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string: " + key);
        String s = e.getAsString();
        if (s.length() > max || s.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) throw new IllegalArgumentException("Invalid text: " + key);
        return s;
    }
    public static int integer(JsonObject o, String key, int min, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number: " + key);
        int n;
        try { n = e.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Expected integer: " + key); }
        if (n < min || n > max) throw new IllegalArgumentException("Out of range: " + key);
        return n;
    }
}
