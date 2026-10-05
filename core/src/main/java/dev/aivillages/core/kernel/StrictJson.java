package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version 1's strict, integer-only JSON representation. No Gson coercion or ambient I/O. */
public final class StrictJson {
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_TOKENS = 4_096;
    public static final int MAX_STRING = 1_024;

    private StrictJson() { }

    public static Map<String, Object> object(String input) throws Invalid {
        return object(input, MAX_STRING);
    }

    /** An Ollama chat envelope can contain an entire bounded IR as one JSON string. */
    public static Map<String, Object> transportObject(String input) throws Invalid {
        return object(input, MAX_BYTES);
    }

    private static Map<String, Object> object(String input, int maxString) throws Invalid {
        if (input == null || input.length() > MAX_BYTES
                || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new Invalid("$", "INPUT_LIMIT");
        }
        Parser parser = new Parser(input, maxString);
        Object value = parser.value(0);
        parser.space();
        if (!(value instanceof Map<?, ?> map) || parser.pos != input.length()) {
            throw new Invalid("$", "INVALID_JSON");
        }
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        append(value, out);
        return out.toString();
    }

    private static void append(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) keys.add((String) key);
            Collections.sort(keys);
            boolean first = true;
            for (String key : keys) {
                if (!first) out.append(',');
                first = false;
                quote(key, out);
                out.append(':');
                append(map.get(key), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i != 0) out.append(',');
                append(list.get(i), out);
            }
            out.append(']');
        } else if (value instanceof String string) {
            quote(string, out);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Boolean) {
            out.append(value);
        } else {
            throw new IllegalArgumentException("Unsupported canonical value");
        }
    }

    private static void quote(String string, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        private final String path;
        private final String code;

        public Invalid(String path, String code) {
            super(code + " at " + path);
            this.path = path;
            this.code = code;
        }

        public String path() { return path; }
        public String code() { return code; }
    }

    private static final class Parser {
        private final String input;
        private final int maxString;
        private int pos;
        private int tokens;

        Parser(String input, int maxString) {
            this.input = input;
            this.maxString = maxString;
        }

        void space() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
                pos++;
            }
        }

        Object value(int depth) throws Invalid {
            space();
            if (depth > MAX_DEPTH || ++tokens > MAX_TOKENS) throw invalid("INPUT_LIMIT");
            if (pos == input.length()) throw invalid("INVALID_JSON");
            return switch (input.charAt(pos)) {
                case '{' -> objectValue(depth);
                case '[' -> arrayValue(depth);
                case '"' -> string();
                case 't' -> keyword("true", true);
                case 'f' -> keyword("false", false);
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> throw invalid("INVALID_JSON");
            };
        }

        private Object keyword(String word, boolean result) throws Invalid {
            if (!input.startsWith(word, pos)) throw invalid("INVALID_JSON");
            pos += word.length();
            return result;
        }

        private Map<String, Object> objectValue(int depth) throws Invalid {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (consume('}')) return map;
            do {
                space();
                if (pos == input.length() || input.charAt(pos) != '"') throw invalid("INVALID_JSON");
                String key = string();
                if (key.length() > 128) throw invalid("INPUT_LIMIT");
                space();
                expect(':');
                if (map.containsKey(key)) throw invalid("DUPLICATE_FIELD");
                map.put(key, value(depth + 1));
                space();
                if (consume('}')) return map;
                expect(',');
            } while (true);
        }

        private List<Object> arrayValue(int depth) throws Invalid {
            pos++;
            List<Object> list = new ArrayList<>();
            space();
            if (consume(']')) return list;
            do {
                list.add(value(depth + 1));
                space();
                if (consume(']')) return list;
                expect(',');
            } while (true);
        }

        private String string() throws Invalid {
            pos++;
            StringBuilder result = new StringBuilder();
            boolean terminated = false;
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') { terminated = true; break; }
                if (c < 0x20) throw invalid("INVALID_STRING");
                if (c == '\\') {
                    if (pos == input.length()) throw invalid("INVALID_STRING");
                    c = input.charAt(pos++);
                    c = switch (c) {
                        case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                        case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> unicode();
                        default -> throw invalid("INVALID_STRING");
                    };
                }
                result.append(c);
                if (result.length() > maxString) throw invalid("INPUT_LIMIT");
            }
            if (!terminated) throw invalid("INVALID_STRING");
            String value = result.toString();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                        throw invalid("INVALID_STRING");
                    }
                } else if (Character.isLowSurrogate(c)) throw invalid("INVALID_STRING");
            }
            if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) throw invalid("INVALID_STRING");
            return value;
        }

        private char unicode() throws Invalid {
            if (pos + 4 > input.length()) throw invalid("INVALID_STRING");
            int number = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(pos++), 16);
                if (digit < 0 || input.charAt(pos - 1) > 127) throw invalid("INVALID_STRING");
                number = number * 16 + digit;
            }
            return (char) number;
        }

        private Long number() throws Invalid {
            int start = pos;
            consume('-');
            if (pos == input.length()) throw invalid("INVALID_NUMBER");
            if (consume('0')) {
                if (pos < input.length() && Character.isDigit(input.charAt(pos))) throw invalid("INVALID_NUMBER");
            } else {
                if (input.charAt(pos) < '1' || input.charAt(pos) > '9') throw invalid("INVALID_NUMBER");
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            if (input.charAt(start) == '-' && pos - start == 2 && input.charAt(start + 1) == '0') {
                throw invalid("INVALID_NUMBER");
            }
            if (pos < input.length() && ".eE".indexOf(input.charAt(pos)) >= 0) throw invalid("INVALID_NUMBER");
            try { return Long.parseLong(input.substring(start, pos)); }
            catch (NumberFormatException exception) { throw invalid("INVALID_NUMBER"); }
        }

        private void expect(char expected) throws Invalid {
            if (!consume(expected)) throw invalid("INVALID_JSON");
        }

        private boolean consume(char expected) {
            if (pos < input.length() && input.charAt(pos) == expected) { pos++; return true; }
            return false;
        }

        private Invalid invalid(String code) { return new Invalid("$@" + pos, code); }
    }
}
