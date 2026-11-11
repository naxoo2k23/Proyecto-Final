-- =====================================================================
-- PLAYMATCH DATABASE SCHEMA (PostgreSQL 14+ / SQLite 3 Compatible)
-- Plataforma Web de Emparejamiento Competitivo y Torneos para Gamers
-- Modelo Relacional en Tercera Forma Normal (3NF)
-- =====================================================================

-- 1. TABLA DE USUARIOS REGISTRADOS Y CREDENCIALES
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    avatar_url TEXT,
    telefono VARCHAR(30) NOT NULL DEFAULT '',
    phone_verified INT NOT NULL DEFAULT 0,
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
    discord_id VARCHAR(40) DEFAULT '',
    discord_connected INT NOT NULL DEFAULT 0,
    favorite_games VARCHAR(255) DEFAULT '',
    favorite_genres VARCHAR(255) DEFAULT '',
    play_preferences VARCHAR(255) DEFAULT '',
    star_rating DOUBLE PRECISION DEFAULT 4.8,
    rating_count INT NOT NULL DEFAULT 1,
    card_frame VARCHAR(30) NOT NULL DEFAULT 'NONE',
    equipped_title VARCHAR(60) NOT NULL DEFAULT '',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 2B. TABLA DE VALORACIONES POR ESTRELLAS ENTRE JUGADORES (1 A 5 ESTRELLAS)
CREATE TABLE IF NOT EXISTS player_ratings (
    id VARCHAR(36) PRIMARY KEY,
    rater_user_id VARCHAR(36) NOT NULL,
    rated_user_id VARCHAR(36) NOT NULL,
    stars INT NOT NULL CHECK (stars >= 1 AND stars <= 5),
    comment VARCHAR(255) DEFAULT '',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rater_user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (rated_user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(rater_user_id, rated_user_id)
);

-- 3. CATÁLOGO DE JUEGOS DISPONIBLES
CREATE TABLE IF NOT EXISTS games (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE, -- 'valorant', 'lol', 'cs2', 'rocket-league', etc.
    name VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(50) NOT NULL,
    platform VARCHAR(60) DEFAULT 'PC / Multiplataforma',
    max_team_size INT DEFAULT 5,
    description TEXT,
    accent_color VARCHAR(20) DEFAULT '#8b5cf6',
    banner_url VARCHAR(255),
    is_active INT DEFAULT 1,
    sort_order INT DEFAULT 100,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. ESTADÍSTICAS Y RANGOS DE JUGADOR POR JUEGO
CREATE TABLE IF NOT EXISTS player_game_stats (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    game_id VARCHAR(36) NOT NULL,
    in_game_rank VARCHAR(60) NOT NULL,
    preferred_role VARCHAR(60) NOT NULL,
    playstyle VARCHAR(30) DEFAULT 'COMPETITIVE',
    is_primary INT DEFAULT 1,
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
    status VARCHAR(20) DEFAULT 'PENDING',
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
    status VARCHAR(30) DEFAULT 'OPEN_REGISTRATION',
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
    checked_in INT NOT NULL DEFAULT 0,
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
    round_index INT NOT NULL DEFAULT 1,
    match_order INT NOT NULL,
    next_match_order INT DEFAULT 0,
    next_slot INT DEFAULT 0,
    team1_name VARCHAR(100),
    team2_name VARCHAR(100),
    score_team1 INT DEFAULT 0,
    score_team2 INT DEFAULT 0,
    winner_name VARCHAR(100),
    status VARCHAR(20) DEFAULT 'SCHEDULED',
    FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE
);

-- 10. CHAT DE COORDINACIÓN POR CANALES
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(36),
    sender_username VARCHAR(60) NOT NULL,
    sender_avatar TEXT,
    channel VARCHAR(50) NOT NULL DEFAULT 'global',
    content TEXT NOT NULL,
    sent_at VARCHAR(30) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL
);

-- 11. AMISTADES ENTRE JUGADORES
CREATE TABLE IF NOT EXISTS friendships (
    id VARCHAR(36) PRIMARY KEY,
    requester_id VARCHAR(36) NOT NULL,
    addressee_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'ACCEPTED', 'REJECTED'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (addressee_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(requester_id, addressee_id)
);

-- 12. MENSAJES DIRECTOS 1 A 1 ENTRE AMIGOS (ESTILO IG)
CREATE TABLE IF NOT EXISTS direct_messages (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(36) NOT NULL,
    receiver_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    msg_type VARCHAR(20) NOT NULL DEFAULT 'TEXT', -- 'TEXT', 'IMAGE', 'LOBBY_INVITE'
    attachment_url TEXT,
    meta_json TEXT,
    is_read INT NOT NULL DEFAULT 0,
    sent_at VARCHAR(30) NOT NULL,
    created_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 13. LOBBIES / SALAS DE ESCUADRA CON CUPOS EN VIVO
CREATE TABLE IF NOT EXISTS squad_lobbies (
    id VARCHAR(36) PRIMARY KEY,
    host_user_id VARCHAR(36) NOT NULL,
    game_id VARCHAR(36) NOT NULL,
    title VARCHAR(140) NOT NULL,
    mode VARCHAR(40) NOT NULL DEFAULT 'Escuadra',
    max_slots INT NOT NULL DEFAULT 5,
    roles_needed VARCHAR(150) DEFAULT '',
    schedule_label VARCHAR(80) DEFAULT 'Ahora',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- 'OPEN', 'FULL', 'CLOSED'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS squad_lobby_members (
    id VARCHAR(36) PRIMARY KEY,
    lobby_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role_note VARCHAR(60) DEFAULT '',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (lobby_id) REFERENCES squad_lobbies(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(lobby_id, user_id)
);

-- 14. MODERACIÓN: BLOQUEOS Y REPORTES DE JUGADORES
CREATE TABLE IF NOT EXISTS user_blocks (
    id VARCHAR(36) PRIMARY KEY,
    blocker_id VARCHAR(36) NOT NULL,
    blocked_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (blocker_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(blocker_id, blocked_id)
);

CREATE TABLE IF NOT EXISTS player_reports (
    id VARCHAR(36) PRIMARY KEY,
    reporter_id VARCHAR(36) NOT NULL,
    reported_id VARCHAR(36) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    details VARCHAR(300) DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (reported_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ÍNDICES PARA OPTIMIZACIÓN DE CONSULTAS
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_games_code ON games(code);
CREATE INDEX IF NOT EXISTS idx_chat_channel ON chat_messages(channel);
CREATE INDEX IF NOT EXISTS idx_match_receiver ON match_requests(receiver_id);
CREATE INDEX IF NOT EXISTS idx_tournaments_game ON tournaments(game_id);
CREATE INDEX IF NOT EXISTS idx_ratings_rated ON player_ratings(rated_user_id);
CREATE INDEX IF NOT EXISTS idx_friendships_req ON friendships(requester_id);
CREATE INDEX IF NOT EXISTS idx_friendships_addr ON friendships(addressee_id);
CREATE INDEX IF NOT EXISTS idx_dm_pair ON direct_messages(sender_id, receiver_id);
CREATE INDEX IF NOT EXISTS idx_dm_receiver_read ON direct_messages(receiver_id, is_read);
CREATE INDEX IF NOT EXISTS idx_lobbies_status ON squad_lobbies(status);
CREATE INDEX IF NOT EXISTS idx_blocks_pair ON user_blocks(blocker_id, blocked_id);

-- 15. CLIPS Y HIGHLIGHTS DE JUGADORES
CREATE TABLE IF NOT EXISTS player_clips (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    title VARCHAR(120) NOT NULL,
    clip_url TEXT NOT NULL,
    game_code VARCHAR(40) NOT NULL DEFAULT 'valorant',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_clips_user ON player_clips(user_id);
