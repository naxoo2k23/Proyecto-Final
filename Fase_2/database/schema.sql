-- =====================================================================
-- PLAYMATCH DATABASE SCHEMA (FASE 2)
-- Plataforma Web de Emparejamiento Social y Gestión de Torneos para Gamers
-- Diseñado para MySQL / PostgreSQL / SQLite (Relacional y escalable)
-- =====================================================================

-- 1. TABLA DE USUARIOS Y AUTENTICACIÓN
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(255),
    role VARCHAR(20) DEFAULT 'GAMER', -- 'GAMER', 'ORGANIZER', 'ADMIN'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. TABLA DE PERFIL DE JUGADOR Y REPUTACIÓN
CREATE TABLE IF NOT EXISTS player_profiles (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL UNIQUE,
    bio TEXT,
    country VARCHAR(50) DEFAULT 'Chile',
    region VARCHAR(100) DEFAULT 'Región Metropolitana',
    toxic_level VARCHAR(20) DEFAULT 'ZERO_TOXIC', -- 'ZERO_TOXIC', 'FRIENDLY', 'COMPETITIVE'
    reputation_score INT DEFAULT 100,
    preferred_schedule VARCHAR(50) DEFAULT 'Noches (20:00 - 01:00)',
    steam_id VARCHAR(100),
    riot_id VARCHAR(100),
    epic_id VARCHAR(100),
    discord_tag VARCHAR(100),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. JUEGOS SOPORTADOS
CREATE TABLE IF NOT EXISTS games (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE, -- 'valorant', 'lol', 'cs2'
    name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,    -- 'FPS', 'MOBA'
    max_team_size INT DEFAULT 5,
    banner_url VARCHAR(255)
);

-- 4. PERFIL DE JUGADOR POR JUEGO (Rangos y roles)
CREATE TABLE IF NOT EXISTS player_game_stats (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    game_id VARCHAR(36) NOT NULL,
    in_game_rank VARCHAR(50) NOT NULL,  -- ej. 'Diamante II', 'Oro IV'
    preferred_role VARCHAR(50) NOT NULL,-- ej. 'Duelista / Initiator', 'Mid / Jungle'
    playstyle VARCHAR(30) DEFAULT 'COMPETITIVE', -- 'CASUAL', 'COMPETITIVE', 'TRYHARD'
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
    UNIQUE(user_id, game_id)
);

-- 5. MATCHMAKING SOCIAL / LOOKING FOR GROUP (LFG)
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
    prize_pool VARCHAR(100),            -- ej. '$150.000 CLP + Skins'
    format VARCHAR(50) DEFAULT 'SINGLE_ELIMINATION', -- 'SINGLE_ELIMINATION', 'ROUND_ROBIN'
    max_teams INT DEFAULT 8,
    start_date TIMESTAMP,
    status VARCHAR(30) DEFAULT 'OPEN_REGISTRATION', -- 'OPEN_REGISTRATION', 'IN_PROGRESS', 'COMPLETED'
    rules TEXT,
    banner_url VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (organizer_id) REFERENCES users(id)
);

-- 7. EQUIPOS DE TORNEO
CREATE TABLE IF NOT EXISTS teams (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tag VARCHAR(10) NOT NULL,
    captain_id VARCHAR(36) NOT NULL,
    logo_url VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (captain_id) REFERENCES users(id)
);

-- 8. INSCRIPCIONES EN TORNEOS
CREATE TABLE IF NOT EXISTS tournament_registrations (
    id VARCHAR(36) PRIMARY KEY,
    tournament_id VARCHAR(36) NOT NULL,
    team_id VARCHAR(36) NOT NULL,
    seed_number INT,
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE,
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    UNIQUE(tournament_id, team_id)
);

-- 9. LLAVES DE ENFRENTAMIENTOS (BRACKET MATCHES)
CREATE TABLE IF NOT EXISTS tournament_matches (
    id VARCHAR(36) PRIMARY KEY,
    tournament_id VARCHAR(36) NOT NULL,
    round_name VARCHAR(50) NOT NULL,    -- 'Cuartos de Final', 'Semifinal', 'Gran Final'
    match_order INT NOT NULL,
    team1_id VARCHAR(36),
    team2_id VARCHAR(36),
    score_team1 INT DEFAULT 0,
    score_team2 INT DEFAULT 0,
    winner_team_id VARCHAR(36),
    status VARCHAR(20) DEFAULT 'SCHEDULED', -- 'SCHEDULED', 'IN_PROGRESS', 'FINISHED'
    FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE,
    FOREIGN KEY (team1_id) REFERENCES teams(id),
    FOREIGN KEY (team2_id) REFERENCES teams(id),
    FOREIGN KEY (winner_team_id) REFERENCES teams(id)
);

-- 10. CHAT INTERNO (COORDINACIÓN DE PARTIDAS Y COMUNIDAD)
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(36) NOT NULL,
    receiver_id VARCHAR(36),            -- NULL si es canal público del torneo
    tournament_id VARCHAR(36),          -- Opcional si es chat del torneo
    content TEXT NOT NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE
);
