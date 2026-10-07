-- =====================================================================
-- PLAYMATCH SEED DATA (FASE 2)
-- Datos de prueba para simular comunidad gamer chilena y torneos
-- =====================================================================

-- 1. JUEGOS
INSERT INTO games (id, code, name, category, max_team_size, banner_url) VALUES
('g-val', 'valorant', 'Valorant', 'Tactical FPS', 5, 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80'),
('g-lol', 'lol', 'League of Legends', 'MOBA', 5, 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80'),
('g-cs2', 'cs2', 'Counter-Strike 2', 'Tactical FPS', 5, 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80');

-- 2. USUARIOS INICIALES (Contraseñas con hash seguro simulado)
INSERT INTO users (id, username, email, password_hash, role, telefono) VALUES
('u-1', 'Jesus_Villasanti', 'jesus.villasanti@playmatch.cl', '$2a$10$e8Zz294.k119x9f8...hash', 'ADMIN', '+56 9 8123 4567'),
('u-2', 'Datrikk', 'datrikk@playmatch.cl', '$2a$10$abc81726...hash', 'GAMER', '+56 9 7234 5678'),
('u-3', 'dididong', 'dididong@playmatch.cl', '$2a$10$xyz99823...hash', 'GAMER', '+56 9 6345 6789'),
('u-4', 'luchi', 'luchi@playmatch.cl', '$2a$10$jkl33441...hash', 'GAMER', '+56 9 5456 7890'),
('u-5', 'jaeliug', 'jaeliug@playmatch.cl', '$2a$10$mmm12389...hash', 'GAMER', '+56 9 4567 8901'),
('u-6', 'pepitogamer67', 'pepitogamer67@playmatch.cl', '$2a$10$nnn89412...hash', 'GAMER', '+56 9 3678 9012'),
('u-7', 'AndesEsports', 'contacto@andesesports.cl', '$2a$10$ooo98172...hash', 'ORGANIZER', '+56 9 2111 2222');

-- 3. PERFILES DE JUGADORES (Zero Toxic & Plataformas)
INSERT INTO player_profiles (id, user_id, bio, country, region, toxic_level, reputation_score, preferred_schedule, steam_id, riot_id, discord_tag) VALUES
('p-1', 'u-1', 'Main Controller en Valorant. Busco escuadra seria para competir en torneos comunitarios y subir elo.', 'Chile', 'Región Metropolitana (San Joaquín)', 'ZERO_TOXIC', 100, 'Noches (21:00 - 01:00)', '76561198000000001', 'Jesus#CHI', 'jesus_villasanti#0001'),
('p-2', 'u-2', 'Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Excelente sinergia de equipo.', 'Chile', 'Los Lagos (Castro)', 'ZERO_TOXIC', 98, 'Tardes y Noches (19:00 - 23:30)', '76561198000000002', 'Datrikk#SUR', 'datrikk_cl#1234'),
('p-3', 'u-3', 'Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta temporada.', 'Chile', 'Valparaíso (Viña del Mar)', 'ZERO_TOXIC', 99, 'Fines de semana y noches', '76561198000000003', 'dididong#LAS', 'dididong#5544'),
('p-4', 'u-4', 'AWPer en CS2. Comunicación clara por Discord, juego limpio y competitivo. Disponible para scrims.', 'Chile', 'Biobío (Concepción)', 'FRIENDLY', 96, 'Noches (22:00 - 02:00)', '76561198000000004', 'luchi#AWP', 'luchi#8899'),
('p-5', 'u-5', 'Main Duelista en Valorant. Rango Inmortal 2, busco compañeros para torneos 5v5 con micro activo.', 'Chile', 'Región Metropolitana (La Florida)', 'ZERO_TOXIC', 97, 'Tardes (18:00 - 22:30)', '76561198000000005', 'jaeliug#CL1', 'jaeliug#7711'),
('p-6', 'u-6', 'Toplaner en League of Legends. Buen macrogame, splitpush y rotaciones a objetivos. Cero flameo.', 'Chile', 'Región Metropolitana (Maipú)', 'FRIENDLY', 95, 'Noches (21:30 - 01:30)', '76561198000000006', 'pepito#LAS', 'pepitogamer67#3322');

-- 4. ESTADÍSTICAS POR JUEGO
INSERT INTO player_game_stats (id, user_id, game_id, in_game_rank, preferred_role, playstyle) VALUES
('s-1', 'u-1', 'g-val', 'Inmortal 1', 'Controlador (Omen / Viper)', 'TRYHARD'),
('s-2', 'u-2', 'g-val', 'Ascendente 2', 'Iniciador (Fade / Sova)', 'COMPETITIVE'),
('s-3', 'u-3', 'g-lol', 'Diamante IV', 'Support (Thresh / Lulu)', 'COMPETITIVE'),
('s-4', 'u-4', 'g-cs2', 'Nivel 8 Faceit / 16.500 ELO', 'Sniper / Entry', 'TRYHARD'),
('s-5', 'u-5', 'g-val', 'Inmortal 2', 'Duelista (Jett / Reyna)', 'COMPETITIVE'),
('s-6', 'u-6', 'g-lol', 'Platino I', 'Toplaner (Aatrox / Jax)', 'COMPETITIVE');

-- 5. TORNEOS
INSERT INTO tournaments (id, title, game_id, organizer_id, prize_pool, format, max_teams, start_date, status, rules) VALUES
('t-1', 'Copa Universitaria Valorant Santiago 2026', 'g-val', 'u-7', '$200.000 CLP + Skins Riot', 'SINGLE_ELIMINATION', 8, '2026-11-15 18:00:00', 'OPEN_REGISTRATION', 'Torneo 5v5 al mejor de 1 (Bo1). Final Bo3. Tolerancia 10 minutos. Anticheat obligatorio.'),
('t-2', 'Liga Amateur League of Legends Chile - Season 1', 'g-lol', 'u-7', '$150.000 CLP', 'SINGLE_ELIMINATION', 8, '2026-11-20 19:00:00', 'OPEN_REGISTRATION', 'Modo torneo reclutamiento. Servidor LAS. Prohibido conducta antideportiva.'),
('t-3', 'Master Series CS2 - Red Bull Cup', 'g-cs2', 'u-7', '$300.000 CLP + Periféricos', 'SINGLE_ELIMINATION', 4, '2026-11-25 17:00:00', 'IN_PROGRESS', 'Servidores 128 tickrate en Santiago. Eliminación directa.');

-- 6. EQUIPOS REGISTRADOS
INSERT INTO teams (id, name, tag, captain_id, logo_url) VALUES
('tm-1', 'Andes Raptors', 'RAP', 'u-1', 'https://api.dicebear.com/7.x/identicon/svg?seed=RAP'),
('tm-2', 'Cordillera Clan', 'COR', 'u-2', 'https://api.dicebear.com/7.x/identicon/svg?seed=COR'),
('tm-3', 'Valpo Storm', 'VLP', 'u-3', 'https://api.dicebear.com/7.x/identicon/svg?seed=VLP'),
('tm-4', 'Bío Bío Phoenix', 'BBP', 'u-4', 'https://api.dicebear.com/7.x/identicon/svg?seed=BBP');

-- 7. BRACKETS DEL TORNEO CS2 EN PROGRESO
INSERT INTO tournament_matches (id, tournament_id, round_name, match_order, team1_id, team2_id, score_team1, score_team2, winner_team_id, status) VALUES
('m-1', 't-3', 'Semifinal 1', 1, 'tm-1', 'tm-2', 13, 9, 'tm-1', 'FINISHED'),
('m-2', 't-3', 'Semifinal 2', 2, 'tm-3', 'tm-4', 11, 13, 'tm-4', 'FINISHED'),
('m-3', 't-3', 'Gran Final', 3, 'tm-1', 'tm-4', 0, 0, NULL, 'SCHEDULED');
