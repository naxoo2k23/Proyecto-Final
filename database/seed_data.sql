-- =====================================================================
-- PLAYMATCH SEED DATA (PostgreSQL / SQLite Portable Reference)
-- Poblado inicial de juegos disponibles, usuarios reales y torneos
-- =====================================================================

-- 1. JUEGOS DISPONIBLES
INSERT INTO games (id, code, name, category, platform, max_team_size, description, accent_color, banner_url, is_active, sort_order) VALUES
('g-val', 'valorant', 'Valorant', 'Tactical FPS', 'PC / Consola', 5, 'Shooter táctico 5v5 basado en precisión de disparo y habilidades de agentes.', '#ff4655', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80', 1, 1),
('g-lol', 'lol', 'League of Legends', 'MOBA', 'PC (LAS)', 5, 'Arena de batalla multijugador por equipos estratégicos 5v5 en la Grieta del Invocador.', '#8b5cf6', 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80', 1, 2),
('g-cs2', 'cs2', 'Counter-Strike 2', 'Tactical FPS', 'PC (Steam)', 5, 'Competitivo clásico de ataque y defensa táctica con servidores de alta precisión.', '#f59e0b', 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80', 1, 3),
('g-rl', 'rocket-league', 'Rocket League', 'Deportes / Arcade', 'Multiplataforma', 3, 'Fútbol vehicular de alta velocidad 3v3 competitivo con juego aéreo y rotaciones.', '#06b6d4', 'https://images.unsplash.com/photo-1538481199705-c710c4e965fc?auto=format&fit=crop&w=800&q=80', 1, 4)
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
