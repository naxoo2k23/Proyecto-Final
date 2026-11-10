-- =====================================================================
-- PLAYMATCH SEED DATA (SQLite / PostgreSQL Reference)
-- Poblado inicial de juegos disponibles, usuarios reales y torneos
-- =====================================================================

-- 1. JUEGOS DISPONIBLES
INSERT OR IGNORE INTO games (id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active) VALUES
('g-val', 'valorant', 'Valorant', 'Tactical FPS', 'PC / Consola', 5, 'Shooter táctico 5v5 basado en precisión de disparo y habilidades de agentes.', '#ff4655', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80', 1),
('g-lol', 'lol', 'League of Legends', 'MOBA', 'PC (LAS)', 5, 'Arena de batalla multijugador por equipos estratégicos 5v5 en la Grieta del Invocador.', '#8b5cf6', 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80', 1),
('g-cs2', 'cs2', 'Counter-Strike 2', 'Tactical FPS', 'PC (Steam)', 5, 'Competitivo clásico de ataque y defensa táctica con servidores de alta precisión.', '#f59e0b', 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80', 1),
('g-rl', 'rocket-league', 'Rocket League', 'Deportes / Arcade', 'Multiplataforma', 3, 'Fútbol vehicular de alta velocidad 3v3 competitivo con juego aéreo y rotaciones.', '#06b6d4', 'https://images.unsplash.com/photo-1538481199705-c710c4e965fc?auto=format&fit=crop&w=800&q=80', 1);

-- 2. USUARIOS INICIALES (Las contraseñas en runtime usan PBKDF2WithHmacSHA256)
INSERT OR IGNORE INTO users (id, username, email, password_hash, role, telefono) VALUES
('u-1', 'Jesus_Villasanti', 'jesus.villasanti@playmatch.cl', 'pbkdf2$seed_placeholder', 'ADMIN', '+56 9 8123 4567'),
('u-2', 'Datrikk', 'datrikk@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 7234 5678'),
('u-3', 'dididong', 'dididong@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 6345 6789'),
('u-4', 'luchi', 'luchi@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 5456 7890'),
('u-5', 'jaeliug', 'jaeliug@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 4567 8901'),
('u-6', 'pepitogamer67', 'pepitogamer67@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 3678 9012'),
('u-7', 'AndesEsports', 'contacto@andesesports.cl', 'pbkdf2$seed_placeholder', 'ORGANIZER', '+56 9 2111 2222');

-- 3. PERFILES DE JUGADORES
INSERT OR IGNORE INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag) VALUES
('p-1', 'u-1', 'Main Controller en Valorant. Busco escuadra seria para competir en torneos comunitarios y subir elo.', 'Chile', 'Región Metropolitana (San Joaquín)', 'ZERO_TOXIC', 100, 'Noches (21:00 - 01:00)', '76561198000000001', 'Jesus#CHI', 'jesus_villasanti#0001'),
('p-2', 'u-2', 'Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Excelente sinergia de equipo.', 'Chile', 'Los Lagos (Castro)', 'ZERO_TOXIC', 98, 'Tardes y Noches (19:00 - 23:30)', '76561198000000002', 'Datrikk#SUR', 'datrikk_cl#1234'),
('p-3', 'u-3', 'Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta temporada.', 'Chile', 'Valparaíso (Viña del Mar)', 'ZERO_TOXIC', 99, 'Fines de semana y noches', '76561198000000003', 'dididong#LAS', 'dididong#5544'),
('p-4', 'u-4', 'AWPer en CS2. Comunicación clara por Discord, juego limpio y competitivo. Disponible para scrims.', 'Chile', 'Biobío (Concepción)', 'FRIENDLY', 96, 'Noches (22:00 - 02:00)', '76561198000000004', 'luchi#AWP', 'luchi#8899'),
('p-5', 'u-5', 'Main Duelista en Valorant. Rango Inmortal 2, busco compañeros para torneos 5v5 con micro activo.', 'Chile', 'Región Metropolitana (La Florida)', 'ZERO_TOXIC', 97, 'Tardes (18:00 - 22:30)', '76561198000000005', 'jaeliug#CL1', 'jaeliug#7711'),
('p-6', 'u-6', 'Toplaner en League of Legends. Buen macrogame, splitpush y rotaciones a objetivos. Cero flameo.', 'Chile', 'Región Metropolitana (Maipú)', 'FRIENDLY', 95, 'Noches (21:30 - 01:30)', '76561198000000006', 'pepito#LAS', 'pepitogamer67#3322');
