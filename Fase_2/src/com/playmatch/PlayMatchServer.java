package com.playmatch;

import com.playmatch.model.*;
import com.playmatch.repository.DatabaseManager;
import com.playmatch.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;

public class PlayMatchServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String PUBLIC_DIR = "public";

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        DatabaseManager db = DatabaseManager.getInstance();

        // Router principal
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/players", new PlayersHandler(db));
        server.createContext("/api/auth", new AuthHandler(db));
        server.createContext("/api/tournaments", new TournamentsHandler(db));
        server.createContext("/api/chat", new ChatHandler(db));
        server.createContext("/api/match", new MatchHandler(db));
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("===============================================================");
        System.out.println("   PLAYMATCH - Plataforma Web Gamers (Servidor Java Iniciado)  ");
        System.out.println("===============================================================");
        System.out.println(" > Servidor activo en: http://localhost:" + port);
        System.out.println(" > Directorio estático: " + new File(PUBLIC_DIR).getAbsolutePath());
        System.out.println(" > Base de datos: Almacén relacional simulado en memoria");
        System.out.println(" > Presione Ctrl+C para detener el servidor.");
        System.out.println("===============================================================");
    }

    // --- MANEJADORES DE LA API ---

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "");
                return;
            }
            String json = "{\"status\":\"UP\",\"project\":\"PlayMatch\",\"phase\":\"Fase 2 - MVP\",\"time\":\"" + new Date() + "\"}";
            sendJsonResponse(exchange, 200, json);
        }
    }

    static class PlayersHandler implements HttpHandler {
        private final DatabaseManager db;
        public PlayersHandler(DatabaseManager db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, "");
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                String query = exchange.getRequestURI().getQuery();
                String filterGame = null;
                String filterToxic = null;

                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length == 2) {
                            if ("game".equalsIgnoreCase(pair[0])) filterGame = URLDecoder.decode(pair[1], "UTF-8");
                            if ("toxic".equalsIgnoreCase(pair[0])) filterToxic = URLDecoder.decode(pair[1], "UTF-8");
                        }
                    }
                }

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (PlayerProfile p : db.getAllProfiles()) {
                    if (filterGame != null && !filterGame.isEmpty() && !filterGame.equalsIgnoreCase("all") && !p.getGame().equalsIgnoreCase(filterGame)) {
                        continue;
                    }
                    if (filterToxic != null && !filterToxic.isEmpty() && !p.getToxicLevel().equalsIgnoreCase(filterToxic)) {
                        continue;
                    }
                    if (!first) sb.append(",");
                    sb.append(profileToJson(p));
                    first = false;
                }
                sb.append("]");
                sendJsonResponse(exchange, 200, sb.toString());

            } else if ("POST".equalsIgnoreCase(method)) {
                // Actualizar perfil
                String body = readBody(exchange);
                Map<String, String> data = JsonUtils.parseSimpleJsonObject(body);
                String username = data.get("username");
                if (username == null || username.isEmpty()) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Falta username\"}");
                    return;
                }
                PlayerProfile profile = db.findProfileByUsername(username);
                if (profile == null) {
                    profile = new PlayerProfile();
                    profile.setUsername(username);
                }
                if (data.containsKey("bio")) profile.setBio(data.get("bio"));
                if (data.containsKey("region")) profile.setRegion(data.get("region"));
                if (data.containsKey("game")) profile.setGame(data.get("game"));
                if (data.containsKey("rank")) profile.setRank(data.get("rank"));
                if (data.containsKey("role")) profile.setRole(data.get("role"));
                if (data.containsKey("playstyle")) profile.setPlaystyle(data.get("playstyle"));
                if (data.containsKey("steamId")) profile.setSteamId(data.get("steamId"));
                if (data.containsKey("riotId")) profile.setRiotId(data.get("riotId"));
                if (data.containsKey("discordTag")) profile.setDiscordTag(data.get("discordTag"));
                if (data.containsKey("preferredSchedule")) profile.setPreferredSchedule(data.get("preferredSchedule"));
                if (data.containsKey("toxicLevel")) profile.setToxicLevel(data.get("toxicLevel"));

                db.updateProfile(profile);
                sendJsonResponse(exchange, 200, "{\"message\":\"Perfil actualizado con éxito\",\"profile\":" + profileToJson(profile) + "}");
            }
        }
    }

    static class AuthHandler implements HttpHandler {
        private final DatabaseManager db;
        public AuthHandler(DatabaseManager db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, "");
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                String body = readBody(exchange);
                Map<String, String> data = JsonUtils.parseSimpleJsonObject(body);

                if (path.endsWith("/register")) {
                    String username = data.get("username");
                    String email = data.get("email");
                    String password = data.get("password");

                    if (username == null || email == null || password == null) {
                        sendJsonResponse(exchange, 400, "{\"error\":\"Campos incompletos\"}");
                        return;
                    }
                    if (db.findUserByUsername(username) != null) {
                        sendJsonResponse(exchange, 409, "{\"error\":\"El nombre de usuario ya existe\"}");
                        return;
                    }
                    User u = db.createUser(username, email, password);
                    PlayerProfile p = db.findProfileByUsername(username);
                    sendJsonResponse(exchange, 201, "{\"message\":\"Usuario registrado\",\"user\":" + userToJson(u) + ",\"profile\":" + (p != null ? profileToJson(p) : "null") + "}");

                } else if (path.endsWith("/login")) {
                    String username = data.get("username");
                    String password = data.get("password");

                    User u = db.findUserByUsername(username);
                    if (u != null) {
                        PlayerProfile p = db.findProfileByUsername(username);
                        sendJsonResponse(exchange, 200, "{\"token\":\"tok_" + UUID.randomUUID() + "\",\"user\":" + userToJson(u) + ",\"profile\":" + (p != null ? profileToJson(p) : "null") + "}");
                    } else {
                        sendJsonResponse(exchange, 401, "{\"error\":\"Credenciales inválidas\"}");
                    }
                }
            }
        }
    }

    static class TournamentsHandler implements HttpHandler {
        private final DatabaseManager db;
        public TournamentsHandler(DatabaseManager db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, "");
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                // Si es detalle /api/tournaments/{id}
                String[] parts = path.split("/");
                if (parts.length > 3) {
                    String id = parts[3];
                    Tournament t = db.getTournamentById(id);
                    if (t != null) {
                        sendJsonResponse(exchange, 200, tournamentToJson(t));
                        return;
                    } else {
                        sendJsonResponse(exchange, 404, "{\"error\":\"Torneo no encontrado\"}");
                        return;
                    }
                }

                // Lista de todos los torneos
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Tournament t : db.getAllTournaments()) {
                    if (!first) sb.append(",");
                    sb.append(tournamentToJson(t));
                    first = false;
                }
                sb.append("]");
                sendJsonResponse(exchange, 200, sb.toString());

            } else if ("POST".equalsIgnoreCase(method) && path.contains("/join")) {
                // Inscribir equipo en torneo
                String[] parts = path.split("/");
                String tournamentId = parts[3];
                String body = readBody(exchange);
                Map<String, String> data = JsonUtils.parseSimpleJsonObject(body);
                String teamName = data.get("teamName");

                if (teamName == null || teamName.isEmpty()) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Debe ingresar el nombre del equipo\"}");
                    return;
                }

                boolean ok = db.registerTeamToTournament(tournamentId, teamName);
                if (ok) {
                    sendJsonResponse(exchange, 200, "{\"message\":\"Equipo " + teamName + " inscrito exitosamente\"}");
                } else {
                    sendJsonResponse(exchange, 400, "{\"error\":\"No se pudo inscribir (cupos llenos o torneo no existe)\"}");
                }
            }
        }
    }

    static class ChatHandler implements HttpHandler {
        private final DatabaseManager db;
        public ChatHandler(DatabaseManager db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, "");
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (ChatMessage m : db.getChatMessages()) {
                    if (!first) sb.append(",");
                    sb.append("{\"id\":\"").append(m.getId()).append("\",")
                      .append("\"sender\":\"").append(JsonUtils.escape(m.getSenderUsername())).append("\",")
                      .append("\"avatar\":\"").append(JsonUtils.escape(m.getSenderAvatar())).append("\",")
                      .append("\"channel\":\"").append(JsonUtils.escape(m.getChannel())).append("\",")
                      .append("\"content\":\"").append(JsonUtils.escape(m.getContent())).append("\",")
                      .append("\"timestamp\":\"").append(m.getTimestamp()).append("\"}");
                    first = false;
                }
                sb.append("]");
                sendJsonResponse(exchange, 200, sb.toString());

            } else if ("POST".equalsIgnoreCase(method)) {
                String body = readBody(exchange);
                Map<String, String> data = JsonUtils.parseSimpleJsonObject(body);
                String sender = data.get("sender");
                String content = data.get("content");
                String channel = data.get("channel");

                if (sender == null || content == null || content.trim().isEmpty()) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Mensaje vacío o sin remitente\"}");
                    return;
                }

                ChatMessage msg = new ChatMessage(null, sender, null, channel != null ? channel : "global", content, null);
                db.addChatMessage(msg);
                sendJsonResponse(exchange, 201, "{\"message\":\"Mensaje enviado\",\"id\":\"" + msg.getId() + "\"}");
            }
        }
    }

    static class MatchHandler implements HttpHandler {
        private final DatabaseManager db;
        public MatchHandler(DatabaseManager db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, "");
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                String body = readBody(exchange);
                Map<String, String> data = JsonUtils.parseSimpleJsonObject(body);
                String sender = data.get("sender");
                String receiver = data.get("receiver");
                String game = data.get("game");
                String message = data.get("message");

                MatchRequest req = new MatchRequest(null, sender, receiver, game, message, "PENDING");
                db.addMatchRequest(req);

                // Agregar aviso automático en el chat para ambos jugadores
                ChatMessage alert = new ChatMessage(null, "PlayMatch Bot", "https://api.dicebear.com/7.x/bottts/svg?seed=SystemBot", "global",
                        "⚔️ ¡Solicitud de Match! @" + sender + " invitó a @" + receiver + " para jugar " + game + ": \"" + message + "\"", null);
                db.addChatMessage(alert);

                sendJsonResponse(exchange, 201, "{\"message\":\"Solicitud de emparejamiento enviada exitosamente\",\"requestId\":\"" + req.getId() + "\"}");
            }
        }
    }

    // --- MANEJADOR DE ARCHIVOS ESTÁTICOS ---

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }

            // Sanitización básica para evitar Directory Traversal
            if (path.contains("..")) {
                sendResponse(exchange, 403, "403 Forbidden");
                return;
            }

            File file = new File(PUBLIC_DIR, path.startsWith("/") ? path.substring(1) : path);
            if (!file.exists() || file.isDirectory()) {
                // Fallback a index.html para SPA routing
                file = new File(PUBLIC_DIR, "index.html");
                if (!file.exists()) {
                    sendResponse(exchange, 404, "404 Not Found");
                    return;
                }
            }

            String mime = getMimeType(file.getName());
            exchange.getResponseHeaders().set("Content-Type", mime);
            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String getMimeType(String filename) {
            if (filename.endsWith(".html")) return "text/html; charset=UTF-8";
            if (filename.endsWith(".css")) return "text/css; charset=UTF-8";
            if (filename.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (filename.endsWith(".json")) return "application/json; charset=UTF-8";
            if (filename.endsWith(".svg")) return "image/svg+xml";
            if (filename.endsWith(".png")) return "image/png";
            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
            if (filename.endsWith(".ico")) return "image/x-icon";
            return "text/plain; charset=UTF-8";
        }
    }

    // --- MÉTODOS DE SERIALIZACIÓN Y UTILIDADES HTTP ---

    private static String profileToJson(PlayerProfile p) {
        return "{" +
                "\"id\":\"" + p.getId() + "\"," +
                "\"userId\":\"" + p.getUserId() + "\"," +
                "\"username\":\"" + JsonUtils.escape(p.getUsername()) + "\"," +
                "\"bio\":\"" + JsonUtils.escape(p.getBio()) + "\"," +
                "\"region\":\"" + JsonUtils.escape(p.getRegion()) + "\"," +
                "\"toxicLevel\":\"" + p.getToxicLevel() + "\"," +
                "\"reputationScore\":" + p.getReputationScore() + "," +
                "\"preferredSchedule\":\"" + JsonUtils.escape(p.getPreferredSchedule()) + "\"," +
                "\"steamId\":\"" + JsonUtils.escape(p.getSteamId()) + "\"," +
                "\"riotId\":\"" + JsonUtils.escape(p.getRiotId()) + "\"," +
                "\"discordTag\":\"" + JsonUtils.escape(p.getDiscordTag()) + "\"," +
                "\"game\":\"" + JsonUtils.escape(p.getGame()) + "\"," +
                "\"gameName\":\"" + JsonUtils.escape(p.getGameName()) + "\"," +
                "\"rank\":\"" + JsonUtils.escape(p.getRank()) + "\"," +
                "\"role\":\"" + JsonUtils.escape(p.getRole()) + "\"," +
                "\"playstyle\":\"" + p.getPlaystyle() + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(p.getAvatarUrl()) + "\"" +
                "}";
    }

    private static String userToJson(User u) {
        return "{" +
                "\"id\":\"" + u.getId() + "\"," +
                "\"username\":\"" + JsonUtils.escape(u.getUsername()) + "\"," +
                "\"email\":\"" + JsonUtils.escape(u.getEmail()) + "\"," +
                "\"role\":\"" + u.getRole() + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(u.getAvatarUrl()) + "\"" +
                "}";
    }

    private static String tournamentToJson(Tournament t) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":\"").append(t.getId()).append("\",");
        sb.append("\"title\":\"").append(JsonUtils.escape(t.getTitle())).append("\",");
        sb.append("\"game\":\"").append(t.getGame()).append("\",");
        sb.append("\"gameName\":\"").append(JsonUtils.escape(t.getGameName())).append("\",");
        sb.append("\"organizer\":\"").append(JsonUtils.escape(t.getOrganizer())).append("\",");
        sb.append("\"prizePool\":\"").append(JsonUtils.escape(t.getPrizePool())).append("\",");
        sb.append("\"format\":\"").append(t.getFormat()).append("\",");
        sb.append("\"maxTeams\":").append(t.getMaxTeams()).append(",");
        sb.append("\"registeredTeamsCount\":").append(t.getRegisteredTeamsCount()).append(",");
        sb.append("\"startDate\":\"").append(JsonUtils.escape(t.getStartDate())).append("\",");
        sb.append("\"status\":\"").append(t.getStatus()).append("\",");
        sb.append("\"rules\":\"").append(JsonUtils.escape(t.getRules())).append("\",");
        sb.append("\"bannerUrl\":\"").append(JsonUtils.escape(t.getBannerUrl())).append("\",");

        // Equipos
        sb.append("\"registeredTeams\":[");
        for (int i = 0; i < t.getRegisteredTeams().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(JsonUtils.escape(t.getRegisteredTeams().get(i))).append("\"");
        }
        sb.append("],");

        // Matches / Brackets
        sb.append("\"matches\":[");
        for (int i = 0; i < t.getMatches().size(); i++) {
            if (i > 0) sb.append(",");
            TournamentMatch m = t.getMatches().get(i);
            sb.append("{")
              .append("\"id\":\"").append(m.getId()).append("\",")
              .append("\"roundName\":\"").append(JsonUtils.escape(m.getRoundName())).append("\",")
              .append("\"order\":").append(m.getOrder()).append(",")
              .append("\"team1\":\"").append(JsonUtils.escape(m.getTeam1())).append("\",")
              .append("\"team2\":\"").append(JsonUtils.escape(m.getTeam2())).append("\",")
              .append("\"scoreTeam1\":").append(m.getScoreTeam1()).append(",")
              .append("\"scoreTeam2\":").append(m.getScoreTeam2()).append(",")
              .append("\"winner\":").append(m.getWinner() != null ? "\"" + JsonUtils.escape(m.getWinner()) + "\"" : "null").append(",")
              .append("\"status\":\"").append(m.getStatus()).append("\"")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
}
