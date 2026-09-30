package com.apryse.demo;

import java.util.*;

/**
 * Minimal recursive-descent JSON reader/writer with no external dependencies.
 * Sufficient for the Apryse keyValExtract fixture and the canonical extracted.json.
 */
final class MinimalJson {

    // ─── Reader ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) new Parser(json.strip()).parseValue();
    }

    private static final class Parser {
        private final String src;
        private int pos = 0;

        Parser(String src) { this.src = src; }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) return null;
            return switch (src.charAt(pos)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> { pos += 4; yield null; }
                default  -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            do {
                skipWs();
                String key = parseString();
                skipWs(); expect(':');
                map.put(key, parseValue());
                skipWs();
            } while (peek() == ',' && pos++ >= 0);
            expect('}');
            return map;
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            do {
                list.add(parseValue());
                skipWs();
            } while (peek() == ',' && pos++ >= 0);
            expect(']');
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = src.charAt(pos++);
                    sb.append(switch (esc) {
                        case 'n'  -> '\n';
                        case 'r'  -> '\r';
                        case 't'  -> '\t';
                        case 'b'  -> '\b';
                        case 'f'  -> '\f';
                        case 'u'  -> {
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        default   -> esc;
                    });
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string near pos " + pos);
        }

        boolean parseBool() {
            if (src.startsWith("true", pos))  { pos += 4; return true; }
            if (src.startsWith("false", pos)) { pos += 5; return false; }
            throw new IllegalArgumentException("Bad boolean at pos " + pos);
        }

        Number parseNumber() {
            int start = pos;
            while (pos < src.length() && "-+0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
            String s = src.substring(start, pos);
            try { return Long.parseLong(s); }
            catch (NumberFormatException ignored) { return Double.parseDouble(s); }
        }

        void skipWs() { while (pos < src.length() && src.charAt(pos) <= ' ') pos++; }
        char peek()   { skipWs(); return pos < src.length() ? src.charAt(pos) : 0; }
        void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c)
                throw new IllegalArgumentException("Expected '" + c + "' at pos " + pos
                        + " got '" + (pos < src.length() ? src.charAt(pos) : "EOF") + "'");
            pos++;
        }
    }

    // ─── Writer helpers ────────────────────────────────────────────────────

    /** Escapes a string for embedding as a JSON string value (no surrounding quotes). */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Returns {@code "value"} quoted, or {@code null} literal when value is null/blank. */
    static String quoted(String s) {
        return (s == null || s.isBlank()) ? "null" : "\"" + esc(s) + "\"";
    }

    /** Convenience: cast a raw-parsed value to String, null-safe. */
    @SuppressWarnings("unchecked")
    static String str(Object o) {
        if (o == null) return "";
        return o.toString();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    static double dbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
