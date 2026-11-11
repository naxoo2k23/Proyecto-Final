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
        if (esPostgreSQL()) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE player_game_stats ADD COLUMN IF NOT EXISTS is_primary INT DEFAULT 1;");
                st.execute("ALTER TABLE games ADD COLUMN IF NOT EXISTS is_active INT DEFAULT 1;");
                st.execute("ALTER TABLE games ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 100;");
            }
        } else {
            if (!existeColumnaSqlite(conn, "player_game_stats", "is_primary")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE player_game_stats ADD COLUMN is_primary INT DEFAULT 1;");
                }
            }
            if (!existeColumnaSqlite(conn, "games", "is_active")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE games ADD COLUMN is_active INT DEFAULT 1;");
                }
            }
            if (!existeColumnaSqlite(conn, "games", "sort_order")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE games ADD COLUMN sort_order INT DEFAULT 100;");
                }
            }
        }
        // Asegurar orden base de juegos iniciales
        try (PreparedStatement ps = conn.prepareStatement("UPDATE games SET sort_order = ? WHERE id = ? AND sort_order = 100")) {
            String[][] orden = {{"1", "g-val"}, {"2", "g-lol"}, {"3", "g-cs2"}, {"4", "g-rl"}};
            for (String[] item : orden) {
                ps.setInt(1, Integer.parseInt(item[0]));
                ps.setString(2, item[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ignored) {}
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
                "avatar_url VARCHAR(255), telefono VARCHAR(30) NOT NULL DEFAULT '', " +
                "role VARCHAR(20) NOT NULL DEFAULT 'GAMER', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);" +
                "CREATE TABLE IF NOT EXISTS player_profiles (" +
                "id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, bio TEXT, " +
                "country VARCHAR(50) DEFAULT 'Chile', region VARCHAR(100) DEFAULT 'Región Metropolitana', " +
                "toxic_level VARCHAR(20) DEFAULT 'ZERO_TOXIC', reputation_score INT DEFAULT 100, " +
                "preferred_schedule VARCHAR(60) DEFAULT 'Noches (21:00 - 01:00)', " +
                "steam_id VARCHAR(100) DEFAULT '', riot_id VARCHAR(100) DEFAULT '', epic_id VARCHAR(100) DEFAULT '', discord_tag VARCHAR(100) DEFAULT '', " +
                "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE);" +
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
                "seed_number INT DEFAULT 1, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE, UNIQUE(tournament_id, team_id));" +
                "CREATE TABLE IF NOT EXISTS tournament_matches (" +
                "id VARCHAR(36) PRIMARY KEY, tournament_id VARCHAR(36) NOT NULL, round_name VARCHAR(50) NOT NULL, " +
                "match_order INT NOT NULL, team1_name VARCHAR(100), team2_name VARCHAR(100), " +
                "score_team1 INT DEFAULT 0, score_team2 INT DEFAULT 0, winner_name VARCHAR(100), " +
                "status VARCHAR(20) DEFAULT 'SCHEDULED', " +
                "FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE);" +
                "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id VARCHAR(36) PRIMARY KEY, sender_id VARCHAR(36), sender_username VARCHAR(60) NOT NULL, " +
                "sender_avatar VARCHAR(255), channel VARCHAR(50) NOT NULL DEFAULT 'global', content TEXT NOT NULL, " +
                "sent_at VARCHAR(30) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL);";
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

    private void sembrarJuegos(Connection conn) throws SQLException {
        String sql = "INSERT INTO games (id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)";
        Object[][] juegos = {
            {"g-val", "valorant", "Valorant", "Tactical FPS", "PC / Consola", 5,
             "Shooter táctico 5v5 basado en precisión de disparo y habilidades estratégicas de agentes.",
             "#ff4655", "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=900&q=80", 1},
            {"g-lol", "lol", "League of Legends", "MOBA", "PC (LAS)", 5,
             "Arena de batalla multijugador por equipos 5v5 con enfoque en macrogame y objetivos.",
             "#8b5cf6", "https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=900&q=80", 2},
            {"g-cs2", "cs2", "Counter-Strike 2", "Tactical FPS", "PC (Steam)", 5,
             "Competitivo clásico por rondas de ataque y defensa táctica con economía de equipo.",
             "#f59e0b", "https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=900&q=80", 3},
            {"g-rl", "rocket-league", "Rocket League", "Deportes / Arcade", "Multiplataforma", 3,
             "Fútbol vehicular competitivo 3v3 de alta velocidad, rotaciones y mecánicas aéreas.",
             "#06b6d4", "https://images.unsplash.com/photo-1538481199705-c710c4e965fc?auto=format&fit=crop&w=900&q=80", 4}
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] j : juegos) {
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

    private void sembrarUsuariosYPerfiles(Connection conn) throws SQLException {
        String hashAdmin = SeguridadUtil.hashPassword("admin123");
        String hashDefault = SeguridadUtil.hashPassword("password123");

        String sqlUser = "INSERT INTO users (id, username, email, password_hash, avatar_url, telefono, role) VALUES (?, ?, ?, ?, ?, ?, ?)";
        Object[][] usersData = {
            {"u-1", "Jesus_Villasanti", "jesus.villasanti@playmatch.cl", hashAdmin, "https://api.dicebear.com/7.x/bottts/svg?seed=Jesus_Villasanti", "+56 9 8123 4567", "ADMIN"},
            {"u-2", "Datrikk", "datrikk@playmatch.cl", hashDefault, "https://api.dicebear.com/7.x/bottts/svg?seed=Datrikk", "+56 9 7234 5678", "GAMER"},
            {"u-3", "dididong", "dididong@playmatch.cl", hashDefault, "https://api.dicebear.com/7.x/bottts/svg?seed=dididong", "+56 9 6345 6789", "GAMER"},
            {"u-4", "luchi", "luchi@playmatch.cl", hashDefault, "https://api.dicebear.com/7.x/bottts/svg?seed=luchi", "+56 9 5456 7890", "GAMER"},
            {"u-5", "jaeliug", "jaeliug@playmatch.cl", hashDefault, "https://api.dicebear.com/7.x/bottts/svg?seed=jaeliug", "+56 9 4567 8901", "GAMER"},
            {"u-6", "pepitogamer67", "pepitogamer67@playmatch.cl", hashDefault, "https://api.dicebear.com/7.x/bottts/svg?seed=pepitogamer67", "+56 9 3678 9012", "GAMER"},
            {"u-7", "AndesEsports", "contacto@andesesports.cl", hashDefault, "https://api.dicebear.com/7.x/bottts/svg?seed=AndesEsports", "+56 9 2111 2222", "ORGANIZER"}
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

        String sqlProfile = "INSERT INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag) VALUES (?, ?, ?, 'Chile', ?, ?, ?, ?, ?, ?, ?)";
        Object[][] profiles = {
            {"p-1", "u-1", "Main Controller en Valorant. Busco escuadra seria para competir en torneos comunitarios y subir elo.", "Región Metropolitana (San Joaquín)", "ZERO_TOXIC", 100, "Noches (21:00 - 01:00)", "76561198000000001", "Jesus#CHI", "jesus_villasanti#0001"},
            {"p-2", "u-2", "Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Excelente sinergia de equipo.", "Los Lagos (Castro)", "ZERO_TOXIC", 98, "Tardes y Noches (19:00 - 23:30)", "76561198000000002", "Datrikk#SUR", "datrikk_cl#1234"},
            {"p-3", "u-3", "Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta temporada.", "Valparaíso (Viña del Mar)", "ZERO_TOXIC", 99, "Fines de semana y noches", "76561198000000003", "dididong#LAS", "dididong#5544"},
            {"p-4", "u-4", "AWPer en CS2. Comunicación clara por Discord, juego limpio y competitivo. Disponible para scrims.", "Biobío (Concepción)", "FRIENDLY", 96, "Noches (22:00 - 02:00)", "76561198000000004", "luchi#AWP", "luchi#8899"},
            {"p-5", "u-5", "Main Duelista en Valorant. Rango Inmortal 2, busco compañeros para torneos 5v5 con micro activo.", "Región Metropolitana (La Florida)", "ZERO_TOXIC", 97, "Tardes (18:00 - 22:30)", "76561198000000005", "jaeliug#CL1", "jaeliug#7711"},
            {"p-6", "u-6", "Toplaner en League of Legends. Buen macrogame, splitpush y rotaciones a objetivos. Cero flameo.", "Región Metropolitana (Maipú)", "FRIENDLY", 95, "Noches (21:30 - 01:30)", "76561198000000006", "pepito#LAS", "pepitogamer67#3322"}
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
        Object[][] msgs = {
            {"msg-1", "u-1", "Jesus_Villasanti", "https://api.dicebear.com/7.x/bottts/svg?seed=Jesus_Villasanti", "global",
             "Bienvenidos a PlayMatch. Todas las partidas, registros y torneos quedan persistidos en base de datos.", "19:00"},
            {"msg-2", "u-2", "Datrikk", "https://api.dicebear.com/7.x/bottts/svg?seed=Datrikk", "scrims-valorant",
             "Buscamos 1 Duelista o Iniciador rango Ascendente+ para scrim hoy a las 21:00.", "19:15"},
            {"msg-3", "u-3", "dididong", "https://api.dicebear.com/7.x/bottts/svg?seed=dididong", "duo-lol",
             "Disponible para ranked Duo en servidor LAS como Support.", "19:22"}
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] m : msgs) {
                ps.setString(1, (String) m[0]);
                ps.setString(2, (String) m[1]);
                ps.setString(3, (String) m[2]);
                ps.setString(4, (String) m[3]);
                ps.setString(5, (String) m[4]);
                ps.setString(6, (String) m[5]);
                ps.setString(7, (String) m[6]);
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

    public List<Usuario> obtenerTodosLosUsuarios(String filtroBusqueda) {
        boolean tieneFiltro = filtroBusqueda != null && !filtroBusqueda.trim().isEmpty();
        String sql = "SELECT id, username, email, password_hash, avatar_url, telefono, role, created_at FROM users " +
                (tieneFiltro ? "WHERE LOWER(username) LIKE ? OR LOWER(email) LIKE ? OR LOWER(telefono) LIKE ? " : "") +
                "ORDER BY created_at DESC, username ASC";
        List<Usuario> lista = new ArrayList<>();
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (tieneFiltro) {
                String pattern = "%" + filtroBusqueda.trim().toLowerCase(Locale.ROOT) + "%";
                ps.setString(1, pattern);
                ps.setString(2, pattern);
                ps.setString(3, pattern);
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

        String sql = "SELECT id, username, email, password_hash, avatar_url, telefono, role, created_at " +
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
        String sql = "SELECT id, username, email, password_hash, avatar_url, telefono, role, created_at " +
                     "FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1";
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
        String avatar = "https://api.dicebear.com/7.x/bottts/svg?seed=" + cleanUser.replace(" ", "_");
        String passwordHash = SeguridadUtil.hashPassword(password);

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                String sqlUser = "INSERT INTO users (id, username, email, password_hash, avatar_url, telefono, role) VALUES (?, ?, ?, ?, ?, ?, 'GAMER')";
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
                String sqlProfile = "INSERT INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag) VALUES (?, ?, ?, 'Chile', 'Región Metropolitana', 'ZERO_TOXIC', 100, 'Noches (21:00 - 01:00)', '', ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sqlProfile)) {
                    ps.setString(1, profileId);
                    ps.setString(2, userId);
                    ps.setString(3, "Nuevo jugador registrado en PlayMatch. Listo para competir.");
                    ps.setString(4, cleanUser + "#CHI");
                    ps.setString(5, cleanUser);
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

        String sql = "UPDATE users SET email = ?, telefono = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = abrirConexion();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nuevoEmail != null && !nuevoEmail.trim().isEmpty() ? nuevoEmail.trim().toLowerCase(Locale.ROOT) : u.getEmail());
            ps.setString(2, nuevoTelefono != null ? nuevoTelefono.trim() : u.getTelefono());
            ps.setString(3, u.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error actualizando datos de cuenta", e);
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
            limpiarTimestamp(rs.getString("created_at"))
        );
    }

    // =========================================================================
    // 3. PERFILES DE JUGADORES Y BUSCADOR LFG
    // =========================================================================

    public List<PerfilJugador> obtenerTodosLosPerfiles() {
        return buscarPerfiles(null, null, null);
    }

    public List<PerfilJugador> buscarPerfiles(String filtroJuego, String filtroToxic, String busqueda) {
        StringBuilder sql = new StringBuilder(
            "SELECT pp.id AS profile_id, u.id AS user_id, u.username, u.avatar_url, " +
            "pp.bio, pp.country, pp.region, pp.toxic_level, pp.reputation_score, pp.preferred_schedule, " +
            "pp.steam_id, pp.riot_id, pp.discord_tag, " +
            "COALESCE(g.code, 'valorant') AS game_code, COALESCE(g.name, 'Valorant') AS game_name, " +
            "COALESCE(pgs.in_game_rank, 'Sin Rango') AS in_game_rank, COALESCE(pgs.preferred_role, 'Flex') AS preferred_role, " +
            "COALESCE(pgs.playstyle, 'COMPETITIVE') AS playstyle " +
            "FROM player_profiles pp " +
            "JOIN users u ON pp.user_id = u.id " +
            "LEFT JOIN player_game_stats pgs ON pgs.user_id = u.id AND pgs.is_primary = 1 " +
            "LEFT JOIN games g ON pgs.game_id = g.id " +
            "WHERE 1=1 "
        );

        List<String> params = new ArrayList<>();
        if (filtroJuego != null && !filtroJuego.trim().isEmpty() && !"all".equalsIgnoreCase(filtroJuego.trim())) {
            sql.append("AND LOWER(g.code) = LOWER(?) ");
            params.add(filtroJuego.trim());
        }
        if (filtroToxic != null && !filtroToxic.trim().isEmpty()) {
            sql.append("AND UPPER(pp.toxic_level) = UPPER(?) ");
            params.add(filtroToxic.trim());
        }
        if (busqueda != null && !busqueda.trim().isEmpty()) {
            sql.append("AND (LOWER(u.username) LIKE ? OR LOWER(pp.region) LIKE ? OR LOWER(pgs.preferred_role) LIKE ? OR LOWER(pgs.in_game_rank) LIKE ?) ");
            String q = "%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%";
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }
        sql.append("ORDER BY pp.reputation_score DESC, u.username ASC");

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

        String sql = "SELECT pp.id AS profile_id, u.id AS user_id, u.username, u.avatar_url, " +
                     "pp.bio, pp.country, pp.region, pp.toxic_level, pp.reputation_score, pp.preferred_schedule, " +
                     "pp.steam_id, pp.riot_id, pp.discord_tag, " +
                     "COALESCE(g.code, 'valorant') AS game_code, COALESCE(g.name, 'Valorant') AS game_name, " +
                     "COALESCE(pgs.in_game_rank, 'Sin Rango') AS in_game_rank, COALESCE(pgs.preferred_role, 'Flex') AS preferred_role, " +
                     "COALESCE(pgs.playstyle, 'COMPETITIVE') AS playstyle " +
                     "FROM player_profiles pp " +
                     "JOIN users u ON pp.user_id = u.id " +
                     "LEFT JOIN player_game_stats pgs ON pgs.user_id = u.id AND pgs.is_primary = 1 " +
                     "LEFT JOIN games g ON pgs.game_id = g.id " +
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

    public synchronized void actualizarPerfil(PerfilJugador actualizado) {
        Usuario u = buscarUsuarioPorUsername(actualizado.getUsername());
        if (u == null) return;

        try (Connection conn = abrirConexion()) {
            conn.setAutoCommit(false);
            try {
                String sqlProf = "UPDATE player_profiles SET bio = ?, region = ?, preferred_schedule = ?, " +
                                 "steam_id = ?, riot_id = ?, discord_tag = ?, toxic_level = COALESCE(?, toxic_level) " +
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
                    ps.setString(8, u.getId());
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

    private PerfilJugador mapearPerfil(ResultSet rs) throws SQLException {
        return new PerfilJugador(
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
        String sqlTeams = "SELECT tm.name FROM tournament_registrations tr JOIN teams tm ON tr.team_id = tm.id " +
                          "WHERE tr.tournament_id = ? ORDER BY tr.seed_number ASC, tr.registered_at ASC";
        try (PreparedStatement ps = conn.prepareStatement(sqlTeams)) {
            ps.setString(1, t.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    t.getEquiposInscritos().add(rs.getString("name"));
                }
            }
        }

        String sqlMatches = "SELECT id, round_name, match_order, team1_name, team2_name, score_team1, score_team2, winner_name, status " +
                            "FROM tournament_matches WHERE tournament_id = ? ORDER BY match_order ASC";
        try (PreparedStatement ps = conn.prepareStatement(sqlMatches)) {
            ps.setString(1, t.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    t.getPartidas().add(new PartidaTorneo(
                        rs.getString("id"),
                        rs.getString("round_name"),
                        rs.getInt("match_order"),
                        rs.getString("team1_name"),
                        rs.getString("team2_name"),
                        rs.getInt("score_team1"),
                        rs.getInt("score_team2"),
                        rs.getString("winner_name"),
                        rs.getString("status")
                    ));
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
        String sql = "SELECT id, sender_username, sender_avatar, channel, content, sent_at FROM chat_messages " +
                     (filtrarCanal ? "WHERE LOWER(channel) = LOWER(?) " : "") +
                     "ORDER BY created_at ASC LIMIT 120";
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
    // 6. SOLICITUDES DE EMPAREJAMIENTO (MATCH REQUESTS)
    // =========================================================================

    public synchronized boolean agregarSolicitudEmparejamiento(SolicitudEmparejamiento req) {
        Usuario sender = buscarUsuarioPorUsername(req.getRemitenteUsername());
        Usuario receiver = buscarUsuarioPorUsername(req.getDestinatarioUsername());
        if (sender == null || receiver == null) return false;

        try (Connection conn = abrirConexion()) {
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
        } catch (SQLException e) {
            throw new RuntimeException("Error consultando estadísticas de base de datos", e);
        }
        return stats;
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
