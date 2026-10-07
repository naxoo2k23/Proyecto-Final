package com.playmatch.repository;

import com.playmatch.model.*;
import com.playmatch.util.JsonUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DatabaseManager {
    private static DatabaseManager instance;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, PlayerProfile> playerProfiles = new ConcurrentHashMap<>();
    private final Map<String, Tournament> tournaments = new ConcurrentHashMap<>();
    private final List<ChatMessage> chatMessages = new CopyOnWriteArrayList<>();
    private final List<MatchRequest> matchRequests = new CopyOnWriteArrayList<>();

    private final File dataFile = new File("data/store.json");

    private DatabaseManager() {
        seedInitialData();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void seedInitialData() {
        // 1. Usuarios base
        User u1 = new User("u-1", "Naxoo_Viper", "jesus.villasanti@playmatch.cl", "hash_secret_123", "ADMIN", "https://api.dicebear.com/7.x/bottts/svg?seed=Naxoo_Viper");
        User u2 = new User("u-2", "ChiloeGamer", "cristobal@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=ChiloeGamer");
        User u3 = new User("u-3", "Valkyria_CL", "valeria.gonzalez@gmail.com", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=Valkyria_CL");
        User u4 = new User("u-4", "ShadowStrike", "matias.perez@live.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=ShadowStrike");
        User u5 = new User("u-5", "AndesEsports", "contacto@andesesports.cl", "hash_secret_123", "ORGANIZER", "https://api.dicebear.com/7.x/bottts/svg?seed=AndesEsports");

        users.put(u1.getId(), u1);
        users.put(u2.getId(), u2);
        users.put(u3.getId(), u3);
        users.put(u4.getId(), u4);
        users.put(u5.getId(), u5);

        // 2. Perfiles de jugador (Chilean gaming context, Zero Toxic)
        PlayerProfile p1 = new PlayerProfile(
            "p-1", u1.getId(), u1.getUsername(),
            "Main Controller en Valorant. Busco equipo serio para competir en torneos de la comunidad chilena.",
            "Chile", "Región Metropolitana (San Joaquín)", "ZERO_TOXIC", 100,
            "Noches (21:00 - 01:00)", "76561198000000001", "Naxoo#CHI", "naxoo_dev#0001",
            "valorant", "Valorant", "Inmortal 1", "Controlador (Omen / Viper)", "TRYHARD", u1.getAvatarUrl()
        );

        PlayerProfile p2 = new PlayerProfile(
            "p-2", u2.getId(), u2.getUsername(),
            "Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Cero toxicidad.",
            "Chile", "Los Lagos (Castro)", "ZERO_TOXIC", 98,
            "Tardes y Noches (19:00 - 23:30)", "76561198000000002", "Chiloe#SUR", "chiloe_cl#1234",
            "valorant", "Valorant", "Ascendente 2", "Iniciador (Fade / Sova)", "COMPETITIVE", u2.getAvatarUrl()
        );

        PlayerProfile p3 = new PlayerProfile(
            "p-3", u3.getId(), u3.getUsername(),
            "Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta season.",
            "Chile", "Valparaíso (Viña del Mar)", "ZERO_TOXIC", 99,
            "Fines de semana y noches", "76561198000000003", "Valkyria#LAS", "valky#5544",
            "lol", "League of Legends", "Diamante IV", "Support (Thresh / Lulu)", "COMPETITIVE", u3.getAvatarUrl()
        );

        PlayerProfile p4 = new PlayerProfile(
            "p-4", u4.getId(), u4.getUsername(),
            "AWPer en CS2. Juego competitivo, comunicación clara por Discord. Disponible para scrims.",
            "Chile", "Biobío (Concepción)", "FRIENDLY", 95,
            "Noches (22:00 - 02:00)", "76561198000000004", "Shadow#AWP", "shadow#8899",
            "cs2", "Counter-Strike 2", "Nivel 8 Faceit (16.500 ELO)", "Sniper / Entry", "TRYHARD", u4.getAvatarUrl()
        );

        playerProfiles.put(p1.getId(), p1);
        playerProfiles.put(p2.getId(), p2);
        playerProfiles.put(p3.getId(), p3);
        playerProfiles.put(p4.getId(), p4);

        // 3. Torneos
        Tournament t1 = new Tournament(
            "t-1", "Copa Universitaria Valorant Santiago 2026", "valorant", "Valorant",
            "Andes Esports", "$200.000 CLP + Skins Riot", "SINGLE_ELIMINATION", 8,
            "15 de Noviembre, 18:00 hrs", "OPEN_REGISTRATION",
            "Torneo 5v5 al mejor de 1 (Bo1). Final Bo3. Anticheat obligatorio. Tolerancia 10 minutos.",
            "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80"
        );
        t1.getRegisteredTeams().add("Andes Raptors (Cap. Naxoo_Viper)");
        t1.getRegisteredTeams().add("Cordillera Clan (Cap. ChiloeGamer)");
        t1.getRegisteredTeams().add("Valpo Storm (Cap. Valkyria_CL)");
        t1.getRegisteredTeams().add("Biobío Phoenix (Cap. ShadowStrike)");

        // Brackets simulados para el torneo en curso
        Tournament t3 = new Tournament(
            "t-3", "Master Series CS2 - Red Bull Cup", "cs2", "Counter-Strike 2",
            "Red Bull Gaming Sphere", "$300.000 CLP + Periféricos HyperX", "SINGLE_ELIMINATION", 4,
            "25 de Noviembre, 17:00 hrs", "IN_PROGRESS",
            "Servidores 128 tickrate en Santiago. Eliminación directa. Overtime en MR3.",
            "https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80"
        );
        t3.getRegisteredTeams().add("Andes Raptors");
        t3.getRegisteredTeams().add("Cordillera Clan");
        t3.getRegisteredTeams().add("Valpo Storm");
        t3.getRegisteredTeams().add("Biobío Phoenix");

        t3.getMatches().add(new TournamentMatch("m-1", "Semifinal 1", 1, "Andes Raptors", "Cordillera Clan", 13, 9, "Andes Raptors", "FINISHED"));
        t3.getMatches().add(new TournamentMatch("m-2", "Semifinal 2", 2, "Valpo Storm", "Biobío Phoenix", 11, 13, "Biobío Phoenix", "FINISHED"));
        t3.getMatches().add(new TournamentMatch("m-3", "Gran Final", 3, "Andes Raptors", "Biobío Phoenix", 0, 0, null, "SCHEDULED"));

        Tournament t2 = new Tournament(
            "t-2", "Liga Amateur League of Legends Chile - Season 1", "lol", "League of Legends",
            "Comunidad Gamer Chile", "$150.000 CLP", "SINGLE_ELIMINATION", 8,
            "20 de Noviembre, 19:00 hrs", "OPEN_REGISTRATION",
            "Modo torneo reclutamiento. Servidor LAS. Prohibido conducta antideportiva.",
            "https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80"
        );
        t2.getRegisteredTeams().add("Fenix Gaming");
        t2.getRegisteredTeams().add("Santiago Knights");

        tournaments.put(t1.getId(), t1);
        tournaments.put(t2.getId(), t2);
        tournaments.put(t3.getId(), t3);

        // 4. Mensajes de chat iniciales
        chatMessages.add(new ChatMessage("c-1", "Naxoo_Viper", u1.getAvatarUrl(), "global", "¡Bienvenidos a la beta de PlayMatch! Buscando 2 jugadores para scrim de Valorant hoy a las 22:00.", "22:10"));
        chatMessages.add(new ChatMessage("c-2", "ChiloeGamer", u2.getAvatarUrl(), "global", "Buena bro, yo me sumo de iniciador. Cero toxicidad.", "22:12"));
        chatMessages.add(new ChatMessage("c-3", "Valkyria_CL", u3.getAvatarUrl(), "global", "¿Alguien para dúo LoL en LAS? Estoy en Diamante IV.", "22:15"));
    }

    // Métodos de acceso y manipulación
    public Collection<User> getAllUsers() { return users.values(); }

    public User findUserByUsername(String username) {
        for (User u : users.values()) {
            if (u.getUsername().equalsIgnoreCase(username)) return u;
        }
        return null;
    }

    public User findUserByEmail(String email) {
        for (User u : users.values()) {
            if (u.getEmail().equalsIgnoreCase(email)) return u;
        }
        return null;
    }

    public synchronized User createUser(String username, String email, String password) {
        User u = new User(UUID.randomUUID().toString(), username, email, "hash_" + password, "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=" + username);
        users.put(u.getId(), u);

        // Crear perfil inicial asociado
        PlayerProfile p = new PlayerProfile(
            UUID.randomUUID().toString(), u.getId(), u.getUsername(),
            "Nuevo jugador en PlayMatch. ¡Listo para jugar!",
            "Chile", "Región Metropolitana", "ZERO_TOXIC", 100,
            "Noches (21:00 - 01:00)", "", username + "#CHI", username,
            "valorant", "Valorant", "Platino I", "Flex", "COMPETITIVE", u.getAvatarUrl()
        );
        playerProfiles.put(p.getId(), p);
        return u;
    }

    public Collection<PlayerProfile> getAllProfiles() { return playerProfiles.values(); }

    public PlayerProfile findProfileByUsername(String username) {
        for (PlayerProfile p : playerProfiles.values()) {
            if (p.getUsername().equalsIgnoreCase(username)) return p;
        }
        return null;
    }

    public synchronized void updateProfile(PlayerProfile updated) {
        for (Map.Entry<String, PlayerProfile> entry : playerProfiles.entrySet()) {
            if (entry.getValue().getUsername().equalsIgnoreCase(updated.getUsername())) {
                updated.setId(entry.getKey());
                playerProfiles.put(entry.getKey(), updated);
                return;
            }
        }
        playerProfiles.put(updated.getId(), updated);
    }

    public Collection<Tournament> getAllTournaments() { return tournaments.values(); }

    public Tournament getTournamentById(String id) { return tournaments.get(id); }

    public synchronized boolean registerTeamToTournament(String tournamentId, String teamName) {
        Tournament t = tournaments.get(tournamentId);
        if (t != null && t.getRegisteredTeams().size() < t.getMaxTeams()) {
            t.getRegisteredTeams().add(teamName);
            return true;
        }
        return false;
    }

    public List<ChatMessage> getChatMessages() { return chatMessages; }

    public synchronized void addChatMessage(ChatMessage msg) {
        chatMessages.add(msg);
        if (chatMessages.size() > 100) {
            chatMessages.remove(0); // limitar tamaño en memoria
        }
    }

    public List<MatchRequest> getMatchRequests() { return matchRequests; }

    public synchronized void addMatchRequest(MatchRequest req) {
        matchRequests.add(req);
    }
}
