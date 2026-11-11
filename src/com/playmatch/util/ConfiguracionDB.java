package com.playmatch.util;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cargador centralizado de configuración de base de datos.
 * Prioridad: System Properties (-D) -> Variables de Entorno -> Archivo .env local.
 * Soporta URLs JDBC estándar y URIs de nube (Neon, Supabase, Railway, Render).
 */
public class ConfiguracionDB {
    private String jdbcUrl;
    private String username;
    private String password;
    private boolean strictPostgres;
    private String sqlitePath;
    private boolean postgresConfigured;

    private ConfiguracionDB() {}

    public static ConfiguracionDB cargar() {
        Map<String, String> dotEnv = leerArchivoDotEnv();
        ConfiguracionDB cfg = new ConfiguracionDB();

        String explicitSqlite = obtenerParam(dotEnv, "playmatch.db.path", "PLAYMATCH_DB_PATH");
        cfg.sqlitePath = (explicitSqlite != null && !explicitSqlite.isEmpty())
                ? explicitSqlite
                : "data/playmatch.db";

        cfg.strictPostgres = "true".equalsIgnoreCase(obtenerParam(dotEnv, "playmatch.db.strict", "DB_STRICT"));

        // Si las pruebas explícitas piden -Dplaymatch.db.path=..., forzar ruta SQLite
        String sysSqlite = System.getProperty("playmatch.db.path");
        if (sysSqlite != null && !sysSqlite.trim().isEmpty()) {
            cfg.postgresConfigured = false;
            return cfg;
        }

        String rawUrl = obtenerParam(dotEnv, "playmatch.pg.url", "DATABASE_URL");
        if (rawUrl == null || rawUrl.isEmpty()) {
            rawUrl = obtenerParam(dotEnv, "playmatch.pg.url", "DB_URL");
        }
        String envUser = obtenerParam(dotEnv, "playmatch.pg.user", "DB_USER");
        if (envUser == null || envUser.isEmpty()) {
            envUser = System.getenv("PGUSER");
        }
        String envPass = obtenerParam(dotEnv, "playmatch.pg.password", "DB_PASSWORD");
        if (envPass == null || envPass.isEmpty()) {
            envPass = System.getenv("PGPASSWORD");
        }

        if (rawUrl != null && !rawUrl.trim().isEmpty()) {
            cfg.parsearUrlPostgres(rawUrl.trim(), envUser, envPass);
        }
        return cfg;
    }

    private void parsearUrlPostgres(String rawUrl, String envUser, String envPass) {
        if (rawUrl.startsWith("jdbc:postgresql://")) {
            this.jdbcUrl = rawUrl;
            this.username = envUser != null ? envUser : "";
            this.password = envPass != null ? envPass : "";
            this.postgresConfigured = true;
            return;
        }

        if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
            try {
                String normalizedScheme = rawUrl.replaceFirst("^postgres(ql)?://", "postgresql://");
                URI uri = URI.create(normalizedScheme);

                String userInfo = uri.getRawUserInfo();
                String parsedUser = envUser != null ? envUser : "";
                String parsedPass = envPass != null ? envPass : "";
                if (userInfo != null && !userInfo.isEmpty()) {
                    int colonIdx = userInfo.indexOf(':');
                    if (colonIdx >= 0) {
                        parsedUser = decodeUtf8(userInfo.substring(0, colonIdx));
                        parsedPass = decodeUtf8(userInfo.substring(colonIdx + 1));
                    } else {
                        parsedUser = decodeUtf8(userInfo);
                    }
                }

                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getRawPath();
                String query = uri.getRawQuery();

                StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                        .append(host).append(":").append(port)
                        .append(path != null && !path.isEmpty() ? path : "/postgres");
                if (query != null && !query.isEmpty()) {
                    jdbc.append("?").append(query);
                }

                this.jdbcUrl = jdbc.toString();
                this.username = parsedUser;
                this.password = parsedPass;
                this.postgresConfigured = (host != null && !host.isEmpty());
            } catch (Exception e) {
                System.err.println("[ConfigDB] No se pudo interpretar DATABASE_URL: " + e.getMessage());
            }
        }
    }

    private static String obtenerParam(Map<String, String> dotEnv, String sysProp, String envKey) {
        String s = System.getProperty(sysProp);
        if (s != null && !s.trim().isEmpty()) return s.trim();
        String e = System.getenv(envKey);
        if (e != null && !e.trim().isEmpty()) return e.trim();
        String d = dotEnv.get(envKey);
        return (d != null && !d.trim().isEmpty()) ? d.trim() : null;
    }

    private static Map<String, String> leerArchivoDotEnv() {
        Map<String, String> map = new HashMap<>();
        File envFile = new File(".env");
        if (!envFile.exists() || !envFile.isFile()) return map;
        try {
            List<String> lines = Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq > 0) {
                    String key = t.substring(0, eq).trim();
                    String val = t.substring(eq + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        if (val.length() >= 2) val = val.substring(1, val.length() - 1);
                    }
                    map.put(key, val);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static String decodeUtf8(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isStrictPostgres() { return strictPostgres; }
    public String getSqlitePath() { return sqlitePath; }
    public boolean isPostgresConfigured() { return postgresConfigured; }

    public String getSafePostgresTarget() {
        if (!postgresConfigured || jdbcUrl == null) return "";
        int q = jdbcUrl.indexOf('?');
        return q > 0 ? jdbcUrl.substring(0, q) : jdbcUrl;
    }
}
