package com.playmatch.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilidad ligera y sin dependencias externas para parseo y serialización de JSON simple y anidado.
 * Soporta comillas escapadas, barras invertidas, objetos/arreglos anidados, números, booleanos y cadenas.
 */
public class JsonUtils {

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Serializa objetos estándar de Java (Map, List, String, Number, Boolean, null) a JSON válido.
     */
    @SuppressWarnings("unchecked")
    public static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof String) {
            return "\"" + escape((String) value) + "\"";
        }
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\":")
                  .append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    public static Map<String, String> parseSimpleJsonObject(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.trim().isEmpty()) return map;

        String clean = json.trim();
        if (clean.startsWith("{")) clean = clean.substring(1);
        if (clean.endsWith("}")) clean = clean.substring(0, clean.length() - 1);

        boolean inQuotes = false;
        boolean escaped = false;
        StringBuilder currentKey = new StringBuilder();
        StringBuilder currentValue = new StringBuilder();
        boolean parsingKey = true;

        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);

            if (escaped) {
                if (parsingKey) {
                    currentKey.append(c);
                } else {
                    currentValue.append(c);
                }
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                if (parsingKey) {
                    currentKey.append(c);
                } else {
                    currentValue.append(c);
                }
                continue;
            }

            if (c == '\"') {
                inQuotes = !inQuotes;
                if (!parsingKey) {
                    currentValue.append(c);
                }
                continue;
            }

            if (!inQuotes && c == ':') {
                parsingKey = false;
                continue;
            }

            if (!inQuotes && c == ',') {
                storeEntry(map, currentKey.toString(), currentValue.toString());
                currentKey.setLength(0);
                currentValue.setLength(0);
                parsingKey = true;
                continue;
            }

            if (parsingKey) {
                currentKey.append(c);
            } else {
                currentValue.append(c);
            }
        }

        storeEntry(map, currentKey.toString(), currentValue.toString());
        return map;
    }

    private static void storeEntry(Map<String, String> map, String rawKey, String rawVal) {
        String k = unescape(rawKey.trim());
        String v = rawVal.trim();
        if (!k.isEmpty()) {
            if ("null".equals(v)) {
                map.put(k, null);
            } else {
                map.put(k, unescape(v));
            }
        }
    }

    private static String unescape(String s) {
        if (s == null) return "";
        String res = s.trim();
        if (res.startsWith("\"") && res.endsWith("\"") && res.length() >= 2) {
            res = res.substring(1, res.length() - 1);
        }
        StringBuilder out = new StringBuilder(res.length());
        for (int i = 0; i < res.length(); i++) {
            char c = res.charAt(i);
            if (c == '\\' && i + 1 < res.length()) {
                char next = res.charAt(i + 1);
                switch (next) {
                    case '"': out.append('"'); i++; break;
                    case '\\': out.append('\\'); i++; break;
                    case '/': out.append('/'); i++; break;
                    case 'n': out.append('\n'); i++; break;
                    case 'r': out.append('\r'); i++; break;
                    case 't': out.append('\t'); i++; break;
                    case 'b': out.append('\b'); i++; break;
                    case 'f': out.append('\f'); i++; break;
                    default: out.append(c); break;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static Object parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        return new RecursiveParser(json).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object obj) {
        return obj instanceof List ? (List<Object>) obj : null;
    }

    public static String asString(Object obj) {
        return obj != null ? String.valueOf(obj) : "";
    }

    public static double asDouble(Object obj, double fallback) {
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        if (obj instanceof String) {
            try { return Double.parseDouble(((String) obj).trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static int asInt(Object obj, int fallback) {
        return (int) Math.round(asDouble(obj, fallback));
    }

    private static final class RecursiveParser {
        private final String s;
        private int pos = 0;

        RecursiveParser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) return null;
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' && s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", pos)) { pos += 4; return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
            while (pos < s.length()) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') break;
                String key = parseString();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ':') pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; break; }
                break;
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (pos < s.length()) {
                list.add(parseValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; break; }
                break;
            }
            return list;
        }

        private String parseString() {
            pos++; // opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < s.length()) {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 <= s.length()) {
                                String hex = s.substring(pos, pos + 4);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException ignored) {}
                                pos += 4;
                            }
                            break;
                        default: sb.append(esc); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
