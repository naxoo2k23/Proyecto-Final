-- =====================================================================
-- PLAYMATCH SEED DATA (PostgreSQL / SQLite Portable Reference)
-- Poblado inicial de juegos disponibles, usuarios reales y torneos
-- =====================================================================

-- 1. JUEGOS DISPONIBLES (10 títulos iniciales)
INSERT INTO games (id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active, sort_order) VALUES
('g-val', 'valorant', 'Valorant', 'Tactical FPS', 'PC / Consola', 5, 'Shooter táctico 5v5 basado en precisión de disparo y habilidades de agentes.', '#ff4655', '/assets/games/valorant.jpg', 1, 1),
('g-lol', 'lol', 'League of Legends', 'MOBA', 'PC (LAS)', 5, 'Arena de batalla multijugador por equipos estratégicos 5v5 en la Grieta del Invocador.', '#8b5cf6', '/assets/games/lol.jpg', 1, 2),
('g-cs2', 'cs2', 'Counter-Strike 2', 'Tactical FPS', 'PC (Steam)', 5, 'Competitivo clásico de ataque y defensa táctica con servidores de alta precisión.', '#f59e0b', '/assets/games/cs2.jpg', 1, 3),
('g-rl', 'rocket-league', 'Rocket League', 'Deportes / Arcade', 'Multiplataforma', 3, 'Fútbol vehicular de alta velocidad 3v3 competitivo con juego aéreo y rotaciones.', '#06b6d4', '/assets/games/rocket-league.jpg', 1, 4),
('g-mr', 'marvel-rivals', 'Marvel Rivals', 'Hero Shooter', 'PC / Consola', 6, 'Hero shooter competitivo 6v6 por equipos con sinergias de habilidades entre superhéroes y villanos.', '#e11d48', '/assets/games/marvel-rivals.jpg', 1, 5),
('g-ow', 'overwatch', 'Overwatch 2', 'Hero Shooter', 'Multiplataforma', 5, 'Combate por objetivos 5v5 con roles de Tanque, Daño y Apoyo enfocado en coordinación de equipo.', '#f97316', '/assets/games/overwatch.jpg', 1, 6),
('g-apex', 'apex-legends', 'Apex Legends', 'Battle Royale', 'Multiplataforma', 3, 'Battle Royale táctico en tríos y dúos con leyendas de movilidad rápida y juego en escuadra.', '#dc2626', '/assets/games/apex-legends.jpg', 1, 7),
('g-fn', 'fortnite', 'Fortnite', 'Battle Royale', 'Multiplataforma', 4, 'Dúos, tríos y escuadrones competitivos en Batalla Campal, Cero Construcción y torneos Arena.', '#3b82f6', '/assets/games/fortnite.jpg', 1, 8),
('g-rbx', 'roblox', 'Roblox', 'Sandbox / Multijuego', 'Multiplataforma', 4, 'Plataforma social multijuego para coordinar partidas competitivas, experiencias y salas privadas.', '#0ea5e9', '/assets/games/roblox.jpg', 1, 9),
('g-mc', 'minecraft', 'Minecraft', 'Supervivencia / PvP', 'Multiplataforma', 4, 'Supervivencia cooperativa, Realms, SkyWars, BedWars y servidores competitivos en equipo.', '#22c55e', '/assets/games/minecraft.jpg', 1, 10)
ON CONFLICT DO NOTHING;

-- 2. USUARIOS INICIALES (Las contraseñas en runtime usan PBKDF2WithHmacSHA256)
INSERT INTO users (id, username, email, password_hash, role, telefono) VALUES
('u-1', 'Jesus_Villasanti', 'jesus.villasanti@playmatch.cl', 'pbkdf2$seed_placeholder', 'ADMIN', '+56 9 8123 4567'),
('u-2', 'Datrikk', 'datrikk@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 7234 5678'),
('u-3', 'dididong', 'dididong@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 6345 6789'),
('u-4', 'luchi', 'luchi@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 5456 7890'),
('u-5', 'jaeliug', 'jaeliug@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 4567 8901'),
('u-6', 'pepitogamer67', 'pepitogamer67@playmatch.cl', 'pbkdf2$seed_placeholder', 'GAMER', '+56 9 3678 9012'),
('u-7', 'AndesEsports', 'contacto@andesesports.cl', 'pbkdf2$seed_placeholder', 'ORGANIZER', '+56 9 2111 2222')
ON CONFLICT DO NOTHING;

-- 3. MENSAJES INICIALES EN LOS 3 APARTADOS DE CHAT GENERAL + SALAS POR JUEGO
INSERT INTO chat_messages (id, sender_id, sender_username, sender_avatar, channel, content, sent_at) VALUES
('msg-1', 'u-1', 'Jesus_Villasanti', '/api/avatar?u=Jesus_Villasanti', 'global', 'Bienvenidos a PlayMatch. Todas las partidas, registros y torneos quedan persistidos en base de datos.', '19:00'),
('msg-4', 'u-5', 'jaeliug', '/api/avatar?u=jaeliug', 'general-duo', '¡Chat abierto para encontrar dúo! Deja tu juego (Valorant, Marvel Rivals, Fortnite, Apex, etc.), rango y horario.', '19:08'),
('msg-5', 'u-2', 'Datrikk', '/api/avatar?u=Datrikk', 'general-escuadra', '¿Buscas armar squad o grupo completo? Coordina aquí escuadras para Marvel Rivals, Overwatch 2, Roblox, Minecraft o CS2.', '19:12'),
('msg-2', 'u-2', 'Datrikk', '/api/avatar?u=Datrikk', 'scrims-valorant', 'Buscamos 1 Duelista o Iniciador rango Ascendente+ para scrim hoy a las 21:00.', '19:15'),
('msg-3', 'u-3', 'dididong', '/api/avatar?u=dididong', 'duo-lol', 'Disponible para ranked Duo en servidor LAS como Support.', '19:22')
ON CONFLICT DO NOTHING;

-- 4. AMISTADES Y CHATS DIRECTOS INICIALES (BANDEJA ESTILO INSTAGRAM)
INSERT INTO friendships (id, requester_id, addressee_id, status) VALUES
('fr-1', 'u-1', 'u-5', 'ACCEPTED'),
('fr-2', 'u-1', 'u-2', 'ACCEPTED'),
('fr-3', 'u-1', 'u-4', 'ACCEPTED'),
('fr-4', 'u-1', 'u-3', 'ACCEPTED'),
('fr-5', 'u-6', 'u-1', 'PENDING')
ON CONFLICT DO NOTHING;

INSERT INTO direct_messages (id, sender_id, receiver_id, content, is_read, sent_at, created_ms) VALUES
('dm-1', 'u-5', 'u-1', 'Wena Jesus, ¿sale Valorant a las 21:00?', 1, '18:40', 1731200001000),
('dm-2', 'u-1', 'u-5', 'De una! Estoy practicando smokes en Ascent.', 1, '18:42', 1731200002000),
('dm-3', 'u-5', 'u-1', 'Estoy listo para jugar Valorant a las 21:00 🔥', 1, '18:45', 1731200003000),
('dm-4', 'u-2', 'u-1', 'Buena partida recién! Mañana repetimos dúo', 1, '17:20', 1731200004000),
('dm-5', 'u-4', 'u-1', 'Falta 1 para Premier CS2 a las 22:00, ¿te sumas?', 0, '19:18', 1731200005000)
ON CONFLICT DO NOTHING;

-- 5. LOBBIES DE ESCUADRA INICIALES CON CUPOS EN VIVO
INSERT INTO squad_lobbies (id, host_user_id, game_id, title, mode, max_slots, roles_needed, schedule_label, status) VALUES
('lob-1', 'u-5', 'g-val', 'Ranked Valorant Ascendente+ · Falta Sentinel', 'Escuadra 5v5', 5, 'Sentinel / Flex', 'Hoy 21:30 hrs', 'OPEN'),
('lob-2', 'u-2', 'g-mr', 'Marvel Rivals Competitivo · Sin Tilt', 'Escuadra 6v6', 6, 'Vanguard / Strategist', 'Tardes / Noches', 'OPEN'),
('lob-3', 'u-4', 'g-apex', 'Apex Legends Tríos · Micrófono activo', 'Trío', 3, 'Entry / Support', '22:00 hrs', 'OPEN')
ON CONFLICT DO NOTHING;

INSERT INTO squad_lobby_members (id, lobby_id, user_id, role_note) VALUES
('lm-1', 'lob-1', 'u-5', 'Duelista'),
('lm-2', 'lob-1', 'u-1', 'Controlador'),
('lm-3', 'lob-1', 'u-2', 'Iniciador'),
('lm-4', 'lob-2', 'u-2', 'Flex'),
('lm-5', 'lob-2', 'u-5', 'Duelista'),
('lm-6', 'lob-3', 'u-4', 'Fragger')
ON CONFLICT DO NOTHING;
