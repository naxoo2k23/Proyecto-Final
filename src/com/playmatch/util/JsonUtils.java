package com.playmatch.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Utilidad ligera y sin dependencias externas para parseo y serialización de JSON simple.
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

        // Parse key-value pairs (basic JSON reader)
        boolean inQuotes = false;
        StringBuilder currentKey = new StringBuilder();
        StringBuilder currentValue = new StringBuilder();
        boolean parsingKey = true;

        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);

            if (c == '\"' && (i == 0 || clean.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
                continue;
            }

            if (!inQuotes && c == ':') {
                parsingKey = false;
                continue;
            }

            if (!inQuotes && c == ',') {
                String k = currentKey.toString().trim();
                String v = currentValue.toString().trim();
                if (!k.isEmpty()) {
                    map.put(k, unescape(v));
                }
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

        String k = currentKey.toString().trim();
        String v = currentValue.toString().trim();
        if (!k.isEmpty()) {
            map.put(k, unescape(v));
        }

        return map;
    }

    private static String unescape(String s) {
        if (s == null) return "";
        String res = s.trim();
        if (res.startsWith("\"") && res.endsWith("\"") && res.length() >= 2) {
            res = res.substring(1, res.length() - 1);
        }
        return res.replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .replace("\\n", "\n")
                  .replace("\\r", "\r")
                  .replace("\\t", "\t");
    }
}
