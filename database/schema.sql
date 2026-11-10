-- =====================================================================
-- PLAYMATCH DATABASE SCHEMA (SQLite / PostgreSQL / MySQL Compatible)
-- Plataforma Web de Emparejamiento Competitivo y Torneos para Gamers
-- Modelo Relacional en Tercera Forma Normal (3NF)
-- =====================================================================

-- 1. TABLA DE USUARIOS REGISTRADOS Y CREDENCIALES
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(255),
    telefono VARCHAR(30) NOT NULL DEFAULT '',
    role VARCHAR(20) NOT NULL DEFAULT 'GAMER', -- 'GAMER', 'ORGANIZER', 'ADMIN'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. TABLA DE PERFILES DE JUGADOR Y CONDUCTA
CREATE TABLE IF NOT EXISTS player_profiles (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL UNIQUE,
    bio TEXT,
    country VARCHAR(50) DEFAULT 'Chile',
    region VARCHAR(100) DEFAULT 'Región Metropolitana',
    toxic_level VARCHAR(20) DEFAULT 'ZERO_TOXIC', -- 'ZERO_TOXIC', 'FRIENDLY', 'COMPETITIVE'
    reputation_score INT DEFAULT 100,
    preferred_schedule VARCHAR(60) DEFAULT 'Noches (21:00 - 01:00)',
    steam_id VARCHAR(100) DEFAULT '',
    riot_id VARCHAR(100) DEFAULT '',
    epic_id VARCHAR(100) DEFAULT '',
    discord_tag VARCHAR(100) DEFAULT '',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. CATÁLOGO DE JUEGOS DISPONIBLES
CREATE TABLE IF NOT EXISTS games (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE, -- 'valorant', 'lol', 'cs2', 'rocket-league', etc.
    name VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(50) NOT NULL,    -- 'Tactical FPS', 'MOBA', 'Battle Royale', 'Deportes'
    platform VARCHAR(60) DEFAULT 'PC / Multiplataforma',
    max_team_size INT DEFAULT 5,
    description TEXT,
    accent_color VARCHAR(20) DEFAULT '#8b5cf6',
    banner_url VARCHAR(255),
    is_active INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. ESTADÍSTICAS Y RANGOS DE JUGADOR POR JUEGO
CREATE TABLE IF NOT EXISTS player_game_stats (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    game_id VARCHAR(36) NOT NULL,
    in_game_rank VARCHAR(60) NOT NULL,  -- ej. 'Inmortal 1', 'Diamante IV'
    preferred_role VARCHAR(60) NOT NULL,-- ej. 'Controlador', 'Support'
    playstyle VARCHAR(30) DEFAULT 'COMPETITIVE', -- 'CASUAL', 'COMPETITIVE', 'TRYHARD'
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
    UNIQUE(user_id, game_id)
);

-- 5. SOLICITUDES DE EMPAREJAMIENTO (MATCHMAKING / LFG)
CREATE TABLE IF NOT EXISTS match_requests (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(36) NOT NULL,
    receiver_id VARCHAR(36) NOT NULL,
    game_id VARCHAR(36) NOT NULL,
    message VARCHAR(255),
    status VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING', 'ACCEPTED', 'REJECTED'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE
);

-- 6. GESTIÓN DE TORNEOS COMPETITIVOS
CREATE TABLE IF NOT EXISTS tournaments (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    game_id VARCHAR(36) NOT NULL,
    organizer_id VARCHAR(36) NOT NULL,
    organizer_name VARCHAR(100) DEFAULT 'Andes Esports',
    prize_pool VARCHAR(120),
    format VARCHAR(50) DEFAULT 'SINGLE_ELIMINATION',
    max_teams INT DEFAULT 8,
    start_date VARCHAR(60),
    status VARCHAR(30) DEFAULT 'OPEN_REGISTRATION', -- 'OPEN_REGISTRATION', 'IN_PROGRESS', 'COMPLETED'
    rules TEXT,
    banner_url VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
    FOREIGN KEY (organizer_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 7. EQUIPOS REGISTRADOS
CREATE TABLE IF NOT EXISTS teams (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tag VARCHAR(15) NOT NULL,
    captain_id VARCHAR(36) NOT NULL,
    logo_url VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (captain_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 8. INSCRIPCIONES EN TORNEOS
CREATE TABLE IF NOT EXISTS tournament_registrations (
    id VARCHAR(36) PRIMARY KEY,
    tournament_id VARCHAR(36) NOT NULL,
    team_id VARCHAR(36) NOT NULL,
    seed_number INT DEFAULT 1,
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE,
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    UNIQUE(tournament_id, team_id)
);

-- 9. LLAVES DE ENFRENTAMIENTOS (BRACKET MATCHES)
CREATE TABLE IF NOT EXISTS tournament_matches (
    id VARCHAR(36) PRIMARY KEY,
    tournament_id VARCHAR(36) NOT NULL,
    round_name VARCHAR(50) NOT NULL,
    match_order INT NOT NULL,
    team1_name VARCHAR(100),
    team2_name VARCHAR(100),
    score_team1 INT DEFAULT 0,
    score_team2 INT DEFAULT 0,
    winner_name VARCHAR(100),
    status VARCHAR(20) DEFAULT 'SCHEDULED', -- 'SCHEDULED', 'IN_PROGRESS', 'FINISHED'
    FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE
);

-- 10. CHAT DE COORDINACIÓN POR CANALES
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(36),
    sender_username VARCHAR(60) NOT NULL,
    sender_avatar VARCHAR(255),
    channel VARCHAR(50) NOT NULL DEFAULT 'global',
    content TEXT NOT NULL,
    sent_at VARCHAR(30) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL
);

-- ÍNDICES PARA OPTIMIZACIÓN DE CONSULTAS
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_games_code ON games(code);
CREATE INDEX IF NOT EXISTS idx_chat_channel ON chat_messages(channel);
CREATE INDEX IF NOT EXISTS idx_match_receiver ON match_requests(receiver_id);
CREATE INDEX IF NOT EXISTS idx_tournaments_game ON tournaments(game_id);
