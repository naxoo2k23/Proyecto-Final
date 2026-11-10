package com.playmatch.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Utilidad ligera y sin dependencias externas para parseo y serialización de JSON simple.
 * Soporta comillas escapadas, barras invertidas, números, booleanos y cadenas con comas o dos puntos.
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
}
