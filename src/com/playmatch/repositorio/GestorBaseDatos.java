package com.playmatch.repositorio;

import com.playmatch.modelo.*;
import com.playmatch.util.ConfiguracionDB;
import com.playmatch.util.SeguridadUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Repositorio relacional robusto sobre JDBC estándar (java.sql.*).
 * Motor principal empresarial: PostgreSQL 14+ gestionado con pool de conexiones HikariCP.
 * Respaldo automático local: SQLite 3 (WAL + Foreign Keys) cuando no hay servidor Postgres configurado.
 */
public class GestorBaseDatos {
    public enum MotorDB {
        POSTGRESQL,
        SQLITE
    }

    private static GestorBaseDatos instancia;

    private final ConfiguracionDB configDB;
    private MotorDB motorActivo;
    private HikariDataSource hikariPool;
    private String sqlitePath;
    private String sqliteJdbcUrl;
    private final AtomicLong ultimoDmMs = new AtomicLong(0L);
    private boolean esSqliteEnMemoria;
    private String notaEstadoMotor = "";

    private static final DateTimeFormatter FMT_HORA = DateTimeFormatter.ofPattern("HH:mm");

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ignored) {}
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {}
    }

    private GestorBaseDatos() {
        this.configDB = ConfiguracionDB.cargar();
        this.sqlitePath = configDB.getSqlitePath();
        this.esSqliteEnMemoria = ":memory:".equalsIgnoreCase(this.sqlitePath);
        this.sqliteJdbcUrl = "jdbc:sqlite:" + this.sqlitePath;

        inicializarMotorYEsquema();

        Runtime.getRuntime().addShutdownHook(new Thread(this::cerrarPool));
    }

    public static synchronized GestorBaseDatos getInstancia() {
        if (instancia == null) {
            instancia = new GestorBaseDatos();
        }
        return instancia;
    }

    private void inicializarMotorYEsquema() {
        if (configDB.isPostgresConfigured()) {
            try {
                iniciarPoolPostgreSQL();
                this.motorActivo = MotorDB.POSTGRESQL;
                this.notaEstadoMotor = "Pool HikariCP activo (10 conexiones)";
                inicializarBaseDatos();
                return;
            } catch (Exception e) {
                cerrarPool();
                if (configDB.isStrictPostgres()) {
                    throw new IllegalStateException("Fallo conexión obligatoria a PostgreSQL (DB_STRICT=true): " + e.getMessage(), e);
                }
                System.err.println("[BaseDatos] Aviso: No se pudo conectar a PostgreSQL (" + e.getMessage() + "). Activando respaldo local SQLite.");
                this.notaEstadoMotor = "Respaldo SQLite local (PostgreSQL inaccesible)";
            }
        } else {
            this.notaEstadoMotor = "Modo local SQLite (configura .env DATABASE_URL para PostgreSQL)";
        }

        this.motorActivo = MotorDB.SQLITE;
        asegurarDirectorioSqlite();
        try {
            inicializarBaseDatos();
        } catch (SQLException e) {
            throw new IllegalStateException("Error inicializando SQLite en " + sqlitePath, e);
        }
    }

    private void iniciarPoolPostgreSQL() throws SQLException {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(configDB.getJdbcUrl());
        if (configDB.getUsername() != null && !configDB.getUsername().isEmpty()) {
            hc.setUsername(configDB.getUsername());
        }
        if (configDB.getPassword() != null && !configDB.getPassword().isEmpty()) {
            hc.setPassword(configDB.getPassword());
        }
        hc.setMaximumPoolSize(10);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(8000);
        hc.setIdleTimeout(300000);
        hc.setMaxLifetime(900000);
        hc.setPoolName("PlayMatch-PostgresPool");
        hc.setConnectionTestQuery("SELECT 1");

        this.hikariPool = new HikariDataSource(hc);
        try (Connection test = hikariPool.getConnection();
             Statement st = test.createStatement();
             ResultSet rs = st.executeQuery("SELECT version()")) {
            if (rs.next()) {
                System.out.println("[PostgreSQL] Conectado: " + rs.getString(1));
            }
        }
    }

    public void cerrarPool() {
        if (hikariPool != null && !hikariPool.isClosed()) {
            try {
                hikariPool.close();
            } catch (Exception ignored) {}
        }
    }

    public boolean esPostgreSQL() {
        return motorActivo == MotorDB.POSTGRESQL;
    }

    public ConfiguracionDB getConfigDB() {
        return configDB;
    }

    public String getNombreMotor() {
        return esPostgreSQL() ? "PostgreSQL (HikariCP)" : "SQLite 3 (JDBC)";
    }

    public String getNotaEstadoMotor() {
        return notaEstadoMotor;
    }

    public String getRutaBaseDatos() {
        if (esPostgreSQL()) {
            return configDB.getSafePostgresTarget();
        }
        if (esSqliteEnMemoria) return ":memory:";
        return new File(sqlitePath).getAbsolutePath();
    }

    public long getTamanoArchivoBytes() {
        if (esPostgreSQL()) {
            try (Connection conn = abrirConexion();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT pg_database_size(current_database())")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
        if (esSqliteEnMemoria) return 0L;
        File f = new File(sqlitePath);
        return f.exists() ? f.length() : 0L;
    }

    private void asegurarDirectorioSqlite() {
        if (esSqliteEnMemoria) return;
        File dbFile = new File(sqlitePath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private Connection abrirConexion() throws SQLException {
        if (esPostgreSQL()) {
            return hikariPool.getConnection();
        }
        Connection conn = DriverManager.getConnection(sqliteJdbcUrl);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
            st.execute("PRAGMA busy_timeout = 5000;");
        }
        return conn;
    }

    private void inicializarBaseDatos() throws SQLException {
        try (Connection conn = abrirConexion()) {
            if (!esPostgreSQL() && !esSqliteEnMemoria) {
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode = WAL;");
                }
            }
            ejecutarEsquema(conn);
            migrarColumnasSiFaltan(conn);
            sembrarDatosInicialesSiVacio(conn);
            asegurarCatalogoYChatGeneral(conn);
        }
    }

    private void ejecutarEsquema(Connection conn) throws SQLException {
        String sqlScript = cargarSchemaDesdeArchivo();
        StringBuilder limpio = new StringBuilder();
        for (String line : sqlScript.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--") && !trimmed.isEmpty()) {
                limpio.append(line).append("\n");
            }
        }
        String[] statements = limpio.toString().split(";");
        try (Statement st = conn.createStatement()) {
            for (String stmt : statements) {
                String s = stmt.trim();
                if (!s.isEmpty()) {
                    st.execute(s);
                }
            }
        }
    }

    private void migrarColumnasSiFaltan(Connection conn) throws SQLException {
        boolean marcoRecienAgregado = false;
        if (esPostgreSQL()) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE player_game_stats ADD COLUMN IF NOT EXISTS is_primary INT DEFAULT 1;");
                st.execute("ALTER TABLE games ADD COLUMN IF NOT EXISTS is_active INT DEFAULT 1;");
                st.execute("ALTER TABLE games ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 100;");
                st.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified INT NOT NULL DEFAULT 0;");
                st.execute("ALTER TABLE users ALTER COLUMN avatar_url TYPE TEXT;");
                st.execute("ALTER TABLE chat_messages ALTER COLUMN sender_avatar TYPE TEXT;");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS discord_id VARCHAR(40) DEFAULT '';");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS discord_connected INT NOT NULL DEFAULT 0;");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS favorite_games VARCHAR(255) DEFAULT '';");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS favorite_genres VARCHAR(255) DEFAULT '';");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS play_preferences VARCHAR(255) DEFAULT '';");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS star_rating DOUBLE PRECISION DEFAULT 4.8;");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS rating_count INT NOT NULL DEFAULT 1;");
                st.execute("ALTER TABLE player_profiles ADD COLUMN IF NOT EXISTS card_frame VARCHAR(30) NOT NULL DEFAULT 'NONE';");
                st.execute("ALTER TABLE direct_messages ADD COLUMN IF NOT EXISTS created_ms BIGINT NOT NULL DEFAULT 0;");
                st.execute("ALTER TABLE direct_messages ADD COLUMN IF NOT EXISTS msg_type VARCHAR(20) NOT NULL DEFAULT 'TEXT';");
                st.execute("ALTER TABLE direct_messages ADD COLUMN IF NOT EXISTS attachment_url TEXT;");
                st.execute("ALTER TABLE direct_messages ADD COLUMN IF NOT EXISTS meta_json TEXT;");
                st.execute("ALTER TABLE tournament_registrations ADD COLUMN IF NOT EXISTS checked_in INT NOT NULL DEFAULT 0;");
                st.execute("ALTER TABLE tournament_matches ADD COLUMN IF NOT EXISTS round_index INT NOT NULL DEFAULT 1;");
                st.execute("ALTER TABLE tournament_matches ADD COLUMN IF NOT EXISTS next_match_order INT DEFAULT 0;");
                st.execute("ALTER TABLE tournament_matches ADD COLUMN IF NOT EXISTS next_slot INT DEFAULT 0;");
            }
        } else {
            marcoRecienAgregado = !existeColumnaSqlite(conn, "player_profiles", "card_frame");
            String[][] columnasSqlite = {
                {"player_game_stats", "is_primary", "INT DEFAULT 1"},
                {"games", "is_active", "INT DEFAULT 1"},
                {"games", "sort_order", "INT DEFAULT 100"},
                {"users", "phone_verified", "INT NOT NULL DEFAULT 0"},
                {"player_profiles", "discord_id", "VARCHAR(40) DEFAULT ''"},
                {"player_profiles", "discord_connected", "INT NOT NULL DEFAULT 0"},
                {"player_profiles", "favorite_games", "VARCHAR(255) DEFAULT ''"},
                {"player_profiles", "favorite_genres", "VARCHAR(255) DEFAULT ''"},
                {"player_profiles", "play_preferences", "VARCHAR(255) DEFAULT ''"},
                {"player_profiles", "star_rating", "DOUBLE PRECISION DEFAULT 4.8"},
                {"player_profiles", "rating_count", "INT NOT NULL DEFAULT 1"},
                {"player_profiles", "card_frame", "VARCHAR(30) NOT NULL DEFAULT 'NONE'"},
                {"direct_messages", "created_ms", "BIGINT NOT NULL DEFAULT 0"},
                {"direct_messages", "msg_type", "VARCHAR(20) NOT NULL DEFAULT 'TEXT'"},
                {"direct_messages", "attachment_url", "TEXT"},
                {"direct_messages", "meta_json", "TEXT"},
                {"tournament_registrations", "checked_in", "INT NOT NULL DEFAULT 0"},
                {"tournament_matches", "round_index", "INT NOT NULL DEFAULT 1"},
                {"tournament_matches", "next_match_order", "INT DEFAULT 0"},
                {"tournament_matches", "next_slot", "INT DEFAULT 0"}
            };
            for (String[] c : columnasSqlite) {
                if (!existeColumnaSqlite(conn, c[0], c[1])) {
                    try (Statement st = conn.createStatement()) {
                        st.execute("ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2] + ";");
                    }
                }
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE users SET phone_verified = 1 WHERE id IN ('u-1','u-2','u-3','u-4','u-5','u-6','u-7') AND phone_verified = 0 AND telefono <> '';");
            st.execute("UPDATE player_profiles SET discord_connected = 1 WHERE id IN ('p-1','p-2','p-3','p-4','p-5','p-6') AND discord_connected = 0 AND discord_tag IS NOT NULL AND discord_tag <> '';");
            st.execute("UPDATE tournament_matches SET round_index = 2, next_match_order = 0, next_slot = 0 WHERE tournament_id = 't-3' AND match_order = 3 AND round_index = 1;");
            st.execute("UPDATE tournament_matches SET round_index = 1, next_match_order = 3, next_slot = 1 WHERE tournament_id = 't-3' AND match_order = 1 AND next_match_order = 0;");
            st.execute("UPDATE tournament_matches SET round_index = 1, next_match_order = 3, next_slot = 2 WHERE tournament_id = 't-3' AND match_order = 2 AND next_match_order = 0;");
            if (marcoRecienAgregado) {
                String[][] marcosDemo = {
                    {"p-1", "CHALLENGER"}, {"p-2", "EMERALD"}, {"p-3", "DIAMOND"},
                    {"p-4", "GOLD"}, {"p-5", "RUBY"}, {"p-6", "GOLD"}
                };
                try (PreparedStatement pf = conn.prepareStatement("UPDATE player_profiles SET card_frame = ? WHERE id = ? AND (card_frame IS NULL OR card_frame = 'NONE')")) {
                    for (String[] mf : marcosDemo) {
                        pf.setString(1, mf[1]);
                        pf.setString(2, mf[0]);
                        pf.addBatch();
                    }
                    pf.executeBatch();
                }
            }
            // Migrar avatares robots antiguos al nuevo avatar SVG propio /api/avatar
            try (PreparedStatement pu = conn.prepareStatement("SELECT id, username FROM users WHERE avatar_url IS NULL OR avatar_url = '' OR avatar_url LIKE '%dicebear.com/7.x/bottts%'");
                 ResultSet rs = pu.executeQuery()) {
                try (PreparedStatement upd = conn.prepareStatement("UPDATE users SET avatar_url = ? WHERE id = ?")) {
                    while (rs.next()) {
                        upd.setString(1, avatarPorDefecto(rs.getString("username")));
                        upd.setString(2, rs.getString("id"));
                        upd.addBatch();
                    }
                    upd.executeBatch();
                }
            }
            try (PreparedStatement pc = conn.prepareStatement("SELECT id, sender_username FROM chat_messages WHERE sender_avatar IS NULL OR sender_avatar = '' OR sender_avatar LIKE '%dicebear.com/7.x/bottts%'");
                 ResultSet rs = pc.executeQuery()) {
                try (PreparedStatement upd = conn.prepareStatement("UPDATE chat_messages SET sender_avatar = ? WHERE id = ?")) {
                    while (rs.next()) {
                        String su = rs.getString("sender_username");
                        upd.setString(1, "PlayMatch Sistema".equals(su) ? "/api/avatar?u=PlayMatch" : avatarPorDefecto(su));
                        upd.setString(2, rs.getString("id"));
                        upd.addBatch();
                    }
                    upd.executeBatch();
                }
            }
        } catch (SQLException ignored) {}
    }

    private static String avatarPorDefecto(String username) {
        String clean = (username != null && !username.trim().isEmpty()) ? username.trim().replace(" ", "_") : "PlayMatch";
        try {
            return "/api/avatar?u=" + java.net.URLEncoder.encode(clean, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "/api/avatar?u=" + clean;
        }
    }

    private boolean existeColumnaSqlite(Connection conn, String tabla, String columna) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ");")) {
            while (rs.next()) {
                if (columna.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String cargarSchemaDesdeArchivo() {
        File schemaFile = new File("database/schema.sql");
        if (schemaFile.exists() && schemaFile.isFile()) {
            try {
                return new String(Files.readAllBytes(schemaFile.toPath()), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
        return getFallbackSchemaSql();
    }

    private String getFallbackSchemaSql() {
        return "CREATE TABLE IF NOT EXISTS users (" +
                "id VARCHAR(36) PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE, " +
                "email VARCHAR(120) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, " +
                "avatar_url TEXT, telefono VARCHAR(30) NOT NULL DEFAULT '', " +
                "phone_verified INT NOT NULL DEFAULT 0, " +
                "role VARCHAR(20) NOT NULL DEFAULT 'GAMER', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);" +
                "CREATE TABLE IF NOT EXISTS player_profiles (" +
                "id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, bio TEXT, " +
                "country VARCHAR(50) DEFAULT 'Chile', region VARCHAR(100) DEFAULT 'Región Metropolitana', " +
                "toxic_level VARCHAR(20) DEFAULT 'ZERO_TOXIC', reputation_score INT DEFAULT 100, " +
                "preferred_schedule VARCHAR(60) DEFAULT 'Noches (21:00 - 01:00)', " +
                "steam_id VARCHAR(100) DEFAULT '', riot_id VARCHAR(100) DEFAULT '', epic_id VARCHAR(100) DEFAULT '', discord_tag VARCHAR(100) DEFAULT '', " +
                "discord_id VARCHAR(40) DEFAULT '', discord_connected INT NOT NULL DEFAULT 0, " +
                "favorite_games VARCHAR(255) DEFAULT '', favorite_genres VARCHAR(255) DEFAULT '', " +
                "play_preferences VARCHAR(255) DEFAULT '', star_rating DOUBLE PRECISION DEFAULT 4.8, " +
                "rating_count INT NOT NULL DEFAULT 1, card_frame VARCHAR(30) NOT NULL DEFAULT 'NONE', " +
                "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS player_ratings (" +
                "id VARCHAR(36) PRIMARY KEY, rater_user_id VARCHAR(36) NOT NULL, rated_user_id VARCHAR(36) NOT NULL, " +
                "stars INT NOT NULL CHECK (stars >= 1 AND stars <= 5), comment VARCHAR(255) DEFAULT '', " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (rater_user_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (rated_user_id) REFERENCES users(id) ON DELETE CASCADE, UNIQUE(rater_user_id, rated_user_id));" +
                "CREATE TABLE IF NOT EXISTS games (" +
                "id VARCHAR(36) PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL UNIQUE, " +
                "category VARCHAR(50) NOT NULL, platform VARCHAR(60) DEFAULT 'PC / Multiplataforma', " +
                "max_team_size INT DEFAULT 5, description TEXT, accent_color VARCHAR(20) DEFAULT '#8b5cf6', " +
                "banner_url VARCHAR(255), is_active INT DEFAULT 1, sort_order INT DEFAULT 100, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);" +
                "CREATE TABLE IF NOT EXISTS player_game_stats (" +
                "id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, game_id VARCHAR(36) NOT NULL, " +
                "in_game_rank VARCHAR(60) NOT NULL, preferred_role VARCHAR(60) NOT NULL, " +
                "playstyle VARCHAR(30) DEFAULT 'COMPETITIVE', is_primary INT DEFAULT 1, " +
                "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE, UNIQUE(user_id, game_id));" +
                "CREATE TABLE IF NOT EXISTS match_requests (" +
                "id VARCHAR(36) PRIMARY KEY, sender_id VARCHAR(36) NOT NULL, receiver_id VARCHAR(36) NOT NULL, " +
                "game_id VARCHAR(36) NOT NULL, message VARCHAR(255), status VARCHAR(20) DEFAULT 'PENDING', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS tournaments (" +
                "id VARCHAR(36) PRIMARY KEY, title VARCHAR(150) NOT NULL, game_id VARCHAR(36) NOT NULL, " +
                "organizer_id VARCHAR(36) NOT NULL, organizer_name VARCHAR(100) DEFAULT 'Andes Esports', " +
                "prize_pool VARCHAR(120), format VARCHAR(50) DEFAULT 'SINGLE_ELIMINATION', max_teams INT DEFAULT 8, " +
                "start_date VARCHAR(60), status VARCHAR(30) DEFAULT 'OPEN_REGISTRATION', rules TEXT, banner_url VARCHAR(255), " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (organizer_id) REFERENCES users(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS teams (" +
                "id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, tag VARCHAR(15) NOT NULL, " +
                "captain_id VARCHAR(36) NOT NULL, logo_url VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (captain_id) REFERENCES users(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS tournament_registrations (" +
                "id VARCHAR(36) PRIMARY KEY, tournament_id VARCHAR(36) NOT NULL, team_id VARCHAR(36) NOT NULL, " +
                "seed_number INT DEFAULT 1, checked_in INT NOT NULL DEFAULT 0, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE, UNIQUE(tournament_id, team_id));" +
                "CREATE TABLE IF NOT EXISTS tournament_matches (" +
                "id VARCHAR(36) PRIMARY KEY, tournament_id VARCHAR(36) NOT NULL, round_name VARCHAR(50) NOT NULL, " +
                "round_index INT NOT NULL DEFAULT 1, match_order INT NOT NULL, next_match_order INT DEFAULT 0, next_slot INT DEFAULT 0, " +
                "team1_name VARCHAR(100), team2_name VARCHAR(100), " +
                "score_team1 INT DEFAULT 0, score_team2 INT DEFAULT 0, winner_name VARCHAR(100), " +
                "status VARCHAR(20) DEFAULT 'SCHEDULED', " +
                "FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id VARCHAR(36) PRIMARY KEY, sender_id VARCHAR(36), sender_username VARCHAR(60) NOT NULL, " +
                "sender_avatar TEXT, channel VARCHAR(50) NOT NULL DEFAULT 'global', content TEXT NOT NULL, " +
                "sent_at VARCHAR(30) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL);" +
                "CREATE TABLE IF NOT EXISTS friendships (" +
                "id VARCHAR(36) PRIMARY KEY, requester_id VARCHAR(36) NOT NULL, addressee_id VARCHAR(36) NOT NULL, " +
                "status VARCHAR(20) NOT NULL DEFAULT 'PENDING', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (addressee_id) REFERENCES users(id) ON DELETE CASCADE, UNIQUE(requester_id, addressee_id));" +
                "CREATE TABLE IF NOT EXISTS direct_messages (" +
                "id VARCHAR(36) PRIMARY KEY, sender_id VARCHAR(36) NOT NULL, receiver_id VARCHAR(36) NOT NULL, " +
                "content TEXT NOT NULL, msg_type VARCHAR(20) NOT NULL DEFAULT 'TEXT', attachment_url TEXT, meta_json TEXT, " +
                "is_read INT NOT NULL DEFAULT 0, sent_at VARCHAR(30) NOT NULL, " +
                "created_ms BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS squad_lobbies (" +
                "id VARCHAR(36) PRIMARY KEY, host_user_id VARCHAR(36) NOT NULL, game_id VARCHAR(36) NOT NULL, " +
                "title VARCHAR(140) NOT NULL, mode VARCHAR(40) NOT NULL DEFAULT 'Escuadra', max_slots INT NOT NULL DEFAULT 5, " +
                "roles_needed VARCHAR(150) DEFAULT '', schedule_label VARCHAR(80) DEFAULT 'Ahora', status VARCHAR(20) NOT NULL DEFAULT 'OPEN', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS squad_lobby_members (" +
                "id VARCHAR(36) PRIMARY KEY, lobby_id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL, " +
                "role_note VARCHAR(60) DEFAULT '', joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (lobby_id) REFERENCES squad_lobbies(id) ON DELETE CASCADE, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "UNIQUE(lobby_id, user_id));" +
                "CREATE TABLE IF NOT EXISTS user_blocks (" +
                "id VARCHAR(36) PRIMARY KEY, blocker_id VARCHAR(36) NOT NULL, blocked_id VARCHAR(36) NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (blocker_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE, " +
                "UNIQUE(blocker_id, blocked_id));" +
                "CREATE TABLE IF NOT EXISTS player_reports (" +
                "id VARCHAR(36) PRIMARY KEY, reporter_id VARCHAR(36) NOT NULL, reported_id VARCHAR(36) NOT NULL, " +
                "reason VARCHAR(80) NOT NULL, details VARCHAR(300) DEFAULT '', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (reported_id) REFERENCES users(id) ON DELETE CASCADE);";
    }

    private void sembrarDatosInicialesSiVacio(Connection conn) throws SQLException {
        if (contarFilas(conn, "games") > 0 && contarFilas(conn, "users") > 0) {
            return;
        }

        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (contarFilas(conn, "games") == 0) {
                sembrarJuegos(conn);
            }
            if (contarFilas(conn, "users") == 0) {
                sembrarUsuariosYPerfiles(conn);
            }
            if (contarFilas(conn, "tournaments") == 0) {
                sembrarTorneosYBrackets(conn);
            }
            if (contarFilas(conn, "chat_messages") == 0) {
                sembrarMensajesIniciales(conn);
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private int contarFilas(Connection conn, String tabla) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabla)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static final Object[][] JUEGOS_CATALOGO_BASE = {
        {"g-val", "valorant", "Valorant", "Tactical FPS", "PC / Consola", 5,
         "Shooter táctico 5v5 basado en precisión de disparo y habilidades estratégicas de agentes.",
         "#ff4655", "/assets/games/valorant.jpg", 1},
        {"g-lol", "lol", "League of Legends", "MOBA", "PC (LAS)", 5,
         "Arena de batalla multijugador por equipos 5v5 con enfoque en macrogame y objetivos.",
         "#8b5cf6", "/assets/games/lol.jpg", 2},
        {"g-cs2", "cs2", "Counter-Strike 2", "Tactical FPS", "PC (Steam)", 5,
         "Competitivo clásico por rondas de ataque y defensa táctica con economía de equipo.",
         "#f59e0b", "/assets/games/cs2.jpg", 3},
        {"g-rl", "rocket-league", "Rocket League", "Deportes / Arcade", "Multiplataforma", 3,
         "Fútbol vehicular competitivo 3v3 de alta velocidad, rotaciones y mecánicas aéreas.",
         "#06b6d4", "/assets/games/rocket-league.jpg", 4},
        {"g-mr", "marvel-rivals", "Marvel Rivals", "Hero Shooter", "PC / Consola", 6,
         "Hero shooter competitivo 6v6 por equipos con sinergias de habilidades entre superhéroes y villanos.",
         "#e11d48", "/assets/games/marvel-rivals.jpg", 5},
        {"g-ow", "overwatch", "Overwatch 2", "Hero Shooter", "Multiplataforma", 5,
         "Combate por objetivos 5v5 con roles de Tanque, Daño y Apoyo enfocado en coordinación de equipo.",
         "#f97316", "/assets/games/overwatch.jpg", 6},
        {"g-apex", "apex-legends", "Apex Legends", "Battle Royale", "Multiplataforma", 3,
         "Battle Royale táctico en tríos y dúos con leyendas de movilidad rápida y juego en escuadra.",
         "#dc2626", "/assets/games/apex-legends.jpg", 7},
        {"g-fn", "fortnite", "Fortnite", "Battle Royale", "Multiplataforma", 4,
         "Dúos, tríos y escuadrones competitivos en Batalla Campal, Cero Construcción y torneos Arena.",
         "#3b82f6", "/assets/games/fortnite.jpg", 8},
        {"g-rbx", "roblox", "Roblox", "Sandbox / Multijuego", "Multiplataforma", 4,
         "Plataforma social multijuego para coordinar partidas competitivas, experiencias y salas privadas.",
         "#0ea5e9", "/assets/games/roblox.jpg", 9},
        {"g-mc", "minecraft", "Minecraft", "Supervivencia / PvP", "Multiplataforma", 4,
         "Supervivencia cooperativa, Realms, SkyWars, BedWars y servidores competitivos en equipo.",
         "#22c55e", "/assets/games/minecraft.jpg", 10}
    };

    private static final String[][] MENSAJES_CANALES_INICIALES = {
        {"msg-1", "u-1", "Jesus_Villasanti", "/api/avatar?u=Jesus_Villasanti", "global",
         "Bienvenidos a PlayMatch. Todas las partidas, registros y torneos quedan persistidos en base de datos.", "19:00"},
        {"msg-4", "u-5", "jaeliug", "/api/avatar?u=jaeliug", "general-duo",
         "¡Chat abierto para encontrar dúo! Deja tu juego (Valorant, Marvel Rivals, Fortnite, Apex, etc.), rango y horario.", "19:08"},
        {"msg-5", "u-2", "Datrikk", "/api/avatar?u=Datrikk", "general-escuadra",
         "¿Buscas armar squad o grupo completo? Coordina aquí escuadras para Marvel Rivals, Overwatch 2, Roblox, Minecraft o CS2.", "19:12"},
        {"msg-2", "u-2", "Datrikk", "/api/avatar?u=Datrikk", "scrims-valorant",
         "Buscamos 1 Duelista o Iniciador rango Ascendente+ para scrim hoy a las 21:00.", "19:15"},
        {"msg-3", "u-3", "dididong", "/api/avatar?u=dididong", "duo-lol",
         "Disponible para ranked Duo en servidor LAS como Support.", "19:22"}
    };

    private void sembrarJuegos(Connection conn) throws SQLException {
        String sql = "INSERT INTO games (id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active, sort_order) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] j : JUEGOS_CATALOGO_BASE) {
                ps.setString(1, (String) j[0]);
                ps.setString(2, (String) j[1]);
                ps.setString(3, (String) j[2]);
                ps.setString(4, (String) j[3]);
                ps.setString(5, (String) j[4]);
                ps.setInt(6, (Integer) j[5]);
                ps.setString(7, (String) j[6]);
                ps.setString(8, (String) j[7]);
                ps.setString(9, (String) j[8]);
                ps.setInt(10, (Integer) j[9]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void asegurarCatalogoYChatGeneral(Connection conn) throws SQLException {
        // Garantizar que bases ya existentes reciban los nuevos títulos sin duplicar registros
        sembrarJuegos(conn);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE games SET sort_order = ? WHERE id = ? AND sort_order = 100")) {
            for (Object[] j : JUEGOS_CATALOGO_BASE) {
                ps.setInt(1, (Integer) j[9]);
                ps.setString(2, (String) j[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ignored) {}

        // Actualizar banners de títulos base si conservan placeholders antiguos (Unsplash / SVG)
        String sqlFixBanner = "UPDATE games SET banner_url = ? WHERE id = ? AND (banner_url IS NULL OR banner_url = '' OR banner_url LIKE 'https://images.unsplash.com/%' OR banner_url LIKE '/assets/games/%.svg')";
        try (PreparedStatement ps = conn.prepareStatement(sqlFixBanner)) {
            for (Object[] j : JUEGOS_CATALOGO_BASE) {
                ps.setString(1, (String) j[8]);
                ps.setString(2, (String) j[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ignored) {}

        // Garantizar mensaje guía inicial en cada apartado de chat general si el canal está vacío
        String sqlCheckChan = "SELECT COUNT(*) FROM chat_messages WHERE LOWER(channel) = LOWER(?)";
        String sqlCheckUser = "SELECT 1 FROM users WHERE id = ?";
        String sqlInsMsg = "INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

        for (String[] m : MENSAJES_CANALES_INICIALES) {
            String channel = m[4];
            if (!"global".equals(channel) && !"general-duo".equals(channel) && !"general-escuadra".equals(channel)) {
                continue;
            }
            boolean channelEmpty = false;
            try (PreparedStatement pc = conn.prepareStatement(sqlCheckChan)) {
                pc.setString(1, channel);
                try (ResultSet rs = pc.executeQuery()) {
                    channelEmpty = rs.next() && rs.getInt(1) == 0;
                }
            }
            if (!channelEmpty) continue;

            boolean senderExists = false;
            try (PreparedStatement pu = conn.prepareStatement(sqlCheckUser)) {
                pu.setString(1, m[1]);
                try (ResultSet rs = pu.executeQuery()) {
                    senderExists = rs.next();
                }
            }
            if (!senderExists) continue;

            try (PreparedStatement pi = conn.prepareStatement(sqlInsMsg)) {
                for (int i = 0; i < 7; i++) {
                    pi.setString(i + 1, m[i]);
                }
                pi.executeUpdate();
            }
        }

        // Asegurar gustos iniciales en perfiles base + perfil de demostración < 2 estrellas (Kuro_Tilt)
        asegurarGustosYValoracionesDemo(conn);
    }

    private void asegurarGustosYValoracionesDemo(Connection conn) {
        String[][] gustosSeed = {
            {"p-1", "valorant,marvel-rivals,cs2", "Tactical FPS,Hero Shooter", "Escuadra,Con Micrófono,Sin Toxicidad,Rankeds Tryhard", "4.9", "3"},
            {"p-2", "valorant,cs2,apex-legends", "Tactical FPS,Battle Royale", "Dúo,Escuadra,Con Micrófono,Partidas Chill", "4.8", "3"},
            {"p-3", "lol,overwatch,fortnite", "MOBA,Hero Shooter", "Dúo,Sin Toxicidad,Con Micrófono", "4.7", "2"},
            {"p-4", "cs2,valorant,apex-legends", "Tactical FPS,Battle Royale", "Escuadra,Rankeds Tryhard,Con Micrófono", "4.6", "2"},
            {"p-5", "valorant,marvel-rivals,fortnite", "Tactical FPS,Hero Shooter,Battle Royale", "Dúo,Escuadra,Con Micrófono,Sin Toxicidad", "4.9", "3"},
            {"p-6", "lol,minecraft,roblox", "MOBA,Supervivencia / PvP,Sandbox / Multijuego", "Dúo,Partidas Chill,Sin Toxicidad", "4.5", "2"}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE player_profiles SET favorite_games = ?, favorite_genres = ?, play_preferences = ?, star_rating = ?, rating_count = ? " +
                "WHERE id = ? AND (favorite_games IS NULL OR favorite_games = '')")) {
            for (String[] g : gustosSeed) {
                ps.setString(1, g[1]);
                ps.setString(2, g[2]);
                ps.setString(3, g[3]);
                ps.setDouble(4, Double.parseDouble(g[4]));
                ps.setInt(5, Integer.parseInt(g[5]));
                ps.setString(6, g[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ignored) {}

        // Sembrar perfil demo de baja valoración (< 2.0 estrellas) para evidenciar penalización IA
        try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM users WHERE id = 'u-8'");
             ResultSet rs = check.executeQuery()) {
            if (!rs.next()) {
                String hashDef = SeguridadUtil.hashPassword("password123");
                try (PreparedStatement pu = conn.prepareStatement(
                        "INSERT INTO users (id, username, email, password_hash, avatar_url, telefono, phone_verified, role) " +
                        "VALUES ('u-8', 'Kuro_Tilt', 'kuro.tilt@playmatch.cl', ?, '/api/avatar?u=Kuro_Tilt', '+56 9 1999 0008', 1, 'GAMER') ON CONFLICT DO NOTHING")) {
                    pu.setString(1, hashDef);
                    pu.executeUpdate();
                }
                try (PreparedStatement pp = conn.prepareStatement(
                        "INSERT INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag, discord_id, discord_connected, favorite_games, favorite_genres, play_preferences, star_rating, rating_count) " +
                        "VALUES ('p-8', 'u-8', 'Juego Valorant y Marvel Rivals agresivo en rankeds nocturnas.', 'Chile', 'Región Metropolitana', 'COMPETITIVE', 32, 'Noches (21:00 - 01:00)', '', 'Kuro#TILT', '', '', 0, 'valorant,marvel-rivals', 'Tactical FPS,Hero Shooter', 'Rankeds Tryhard', 1.5, 2) ON CONFLICT DO NOTHING")) {
                    pp.executeUpdate();
                }
                try (PreparedStatement pgs = conn.prepareStatement(
                        "INSERT INTO player_game_stats (id, user_id, game_id, in_game_rank, preferred_role, playstyle, is_primary) " +
                        "VALUES ('s-8', 'u-8', 'g-val', 'Platino II', 'Duelista (Reyna)', 'TRYHARD', 1) ON CONFLICT DO NOTHING")) {
                    pgs.executeUpdate();
                }
            }
        } catch (SQLException ignored) {}

        // Sembrar votos base idempotentes para cálculos reales de AVG(stars)
        Object[][] votosSeed = {
            {"rt-1", "u-2", "u-1", 5, "Excelente comunicación y sin toxicidad."},
            {"rt-2", "u-5", "u-1", 5, "Gran controlador en equipo."},
            {"rt-3", "u-3", "u-1", 5, "Muy puntual en torneos."},
            {"rt-4", "u-1", "u-2", 5, "Buen compañero de escuadra."},
            {"rt-5", "u-5", "u-2", 5, "Buena energía en partidas."},
            {"rt-6", "u-4", "u-2", 4, "Sólido iniciador."},
            {"rt-7", "u-1", "u-5", 5, "Duelista muy constante."},
            {"rt-8", "u-2", "u-5", 5, "Recomendado para dúo."},
            {"rt-9", "u-3", "u-5", 5, "Cero flameo."},
            {"rt-10", "u-1", "u-8", 1, "Abandonó partida clasificatoria."},
            {"rt-11", "u-2", "u-8", 2, "Mucha actitud negativa por voz."}
        };
        try (PreparedStatement pr = conn.prepareStatement(
                "INSERT INTO player_ratings (id, rater_user_id, rated_user_id, stars, comment) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            for (Object[] v : votosSeed) {
                pr.setString(1, (String) v[0]);
                pr.setString(2, (String) v[1]);
                pr.setString(3, (String) v[2]);
                pr.setInt(4, (Integer) v[3]);
                pr.setString(5, (String) v[4]);
                pr.addBatch();
            }
            pr.executeBatch();
        } catch (SQLException ignored) {}

        asegurarAmistadesYChatsDemo(conn);
        asegurarLobbiesDemo(conn);
    }

    private void asegurarAmistadesYChatsDemo(Connection conn) {
        try {
            if (contarFilas(conn, "friendships") > 0 || contarFilas(conn, "direct_messages") > 0) {
                return;
            }
            Set<String> idsExistentes = new HashSet<>();
            try (PreparedStatement pu = conn.prepareStatement("SELECT id FROM users WHERE id IN ('u-1','u-2','u-3','u-4','u-5','u-6')");
                 ResultSet rs = pu.executeQuery()) {
                while (rs.next()) {
                    idsExistentes.add(rs.getString("id"));
                }
            }
            if (!idsExistentes.contains("u-1") || !idsExistentes.contains("u-5")) {
                return;
            }

            Object[][] amistadesSeed = {
                {"fr-1", "u-1", "u-5", "ACCEPTED"},
                {"fr-2", "u-1", "u-2", "ACCEPTED"},
                {"fr-3", "u-1", "u-4", "ACCEPTED"},
                {"fr-4", "u-1", "u-3", "ACCEPTED"},
                {"fr-5", "u-6", "u-1", "PENDING"},
                {"fr-6", "u-2", "u-5", "ACCEPTED"},
                {"fr-7", "u-3", "u-6", "ACCEPTED"},
                {"fr-8", "u-4", "u-2", "ACCEPTED"}
            };
            String sqlF = "INSERT INTO friendships (id, requester_id, addressee_id, status) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
            try (PreparedStatement pf = conn.prepareStatement(sqlF)) {
                for (Object[] f : amistadesSeed) {
                    if (idsExistentes.contains((String) f[1]) && idsExistentes.contains((String) f[2])) {
                        pf.setString(1, (String) f[0]);
                        pf.setString(2, (String) f[1]);
                        pf.setString(3, (String) f[2]);
                        pf.setString(4, (String) f[3]);
                        pf.addBatch();
                    }
                }
                pf.executeBatch();
            }

            Object[][] dmSeed = {
                {"dm-1", "u-5", "u-1", "Wena Jesus, ¿sale Valorant a las 21:00?", 1, "18:40", 1731200001000L},
                {"dm-2", "u-1", "u-5", "De una! Estoy practicando smokes en Ascent.", 1, "18:42", 1731200002000L},
                {"dm-3", "u-5", "u-1", "Estoy listo para jugar Valorant a las 21:00 🔥", 1, "18:45", 1731200003000L},
                {"dm-4", "u-2", "u-1", "Buena partida recién! Mañana repetimos dúo", 1, "17:20", 1731200004000L},
                {"dm-5", "u-4", "u-1", "Falta 1 para Premier CS2 a las 22:00, ¿te sumas?", 0, "19:18", 1731200005000L},
                {"dm-6", "u-3", "u-1", "Cuando quieras armamos flex en LoL!", 1, "16:10", 1731200000500L},
                {"dm-7", "u-5", "u-2", "¿Armamos dúo en Ascendente hoy?", 0, "18:55", 1731200006000L}
            };
            String sqlDm = "INSERT INTO direct_messages (id, sender_id, receiver_id, content, is_read, sent_at, created_ms) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
            try (PreparedStatement pd = conn.prepareStatement(sqlDm)) {
                for (Object[] d : dmSeed) {
                    if (idsExistentes.contains((String) d[1]) && idsExistentes.contains((String) d[2])) {
                        pd.setString(1, (String) d[0]);
                        pd.setString(2, (String) d[1]);
                        pd.setString(3, (String) d[2]);
                        pd.setString(4, (String) d[3]);
                        pd.setInt(5, (Integer) d[4]);
                        pd.setString(6, (String) d[5]);
                        pd.setLong(7, (Long) d[6]);
                        pd.addBatch();
                    }
                }
                pd.executeBatch();
            }
        } catch (SQLException ignored) {}
    }

    private void asegurarLobbiesDemo(Connection conn) {
        try {
            if (contarFilas(conn, "squad_lobbies") > 0) return;

            Set<String> ids = new HashSet<>();
            try (PreparedStatement pu = conn.prepareStatement("SELECT id FROM users WHERE id IN ('u-1','u-2','u-4','u-5')");
                 ResultSet rs = pu.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("id"));
            }
            if (!ids.contains("u-1") || !ids.contains("u-5")) return;

            String gVal = resolverGameId(conn, "valorant");
            String gMr = resolverGameId(conn, "marvel-rivals");
            String gApex = resolverGameId(conn, "apex");
            if (gMr == null) gMr = gVal;
            if (gApex == null) gApex = gVal;
            if (gVal == null) return;

            Object[][] lobbiesSeed = {
                {"lob-1", "u-5", gVal, "Ranked Valorant Ascendente+ · Falta Sentinel", "Escuadra 5v5", 5, "Sentinel / Flex", "Hoy 21:30 hrs", "OPEN"},
                {"lob-2", "u-2", gMr, "Marvel Rivals Competitivo · Sin Tilt", "Escuadra 6v6", 6, "Vanguard / Strategist", "Tardes / Noches", "OPEN"},
                {"lob-3", "u-4", gApex, "Apex Legends Tríos · Micrófono activo", "Trío", 3, "Entry / Support", "22:00 hrs", "OPEN"}
            };
            String sqlL = "INSERT INTO squad_lobbies (id, host_user_id, game_id, title, mode, max_slots, roles_needed, schedule_label, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
            try (PreparedStatement pl = conn.prepareStatement(sqlL)) {
                for (Object[] l : lobbiesSeed) {
                    if (ids.contains((String) l[1])) {
                        pl.setString(1, (String) l[0]);
                        pl.setString(2, (String) l[1]);
                        pl.setString(3, (String) l[2]);
                        pl.setString(4, (String) l[3]);
                        pl.setString(5, (String) l[4]);
                        pl.setInt(6, (Integer) l[5]);
                        pl.setString(7, (String) l[6]);
                        pl.setString(8, (String) l[7]);
                        pl.setString(9, (String) l[8]);
                        pl.addBatch();
                    }
                }
                pl.executeBatch();
            }

            String[][] membersSeed = {
                {"lm-1", "lob-1", "u-5", "Duelista"},
                {"lm-2", "lob-1", "u-1", "Controlador"},
                {"lm-3", "lob-1", "u-2", "Iniciador"},
                {"lm-4", "lob-2", "u-2", "Flex"},
                {"lm-5", "lob-2", "u-5", "Duelista"},
                {"lm-6", "lob-3", "u-4", "Fragger"}
            };
            String sqlM = "INSERT INTO squad_lobby_members (id, lobby_id, user_id, role_note) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
            try (PreparedStatement pm = conn.prepareStatement(sqlM)) {
                for (String[] m : membersSeed) {
                    if (ids.contains(m[2])) {
                        pm.setString(1, m[0]);
                        pm.setString(2, m[1]);
                        pm.setString(3, m[2]);
                        pm.setString(4, m[3]);
                        pm.addBatch();
                    }
                }
                pm.executeBatch();
            }

            String[][] lobbyChatSeed = {
                {"lm-msg-1", "u-5", "jaeliug", "/api/avatar?u=jaeliug", "lobby-lob-1", "Sala grupal lista! Faltan 2 jugadores para completar 5v5 hoy a las 21:30.", "19:25"},
                {"lm-msg-2", "u-2", "Datrikk", "/api/avatar?u=Datrikk", "lobby-lob-2", "Armando escuadra para Marvel Rivals, únanse al lobby!", "19:30"}
            };
            String sqlLc = "INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
            try (PreparedStatement plc = conn.prepareStatement(sqlLc)) {
                for (String[] mc : lobbyChatSeed) {
                    if (ids.contains(mc[1])) {
                        for (int i = 0; i < 7; i++) plc.setString(i + 1, mc[i]);
                        plc.addBatch();
                    }
                }
                plc.executeBatch();
            }
        } catch (SQLException ignored) {}
    }

    private void sembrarUsuariosYPerfiles(Connection conn) throws SQLException {
        String hashAdmin = SeguridadUtil.hashPassword("admin123");
        String hashDefault = SeguridadUtil.hashPassword("password123");

        String sqlUser = "INSERT INTO users (id, username, email, password_hash, avatar_url, telefono, phone_verified, role) VALUES (?, ?, ?, ?, ?, ?, 1, ?)";
        Object[][] usersData = {
            {"u-1", "Jesus_Villasanti", "jesus.villasanti@playmatch.cl", hashAdmin, avatarPorDefecto("Jesus_Villasanti"), "+56 9 8123 4567", "ADMIN"},
            {"u-2", "Datrikk", "datrikk@playmatch.cl", hashDefault, avatarPorDefecto("Datrikk"), "+56 9 7234 5678", "GAMER"},
            {"u-3", "dididong", "dididong@playmatch.cl", hashDefault, avatarPorDefecto("dididong"), "+56 9 6345 6789", "GAMER"},
            {"u-4", "luchi", "luchi@playmatch.cl", hashDefault, avatarPorDefecto("luchi"), "+56 9 5456 7890", "GAMER"},
            {"u-5", "jaeliug", "jaeliug@playmatch.cl", hashDefault, avatarPorDefecto("jaeliug"), "+56 9 4567 8901", "GAMER"},
            {"u-6", "pepitogamer67", "pepitogamer67@playmatch.cl", hashDefault, avatarPorDefecto("pepitogamer67"), "+56 9 3678 9012", "GAMER"},
            {"u-7", "AndesEsports", "contacto@andesesports.cl", hashDefault, avatarPorDefecto("AndesEsports"), "+56 9 2111 2222", "ORGANIZER"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlUser)) {
            for (Object[] u : usersData) {
                ps.setString(1, (String) u[0]);
                ps.setString(2, (String) u[1]);
                ps.setString(3, (String) u[2]);
                ps.setString(4, (String) u[3]);
                ps.setString(5, (String) u[4]);
                ps.setString(6, (String) u[5]);
                ps.setString(7, (String) u[6]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String sqlProfile = "INSERT INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag, discord_id, discord_connected, card_frame) VALUES (?, ?, ?, 'Chile', ?, ?, ?, ?, ?, ?, ?, '', 1, ?)";
        Object[][] profiles = {
            {"p-1", "u-1", "Main Controller en Valorant. Busco escuadra seria para competir en torneos comunitarios y subir elo.", "Región Metropolitana (San Joaquín)", "ZERO_TOXIC", 100, "Noches (21:00 - 01:00)", "76561198000000001", "Jesus#CHI", "jesus_villasanti#0001", "CHALLENGER"},
            {"p-2", "u-2", "Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Excelente sinergia de equipo.", "Los Lagos (Castro)", "ZERO_TOXIC", 98, "Tardes y Noches (19:00 - 23:30)", "76561198000000002", "Datrikk#SUR", "datrikk_cl#1234", "EMERALD"},
            {"p-3", "u-3", "Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta temporada.", "Valparaíso (Viña del Mar)", "ZERO_TOXIC", 99, "Fines de semana y noches", "76561198000000003", "dididong#LAS", "dididong#5544", "DIAMOND"},
            {"p-4", "u-4", "AWPer en CS2. Comunicación clara por Discord, juego limpio y competitivo. Disponible para scrims.", "Biobío (Concepción)", "FRIENDLY", 96, "Noches (22:00 - 02:00)", "76561198000000004", "luchi#AWP", "luchi#8899", "GOLD"},
            {"p-5", "u-5", "Main Duelista en Valorant. Rango Inmortal 2, busco compañeros para torneos 5v5 con micro activo.", "Región Metropolitana (La Florida)", "ZERO_TOXIC", 97, "Tardes (18:00 - 22:30)", "76561198000000005", "jaeliug#CL1", "jaeliug#7711", "RUBY"},
            {"p-6", "u-6", "Toplaner en League of Legends. Buen macrogame, splitpush y rotaciones a objetivos. Cero flameo.", "Región Metropolitana (Maipú)", "FRIENDLY", 95, "Noches (21:30 - 01:30)", "76561198000000006", "pepito#LAS", "pepitogamer67#3322", "GOLD"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlProfile)) {
            for (Object[] p : profiles) {
                ps.setString(1, (String) p[0]);
                ps.setString(2, (String) p[1]);
                ps.setString(3, (String) p[2]);
                ps.setString(4, (String) p[3]);
                ps.setString(5, (String) p[4]);
                ps.setInt(6, (Integer) p[5]);
                ps.setString(7, (String) p[6]);
                ps.setString(8, (String) p[7]);
                ps.setString(9, (String) p[8]);
                ps.setString(10, (String) p[9]);
                ps.setString(11, (String) p[10]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String sqlStats = "INSERT INTO player_game_stats (id, user_id, game_id, in_game_rank, preferred_role, playstyle, is_primary) VALUES (?, ?, ?, ?, ?, ?, 1)";
        Object[][] stats = {
            {"s-1", "u-1", "g-val", "Inmortal 1", "Controlador (Omen / Viper)", "TRYHARD"},
            {"s-2", "u-2", "g-val", "Ascendente 2", "Iniciador (Fade / Sova)", "COMPETITIVE"},
            {"s-3", "u-3", "g-lol", "Diamante IV", "Support (Thresh / Lulu)", "COMPETITIVE"},
            {"s-4", "u-4", "g-cs2", "Nivel 8 Faceit (16.500 ELO)", "Sniper / Entry", "TRYHARD"},
            {"s-5", "u-5", "g-val", "Inmortal 2", "Duelista (Jett / Reyna)", "COMPETITIVE"},
            {"s-6", "u-6", "g-lol", "Platino I", "Toplaner (Aatrox / Jax)", "COMPETITIVE"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlStats)) {
            for (Object[] s : stats) {
                ps.setString(1, (String) s[0]);
                ps.setString(2, (String) s[1]);
                ps.setString(3, (String) s[2]);
                ps.setString(4, (String) s[3]);
                ps.setString(5, (String) s[4]);
                ps.setString(6, (String) s[5]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void sembrarTorneosYBrackets(Connection conn) throws SQLException {
        String sqlT = "INSERT INTO tournaments (id, title, game_id, organizer_id, organizer_name, prize_pool, format, max_teams, start_date, status, rules, banner_url) VALUES (?, ?, ?, 'u-7', ?, ?, 'SINGLE_ELIMINATION', ?, ?, ?, ?, ?)";
        Object[][] torneosData = {
            {"t-1", "Copa Universitaria Valorant Santiago 2026", "g-val", "Andes Esports", "$200.000 CLP + Skins Riot", 8, "15 de Noviembre, 18:00 hrs", "OPEN_REGISTRATION",
             "Torneo 5v5 al mejor de 1 (Bo1). Final Bo3. Anticheat obligatorio. Tolerancia 10 minutos.",
             "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80"},
            {"t-2", "Liga Amateur League of Legends Chile - Season 1", "g-lol", "Comunidad Gamer Chile", "$150.000 CLP", 8, "20 de Noviembre, 19:00 hrs", "OPEN_REGISTRATION",
             "Modo torneo reclutamiento. Servidor LAS. Prohibido conducta antideportiva.",
             "https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80"},
            {"t-3", "Master Series CS2 - Red Bull Cup", "g-cs2", "Red Bull Gaming Sphere", "$300.000 CLP + Periféricos HyperX", 4, "25 de Noviembre, 17:00 hrs", "IN_PROGRESS",
             "Servidores 128 tickrate en Santiago. Eliminación directa. Overtime en MR3.",
             "https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlT)) {
            for (Object[] t : torneosData) {
                ps.setString(1, (String) t[0]);
                ps.setString(2, (String) t[1]);
                ps.setString(3, (String) t[2]);
                ps.setString(4, (String) t[3]);
                ps.setString(5, (String) t[4]);
                ps.setInt(6, (Integer) t[5]);
                ps.setString(7, (String) t[6]);
                ps.setString(8, (String) t[7]);
                ps.setString(9, (String) t[8]);
                ps.setString(10, (String) t[9]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String sqlTeam = "INSERT INTO teams (id, name, tag, captain_id) VALUES (?, ?, ?, ?)";
        Object[][] teams = {
            {"tm-1", "Andes Raptors", "RAP", "u-1"},
            {"tm-2", "Cordillera Clan", "COR", "u-2"},
            {"tm-3", "Valpo Storm", "VLP", "u-3"},
            {"tm-4", "Biobío Phoenix", "BBP", "u-4"},
            {"tm-5", "Fenix Gaming", "FNX", "u-5"},
            {"tm-6", "Santiago Knights", "STG", "u-6"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlTeam)) {
            for (Object[] tm : teams) {
                ps.setString(1, (String) tm[0]);
                ps.setString(2, (String) tm[1]);
                ps.setString(3, (String) tm[2]);
                ps.setString(4, (String) tm[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String sqlReg = "INSERT INTO tournament_registrations (id, tournament_id, team_id, seed_number) VALUES (?, ?, ?, ?)";
        Object[][] regs = {
            {"tr-1", "t-1", "tm-1", 1}, {"tr-2", "t-1", "tm-2", 2}, {"tr-3", "t-1", "tm-3", 3}, {"tr-4", "t-1", "tm-4", 4},
            {"tr-5", "t-2", "tm-5", 1}, {"tr-6", "t-2", "tm-6", 2},
            {"tr-7", "t-3", "tm-1", 1}, {"tr-8", "t-3", "tm-2", 2}, {"tr-9", "t-3", "tm-3", 3}, {"tr-10", "t-3", "tm-4", 4}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlReg)) {
            for (Object[] r : regs) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setInt(4, (Integer) r[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String sqlMatches = "INSERT INTO tournament_matches (id, tournament_id, round_name, match_order, team1_name, team2_name, score_team1, score_team2, winner_name, status) VALUES (?, 't-3', ?, ?, ?, ?, ?, ?, ?, ?)";
        Object[][] matches = {
            {"m-1", "Semifinal 1", 1, "Andes Raptors", "Cordillera Clan", 13, 9, "Andes Raptors", "FINISHED"},
            {"m-2", "Semifinal 2", 2, "Valpo Storm", "Biobío Phoenix", 11, 13, "Biobío Phoenix", "FINISHED"},
            {"m-3", "Gran Final", 3, "Andes Raptors", "Biobío Phoenix", 0, 0, null, "SCHEDULED"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sqlMatches)) {
            for (Object[] m : matches) {
                ps.setString(1, (String) m[0]);
                ps.setString(2, (String) m[1]);
                ps.setInt(3, (Integer) m[2]);
                ps.setString(4, (String) m[3]);
                ps.setString(5, (String) m[4]);
                ps.setInt(6, (Integer) m[5]);
                ps.setInt(7, (Integer) m[6]);
                if (m[7] == null) ps.setNull(8, Types.VARCHAR); else ps.setString(8, (String) m[7]);
                ps.setString(9, (String) m[8]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void sembrarMensajesIniciales(Connection conn) throws SQLException {
        String sql = "INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] m : MENSAJES_CANALES_INICIALES) {
                for (int i = 0; i < 7; i++) {
                    ps.setString(i + 1, m[i]);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // =========================================================================
    // 1. GESTIÓN DE JUEGOS DISPONIBLES (TABLA GAMES)
    // =========================================================================

    public List<Juego> obtenerTodosLosJuegos(boolean soloActivos) {
        StringBuilder sql = new StringBuilder(
            "SELECT g.id, g.code, g.name, g.category, g.platform, g.max_team_size, " +
            "g.description, g.accent_color, g.banner_url, g.is_active, g.sort_order, g.created_at, " +
            "COUNT(DISTINCT CASE WHEN pgs.is_primary = 1 THEN pgs.user_id END) AS player_count, " +
            "COUNT(DISTINCT t.id) AS tournament_count " +
            "FROM games g " +
            "LEFT JOIN player_game_stats pgs ON pgs.game_id = g.id " +
            "LEFT JOIN tournaments t ON t.game_id = g.id "
        );
        if (soloActivos) {
            sql.append("WHERE g.is_active = 1 ");
        }
        sql.append("GROUP BY g.id, g.code, g.name, g.category, g.platform, g.max_team_size, " +
                   "g.description, g.accent_color, g.banner_url, g.is_active, g.sort_order, g.created_at " +
                   "ORDER BY g.sort_order ASC, g.created_at ASC, g.name ASC");

        List<Juego> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Juego j = mapearJuego(rs);
                j.setCantidadJugadores(rs.getInt("player_count"));
                j.setCantidadTorneos(rs.getInt("tournament_count"));
                lista.add(j);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error consultando juegos disponibles", e);
        }
        return lista;
    }

    public Juego buscarJuegoPorCodigoOId(String valor) {
        if (valor == null || valor.trim().isEmpty()) return null;
        String sql = "SELECT id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active, created_at " +
                     "FROM games WHERE LOWER(code) = LOWER(?) OR LOWER(id) = LOWER(?) OR LOWER(name) = LOWER(?) LIMIT 1";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String clean = valor.trim();
            ps.setString(1, clean);
            ps.setString(2, clean);
            ps.setString(3, clean);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapearJuego(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error buscando juego: " + valor, e);
        }
        return null;
    }

    public synchronized Juego crearJuego(String codigo, String nombre, String categoria, String plataforma,
                                         int maxTamanoEquipo, String descripcion, String colorAcento, String bannerUrl) {
        String codeFinal = (codigo != null && !codigo.trim().isEmpty())
                ? SeguridadUtil.generarCodigoJuego(codigo)
                : SeguridadUtil.generarCodigoJuego(nombre);
        Juego existente = buscarJuegoPorCodigoOId(codeFinal);
        if (existente != null || buscarJuegoPorCodigoOId(nombre) != null) {
            return null;
        }

        Juego j = new Juego(UUID.randomUUID().toString(), codeFinal, nombre.trim(), categoria, plataforma,
                maxTamanoEquipo, descripcion, colorAcento, bannerUrl, true);
        String sql = "INSERT INTO games (id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 100)";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, j.getId());
            ps.setString(2, j.getCodigo());
            ps.setString(3, j.getNombre());
            ps.setString(4, j.getCategoria());
            ps.setString(5, j.getPlataforma());
            ps.setInt(6, j.getMaxTamanoEquipo());
            ps.setString(7, j.getDescripcion());
            ps.setString(8, j.getColorAcento());
            ps.setString(9, j.getBannerUrl());
            ps.executeUpdate();
            return buscarJuegoPorCodigoOId(j.getId());
        } catch (SQLException e) {
            throw new RuntimeException("Error insertando juego en la base de datos", e);
        }
    }

    private Juego mapearJuego(ResultSet rs) throws SQLException {
        Juego j = new Juego(
            rs.getString("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("platform"),
            rs.getInt("max_team_size"),
            rs.getString("description"),
            rs.getString("accent_color"),
            rs.getString("banner_url"),
            rs.getInt("is_active") == 1
        );
        j.setFechaCreacion(limpiarTimestamp(rs.getString("created_at")));
        return j;
    }

    // =========================================================================
    // 2. GESTIÓN DE USUARIOS, CORREOS, TELÉFONOS Y AUTENTICACIÓN
    // =========================================================================

    public List<Usuario> obtenerTodosLosUsuarios() {
        return obtenerTodosLosUsuarios(null);
    }

    private static final String COLS_USER_SELECT =
            "id, username, email, password_hash, avatar_url, telefono, phone_verified, role, created_at";

    public List<Usuario> obtenerTodosLosUsuarios(String filtroBusqueda) {
        boolean tieneFiltro = filtroBusqueda != null && !filtroBusqueda.trim().isEmpty();
        String sql = "SELECT " + COLS_USER_SELECT + " FROM users " +
                (tieneFiltro ? "WHERE LOWER(username) LIKE ? OR LOWER(email) LIKE ? " : "") +
                "ORDER BY created_at DESC, username ASC";
        List<Usuario> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (tieneFiltro) {
                String pattern = "%" + filtroBusqueda.trim().toLowerCase(Locale.ROOT) + "%";
                ps.setString(1, pattern);
                ps.setString(2, pattern);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapearUsuario(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error obteniendo usuarios registrados", e);
        }
        return lista;
    }

    public Usuario buscarUsuarioPorUsername(String username) {
        if (username == null || username.trim().isEmpty()) return null;
        String raw = username.trim();
        String conGuion = raw.replace(" ", "_");
        String conEspacio = raw.replace("_", " ");

        String sql = "SELECT " + COLS_USER_SELECT + " " +
                     "FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(username) = LOWER(?) OR LOWER(REPLACE(username, '_', ' ')) = LOWER(?) LIMIT 1";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, raw);
            ps.setString(2, conGuion);
            ps.setString(3, conEspacio);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapearUsuario(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error buscando usuario por username", e);
        }
        return null;
    }

    public Usuario buscarUsuarioPorEmail(String email) {
        if (email == null || email.trim().isEmpty()) return null;
        String sql = "SELECT " + COLS_USER_SELECT + " FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapearUsuario(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error buscando usuario por email", e);
        }
        return null;
    }

    public Usuario buscarUsuarioPorIdentificador(String identifier) {
        Usuario u = buscarUsuarioPorUsername(identifier);
        if (u != null) return u;
        return buscarUsuarioPorEmail(identifier);
    }

    public synchronized Usuario crearUsuario(String username, String email, String password) {
        return crearUsuario(username, email, password, "", "valorant");
    }

    public synchronized Usuario crearUsuario(String username, String email, String password, String telefono) {
        return crearUsuario(username, email, password, telefono, "valorant");
    }

    public synchronized Usuario crearUsuario(String username, String email, String password, String telefono, String gameCode) {
        String cleanUser = username.trim();
        String cleanEmail = email.trim().toLowerCase(Locale.ROOT);
        String cleanTel = telefono != null ? telefono.trim() : "";
        String userId = UUID.randomUUID().toString();
        String avatar = avatarPorDefecto(cleanUser);
        String passwordHash = SeguridadUtil.hashPassword(password);

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                String sqlUser = "INSERT INTO users (id, username, email, password_hash, avatar_url, telefono, phone_verified, role) VALUES (?, ?, ?, ?, ?, ?, 0, 'GAMER')";
                try (PreparedStatement ps = conn.prepareStatement(sqlUser)) {
                    ps.setString(1, userId);
                    ps.setString(2, cleanUser);
                    ps.setString(3, cleanEmail);
                    ps.setString(4, passwordHash);
                    ps.setString(5, avatar);
                    ps.setString(6, cleanTel);
                    ps.executeUpdate();
                }

                String profileId = UUID.randomUUID().toString();
                String cleanFavGame = (gameCode != null && !gameCode.trim().isEmpty()) ? gameCode.trim().toLowerCase(Locale.ROOT) : "valorant";
                String sqlProfile = "INSERT INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag, discord_id, discord_connected, favorite_games, favorite_genres, play_preferences, star_rating, rating_count) VALUES (?, ?, ?, 'Chile', 'Región Metropolitana', 'ZERO_TOXIC', 95, 'Noches (21:00 - 01:00)', '', ?, '', '', 0, ?, '', 'Dúo,Con Micrófono', 4.5, 0)";
                try (PreparedStatement ps = conn.prepareStatement(sqlProfile)) {
                    ps.setString(1, profileId);
                    ps.setString(2, userId);
                    ps.setString(3, "Nuevo jugador registrado en PlayMatch. Listo para competir.");
                    ps.setString(4, cleanUser + "#CHI");
                    ps.setString(5, cleanFavGame);
                    ps.executeUpdate();
                }

                String gameId = resolverGameId(conn, gameCode);
                if (gameId != null) {
                    String sqlStat = "INSERT INTO player_game_stats (id, user_id, game_id, in_game_rank, preferred_role, playstyle, is_primary) VALUES (?, ?, ?, 'Sin Rango', 'Flex', 'COMPETITIVE', 1)";
                    try (PreparedStatement ps = conn.prepareStatement(sqlStat)) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, userId);
                        ps.setString(3, gameId);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
                return buscarUsuarioPorUsername(cleanUser);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error creando usuario en base de datos", e);
        }
    }

    public synchronized boolean actualizarCuentaUsuario(String username, String nuevoEmail, String nuevoTelefono) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null) return false;

        String emailFinal = (nuevoEmail != null && !nuevoEmail.trim().isEmpty())
                ? nuevoEmail.trim().toLowerCase(Locale.ROOT)
                : u.getEmail();
        String telFinal = nuevoTelefono != null ? nuevoTelefono.trim() : u.getTelefono();
        boolean phoneUnchanged = telFinal.equals(u.getTelefono() != null ? u.getTelefono().trim() : "");
        int verifiedFlag = (phoneUnchanged && u.isTelefonoVerificado()) ? 1 : 0;

        String sql = "UPDATE users SET email = ?, telefono = ?, phone_verified = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emailFinal);
            ps.setString(2, telFinal);
            ps.setInt(3, verifiedFlag);
            ps.setString(4, u.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando datos de cuenta", e);
        }
    }

    public synchronized boolean actualizarAvatarUsuario(String username, String nuevoAvatarUrl) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null) return false;

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET avatar_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    ps.setString(1, nuevoAvatarUrl.trim());
                    ps.setString(2, u.getId());
                    ps.executeUpdate();
                }
                try (PreparedStatement psMsg = conn.prepareStatement(
                        "UPDATE chat_messages SET sender_avatar = ? WHERE sender_id = ? OR LOWER(sender_username) = LOWER(?)")) {
                    psMsg.setString(1, nuevoAvatarUrl.trim());
                    psMsg.setString(2, u.getId());
                    psMsg.setString(3, u.getUsername());
                    psMsg.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando foto de perfil", e);
        }
    }

    public synchronized boolean marcarTelefonoVerificado(String username) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null) return false;
        String sql = "UPDATE users SET phone_verified = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, u.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error marcando teléfono como verificado", e);
        }
    }

    private String resolverGameId(Connection conn, String codeONombre) throws SQLException {
        String target = (codeONombre != null && !codeONombre.trim().isEmpty()) ? codeONombre.trim() : "valorant";
        String sql = "SELECT id FROM games WHERE LOWER(code) = LOWER(?) OR LOWER(id) = LOWER(?) OR LOWER(name) = LOWER(?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target);
            ps.setString(2, target);
            ps.setString(3, target);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("id");
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM games WHERE is_active = 1 LIMIT 1")) {
            return rs.next() ? rs.getString("id") : null;
        }
    }

    private Usuario mapearUsuario(ResultSet rs) throws SQLException {
        return new Usuario(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("avatar_url"),
            rs.getString("telefono"),
            rs.getInt("phone_verified") == 1,
            limpiarTimestamp(rs.getString("created_at"))
        );
    }

    // =========================================================================
    // 3. PERFILES DE JUGADORES Y BUSCADOR LFG
    // =========================================================================

    private static final String SQL_SELECT_PERFIL =
            "SELECT pp.id AS profile_id, u.id AS user_id, u.username, u.avatar_url, " +
            "pp.bio, pp.country, pp.region, pp.toxic_level, pp.reputation_score, pp.preferred_schedule, " +
            "pp.steam_id, pp.riot_id, pp.discord_tag, pp.discord_id, pp.discord_connected, " +
            "COALESCE(pp.favorite_games, '') AS favorite_games, " +
            "COALESCE(pp.favorite_genres, '') AS favorite_genres, " +
            "COALESCE(pp.play_preferences, '') AS play_preferences, " +
            "COALESCE(pp.star_rating, 4.8) AS star_rating, " +
            "COALESCE(pp.rating_count, 0) AS rating_count, " +
            "COALESCE(pp.card_frame, 'NONE') AS card_frame, " +
            "COALESCE(g.code, 'valorant') AS game_code, COALESCE(g.name, 'Valorant') AS game_name, " +
            "COALESCE(pgs.in_game_rank, 'Sin Rango') AS in_game_rank, COALESCE(pgs.preferred_role, 'Flex') AS preferred_role, " +
            "COALESCE(pgs.playstyle, 'COMPETITIVE') AS playstyle " +
            "FROM player_profiles pp " +
            "JOIN users u ON pp.user_id = u.id " +
            "LEFT JOIN player_game_stats pgs ON pgs.user_id = u.id AND pgs.is_primary = 1 " +
            "LEFT JOIN games g ON pgs.game_id = g.id ";

    public List<PerfilJugador> obtenerTodosLosPerfiles() {
        return buscarPerfiles(null, null, null);
    }

    public List<PerfilJugador> buscarPerfiles(String filtroJuego, String filtroToxic, String busqueda) {
        StringBuilder sql = new StringBuilder(SQL_SELECT_PERFIL).append("WHERE 1=1 ");

        List<String> params = new ArrayList<>();
        if (filtroJuego != null && !filtroJuego.trim().isEmpty() && !"all".equalsIgnoreCase(filtroJuego.trim())) {
            sql.append("AND (LOWER(g.code) = LOWER(?) OR LOWER(COALESCE(pp.favorite_games, '')) LIKE ?) ");
            params.add(filtroJuego.trim());
            params.add("%" + filtroJuego.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (filtroToxic != null && !filtroToxic.trim().isEmpty()) {
            sql.append("AND UPPER(pp.toxic_level) = UPPER(?) ");
            params.add(filtroToxic.trim());
        }
        if (busqueda != null && !busqueda.trim().isEmpty()) {
            sql.append("AND (LOWER(u.username) LIKE ? OR LOWER(pp.region) LIKE ? OR LOWER(pgs.preferred_role) LIKE ? OR LOWER(pgs.in_game_rank) LIKE ? OR LOWER(COALESCE(pp.favorite_games, '')) LIKE ? OR LOWER(COALESCE(pp.favorite_genres, '')) LIKE ?) ");
            String q = "%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%";
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }
        sql.append("ORDER BY pp.star_rating DESC, pp.reputation_score DESC, u.username ASC");

        List<PerfilJugador> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapearPerfil(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error consultando perfiles LFG", e);
        }
        return lista;
    }

    public PerfilJugador buscarPerfilPorUsername(String username) {
        if (username == null || username.trim().isEmpty()) return null;
        String raw = username.trim();
        String conGuion = raw.replace(" ", "_");
        String conEspacio = raw.replace("_", " ");

        String sql = SQL_SELECT_PERFIL +
                     "WHERE LOWER(u.username) = LOWER(?) OR LOWER(u.username) = LOWER(?) OR LOWER(REPLACE(u.username, '_', ' ')) = LOWER(?) LIMIT 1";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, raw);
            ps.setString(2, conGuion);
            ps.setString(3, conEspacio);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapearPerfil(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error buscando perfil de jugador", e);
        }
        return null;
    }

    public synchronized void actualizarConexionDiscord(String username, String discordTag, String discordId, boolean connected) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null) return;
        String sql = "UPDATE player_profiles SET discord_tag = ?, discord_id = ?, discord_connected = ? WHERE user_id = ?";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, connected ? nullSafe(discordTag) : "");
            ps.setString(2, connected ? nullSafe(discordId) : "");
            ps.setInt(3, connected ? 1 : 0);
            ps.setString(4, u.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando conexión con Discord", e);
        }
    }

    public synchronized void actualizarPerfil(PerfilJugador actualizado) {
        Usuario u = buscarUsuarioPorUsername(actualizado.getUsername());
        if (u == null) return;

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                String sqlProf = "UPDATE player_profiles SET bio = ?, region = ?, preferred_schedule = ?, " +
                                 "steam_id = ?, riot_id = ?, discord_tag = ?, toxic_level = COALESCE(?, toxic_level), " +
                                 "favorite_games = ?, favorite_genres = ?, play_preferences = ?, card_frame = ? " +
                                 "WHERE user_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sqlProf)) {
                    ps.setString(1, nullSafe(actualizado.getBiografia()));
                    ps.setString(2, nullSafe(actualizado.getRegion()));
                    ps.setString(3, nullSafe(actualizado.getHorarioPreferido()));
                    ps.setString(4, nullSafe(actualizado.getSteamId()));
                    ps.setString(5, nullSafe(actualizado.getRiotId()));
                    ps.setString(6, nullSafe(actualizado.getDiscordTag()));
                    if (actualizado.getNivelToxicidad() == null) {
                        ps.setNull(7, Types.VARCHAR);
                    } else {
                        ps.setString(7, actualizado.getNivelToxicidad());
                    }
                    ps.setString(8, nullSafe(actualizado.getFavoriteGames()));
                    ps.setString(9, nullSafe(actualizado.getFavoriteGenres()));
                    ps.setString(10, nullSafe(actualizado.getPlayPreferences()));
                    ps.setString(11, SeguridadUtil.normalizarMarcoPerfil(actualizado.getCardFrame()));
                    ps.setString(12, u.getId());
                    ps.executeUpdate();
                }

                String gameId = resolverGameId(conn, actualizado.getJuego());
                if (gameId != null) {
                    try (PreparedStatement psReset = conn.prepareStatement("UPDATE player_game_stats SET is_primary = 0 WHERE user_id = ?")) {
                        psReset.setString(1, u.getId());
                        psReset.executeUpdate();
                    }

                    String sqlUpsert = "INSERT INTO player_game_stats (id, user_id, game_id, in_game_rank, preferred_role, playstyle, is_primary) " +
                                       "VALUES (?, ?, ?, ?, ?, ?, 1) " +
                                       "ON CONFLICT(user_id, game_id) DO UPDATE SET " +
                                       "in_game_rank = EXCLUDED.in_game_rank, " +
                                       "preferred_role = EXCLUDED.preferred_role, " +
                                       "playstyle = EXCLUDED.playstyle, " +
                                       "is_primary = 1";
                    try (PreparedStatement psStat = conn.prepareStatement(sqlUpsert)) {
                        psStat.setString(1, UUID.randomUUID().toString());
                        psStat.setString(2, u.getId());
                        psStat.setString(3, gameId);
                        psStat.setString(4, !nullSafe(actualizado.getRango()).isEmpty() ? actualizado.getRango() : "Sin Rango");
                        psStat.setString(5, !nullSafe(actualizado.getRol()).isEmpty() ? actualizado.getRol() : "Flex");
                        psStat.setString(6, !nullSafe(actualizado.getEstiloJuego()).isEmpty() ? actualizado.getEstiloJuego() : "COMPETITIVE");
                        psStat.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando perfil de jugador", e);
        }
    }

    public synchronized PerfilJugador calificarJugador(String raterUsername, String ratedUsername, int stars, String comment) {
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("La valoración debe estar entre 1 y 5 estrellas.");
        }
        Usuario rater = buscarUsuarioPorUsername(raterUsername);
        Usuario rated = buscarUsuarioPorUsername(ratedUsername);
        if (rater == null || rated == null) {
            return null;
        }
        if (rater.getId().equals(rated.getId())) {
            throw new IllegalArgumentException("No puedes valorar tu propio perfil.");
        }

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                String sqlUpsert = "INSERT INTO player_ratings (id, rater_user_id, rated_user_id, stars, comment, updated_at) " +
                                   "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                                   "ON CONFLICT(rater_user_id, rated_user_id) DO UPDATE SET " +
                                   "stars = EXCLUDED.stars, comment = EXCLUDED.comment, updated_at = CURRENT_TIMESTAMP";
                try (PreparedStatement ps = conn.prepareStatement(sqlUpsert)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, rater.getId());
                    ps.setString(3, rated.getId());
                    ps.setInt(4, stars);
                    ps.setString(5, comment != null ? comment.trim() : "");
                    ps.executeUpdate();
                }

                double avg = stars;
                int count = 1;
                try (PreparedStatement psAvg = conn.prepareStatement(
                        "SELECT COALESCE(AVG(stars), ?) AS avg_stars, COUNT(*) AS total_votes FROM player_ratings WHERE rated_user_id = ?")) {
                    psAvg.setDouble(1, (double) stars);
                    psAvg.setString(2, rated.getId());
                    try (ResultSet rs = psAvg.executeQuery()) {
                        if (rs.next()) {
                            avg = rs.getDouble("avg_stars");
                            count = rs.getInt("total_votes");
                        }
                    }
                }

                double avgRedondeado = Math.round(avg * 10.0) / 10.0;
                int rep = (int) Math.min(100, Math.max(10, Math.round(avg * 20.0)));
                try (PreparedStatement psUpd = conn.prepareStatement(
                        "UPDATE player_profiles SET star_rating = ?, rating_count = ?, reputation_score = ? WHERE user_id = ?")) {
                    psUpd.setDouble(1, avgRedondeado);
                    psUpd.setInt(2, count);
                    psUpd.setInt(3, rep);
                    psUpd.setString(4, rated.getId());
                    psUpd.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error guardando valoración por estrellas", e);
        }
        return buscarPerfilPorUsername(rated.getUsername());
    }

    private PerfilJugador mapearPerfil(ResultSet rs) throws SQLException {
        PerfilJugador p = new PerfilJugador(
            rs.getString("profile_id"),
            rs.getString("user_id"),
            rs.getString("username"),
            rs.getString("bio"),
            rs.getString("country"),
            rs.getString("region"),
            rs.getString("toxic_level"),
            rs.getInt("reputation_score"),
            rs.getString("preferred_schedule"),
            rs.getString("steam_id"),
            rs.getString("riot_id"),
            rs.getString("discord_tag"),
            rs.getString("game_code"),
            rs.getString("game_name"),
            rs.getString("in_game_rank"),
            rs.getString("preferred_role"),
            rs.getString("playstyle"),
            rs.getString("avatar_url")
        );
        p.setDiscordId(nullSafe(rs.getString("discord_id")));
        p.setDiscordConnected(rs.getInt("discord_connected") == 1);
        p.setFavoriteGames(nullSafe(rs.getString("favorite_games")));
        p.setFavoriteGenres(nullSafe(rs.getString("favorite_genres")));
        p.setPlayPreferences(nullSafe(rs.getString("play_preferences")));
        p.setStarRating(rs.getDouble("star_rating"));
        p.setRatingCount(rs.getInt("rating_count"));
        p.setCardFrame(nullSafe(rs.getString("card_frame")).isEmpty() ? "NONE" : rs.getString("card_frame"));
        return p;
    }

    // =========================================================================
    // 4. TORNEOS, EQUIPOS INSCRITOS Y BRACKETS
    // =========================================================================

    public List<Torneo> obtenerTodosLosTorneos() {
        String sql = "SELECT t.id, t.title, g.code AS game_code, g.name AS game_name, " +
                     "t.organizer_name, t.prize_pool, t.format, t.max_teams, t.start_date, " +
                     "t.status, t.rules, t.banner_url " +
                     "FROM tournaments t JOIN games g ON t.game_id = g.id " +
                     "ORDER BY t.created_at ASC, t.title ASC";
        List<Torneo> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Torneo t = mapearTorneo(rs);
                cargarEquiposYPartidas(conn, t);
                lista.add(t);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error obteniendo torneos", e);
        }
        return lista;
    }

    public Torneo obtenerTorneoPorId(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String sql = "SELECT t.id, t.title, g.code AS game_code, g.name AS game_name, " +
                     "t.organizer_name, t.prize_pool, t.format, t.max_teams, t.start_date, " +
                     "t.status, t.rules, t.banner_url " +
                     "FROM tournaments t JOIN games g ON t.game_id = g.id WHERE t.id = ? LIMIT 1";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Torneo t = mapearTorneo(rs);
                    cargarEquiposYPartidas(conn, t);
                    return t;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error buscando torneo: " + id, e);
        }
        return null;
    }

    public synchronized Torneo crearTorneo(String titulo, String gameCode, String organizadorUsername,
                                           String pozoPremios, String formato, int maxEquipos,
                                           String fechaInicio, String reglas) {
        try (Connection conn = abrirConexion()) {
            String gameId = resolverGameId(conn, gameCode);
            if (gameId == null) return null;

            Usuario org = buscarUsuarioPorUsername(organizadorUsername);
            String organizerId = (org != null) ? org.getId() : "u-1";
            String organizerName = (org != null) ? org.getUsername() : "Comunidad PlayMatch";

            String torneoId = "t-" + UUID.randomUUID().toString().substring(0, 6);
            String sql = "INSERT INTO tournaments (id, title, game_id, organizer_id, organizer_name, prize_pool, format, max_teams, start_date, status, rules, banner_url) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN_REGISTRATION', ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, torneoId);
                ps.setString(2, titulo.trim());
                ps.setString(3, gameId);
                ps.setString(4, organizerId);
                ps.setString(5, organizerName);
                ps.setString(6, !nullSafe(pozoPremios).isEmpty() ? pozoPremios.trim() : "Gloria Comunitaria");
                ps.setString(7, !nullSafe(formato).isEmpty() ? formato.trim() : "SINGLE_ELIMINATION");
                ps.setInt(8, maxEquipos > 1 ? maxEquipos : 8);
                ps.setString(9, !nullSafe(fechaInicio).isEmpty() ? fechaInicio.trim() : "Por definir");
                ps.setString(10, !nullSafe(reglas).isEmpty() ? reglas.trim() : "Reglamento estándar PlayMatch Zero-Toxic.");
                ps.setString(11, "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80");
                ps.executeUpdate();
            }
            return obtenerTorneoPorId(torneoId);
        } catch (SQLException e) {
            throw new RuntimeException("Error creando torneo", e);
        }
    }

    public synchronized boolean inscribirEquipoEnTorneo(String torneoId, String nombreEquipo) {
        return inscribirEquipoEnTorneo(torneoId, nombreEquipo, null);
    }

    public synchronized boolean inscribirEquipoEnTorneo(String torneoId, String nombreEquipo, String capitanUsername) {
        if (torneoId == null || nombreEquipo == null || nombreEquipo.trim().isEmpty()) return false;
        String cleanTeam = nombreEquipo.trim();

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                int maxEquipos = 0;
                int actuales = 0;
                String sqlCheck = "SELECT t.max_teams, COUNT(tr.id) AS enrolled " +
                                  "FROM tournaments t LEFT JOIN tournament_registrations tr ON t.id = tr.tournament_id " +
                                  "WHERE t.id = ? GROUP BY t.id, t.max_teams";
                try (PreparedStatement ps = conn.prepareStatement(sqlCheck)) {
                    ps.setString(1, torneoId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        maxEquipos = rs.getInt("max_teams");
                        actuales = rs.getInt("enrolled");
                    }
                }
                if (actuales >= maxEquipos) {
                    conn.rollback();
                    return false;
                }

                String sqlDup = "SELECT COUNT(*) FROM tournament_registrations tr " +
                                "JOIN teams tm ON tr.team_id = tm.id " +
                                "WHERE tr.tournament_id = ? AND LOWER(tm.name) = LOWER(?)";
                try (PreparedStatement ps = conn.prepareStatement(sqlDup)) {
                    ps.setString(1, torneoId);
                    ps.setString(2, cleanTeam);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            conn.rollback();
                            return false;
                        }
                    }
                }

                Usuario captain = (capitanUsername != null) ? buscarUsuarioPorUsername(capitanUsername) : null;
                String captainId = (captain != null) ? captain.getId() : "u-1";

                String teamId = UUID.randomUUID().toString();
                String tag = cleanTeam.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
                if (tag.length() > 4) tag = tag.substring(0, 4);
                if (tag.isEmpty()) tag = "TEAM";

                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO teams (id, name, tag, captain_id) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, teamId);
                    ps.setString(2, cleanTeam);
                    ps.setString(3, tag);
                    ps.setString(4, captainId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO tournament_registrations (id, tournament_id, team_id, seed_number) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, torneoId);
                    ps.setString(3, teamId);
                    ps.setInt(4, actuales + 1);
                    ps.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error inscribiendo equipo en torneo", e);
        }
    }

    public synchronized Torneo hacerCheckInTorneo(String torneoId, String teamName, String actorUsername) {
        if (torneoId == null || torneoId.trim().isEmpty() || teamName == null || teamName.trim().isEmpty()) {
            throw new IllegalArgumentException("Torneo y nombre de equipo son obligatorios para check-in.");
        }
        try (Connection conn = abrirConexion()) {
            String orgId = null;
            String tStatus = null;
            try (PreparedStatement pt = conn.prepareStatement("SELECT organizer_id, status FROM tournaments WHERE id = ?")) {
                pt.setString(1, torneoId.trim());
                try (ResultSet rs = pt.executeQuery()) {
                    if (!rs.next()) return null;
                    orgId = rs.getString("organizer_id");
                    tStatus = rs.getString("status");
                }
            }
            if (!"OPEN_REGISTRATION".equalsIgnoreCase(tStatus)) {
                throw new IllegalArgumentException("El check-in solo está disponible antes del sorteo de llaves.");
            }
            String regId = null;
            String captainId = null;
            try (PreparedStatement pr = conn.prepareStatement(
                    "SELECT tr.id, tm.captain_id FROM tournament_registrations tr JOIN teams tm ON tr.team_id = tm.id " +
                    "WHERE tr.tournament_id = ? AND LOWER(tm.name) = LOWER(?) LIMIT 1")) {
                pr.setString(1, torneoId.trim());
                pr.setString(2, teamName.trim());
                try (ResultSet rs = pr.executeQuery()) {
                    if (rs.next()) {
                        regId = rs.getString("id");
                        captainId = rs.getString("captain_id");
                    }
                }
            }
            if (regId == null) {
                throw new IllegalArgumentException("Equipo no inscrito en este torneo.");
            }
            Usuario actor = actorUsername != null ? buscarUsuarioPorUsername(actorUsername) : null;
            if (actor != null && !"ADMIN".equalsIgnoreCase(actor.getRol())
                    && !actor.getId().equals(orgId) && !actor.getId().equals(captainId)) {
                throw new SecurityException("Solo el capitán del equipo, el organizador o un administrador puede confirmar el check-in.");
            }
            try (PreparedStatement pu = conn.prepareStatement("UPDATE tournament_registrations SET checked_in = 1 WHERE id = ?")) {
                pu.setString(1, regId);
                pu.executeUpdate();
            }
            return obtenerTorneoPorId(torneoId.trim());
        } catch (SQLException e) {
            throw new RuntimeException("Error realizando check-in del torneo", e);
        }
    }

    public synchronized Torneo generarLlavesTorneo(String torneoId, String actorUsername) {
        if (torneoId == null || torneoId.trim().isEmpty()) return null;
        try (Connection conn = abrirConexion()) {
            String orgId = null;
            try (PreparedStatement pt = conn.prepareStatement("SELECT organizer_id FROM tournaments WHERE id = ?")) {
                pt.setString(1, torneoId.trim());
                try (ResultSet rs = pt.executeQuery()) {
                    if (!rs.next()) return null;
                    orgId = rs.getString("organizer_id");
                }
            }
            Usuario actor = actorUsername != null ? buscarUsuarioPorUsername(actorUsername) : null;
            if (actor != null && !"ADMIN".equalsIgnoreCase(actor.getRol()) && !actor.getId().equals(orgId)) {
                throw new SecurityException("Solo el organizador o un administrador puede generar llaves.");
            }

            List<String> teams = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tm.name FROM tournament_registrations tr JOIN teams tm ON tr.team_id = tm.id " +
                    "WHERE tr.tournament_id = ? ORDER BY tr.checked_in DESC, tr.seed_number ASC, tr.registered_at ASC")) {
                ps.setString(1, torneoId.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) teams.add(rs.getString("name"));
                }
            }
            if (teams.size() < 2) {
                throw new IllegalArgumentException("Se necesitan al menos 2 equipos inscritos para generar el bracket.");
            }
            if (teams.size() > 16) {
                teams = new ArrayList<>(teams.subList(0, 16));
            }

            try (PreparedStatement pc = conn.prepareStatement("SELECT COUNT(*) FROM tournament_matches WHERE tournament_id = ?")) {
                pc.setString(1, torneoId.trim());
                try (ResultSet rs = pc.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        throw new IllegalArgumentException("Este torneo ya tiene llaves generadas.");
                    }
                }
            }

            int bracketSize = 2;
            while (bracketSize < teams.size() && bracketSize < 16) {
                bracketSize *= 2;
            }
            int totalRounds = Integer.numberOfTrailingZeros(bracketSize);

            // Seeding estándar de eliminación directa [0 vs B-1, ...]
            List<Integer> seedOrder = new ArrayList<>();
            seedOrder.add(0);
            while (seedOrder.size() < bracketSize) {
                int sum = seedOrder.size() * 2 - 1;
                List<Integer> next = new ArrayList<>(seedOrder.size() * 2);
                for (int s : seedOrder) {
                    next.add(s);
                    next.add(sum - s);
                }
                seedOrder = next;
            }

            int[] roundStart = new int[totalRounds + 1];
            int cursor = 1;
            for (int r = 1; r <= totalRounds; r++) {
                roundStart[r] = cursor;
                cursor += (bracketSize >> r);
            }

            class BracketNode {
                int order;
                int roundIdx;
                String roundName;
                int nextOrder;
                int nextSlot;
                String t1;
                String t2;
                int s1;
                int s2;
                String winner;
                String status = "SCHEDULED";
            }

            Map<Integer, BracketNode> nodes = new LinkedHashMap<>();
            for (int r = 1; r <= totalRounds; r++) {
                int count = bracketSize >> r;
                int rem = totalRounds - r;
                String baseName = rem == 0 ? "Gran Final"
                        : (rem == 1 ? "Semifinal"
                        : (rem == 2 ? "Cuartos" : "Octavos"));
                for (int i = 0; i < count; i++) {
                    BracketNode n = new BracketNode();
                    n.order = roundStart[r] + i;
                    n.roundIdx = r;
                    n.roundName = rem == 0 ? baseName : (baseName + " " + (i + 1));
                    n.nextOrder = (r < totalRounds) ? (roundStart[r + 1] + (i / 2)) : 0;
                    n.nextSlot = (r < totalRounds) ? ((i % 2) + 1) : 0;
                    nodes.put(n.order, n);
                }
            }

            int r1Count = bracketSize >> 1;
            for (int i = 0; i < r1Count; i++) {
                BracketNode n = nodes.get(roundStart[1] + i);
                int idx1 = seedOrder.get(i * 2);
                int idx2 = seedOrder.get(i * 2 + 1);
                n.t1 = idx1 < teams.size() ? teams.get(idx1) : null;
                n.t2 = idx2 < teams.size() ? teams.get(idx2) : null;

                if (n.t1 != null && n.t2 == null) {
                    n.t2 = "BYE";
                    n.s1 = 1;
                    n.s2 = 0;
                    n.winner = n.t1;
                    n.status = "FINISHED";
                    if (n.nextOrder > 0) {
                        BracketNode nextNode = nodes.get(n.nextOrder);
                        if (n.nextSlot == 1) nextNode.t1 = n.winner; else nextNode.t2 = n.winner;
                    }
                } else if (n.t1 == null && n.t2 != null) {
                    n.t1 = "BYE";
                    n.s1 = 0;
                    n.s2 = 1;
                    n.winner = n.t2;
                    n.status = "FINISHED";
                    if (n.nextOrder > 0) {
                        BracketNode nextNode = nodes.get(n.nextOrder);
                        if (n.nextSlot == 1) nextNode.t1 = n.winner; else nextNode.t2 = n.winner;
                    }
                } else if (n.t1 == null) {
                    n.t1 = "BYE";
                    n.t2 = "BYE";
                    n.status = "FINISHED";
                }
            }

            conn.setAutoCommit(false);
            try {
                String sqlIns = "INSERT INTO tournament_matches (id, tournament_id, round_name, round_index, match_order, next_match_order, next_slot, team1_name, team2_name, score_team1, score_team2, winner_name, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement pi = conn.prepareStatement(sqlIns)) {
                    for (BracketNode n : nodes.values()) {
                        pi.setString(1, UUID.randomUUID().toString());
                        pi.setString(2, torneoId.trim());
                        pi.setString(3, n.roundName);
                        pi.setInt(4, n.roundIdx);
                        pi.setInt(5, n.order);
                        pi.setInt(6, n.nextOrder);
                        pi.setInt(7, n.nextSlot);
                        if (n.t1 == null) pi.setNull(8, Types.VARCHAR); else pi.setString(8, n.t1);
                        if (n.t2 == null) pi.setNull(9, Types.VARCHAR); else pi.setString(9, n.t2);
                        pi.setInt(10, n.s1);
                        pi.setInt(11, n.s2);
                        if (n.winner == null) pi.setNull(12, Types.VARCHAR); else pi.setString(12, n.winner);
                        pi.setString(13, n.status);
                        pi.addBatch();
                    }
                    pi.executeBatch();
                }
                try (PreparedStatement pu = conn.prepareStatement("UPDATE tournaments SET status = 'IN_PROGRESS' WHERE id = ?")) {
                    pu.setString(1, torneoId.trim());
                    pu.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
            return obtenerTorneoPorId(torneoId.trim());
        } catch (SQLException e) {
            throw new RuntimeException("Error generando llaves del torneo", e);
        }
    }

    public synchronized Torneo registrarResultadoPartida(String torneoId, String matchId, String actorUsername, int score1, int score2) {
        if (score1 < 0 || score2 < 0 || score1 == score2) {
            throw new IllegalArgumentException("Ingresa marcadores válidos y sin empate para definir al ganador.");
        }
        try (Connection conn = abrirConexion()) {
            String orgId = null;
            try (PreparedStatement pt = conn.prepareStatement("SELECT organizer_id FROM tournaments WHERE id = ?")) {
                pt.setString(1, torneoId.trim());
                try (ResultSet rs = pt.executeQuery()) {
                    if (!rs.next()) return null;
                    orgId = rs.getString("organizer_id");
                }
            }
            Usuario actor = actorUsername != null ? buscarUsuarioPorUsername(actorUsername) : null;
            if (actor != null && !"ADMIN".equalsIgnoreCase(actor.getRol()) && !actor.getId().equals(orgId)) {
                throw new SecurityException("Solo el organizador del torneo o un administrador puede registrar resultados.");
            }

            String team1 = null;
            String team2 = null;
            String roundName = "";
            int order = 0;
            int nextMatchOrder = 0;
            int nextSlot = 0;
            try (PreparedStatement pm = conn.prepareStatement(
                    "SELECT round_name, team1_name, team2_name, match_order, next_match_order, next_slot FROM tournament_matches WHERE id = ? AND tournament_id = ?")) {
                pm.setString(1, matchId.trim());
                pm.setString(2, torneoId.trim());
                try (ResultSet rs = pm.executeQuery()) {
                    if (!rs.next()) return null;
                    roundName = nullSafe(rs.getString("round_name"));
                    team1 = rs.getString("team1_name");
                    team2 = rs.getString("team2_name");
                    order = rs.getInt("match_order");
                    nextMatchOrder = rs.getInt("next_match_order");
                    nextSlot = rs.getInt("next_slot");
                }
            }
            if (nullSafe(team1).isEmpty() || nullSafe(team2).isEmpty()) {
                throw new IllegalArgumentException("Esta llave aún espera a los equipos clasificados de la ronda anterior.");
            }

            if (nextMatchOrder <= 0 && !roundName.toLowerCase(Locale.ROOT).contains("gran final") && (order == 1 || order == 2)) {
                nextMatchOrder = 3;
                nextSlot = order;
            }

            String winner = score1 > score2 ? team1 : team2;

            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pu = conn.prepareStatement(
                        "UPDATE tournament_matches SET score_team1 = ?, score_team2 = ?, winner_name = ?, status = 'FINISHED' WHERE id = ?")) {
                    pu.setInt(1, score1);
                    pu.setInt(2, score2);
                    pu.setString(3, winner);
                    pu.setString(4, matchId.trim());
                    pu.executeUpdate();
                }

                if (nextMatchOrder > 0) {
                    String col = (nextSlot == 2) ? "team2_name" : "team1_name";
                    try (PreparedStatement pf = conn.prepareStatement(
                            "UPDATE tournament_matches SET " + col + " = ? WHERE tournament_id = ? AND match_order = ?")) {
                        pf.setString(1, winner);
                        pf.setString(2, torneoId.trim());
                        pf.setInt(3, nextMatchOrder);
                        pf.executeUpdate();
                    }
                } else {
                    try (PreparedStatement pt = conn.prepareStatement("UPDATE tournaments SET status = 'FINISHED' WHERE id = ?")) {
                        pt.setString(1, torneoId.trim());
                        pt.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
            return obtenerTorneoPorId(torneoId.trim());
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando marcador del torneo", e);
        }
    }

    public List<Map<String, Object>> obtenerLeaderboard(String viewerUsername) {
        Set<String> blockedIds = (viewerUsername != null && !viewerUsername.trim().isEmpty())
                ? obtenerIdsBloqueadosMutuos(viewerUsername) : Collections.emptySet();
        String sql =
            "SELECT u.id, u.username, u.avatar_url, pp.star_rating, pp.rating_count, pp.reputation_score, " +
            "COALESCE(pp.card_frame, 'NONE') AS card_frame, " +
            "g.code AS game_code, g.name AS game_name, pgs.in_game_rank " +
            "FROM player_profiles pp " +
            "JOIN users u ON pp.user_id = u.id " +
            "LEFT JOIN player_game_stats pgs ON pgs.user_id = u.id AND pgs.is_primary = 1 " +
            "LEFT JOIN games g ON pgs.game_id = g.id " +
            "ORDER BY pp.star_rating DESC, pp.rating_count DESC, pp.reputation_score DESC, u.username ASC LIMIT 20";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int rankPos = 1;
            while (rs.next()) {
                if (blockedIds.contains(rs.getString("id"))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                String uname = rs.getString("username");
                String av = rs.getString("avatar_url");
                if (av == null || av.isEmpty()) av = avatarPorDefecto(uname);
                row.put("position", rankPos++);
                row.put("username", uname);
                row.put("avatarUrl", av);
                row.put("starRating", Math.round(rs.getDouble("star_rating") * 10.0) / 10.0);
                row.put("ratingCount", rs.getInt("rating_count"));
                row.put("reputationScore", rs.getInt("reputation_score"));
                row.put("cardFrame", nullSafe(rs.getString("card_frame")).isEmpty() ? "NONE" : rs.getString("card_frame"));
                row.put("game", nullSafe(rs.getString("game_code")));
                row.put("gameName", nullSafe(rs.getString("game_name")).isEmpty() ? "Multi-Juego" : rs.getString("game_name"));
                row.put("rank", nullSafe(rs.getString("in_game_rank")).isEmpty() ? "Competitivo" : rs.getString("in_game_rank"));
                out.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error cargando leaderboard", e);
        }
        return out;
    }

    private Torneo mapearTorneo(ResultSet rs) throws SQLException {
        return new Torneo(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("game_code"),
            rs.getString("game_name"),
            rs.getString("organizer_name"),
            rs.getString("prize_pool"),
            rs.getString("format"),
            rs.getInt("max_teams"),
            rs.getString("start_date"),
            rs.getString("status"),
            rs.getString("rules"),
            rs.getString("banner_url")
        );
    }

    private void cargarEquiposYPartidas(Connection conn, Torneo t) throws SQLException {
        String sqlTeams = "SELECT tm.name, tm.tag, tr.checked_in, COALESCE(cu.username, '') AS captain_username " +
                          "FROM tournament_registrations tr JOIN teams tm ON tr.team_id = tm.id " +
                          "LEFT JOIN users cu ON tm.captain_id = cu.id " +
                          "WHERE tr.tournament_id = ? ORDER BY tr.seed_number ASC, tr.registered_at ASC";
        try (PreparedStatement ps = conn.prepareStatement(sqlTeams)) {
            ps.setString(1, t.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tName = rs.getString("name");
                    t.getEquiposInscritos().add(tName);
                    Map<String, Object> reg = new LinkedHashMap<>();
                    reg.put("teamName", tName);
                    reg.put("tag", nullSafe(rs.getString("tag")));
                    reg.put("captain", nullSafe(rs.getString("captain_username")));
                    reg.put("checkedIn", rs.getInt("checked_in") == 1);
                    t.getInscripciones().add(reg);
                }
            }
        }

        String sqlMatches = "SELECT id, round_name, round_index, match_order, next_match_order, next_slot, team1_name, team2_name, score_team1, score_team2, winner_name, status " +
                            "FROM tournament_matches WHERE tournament_id = ? ORDER BY round_index ASC, match_order ASC";
        try (PreparedStatement ps = conn.prepareStatement(sqlMatches)) {
            ps.setString(1, t.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PartidaTorneo p = new PartidaTorneo(
                        rs.getString("id"),
                        rs.getString("round_name"),
                        rs.getInt("match_order"),
                        rs.getString("team1_name"),
                        rs.getString("team2_name"),
                        rs.getInt("score_team1"),
                        rs.getInt("score_team2"),
                        rs.getString("winner_name"),
                        rs.getString("status")
                    );
                    p.setRoundIndex(rs.getInt("round_index"));
                    p.setNextMatchOrder(rs.getInt("next_match_order"));
                    p.setNextSlot(rs.getInt("next_slot"));
                    t.getPartidas().add(p);
                }
            }
        }
    }

    // =========================================================================
    // 5. CHAT DE COORDINACIÓN POR CANALES
    // =========================================================================

    public List<MensajeChat> obtenerMensajesChat() {
        return obtenerMensajesChat(null);
    }

    public List<MensajeChat> obtenerMensajesChat(String canal) {
        boolean filtrarCanal = canal != null && !canal.trim().isEmpty() && !"all".equalsIgnoreCase(canal.trim());
        String sql = "SELECT cm.id, cm.sender_username, COALESCE(u.avatar_url, cm.sender_avatar) AS sender_avatar, " +
                     "cm.channel, cm.content, cm.sent_at " +
                     "FROM chat_messages cm " +
                     "LEFT JOIN users u ON (cm.sender_id = u.id OR (cm.sender_id IS NULL AND LOWER(cm.sender_username) = LOWER(u.username))) " +
                     (filtrarCanal ? "WHERE LOWER(cm.channel) = LOWER(?) " : "") +
                     "ORDER BY cm.created_at ASC LIMIT 120";
        List<MensajeChat> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtrarCanal) {
                ps.setString(1, canal.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new MensajeChat(
                        rs.getString("id"),
                        rs.getString("sender_username"),
                        rs.getString("sender_avatar"),
                        rs.getString("channel"),
                        rs.getString("content"),
                        rs.getString("sent_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error cargando mensajes del chat", e);
        }
        return lista;
    }

    public synchronized void agregarMensajeChat(MensajeChat msg) {
        Usuario sender = buscarUsuarioPorUsername(msg.getRemitenteUsername());
        String senderId = sender != null ? sender.getId() : null;
        String avatar = (sender != null && sender.getAvatarUrl() != null)
                ? sender.getAvatarUrl()
                : msg.getRemitenteAvatar();
        String hora = (msg.getHoraEnvio() != null && !msg.getHoraEnvio().isEmpty())
                ? msg.getHoraEnvio()
                : LocalDateTime.now().format(FMT_HORA);

        String sql = "INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, msg.getId());
            if (senderId == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, senderId);
            ps.setString(3, msg.getRemitenteUsername());
            ps.setString(4, avatar);
            ps.setString(5, msg.getCanal() != null ? msg.getCanal() : "global");
            ps.setString(6, msg.getContenido());
            ps.setString(7, hora);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error guardando mensaje de chat", e);
        }
    }

    // =========================================================================
    // 5B. AMISTADES Y MENSAJES DIRECTOS ESTILO INSTAGRAM (DM)
    // =========================================================================

    private long siguienteDmTimestampMs() {
        while (true) {
            long prev = ultimoDmMs.get();
            long now = Math.max(System.currentTimeMillis(), prev + 1L);
            if (ultimoDmMs.compareAndSet(prev, now)) {
                return now;
            }
        }
    }

    private boolean sonAmigosAceptados(Connection conn, String userId1, String userId2) throws SQLException {
        String sql = "SELECT 1 FROM friendships WHERE status = 'ACCEPTED' AND " +
                     "((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId1);
            ps.setString(2, userId2);
            ps.setString(3, userId2);
            ps.setString(4, userId1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Map<String, Object> obtenerBandejaAmigos(String username) {
        Usuario me = buscarUsuarioPorUsername(username);
        if (me == null) return null;

        List<Map<String, Object>> friends = new ArrayList<>();
        List<Map<String, Object>> incomingRequests = new ArrayList<>();
        List<Map<String, Object>> outgoingRequests = new ArrayList<>();
        Map<String, String> friendshipStatuses = new LinkedHashMap<>();

        Map<String, String> lastMsgMap = new HashMap<>();
        Map<String, String> lastSenderMap = new HashMap<>();
        Map<String, String> lastTimeMap = new HashMap<>();
        Map<String, Long> lastMsMap = new HashMap<>();
        Map<String, Integer> unreadMap = new HashMap<>();

        try (Connection conn = abrirConexion()) {
            // 1. Obtener resumen de últimos mensajes directos y conteo de no leídos
            String sqlDmSummary =
                "SELECT dm.sender_id, dm.receiver_id, dm.content, dm.is_read, dm.sent_at, dm.created_ms, su.username AS sender_username " +
                "FROM direct_messages dm " +
                "JOIN users su ON su.id = dm.sender_id " +
                "WHERE dm.sender_id = ? OR dm.receiver_id = ? " +
                "ORDER BY dm.created_ms DESC, dm.created_at DESC";
            try (PreparedStatement pd = conn.prepareStatement(sqlDmSummary)) {
                pd.setString(1, me.getId());
                pd.setString(2, me.getId());
                try (ResultSet rs = pd.executeQuery()) {
                    while (rs.next()) {
                        String sId = rs.getString("sender_id");
                        String rId = rs.getString("receiver_id");
                        String partnerId = me.getId().equals(sId) ? rId : sId;
                        if (!lastMsgMap.containsKey(partnerId)) {
                            lastMsgMap.put(partnerId, nullSafe(rs.getString("content")));
                            lastSenderMap.put(partnerId, nullSafe(rs.getString("sender_username")));
                            lastTimeMap.put(partnerId, nullSafe(rs.getString("sent_at")));
                            lastMsMap.put(partnerId, rs.getLong("created_ms"));
                        }
                        if (me.getId().equals(rId) && rs.getInt("is_read") == 0) {
                            unreadMap.put(sId, unreadMap.getOrDefault(sId, 0) + 1);
                        }
                    }
                }
            }

            // 2. Consultar amistades y solicitudes con datos públicos de perfil
            String sqlF =
                "SELECT f.id AS friendship_id, f.status, f.requester_id, f.addressee_id, f.created_at, " +
                "u.id AS partner_id, u.username, u.avatar_url, " +
                "pp.star_rating, pp.rating_count, pp.discord_tag, pp.discord_connected, " +
                "pgs.in_game_rank, pgs.preferred_role, g.code AS game_code, g.name AS game_name " +
                "FROM friendships f " +
                "JOIN users u ON u.id = (CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END) " +
                "LEFT JOIN player_profiles pp ON pp.user_id = u.id " +
                "LEFT JOIN player_game_stats pgs ON pgs.user_id = u.id AND pgs.is_primary = 1 " +
                "LEFT JOIN games g ON pgs.game_id = g.id " +
                "WHERE f.requester_id = ? OR f.addressee_id = ? " +
                "ORDER BY u.username ASC";

            Set<String> seenPartners = new HashSet<>();
            try (PreparedStatement pf = conn.prepareStatement(sqlF)) {
                pf.setString(1, me.getId());
                pf.setString(2, me.getId());
                pf.setString(3, me.getId());
                try (ResultSet rs = pf.executeQuery()) {
                    while (rs.next()) {
                        String partnerId = rs.getString("partner_id");
                        if (!seenPartners.add(partnerId)) continue;

                        String partnerUser = rs.getString("username");
                        String avatar = rs.getString("avatar_url");
                        if (avatar == null || avatar.isEmpty()) {
                            avatar = avatarPorDefecto(partnerUser);
                        }
                        String status = nullSafe(rs.getString("status")).toUpperCase(Locale.ROOT);
                        boolean iAmRequester = me.getId().equals(rs.getString("requester_id"));

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("friendshipId", rs.getString("friendship_id"));
                        item.put("username", partnerUser);
                        item.put("avatarUrl", avatar);
                        item.put("game", nullSafe(rs.getString("game_code")).isEmpty() ? "valorant" : rs.getString("game_code"));
                        item.put("gameName", nullSafe(rs.getString("game_name")).isEmpty() ? "Multi-Juego" : rs.getString("game_name"));
                        item.put("rank", nullSafe(rs.getString("in_game_rank")).isEmpty() ? "Sin rango" : rs.getString("in_game_rank"));
                        item.put("role", nullSafe(rs.getString("preferred_role")));
                        double stars = rs.getObject("star_rating") != null ? rs.getDouble("star_rating") : 4.8;
                        item.put("starRating", Math.round(stars * 10.0) / 10.0);
                        item.put("ratingCount", rs.getObject("rating_count") != null ? rs.getInt("rating_count") : 0);
                        item.put("discordTag", nullSafe(rs.getString("discord_tag")));
                        item.put("discordConnected", rs.getInt("discord_connected") == 1);

                        if ("ACCEPTED".equals(status)) {
                            item.put("lastMessage", lastMsgMap.getOrDefault(partnerId, ""));
                            item.put("lastSender", lastSenderMap.getOrDefault(partnerId, ""));
                            item.put("lastTime", lastTimeMap.getOrDefault(partnerId, ""));
                            item.put("lastTimestampMs", lastMsMap.getOrDefault(partnerId, 0L));
                            item.put("unreadCount", unreadMap.getOrDefault(partnerId, 0));
                            friends.add(item);
                            friendshipStatuses.put(partnerUser, "ACCEPTED");
                        } else if ("PENDING".equals(status)) {
                            item.put("createdAt", limpiarTimestamp(rs.getString("created_at")));
                            if (iAmRequester) {
                                outgoingRequests.add(item);
                                friendshipStatuses.put(partnerUser, "PENDING_OUTGOING");
                            } else {
                                incomingRequests.add(item);
                                friendshipStatuses.put(partnerUser, "PENDING_INCOMING");
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error cargando bandeja de amigos y mensajes", e);
        }

        // Ordenar hilos de conversación estilo Instagram: mensajes más recientes arriba
        friends.sort((a, b) -> {
            long msA = ((Number) a.get("lastTimestampMs")).longValue();
            long msB = ((Number) b.get("lastTimestampMs")).longValue();
            int cmp = Long.compare(msB, msA);
            if (cmp != 0) return cmp;
            return String.valueOf(a.get("username")).compareToIgnoreCase(String.valueOf(b.get("username")));
        });

        int totalUnread = 0;
        for (Map<String, Object> f : friends) {
            totalUnread += ((Number) f.get("unreadCount")).intValue();
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("username", me.getUsername());
        res.put("friendsCount", friends.size());
        res.put("totalUnread", totalUnread);
        res.put("pendingIncomingCount", incomingRequests.size());
        res.put("friends", friends);
        res.put("incomingRequests", incomingRequests);
        res.put("outgoingRequests", outgoingRequests);
        res.put("friendshipStatuses", friendshipStatuses);
        return res;
    }

    public synchronized Map<String, Object> enviarSolicitudAmistad(String requesterUsername, String targetUsername) {
        Usuario req = buscarUsuarioPorUsername(requesterUsername);
        Usuario target = buscarUsuarioPorUsername(targetUsername);
        if (req == null || target == null) {
            return null;
        }
        if (req.getId().equals(target.getId())) {
            throw new IllegalArgumentException("No puedes enviarte una solicitud de amistad a ti mismo.");
        }

        try (Connection conn = abrirConexion()) {
            if (estanBloqueados(conn, req.getId(), target.getId())) {
                throw new SecurityException("No es posible enviar solicitud por bloqueo activo entre usuarios.");
            }
            String sqlCheck = "SELECT id, requester_id, addressee_id, status FROM friendships " +
                              "WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?) LIMIT 1";
            String existingId = null;
            String existingReqId = null;
            String existingStatus = null;
            try (PreparedStatement ps = conn.prepareStatement(sqlCheck)) {
                ps.setString(1, req.getId());
                ps.setString(2, target.getId());
                ps.setString(3, target.getId());
                ps.setString(4, req.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getString("id");
                        existingReqId = rs.getString("requester_id");
                        existingStatus = nullSafe(rs.getString("status")).toUpperCase(Locale.ROOT);
                    }
                }
            }

            Map<String, Object> res = new LinkedHashMap<>();
            if (existingId != null) {
                if ("ACCEPTED".equals(existingStatus)) {
                    res.put("friendshipId", existingId);
                    res.put("status", "ACCEPTED");
                    res.put("message", "@" + target.getUsername() + " ya es tu amigo.");
                    return res;
                }
                if ("PENDING".equals(existingStatus)) {
                    if (target.getId().equals(existingReqId)) {
                        // Solicitud mutua: el otro jugador ya nos había enviado solicitud -> aceptar automáticamente
                        try (PreparedStatement pu = conn.prepareStatement(
                                "UPDATE friendships SET status = 'ACCEPTED', updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                            pu.setString(1, existingId);
                            pu.executeUpdate();
                        }
                        res.put("friendshipId", existingId);
                        res.put("status", "ACCEPTED");
                        res.put("message", "¡Solicitud mutua aceptada! Ahora eres amigo de @" + target.getUsername() + ".");
                        return res;
                    }
                    res.put("friendshipId", existingId);
                    res.put("status", "PENDING");
                    res.put("message", "Ya existe una solicitud pendiente para @" + target.getUsername() + ".");
                    return res;
                }
                // Si estaba REJECTED, reactivar como PENDING desde el solicitante actual
                try (PreparedStatement pu = conn.prepareStatement(
                        "UPDATE friendships SET requester_id = ?, addressee_id = ?, status = 'PENDING', updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    pu.setString(1, req.getId());
                    pu.setString(2, target.getId());
                    pu.setString(3, existingId);
                    pu.executeUpdate();
                }
                res.put("friendshipId", existingId);
                res.put("status", "PENDING");
                res.put("message", "Solicitud de amistad enviada a @" + target.getUsername() + ".");
                return res;
            }

            String newId = UUID.randomUUID().toString();
            try (PreparedStatement pi = conn.prepareStatement(
                    "INSERT INTO friendships (id, requester_id, addressee_id, status) VALUES (?, ?, ?, 'PENDING')")) {
                pi.setString(1, newId);
                pi.setString(2, req.getId());
                pi.setString(3, target.getId());
                pi.executeUpdate();
            }
            res.put("friendshipId", newId);
            res.put("status", "PENDING");
            res.put("message", "Solicitud de amistad enviada a @" + target.getUsername() + ".");
            return res;
        } catch (SQLException e) {
            throw new RuntimeException("Error enviando solicitud de amistad", e);
        }
    }

    public synchronized Map<String, Object> responderSolicitudAmistad(String friendshipId, String actorUsername, String nuevoEstado) {
        if (friendshipId == null || friendshipId.trim().isEmpty() || nuevoEstado == null) {
            throw new IllegalArgumentException("Faltan parámetros de respuesta.");
        }
        String estadoUpper = nuevoEstado.trim().toUpperCase(Locale.ROOT);
        if (!"ACCEPTED".equals(estadoUpper) && !"REJECTED".equals(estadoUpper)) {
            throw new IllegalArgumentException("Estado inválido (usa ACCEPTED o REJECTED).");
        }

        Usuario actor = actorUsername != null && !actorUsername.trim().isEmpty() ? buscarUsuarioPorUsername(actorUsername) : null;
        try (Connection conn = abrirConexion()) {
            String addresseeId = null;
            String requesterId = null;
            try (PreparedStatement pq = conn.prepareStatement("SELECT requester_id, addressee_id FROM friendships WHERE id = ?")) {
                pq.setString(1, friendshipId.trim());
                try (ResultSet rs = pq.executeQuery()) {
                    if (!rs.next()) return null;
                    requesterId = rs.getString("requester_id");
                    addresseeId = rs.getString("addressee_id");
                }
            }
            if (actor != null) {
                boolean isAddressee = actor.getId().equals(addresseeId);
                boolean isRequester = actor.getId().equals(requesterId);
                if ("ACCEPTED".equals(estadoUpper) && !isAddressee) {
                    throw new SecurityException("Solo el destinatario puede aceptar la solicitud de amistad.");
                }
                if (!isAddressee && !isRequester) {
                    throw new SecurityException("No perteneces a esta solicitud de amistad.");
                }
            }

            try (PreparedStatement pu = conn.prepareStatement(
                    "UPDATE friendships SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                pu.setString(1, estadoUpper);
                pu.setString(2, friendshipId.trim());
                pu.executeUpdate();
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("friendshipId", friendshipId.trim());
            res.put("status", estadoUpper);
            res.put("message", "ACCEPTED".equals(estadoUpper)
                    ? "Solicitud aceptada. ¡Ya pueden chatear por mensaje directo!"
                    : "Solicitud rechazada.");
            return res;
        } catch (SQLException e) {
            throw new RuntimeException("Error respondiendo solicitud de amistad", e);
        }
    }

    public synchronized boolean eliminarAmistad(String username, String friendUsername) {
        Usuario u1 = buscarUsuarioPorUsername(username);
        Usuario u2 = buscarUsuarioPorUsername(friendUsername);
        if (u1 == null || u2 == null) return false;
        String sql = "DELETE FROM friendships WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, u1.getId());
            ps.setString(2, u2.getId());
            ps.setString(3, u2.getId());
            ps.setString(4, u1.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error eliminando amistad", e);
        }
    }

    public Map<String, Object> obtenerMensajesDirectos(String username, String friendUsername, boolean marcarLeidos) {
        Usuario me = buscarUsuarioPorUsername(username);
        Usuario friend = buscarUsuarioPorUsername(friendUsername);
        if (me == null || friend == null) return null;
        if (me.getId().equals(friend.getId())) {
            throw new IllegalArgumentException("No puedes abrir un chat contigo mismo.");
        }

        try (Connection conn = abrirConexion()) {
            if (!sonAmigosAceptados(conn, me.getId(), friend.getId())) {
                throw new SecurityException("Solo puedes chatear directamente con amigos aceptados.");
            }

            if (marcarLeidos) {
                try (PreparedStatement pr = conn.prepareStatement(
                        "UPDATE direct_messages SET is_read = 1 WHERE sender_id = ? AND receiver_id = ? AND is_read = 0")) {
                    pr.setString(1, friend.getId());
                    pr.setString(2, me.getId());
                    pr.executeUpdate();
                }
            }

            List<Map<String, Object>> mensajes = new ArrayList<>();
            String sql = "SELECT dm.id, su.username AS sender_username, ru.username AS receiver_username, " +
                         "su.avatar_url AS sender_avatar, dm.content, dm.msg_type, dm.attachment_url, dm.meta_json, " +
                         "dm.is_read, dm.sent_at, dm.created_ms " +
                         "FROM direct_messages dm " +
                         "JOIN users su ON dm.sender_id = su.id " +
                         "JOIN users ru ON dm.receiver_id = ru.id " +
                         "WHERE (dm.sender_id = ? AND dm.receiver_id = ?) OR (dm.sender_id = ? AND dm.receiver_id = ?) " +
                         "ORDER BY dm.created_ms ASC, dm.created_at ASC LIMIT 200";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, me.getId());
                ps.setString(2, friend.getId());
                ps.setString(3, friend.getId());
                ps.setString(4, me.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        String sUser = rs.getString("sender_username");
                        String avatar = rs.getString("sender_avatar");
                        if (avatar == null || avatar.isEmpty()) {
                            avatar = avatarPorDefecto(sUser);
                        }
                        m.put("id", rs.getString("id"));
                        m.put("sender", sUser);
                        m.put("receiver", rs.getString("receiver_username"));
                        m.put("avatar", avatar);
                        m.put("content", rs.getString("content"));
                        String mType = nullSafe(rs.getString("msg_type"));
                        m.put("msgType", mType.isEmpty() ? "TEXT" : mType);
                        m.put("attachmentUrl", nullSafe(rs.getString("attachment_url")));
                        m.put("metaJson", nullSafe(rs.getString("meta_json")));
                        m.put("isRead", rs.getInt("is_read") == 1);
                        m.put("timestamp", rs.getString("sent_at"));
                        m.put("createdMs", rs.getLong("created_ms"));
                        mensajes.add(m);
                    }
                }
            }

            Map<String, Object> friendInfo = new LinkedHashMap<>();
            friendInfo.put("username", friend.getUsername());
            friendInfo.put("avatarUrl", (friend.getAvatarUrl() != null && !friend.getAvatarUrl().isEmpty())
                    ? friend.getAvatarUrl()
                    : avatarPorDefecto(friend.getUsername()));
            PerfilJugador p = buscarPerfilPorUsername(friend.getUsername());
            if (p != null) {
                friendInfo.put("game", nullSafe(p.getJuego()));
                friendInfo.put("gameName", nullSafe(p.getNombreJuego()));
                friendInfo.put("rank", nullSafe(p.getRango()));
                friendInfo.put("role", nullSafe(p.getRol()));
                friendInfo.put("starRating", Math.round(p.getStarRating() * 10.0) / 10.0);
                friendInfo.put("cardFrame", p.getCardFrame());
                friendInfo.put("discordTag", nullSafe(p.getDiscordTag()));
                friendInfo.put("discordConnected", p.isDiscordConnected());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("friend", friendInfo);
            out.put("messages", mensajes);
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Error cargando mensajes directos", e);
        }
    }

    public synchronized Map<String, Object> enviarMensajeDirecto(String senderUsername, String receiverUsername, String content) {
        return enviarMensajeDirecto(senderUsername, receiverUsername, content, "TEXT", null, null);
    }

    public synchronized Map<String, Object> enviarMensajeDirecto(String senderUsername, String receiverUsername, String content,
                                                                 String msgType, String attachmentUrl, String lobbyId) {
        String tipo = (msgType != null && !msgType.trim().isEmpty()) ? msgType.trim().toUpperCase(Locale.ROOT) : "TEXT";
        String limpio = content != null ? content.trim() : "";
        String attach = attachmentUrl != null ? attachmentUrl.trim() : "";
        String meta = "";

        if ("IMAGE".equals(tipo)) {
            if (!SeguridadUtil.isValidAvatarUrl(attach)) {
                throw new IllegalArgumentException("Imagen adjunta inválida (usa PNG, JPG, WebP, GIF o HTTPS válido).");
            }
            if (limpio.isEmpty()) limpio = "Imagen compartida";
        } else if ("LOBBY_INVITE".equals(tipo)) {
            if (lobbyId == null || lobbyId.trim().isEmpty()) {
                throw new IllegalArgumentException("Debes seleccionar un lobby válido para compartir.");
            }
        } else {
            tipo = "TEXT";
            if (limpio.isEmpty()) {
                throw new IllegalArgumentException("El mensaje no puede estar vacío.");
            }
        }

        if (limpio.length() > 2000) {
            throw new IllegalArgumentException("El mensaje supera el máximo de 2000 caracteres.");
        }

        Usuario sender = buscarUsuarioPorUsername(senderUsername);
        Usuario receiver = buscarUsuarioPorUsername(receiverUsername);
        if (sender == null || receiver == null) return null;
        if (sender.getId().equals(receiver.getId())) {
            throw new IllegalArgumentException("No puedes enviarte un mensaje directo a ti mismo.");
        }

        try (Connection conn = abrirConexion()) {
            if (estanBloqueados(conn, sender.getId(), receiver.getId())) {
                throw new SecurityException("No puedes enviar mensajes directos porque existe un bloqueo activo.");
            }
            if (!sonAmigosAceptados(conn, sender.getId(), receiver.getId())) {
                throw new SecurityException("Deben ser amigos aceptados para enviarse mensajes directos.");
            }

            if ("LOBBY_INVITE".equals(tipo)) {
                String sqlLob = "SELECT sl.id, sl.title, sl.mode, sl.max_slots, sl.schedule_label, g.name AS game_name, " +
                                "(SELECT COUNT(*) FROM squad_lobby_members m WHERE m.lobby_id = sl.id) AS curr " +
                                "FROM squad_lobbies sl JOIN games g ON sl.game_id = g.id WHERE sl.id = ? LIMIT 1";
                try (PreparedStatement pl = conn.prepareStatement(sqlLob)) {
                    pl.setString(1, lobbyId.trim());
                    try (ResultSet rs = pl.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("El lobby seleccionado ya no existe.");
                        }
                        Map<String, Object> mMeta = new LinkedHashMap<>();
                        mMeta.put("lobbyId", rs.getString("id"));
                        mMeta.put("title", rs.getString("title"));
                        mMeta.put("mode", rs.getString("mode"));
                        mMeta.put("maxSlots", rs.getInt("max_slots"));
                        mMeta.put("currentCount", rs.getInt("curr"));
                        mMeta.put("scheduleLabel", rs.getString("schedule_label"));
                        mMeta.put("gameName", rs.getString("game_name"));
                        meta = com.playmatch.util.JsonUtils.toJson(mMeta);
                        if (limpio.isEmpty()) {
                            limpio = "Te invito a mi escuadra: " + rs.getString("title");
                        }
                    }
                }
            }

            String id = UUID.randomUUID().toString();
            String hora = LocalDateTime.now().format(FMT_HORA);
            long ms = siguienteDmTimestampMs();

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO direct_messages (id, sender_id, receiver_id, content, msg_type, attachment_url, meta_json, is_read, sent_at, created_ms) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, sender.getId());
                ps.setString(3, receiver.getId());
                ps.setString(4, limpio);
                ps.setString(5, tipo);
                ps.setString(6, attach);
                ps.setString(7, meta);
                ps.setString(8, hora);
                ps.setLong(9, ms);
                ps.executeUpdate();
            }

            try (PreparedStatement pu = conn.prepareStatement(
                    "UPDATE friendships SET updated_at = CURRENT_TIMESTAMP WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)")) {
                pu.setString(1, sender.getId());
                pu.setString(2, receiver.getId());
                pu.setString(3, receiver.getId());
                pu.setString(4, sender.getId());
                pu.executeUpdate();
            } catch (SQLException ignored) {}

            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", id);
            dm.put("sender", sender.getUsername());
            dm.put("receiver", receiver.getUsername());
            dm.put("avatar", (sender.getAvatarUrl() != null && !sender.getAvatarUrl().isEmpty())
                    ? sender.getAvatarUrl()
                    : avatarPorDefecto(sender.getUsername()));
            dm.put("content", limpio);
            dm.put("msgType", tipo);
            dm.put("attachmentUrl", attach);
            dm.put("metaJson", meta);
            dm.put("isRead", false);
            dm.put("timestamp", hora);
            dm.put("createdMs", ms);
            return dm;
        } catch (SQLException e) {
            throw new RuntimeException("Error guardando mensaje directo", e);
        }
    }

    public synchronized int marcarMensajesDirectosLeidos(String readerUsername, String friendUsername) {
        Usuario reader = buscarUsuarioPorUsername(readerUsername);
        Usuario friend = buscarUsuarioPorUsername(friendUsername);
        if (reader == null || friend == null) return 0;
        String sql = "UPDATE direct_messages SET is_read = 1 WHERE sender_id = ? AND receiver_id = ? AND is_read = 0";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, friend.getId());
            ps.setString(2, reader.getId());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error marcando mensajes directos como leídos", e);
        }
    }

    // =========================================================================
    // 6. SOLICITUDES DE EMPAREJAMIENTO (MATCH REQUESTS)
    // =========================================================================

    public synchronized boolean agregarSolicitudEmparejamiento(SolicitudEmparejamiento req) {
        Usuario sender = buscarUsuarioPorUsername(req.getRemitenteUsername());
        Usuario receiver = buscarUsuarioPorUsername(req.getDestinatarioUsername());
        if (sender == null || receiver == null) return false;

        try (Connection conn = abrirConexion()) {
            if (estanBloqueados(conn, sender.getId(), receiver.getId())) {
                throw new SecurityException("No puedes invitar a este jugador porque existe un bloqueo activo.");
            }
            String gameId = resolverGameId(conn, req.getJuego());
            if (gameId == null) return false;

            String sql = "INSERT INTO match_requests (id, sender_id, receiver_id, game_id, message, status) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, req.getId());
                ps.setString(2, sender.getId());
                ps.setString(3, receiver.getId());
                ps.setString(4, gameId);
                ps.setString(5, nullSafe(req.getMensaje()));
                ps.setString(6, req.getEstado() != null ? req.getEstado() : "PENDING");
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Error guardando solicitud de emparejamiento", e);
        }
    }

    public List<SolicitudEmparejamiento> obtenerSolicitudesEmparejamiento() {
        return obtenerSolicitudesPorUsuario(null);
    }

    public List<SolicitudEmparejamiento> obtenerSolicitudesPorUsuario(String username) {
        boolean filtrar = username != null && !username.trim().isEmpty();
        String sql = "SELECT mr.id, su.username AS sender_username, ru.username AS receiver_username, " +
                     "g.name AS game_name, mr.message, mr.status, mr.created_at " +
                     "FROM match_requests mr " +
                     "JOIN users su ON mr.sender_id = su.id " +
                     "JOIN users ru ON mr.receiver_id = ru.id " +
                     "JOIN games g ON mr.game_id = g.id " +
                     (filtrar ? "WHERE LOWER(su.username) = LOWER(?) OR LOWER(ru.username) = LOWER(?) " : "") +
                     "ORDER BY mr.created_at DESC LIMIT 50";
        List<SolicitudEmparejamiento> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtrar) {
                ps.setString(1, username.trim());
                ps.setString(2, username.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SolicitudEmparejamiento s = new SolicitudEmparejamiento(
                        rs.getString("id"),
                        rs.getString("sender_username"),
                        rs.getString("receiver_username"),
                        rs.getString("game_name"),
                        rs.getString("message"),
                        rs.getString("status")
                    );
                    s.setFechaCreacion(limpiarTimestamp(rs.getString("created_at")));
                    lista.add(s);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error consultando solicitudes de emparejamiento", e);
        }
        return lista;
    }

    public synchronized boolean actualizarEstadoSolicitud(String requestId, String nuevoEstado) {
        if (requestId == null || nuevoEstado == null) return false;
        String sql = "UPDATE match_requests SET status = ? WHERE id = ?";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nuevoEstado.trim().toUpperCase(Locale.ROOT));
            ps.setString(2, requestId.trim());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando estado de solicitud", e);
        }
    }

    // =========================================================================
    // 7. TELEMETRÍA Y ESTADÍSTICAS DE LA BASE DE DATOS
    // =========================================================================

    public Map<String, Integer> obtenerEstadisticasGenerales() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        try (Connection conn = abrirConexion()) {
            stats.put("users", contarFilas(conn, "users"));
            stats.put("profiles", contarFilas(conn, "player_profiles"));
            stats.put("games", contarFilas(conn, "games"));
            stats.put("tournaments", contarFilas(conn, "tournaments"));
            stats.put("teams", contarFilas(conn, "teams"));
            stats.put("messages", contarFilas(conn, "chat_messages"));
            stats.put("matchRequests", contarFilas(conn, "match_requests"));
            stats.put("friendships", contarFilas(conn, "friendships"));
            stats.put("directMessages", contarFilas(conn, "direct_messages"));
            stats.put("lobbies", contarFilas(conn, "squad_lobbies"));
        } catch (SQLException e) {
            throw new RuntimeException("Error consultando estadísticas de base de datos", e);
        }
        return stats;
    }

    // =========================================================================
    // 8. DOSSIER PÚBLICO, LOBBIES CON CUPOS Y MODERACIÓN (BLOQUEOS/REPORTES)
    // =========================================================================

    public boolean estanBloqueados(Connection conn, String userId1, String userId2) throws SQLException {
        String sql = "SELECT 1 FROM user_blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId1);
            ps.setString(2, userId2);
            ps.setString(3, userId2);
            ps.setString(4, userId1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Set<String> obtenerIdsBloqueadosMutuos(String username) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        String sql = "SELECT blocker_id, blocked_id FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, u.getId());
            ps.setString(2, u.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String b1 = rs.getString("blocker_id");
                    String b2 = rs.getString("blocked_id");
                    ids.add(u.getId().equals(b1) ? b2 : b1);
                }
            }
        } catch (SQLException ignored) {}
        return ids;
    }

    public List<Map<String, Object>> obtenerUsuariosBloqueados(String username) {
        Usuario me = buscarUsuarioPorUsername(username);
        if (me == null) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT u.username, u.avatar_url, ub.created_at FROM user_blocks ub JOIN users u ON ub.blocked_id = u.id WHERE ub.blocker_id = ? ORDER BY ub.created_at DESC";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, me.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String uname = rs.getString("username");
                    String av = rs.getString("avatar_url");
                    if (av == null || av.isEmpty()) av = avatarPorDefecto(uname);
                    m.put("username", uname);
                    m.put("avatarUrl", av);
                    m.put("blockedAt", limpiarTimestamp(rs.getString("created_at")));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error consultando usuarios bloqueados", e);
        }
        return out;
    }

    public synchronized boolean bloquearUsuario(String blockerUsername, String blockedUsername) {
        Usuario b1 = buscarUsuarioPorUsername(blockerUsername);
        Usuario b2 = buscarUsuarioPorUsername(blockedUsername);
        if (b1 == null || b2 == null) return false;
        if (b1.getId().equals(b2.getId())) {
            throw new IllegalArgumentException("No puedes bloquearte a ti mismo.");
        }
        try (Connection conn = abrirConexion()) {
            try (PreparedStatement pi = conn.prepareStatement(
                    "INSERT INTO user_blocks (id, blocker_id, blocked_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                pi.setString(1, UUID.randomUUID().toString());
                pi.setString(2, b1.getId());
                pi.setString(3, b2.getId());
                pi.executeUpdate();
            }
            // Eliminar amistad si existía
            try (PreparedStatement pd = conn.prepareStatement(
                    "DELETE FROM friendships WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)")) {
                pd.setString(1, b1.getId());
                pd.setString(2, b2.getId());
                pd.setString(3, b2.getId());
                pd.setString(4, b1.getId());
                pd.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Error bloqueando usuario", e);
        }
    }

    public synchronized boolean desbloquearUsuario(String blockerUsername, String blockedUsername) {
        Usuario b1 = buscarUsuarioPorUsername(blockerUsername);
        Usuario b2 = buscarUsuarioPorUsername(blockedUsername);
        if (b1 == null || b2 == null) return false;
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?")) {
            ps.setString(1, b1.getId());
            ps.setString(2, b2.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error desbloqueando usuario", e);
        }
    }

    public synchronized boolean reportarJugador(String reporterUsername, String reportedUsername, String reason, String details) {
        Usuario r1 = buscarUsuarioPorUsername(reporterUsername);
        Usuario r2 = buscarUsuarioPorUsername(reportedUsername);
        if (r1 == null || r2 == null) return false;
        if (r1.getId().equals(r2.getId())) {
            throw new IllegalArgumentException("No puedes reportarte a ti mismo.");
        }
        String rClean = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Conducta inapropiada";
        String dClean = details != null ? details.trim() : "";
        if (dClean.length() > 290) dClean = dClean.substring(0, 290);
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO player_reports (id, reporter_id, reported_id, reason, details) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, r1.getId());
            ps.setString(3, r2.getId());
            ps.setString(4, rClean);
            ps.setString(5, dClean);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Error registrando reporte", e);
        }
    }

    public Map<String, Object> obtenerDossierPublico(String targetUsername, String viewerUsername) {
        PerfilJugador p = buscarPerfilPorUsername(targetUsername);
        Usuario targetUser = buscarUsuarioPorUsername(targetUsername);
        if (targetUser == null) return null;

        Map<String, Object> out = new LinkedHashMap<>();
        String avatar = (targetUser.getAvatarUrl() != null && !targetUser.getAvatarUrl().isEmpty())
                ? targetUser.getAvatarUrl()
                : avatarPorDefecto(targetUser.getUsername());

        out.put("username", targetUser.getUsername());
        out.put("rolePlatform", targetUser.getRol());
        out.put("avatarUrl", avatar);
        out.put("phoneVerified", targetUser.isTelefonoVerificado());
        out.put("createdAt", nullSafe(targetUser.getFechaCreacion()));

        if (p != null) {
            out.put("bio", nullSafe(p.getBiografia()));
            out.put("region", nullSafe(p.getRegion()));
            out.put("preferredSchedule", nullSafe(p.getHorarioPreferido()));
            out.put("game", nullSafe(p.getJuego()));
            out.put("gameName", nullSafe(p.getNombreJuego()));
            out.put("rank", nullSafe(p.getRango()));
            out.put("role", nullSafe(p.getRol()));
            out.put("playstyle", nullSafe(p.getEstiloJuego()));
            out.put("toxicLevel", nullSafe(p.getNivelToxicidad()));
            out.put("reputationScore", p.getPuntajeReputacion());
            out.put("starRating", Math.round(p.getStarRating() * 10.0) / 10.0);
            out.put("ratingCount", p.getRatingCount());
            out.put("cardFrame", p.getCardFrame());
            out.put("favoriteGames", nullSafe(p.getFavoriteGames()));
            out.put("favoriteGenres", nullSafe(p.getFavoriteGenres()));
            out.put("playPreferences", nullSafe(p.getPlayPreferences()));
            out.put("discordTag", nullSafe(p.getDiscordTag()));
            out.put("discordId", nullSafe(p.getDiscordId()));
            out.put("discordConnected", p.isDiscordConnected());
            out.put("riotId", nullSafe(p.getRiotId()));
            out.put("steamId", nullSafe(p.getSteamId()));
        } else {
            out.put("bio", "");
            out.put("region", "Chile");
            out.put("gameName", "PlayMatch");
            out.put("starRating", 4.8);
            out.put("ratingCount", 0);
            out.put("cardFrame", "NONE");
        }

        List<Map<String, Object>> reviews = new ArrayList<>();
        boolean isBlockedByViewer = false;
        String friendshipStatus = "NONE";
        try (Connection conn = abrirConexion()) {
            String sqlRev = "SELECT pr.stars, pr.comment, pr.updated_at, ru.username AS rater_username, ru.avatar_url AS rater_avatar " +
                            "FROM player_ratings pr JOIN users ru ON pr.rater_user_id = ru.id " +
                            "WHERE pr.rated_user_id = ? ORDER BY pr.updated_at DESC LIMIT 15";
            try (PreparedStatement ps = conn.prepareStatement(sqlRev)) {
                ps.setString(1, targetUser.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> rv = new LinkedHashMap<>();
                        String rName = rs.getString("rater_username");
                        String rAv = rs.getString("rater_avatar");
                        if (rAv == null || rAv.isEmpty()) rAv = avatarPorDefecto(rName);
                        rv.put("rater", rName);
                        rv.put("raterAvatar", rAv);
                        rv.put("stars", rs.getInt("stars"));
                        rv.put("comment", nullSafe(rs.getString("comment")));
                        rv.put("updatedAt", limpiarTimestamp(rs.getString("updated_at")));
                        reviews.add(rv);
                    }
                }
            }

            if (viewerUsername != null && !viewerUsername.trim().isEmpty()) {
                Usuario viewer = buscarUsuarioPorUsername(viewerUsername);
                if (viewer != null && !viewer.getId().equals(targetUser.getId())) {
                    try (PreparedStatement pb = conn.prepareStatement(
                            "SELECT 1 FROM user_blocks WHERE blocker_id = ? AND blocked_id = ? LIMIT 1")) {
                        pb.setString(1, viewer.getId());
                        pb.setString(2, targetUser.getId());
                        try (ResultSet rs = pb.executeQuery()) {
                            isBlockedByViewer = rs.next();
                        }
                    }
                    try (PreparedStatement pf = conn.prepareStatement(
                            "SELECT requester_id, status FROM friendships WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?) LIMIT 1")) {
                        pf.setString(1, viewer.getId());
                        pf.setString(2, targetUser.getId());
                        pf.setString(3, targetUser.getId());
                        pf.setString(4, viewer.getId());
                        try (ResultSet rs = pf.executeQuery()) {
                            if (rs.next()) {
                                String st = rs.getString("status");
                                if ("ACCEPTED".equalsIgnoreCase(st)) friendshipStatus = "ACCEPTED";
                                else if ("PENDING".equalsIgnoreCase(st)) {
                                    friendshipStatus = viewer.getId().equals(rs.getString("requester_id")) ? "PENDING_OUTGOING" : "PENDING_INCOMING";
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error cargando dossier del jugador", e);
        }

        out.put("reviews", reviews);
        out.put("trophies", cargarTrofeosJugador(targetUser.getId()));
        out.put("isBlocked", isBlockedByViewer);
        out.put("friendshipStatus", friendshipStatus);
        return out;
    }

    private List<Map<String, Object>> cargarTrofeosJugador(String userId) {
        List<Map<String, Object>> trophies = new ArrayList<>();
        String sql =
            "SELECT DISTINCT t.id AS tournament_id, t.title, t.prize_pool, t.start_date, g.name AS game_name, tm.name AS team_name " +
            "FROM tournaments t " +
            "JOIN games g ON t.game_id = g.id " +
            "JOIN tournament_matches fin ON fin.tournament_id = t.id AND fin.status = 'FINISHED' AND LOWER(fin.round_name) LIKE '%gran final%' " +
            "JOIN tournament_registrations tr ON tr.tournament_id = t.id " +
            "JOIN teams tm ON tr.team_id = tm.id " +
            "WHERE t.status = 'FINISHED' AND tm.captain_id = ? AND LOWER(tm.name) = LOWER(fin.winner_name) " +
            "ORDER BY t.created_at DESC LIMIT 12";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("tournamentId", rs.getString("tournament_id"));
                    tr.put("title", rs.getString("title"));
                    tr.put("prizePool", nullSafe(rs.getString("prize_pool")));
                    tr.put("startDate", nullSafe(rs.getString("start_date")));
                    tr.put("gameName", rs.getString("game_name"));
                    tr.put("teamName", rs.getString("team_name"));
                    trophies.add(tr);
                }
            }
        } catch (SQLException ignored) {}
        return trophies;
    }

    public List<Map<String, Object>> obtenerLobbies(String gameFilter, String viewerUsername) {
        boolean filtrarJuego = gameFilter != null && !gameFilter.trim().isEmpty() && !"all".equalsIgnoreCase(gameFilter.trim());
        Usuario viewer = (viewerUsername != null && !viewerUsername.trim().isEmpty()) ? buscarUsuarioPorUsername(viewerUsername) : null;
        Set<String> blockedIds = (viewer != null) ? obtenerIdsBloqueadosMutuos(viewer.getUsername()) : Collections.emptySet();

        String sql = "SELECT sl.id, sl.title, sl.mode, sl.max_slots, sl.roles_needed, sl.schedule_label, sl.status, sl.created_at, " +
                     "hu.id AS host_id, hu.username AS host_username, hu.avatar_url AS host_avatar, " +
                     "g.code AS game_code, g.name AS game_name " +
                     "FROM squad_lobbies sl " +
                     "JOIN users hu ON sl.host_user_id = hu.id " +
                     "JOIN games g ON sl.game_id = g.id " +
                     "WHERE sl.status <> 'CLOSED' " +
                     (filtrarJuego ? "AND (LOWER(g.code) = LOWER(?) OR LOWER(g.name) = LOWER(?)) " : "") +
                     "ORDER BY sl.created_at DESC LIMIT 40";

        List<Map<String, Object>> lobbies = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtrarJuego) {
                ps.setString(1, gameFilter.trim());
                ps.setString(2, gameFilter.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (blockedIds.contains(rs.getString("host_id"))) continue;
                    String lobId = rs.getString("id");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", lobId);
                    item.put("title", rs.getString("title"));
                    item.put("mode", rs.getString("mode"));
                    item.put("maxSlots", rs.getInt("max_slots"));
                    item.put("rolesNeeded", nullSafe(rs.getString("roles_needed")));
                    item.put("scheduleLabel", nullSafe(rs.getString("schedule_label")));
                    item.put("status", rs.getString("status"));
                    item.put("hostUsername", rs.getString("host_username"));
                    String hAv = rs.getString("host_avatar");
                    if (hAv == null || hAv.isEmpty()) hAv = avatarPorDefecto(rs.getString("host_username"));
                    item.put("hostAvatar", hAv);
                    item.put("game", rs.getString("game_code"));
                    item.put("gameName", rs.getString("game_name"));
                    item.put("channelId", "lobby-" + lobId);
                    item.put("createdAt", limpiarTimestamp(rs.getString("created_at")));

                    List<Map<String, Object>> members = new ArrayList<>();
                    boolean joined = false;
                    try (PreparedStatement pm = conn.prepareStatement(
                            "SELECT u.id, u.username, u.avatar_url, m.role_note FROM squad_lobby_members m JOIN users u ON m.user_id = u.id WHERE m.lobby_id = ? ORDER BY m.joined_at ASC")) {
                        pm.setString(1, lobId);
                        try (ResultSet rm = pm.executeQuery()) {
                            while (rm.next()) {
                                Map<String, Object> mem = new LinkedHashMap<>();
                                String mu = rm.getString("username");
                                String ma = rm.getString("avatar_url");
                                if (ma == null || ma.isEmpty()) ma = avatarPorDefecto(mu);
                                mem.put("username", mu);
                                mem.put("avatarUrl", ma);
                                mem.put("roleNote", nullSafe(rm.getString("role_note")));
                                members.add(mem);
                                if (viewer != null && viewer.getId().equals(rm.getString("id"))) {
                                    joined = true;
                                }
                            }
                        }
                    }
                    item.put("members", members);
                    item.put("currentCount", members.size());
                    item.put("joined", joined);
                    item.put("isHost", viewer != null && viewer.getId().equals(rs.getString("host_id")));
                    lobbies.add(item);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error cargando lobbies grupales", e);
        }
        return lobbies;
    }

    public synchronized Map<String, Object> crearLobby(String hostUsername, String gameCode, String title,
                                                       String mode, int maxSlots, String rolesNeeded, String scheduleLabel) {
        Usuario host = buscarUsuarioPorUsername(hostUsername);
        if (host == null) return null;
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("El título del lobby es obligatorio.");
        }
        int slots = Math.max(2, Math.min(10, maxSlots));
        String cleanMode = (mode != null && !mode.trim().isEmpty()) ? mode.trim() : "Escuadra";
        String cleanRoles = rolesNeeded != null ? rolesNeeded.trim() : "";
        String cleanSched = (scheduleLabel != null && !scheduleLabel.trim().isEmpty()) ? scheduleLabel.trim() : "Ahora";

        try (Connection conn = abrirConexion()) {
            String gameId = resolverGameId(conn, gameCode);
            if (gameId == null) {
                throw new IllegalArgumentException("Juego seleccionado inválido.");
            }
            String lobbyId = "lob-" + UUID.randomUUID().toString().substring(0, 8);
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pl = conn.prepareStatement(
                        "INSERT INTO squad_lobbies (id, host_user_id, game_id, title, mode, max_slots, roles_needed, schedule_label, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')")) {
                    pl.setString(1, lobbyId);
                    pl.setString(2, host.getId());
                    pl.setString(3, gameId);
                    pl.setString(4, title.trim());
                    pl.setString(5, cleanMode);
                    pl.setInt(6, slots);
                    pl.setString(7, cleanRoles);
                    pl.setString(8, cleanSched);
                    pl.executeUpdate();
                }
                try (PreparedStatement pm = conn.prepareStatement(
                        "INSERT INTO squad_lobby_members (id, lobby_id, user_id, role_note) VALUES (?, ?, ?, ?)")) {
                    pm.setString(1, UUID.randomUUID().toString());
                    pm.setString(2, lobbyId);
                    pm.setString(3, host.getId());
                    pm.setString(4, "Líder");
                    pm.executeUpdate();
                }
                try (PreparedStatement pc = conn.prepareStatement(
                        "INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    pc.setString(1, UUID.randomUUID().toString());
                    pc.setString(2, host.getId());
                    pc.setString(3, host.getUsername());
                    pc.setString(4, host.getAvatarUrl());
                    pc.setString(5, "lobby-" + lobbyId);
                    pc.setString(6, "Lobby creado (" + cleanMode + ", " + slots + " cupos): " + title.trim());
                    pc.setString(7, LocalDateTime.now().format(FMT_HORA));
                    pc.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
            for (Map<String, Object> lob : obtenerLobbies("all", host.getUsername())) {
                if (lobbyId.equals(lob.get("id"))) return lob;
            }
            return Collections.singletonMap("id", lobbyId);
        } catch (SQLException e) {
            throw new RuntimeException("Error creando lobby de escuadra", e);
        }
    }

    public synchronized Map<String, Object> unirseALobby(String lobbyId, String username, String roleNote) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null || lobbyId == null || lobbyId.trim().isEmpty()) return null;

        try (Connection conn = abrirConexion()) {
            String hostId = null;
            int maxSlots = 5;
            String status = "OPEN";
            try (PreparedStatement pl = conn.prepareStatement(
                    "SELECT host_user_id, max_slots, status FROM squad_lobbies WHERE id = ?")) {
                pl.setString(1, lobbyId.trim());
                try (ResultSet rs = pl.executeQuery()) {
                    if (!rs.next()) return null;
                    hostId = rs.getString("host_user_id");
                    maxSlots = rs.getInt("max_slots");
                    status = rs.getString("status");
                }
            }
            if (estanBloqueados(conn, hostId, u.getId())) {
                throw new SecurityException("No puedes unirte a este lobby por un bloqueo activo.");
            }
            if ("CLOSED".equalsIgnoreCase(status)) {
                throw new IllegalArgumentException("Este lobby ya fue cerrado.");
            }

            boolean alreadyIn = false;
            try (PreparedStatement pm = conn.prepareStatement(
                    "SELECT 1 FROM squad_lobby_members WHERE lobby_id = ? AND user_id = ?")) {
                pm.setString(1, lobbyId.trim());
                pm.setString(2, u.getId());
                try (ResultSet rs = pm.executeQuery()) {
                    alreadyIn = rs.next();
                }
            }

            int currentCount = 0;
            try (PreparedStatement pc = conn.prepareStatement("SELECT COUNT(*) FROM squad_lobby_members WHERE lobby_id = ?")) {
                pc.setString(1, lobbyId.trim());
                try (ResultSet rs = pc.executeQuery()) {
                    if (rs.next()) currentCount = rs.getInt(1);
                }
            }

            if (!alreadyIn) {
                if (currentCount >= maxSlots) {
                    throw new IllegalArgumentException("El lobby ya alcanzó el máximo de cupos (" + maxSlots + ").");
                }
                try (PreparedStatement pi = conn.prepareStatement(
                        "INSERT INTO squad_lobby_members (id, lobby_id, user_id, role_note) VALUES (?, ?, ?, ?)")) {
                    pi.setString(1, UUID.randomUUID().toString());
                    pi.setString(2, lobbyId.trim());
                    pi.setString(3, u.getId());
                    pi.setString(4, roleNote != null ? roleNote.trim() : "");
                    pi.executeUpdate();
                }
                currentCount++;
                if (currentCount >= maxSlots) {
                    try (PreparedStatement pu = conn.prepareStatement("UPDATE squad_lobbies SET status = 'FULL' WHERE id = ?")) {
                        pu.setString(1, lobbyId.trim());
                        pu.executeUpdate();
                    }
                }
                try (PreparedStatement pch = conn.prepareStatement(
                        "INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES (?, NULL, 'PlayMatch Sistema', '/api/avatar?u=PlayMatch', ?, ?, ?)")) {
                    pch.setString(1, UUID.randomUUID().toString());
                    pch.setString(2, "lobby-" + lobbyId.trim());
                    pch.setString(3, "@" + u.getUsername() + " se unió al lobby (" + currentCount + "/" + maxSlots + ").");
                    pch.setString(4, LocalDateTime.now().format(FMT_HORA));
                    pch.executeUpdate();
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lobbyId", lobbyId.trim());
            out.put("joined", true);
            out.put("currentCount", currentCount);
            out.put("maxSlots", maxSlots);
            out.put("channelId", "lobby-" + lobbyId.trim());
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Error uniéndose al lobby", e);
        }
    }

    public synchronized Map<String, Object> salirDeLobby(String lobbyId, String username) {
        Usuario u = buscarUsuarioPorUsername(username);
        if (u == null || lobbyId == null || lobbyId.trim().isEmpty()) return null;

        try (Connection conn = abrirConexion()) {
            try (PreparedStatement pd = conn.prepareStatement(
                    "DELETE FROM squad_lobby_members WHERE lobby_id = ? AND user_id = ?")) {
                pd.setString(1, lobbyId.trim());
                pd.setString(2, u.getId());
                pd.executeUpdate();
            }
            int remaining = 0;
            try (PreparedStatement pc = conn.prepareStatement("SELECT COUNT(*) FROM squad_lobby_members WHERE lobby_id = ?")) {
                pc.setString(1, lobbyId.trim());
                try (ResultSet rs = pc.executeQuery()) {
                    if (rs.next()) remaining = rs.getInt(1);
                }
            }
            String nextStatus = remaining == 0 ? "CLOSED" : "OPEN";
            try (PreparedStatement pu = conn.prepareStatement("UPDATE squad_lobbies SET status = ? WHERE id = ?")) {
                pu.setString(1, nextStatus);
                pu.setString(2, lobbyId.trim());
                pu.executeUpdate();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lobbyId", lobbyId.trim());
            out.put("left", true);
            out.put("remaining", remaining);
            out.put("status", nextStatus);
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Error saliendo del lobby", e);
        }
    }

    public boolean verificarSaludConexion() {
        try (Connection conn = abrirConexion();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private String limpiarTimestamp(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        return t.length() > 19 ? t.substring(0, 19) : t;
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}
