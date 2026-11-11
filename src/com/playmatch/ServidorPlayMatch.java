package com.playmatch;

import com.playmatch.modelo.*;
import com.playmatch.repositorio.GestorBaseDatos;
import com.playmatch.servicio.ServicioRecomendacionIA;
import com.playmatch.util.ConfiguracionDB;
import com.playmatch.util.JsonUtils;
import com.playmatch.util.SeguridadUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class ServidorPlayMatch {
    private static final int PUERTO_POR_DEFECTO = 8080;
    private static final String DIRECTORIO_PUBLICO = "public";
    private static final Map<String, String> SESIONES_ACTIVAS = new ConcurrentHashMap<>();
    private static final Map<String, CodigoVerificacion> CODIGOS_TELEFONO = new ConcurrentHashMap<>();
    private static final Map<String, String> DISCORD_OAUTH_STATES = new ConcurrentHashMap<>();
    private static final Map<String, PresenciaJugador> PRESENCIA_JUGADORES = new ConcurrentHashMap<>();
    private static final List<SseClient> SSE_CLIENTES = new CopyOnWriteArrayList<>();

    private static class PresenciaJugador {
        String username;
        String status; // ONLINE, LOOKING, INGAME, OFFLINE
        String activity;
        long updatedAtMs;
        PresenciaJugador(String username, String status, String activity, long updatedAtMs) {
            this.username = username;
            this.status = status;
            this.activity = activity;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private static class SseClient {
        final String usernameLower;
        final OutputStream os;
        volatile boolean open = true;
        SseClient(String usernameLower, OutputStream os) {
            this.usernameLower = usernameLower;
            this.os = os;
        }
        synchronized boolean sendRaw(String frame) {
            if (!open) return false;
            try {
                os.write(frame.getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            } catch (IOException e) {
                open = false;
                return false;
            }
        }
    }

    private static void actualizarPresencia(String username, String status, String activity) {
        if (username == null || username.trim().isEmpty()) return;
        String cleanUser = username.trim();
        String st = (status != null && !status.trim().isEmpty()) ? status.trim().toUpperCase(Locale.ROOT) : "ONLINE";
        if (!"ONLINE".equals(st) && !"LOOKING".equals(st) && !"INGAME".equals(st) && !"OFFLINE".equals(st)) {
            st = "ONLINE";
        }
        String act = activity != null ? activity.trim() : "";
        PRESENCIA_JUGADORES.put(cleanUser.toLowerCase(Locale.ROOT),
                new PresenciaJugador(cleanUser, st, act, System.currentTimeMillis()));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("username", cleanUser);
        ev.put("status", st);
        ev.put("activity", act);
        emitirEventoSse("presence", null, ev);
    }

    private static Map<String, Map<String, Object>> snapshotPresencia() {
        long now = System.currentTimeMillis();
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map.Entry<String, PresenciaJugador> e : PRESENCIA_JUGADORES.entrySet()) {
            PresenciaJugador pj = e.getValue();
            boolean stale = (now - pj.updatedAtMs) > 90_000L;
            String st = stale ? "OFFLINE" : pj.status;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", pj.username);
            row.put("status", st);
            row.put("activity", stale ? "" : pj.activity);
            row.put("updatedAtMs", pj.updatedAtMs);
            map.put(pj.username, row);
        }
        return map;
    }

    private static void emitirEventoSse(String eventName, String targetUsernameOrNull, Map<String, Object> payload) {
        if (SSE_CLIENTES.isEmpty()) return;
        String json = JsonUtils.toJson(payload);
        String frame = "event: " + eventName + "\ndata: " + json + "\n\n";
        String targetLower = targetUsernameOrNull != null ? targetUsernameOrNull.trim().toLowerCase(Locale.ROOT) : null;
        for (SseClient c : SSE_CLIENTES) {
            if (!c.open) {
                SSE_CLIENTES.remove(c);
                continue;
            }
            if (targetLower == null || targetLower.equals(c.usernameLower)) {
                if (!c.sendRaw(frame)) {
                    SSE_CLIENTES.remove(c);
                }
            }
        }
    }

    private static class CodigoVerificacion {
        final String codigo;
        final String telefonoNormalizado;
        final long expiraEnMs;
        CodigoVerificacion(String codigo, String telefonoNormalizado, long expiraEnMs) {
            this.codigo = codigo;
            this.telefonoNormalizado = telefonoNormalizado;
            this.expiraEnMs = expiraEnMs;
        }
    }

    public static void main(String[] args) throws IOException {
        int puerto = PUERTO_POR_DEFECTO;
        if (args.length > 0) {
            try {
                puerto = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        GestorBaseDatos db = GestorBaseDatos.getInstancia();
        HttpServer servidor = HttpServer.create(new InetSocketAddress(puerto), 0);

        // Enrutador de APIs REST y estáticos
        servidor.createContext("/api/health", new ManejadorSalud(db));
        servidor.createContext("/api/stats", new ManejadorEstadisticas(db));
        servidor.createContext("/api/integrations", new ManejadorIntegraciones(db));
        servidor.createContext("/api/events", new ManejadorEventos());
        servidor.createContext("/api/games", new ManejadorJuegos(db));
        servidor.createContext("/api/users", new ManejadorUsuarios(db));
        servidor.createContext("/api/players", new ManejadorJugadores(db));
        servidor.createContext("/api/recommendations", new ManejadorRecomendaciones(db));
        servidor.createContext("/api/auth", new ManejadorAutenticacion(db));
        servidor.createContext("/api/tournaments", new ManejadorTorneos(db));
        servidor.createContext("/api/lobbies", new ManejadorLobbies(db));
        servidor.createContext("/api/moderation", new ManejadorModeracion(db));
        servidor.createContext("/api/chat", new ManejadorChat(db));
        servidor.createContext("/api/friends", new ManejadorAmigos(db));
        servidor.createContext("/api/match", new ManejadorEmparejamiento(db));
        servidor.createContext("/", new ManejadorArchivosEstaticos());

        servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        servidor.start();

        System.out.println("====================================================================");
        System.out.println("   PLAYMATCH ARENA · Servidor Web Java + JDBC (" + db.getNombreMotor() + ")");
        System.out.println("====================================================================");
        System.out.println(" > Servidor HTTP:      http://localhost:" + puerto);
        System.out.println(" > Directorio web:     " + new File(DIRECTORIO_PUBLICO).getAbsolutePath());
        System.out.println(" > Motor Base Datos:   " + db.getNombreMotor() + " [" + db.getRutaBaseDatos() + "]");
        System.out.println(" > Estado del Motor:   " + db.getNotaEstadoMotor());
        System.out.println(" > Presione Ctrl+C para detener el servidor.");
        System.out.println("====================================================================");
    }

    static class ManejadorSalud implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorSalud(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            boolean dbOk = db.verificarSaludConexion();
            Map<String, Integer> stats = db.obtenerEstadisticasGenerales();
            boolean geminiOk = db.getConfigDB() != null && db.getConfigDB().isGeminiConfigured();
            String geminiModel = db.getConfigDB() != null ? db.getConfigDB().getGeminiModel() : "gemini-3.8-flash";
            String json = "{" +
                    "\"status\":\"" + (dbOk ? "UP" : "DEGRADED") + "\"," +
                    "\"project\":\"PlayMatch\"," +
                    "\"database\":{\"engine\":\"" + JsonUtils.escape(db.getNombreMotor()) + "\",\"isPostgres\":" + db.esPostgreSQL() + ",\"note\":\"" + JsonUtils.escape(db.getNotaEstadoMotor()) + "\",\"connected\":" + dbOk + ",\"sizeBytes\":" + db.getTamanoArchivoBytes() + "}," +
                    "\"ai\":{\"geminiConfigured\":" + geminiOk + ",\"model\":\"" + JsonUtils.escape(geminiModel) + "\"}," +
                    "\"counts\":{\"users\":" + stats.get("users") + ",\"games\":" + stats.get("games") + ",\"tournaments\":" + stats.get("tournaments") + "}" +
                    "}";
            enviarRespuestaJson(exchange, dbOk ? 200 : 503, json);
        }
    }

    static class ManejadorEstadisticas implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorEstadisticas(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            try {
                Map<String, Integer> stats = db.obtenerEstadisticasGenerales();
                String json = "{" +
                        "\"dbEngine\":\"" + JsonUtils.escape(db.getNombreMotor()) + "\"," +
                        "\"isPostgres\":" + db.esPostgreSQL() + "," +
                        "\"dbNote\":\"" + JsonUtils.escape(db.getNotaEstadoMotor()) + "\"," +
                        "\"dbPath\":\"" + JsonUtils.escape(db.getRutaBaseDatos()) + "\"," +
                        "\"dbSizeKB\":" + Math.max(1, db.getTamanoArchivoBytes() / 1024) + "," +
                        "\"users\":" + stats.get("users") + "," +
                        "\"profiles\":" + stats.get("profiles") + "," +
                        "\"games\":" + stats.get("games") + "," +
                        "\"tournaments\":" + stats.get("tournaments") + "," +
                        "\"teams\":" + stats.get("teams") + "," +
                        "\"messages\":" + stats.get("messages") + "," +
                        "\"matchRequests\":" + stats.get("matchRequests") + "," +
                        "\"friendships\":" + stats.getOrDefault("friendships", 0) + "," +
                        "\"directMessages\":" + stats.getOrDefault("directMessages", 0) + "," +
                        "\"lobbies\":" + stats.getOrDefault("lobbies", 0) +
                        "}";
                enviarRespuestaJson(exchange, 200, json);
            } catch (Exception e) {
                enviarError(exchange, 500, "Error obteniendo estadísticas de base de datos");
            }
        }
    }

    static class ManejadorIntegraciones implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorIntegraciones(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(metodo)) {
                enviarError(exchange, 405, "Método no permitido.");
                return;
            }
            ConfiguracionDB cfg = db.getConfigDB();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dbEngine", db.getNombreMotor());
            out.put("isPostgres", db.esPostgreSQL());
            out.put("postgresConfigured", cfg.isPostgresConfigured());
            out.put("safePostgresTarget", cfg.getSafePostgresTarget());
            out.put("geminiConfigured", cfg.isGeminiConfigured());
            out.put("geminiModel", cfg.getGeminiModel());
            out.put("discordOAuthConfigured", cfg.isDiscordOAuthConfigured());
            out.put("smsProvider", cfg.getSmsProvider());
            out.put("twilioConfigured", cfg.isTwilioConfigured());
            enviarRespuestaJson(exchange, 200, JsonUtils.toJson(out));
        }
    }

    static class ManejadorEventos implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarError(exchange, 405, "Método no permitido.");
                return;
            }
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String username = params.get("username");
            String unameLower = (username != null && !username.trim().isEmpty())
                    ? username.trim().toLowerCase(Locale.ROOT) : "";

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            SseClient client = new SseClient(unameLower, exchange.getResponseBody());
            SSE_CLIENTES.add(client);

            try {
                if (!unameLower.isEmpty()) {
                    PresenciaJugador prev = PRESENCIA_JUGADORES.get(unameLower);
                    if (prev == null || "OFFLINE".equalsIgnoreCase(prev.status)
                            || (System.currentTimeMillis() - prev.updatedAtMs) > 90_000L) {
                        actualizarPresencia(username.trim(), "ONLINE", "");
                    }
                }
                Map<String, Object> welcome = new LinkedHashMap<>();
                welcome.put("connected", true);
                welcome.put("username", username != null ? username.trim() : "");
                welcome.put("presence", snapshotPresencia());
                client.sendRaw("event: connected\ndata: " + JsonUtils.toJson(welcome) + "\n\n");

                while (client.open) {
                    Thread.sleep(20_000L);
                    if (!client.sendRaw(": ping\n\n")) {
                        break;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                client.open = false;
                SSE_CLIENTES.remove(client);
                try {
                    exchange.getResponseBody().close();
                } catch (IOException ignored) {}
            }
        }
    }

    static class ManejadorJuegos implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorJuegos(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    boolean includeInactive = "true".equalsIgnoreCase(queryParams.get("all"));
                    List<Juego> juegos = db.obtenerTodosLosJuegos(!includeInactive);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < juegos.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(juegoAJson(juegos.get(i)));
                    }
                    sb.append("]");
                    enviarRespuestaJson(exchange, 200, sb.toString());

                } else if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String nombre = datos.get("name");
                    String codigo = datos.get("code");
                    String categoria = datos.get("category");
                    String plataforma = datos.get("platform");
                    String descripcion = datos.get("description");
                    String colorAcento = datos.get("accentColor");
                    String bannerUrl = datos.get("bannerUrl");

                    int maxTeamSize = 5;
                    if (datos.get("maxTeamSize") != null && !datos.get("maxTeamSize").isEmpty()) {
                        try {
                            maxTeamSize = Integer.parseInt(datos.get("maxTeamSize").trim());
                        } catch (NumberFormatException ignored) {}
                    }

                    if (nombre == null || nombre.trim().isEmpty() || categoria == null || categoria.trim().isEmpty()) {
                        enviarError(exchange, 400, "Nombre y categoría del juego son obligatorios.");
                        return;
                    }

                    Juego creado = db.crearJuego(codigo, nombre, categoria, plataforma, maxTeamSize, descripcion, colorAcento, bannerUrl);
                    if (creado == null) {
                        enviarError(exchange, 409, "Ya existe un juego registrado con ese nombre o código.");
                        return;
                    }
                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Juego registrado en la base de datos con éxito\",\"game\":" + juegoAJson(creado) + "}");
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando juegos: " + e.getMessage());
            }
        }
    }

    static class ManejadorUsuarios implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorUsuarios(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();
            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    if (ruta.endsWith("/me")) {
                        String reqUser = params.get("username");
                        Usuario u = db.buscarUsuarioPorUsername(reqUser);
                        String token = extraerTokenBearer(exchange);
                        String authUserId = token != null ? SESIONES_ACTIVAS.get(token) : null;
                        if (u == null || authUserId == null || !u.getId().equals(authUserId)) {
                            enviarError(exchange, 401, "No autorizado para consultar datos privados de cuenta.");
                            return;
                        }
                        enviarRespuestaJson(exchange, 200, "{\"user\":" + usuarioPrivadoAJson(u) + "}");
                        return;
                    }
                    if (ruta.endsWith("/presence")) {
                        enviarRespuestaJson(exchange, 200, JsonUtils.toJson(snapshotPresencia()));
                        return;
                    }
                    if (ruta.endsWith("/discord/oauth-url")) {
                        String reqUser = params.get("username");
                        ConfiguracionDB cfg = db.getConfigDB();
                        Map<String, Object> out = new LinkedHashMap<>();
                        if (!cfg.isDiscordOAuthConfigured()) {
                            out.put("configured", false);
                            out.put("message", "DISCORD_CLIENT_ID y DISCORD_CLIENT_SECRET no están configurados en .env. Puedes usar la vinculación manual de Discord aquí abajo.");
                            enviarRespuestaJson(exchange, 200, JsonUtils.toJson(out));
                            return;
                        }
                        if (reqUser == null || reqUser.trim().isEmpty()) {
                            enviarError(exchange, 400, "Debes indicar el usuario para iniciar Discord OAuth2.");
                            return;
                        }
                        String state = UUID.randomUUID().toString();
                        DISCORD_OAUTH_STATES.put(state, reqUser.trim());
                        String redir = cfg.getDiscordRedirectUri() != null ? cfg.getDiscordRedirectUri() : "http://localhost:8080/api/auth/discord/callback";
                        String url = "https://discord.com/api/oauth2/authorize?client_id=" +
                                URLEncoder.encode(cfg.getDiscordClientId(), "UTF-8") +
                                "&redirect_uri=" + URLEncoder.encode(redir, "UTF-8") +
                                "&response_type=code&scope=identify&state=" + URLEncoder.encode(state, "UTF-8");
                        out.put("configured", true);
                        out.put("url", url);
                        enviarRespuestaJson(exchange, 200, JsonUtils.toJson(out));
                        return;
                    }

                    String q = params.get("q");
                    List<Usuario> lista = db.obtenerTodosLosUsuarios(q);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < lista.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(usuarioPublicoAJson(lista.get(i)));
                    }
                    sb.append("]");
                    enviarRespuestaJson(exchange, 200, sb.toString());

                } else if (("POST".equalsIgnoreCase(metodo) || "PUT".equalsIgnoreCase(metodo)) && ruta.endsWith("/account")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String email = datos.get("email");
                    String telefono = datos.get("telefono");

                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta identificar el usuario.");
                        return;
                    }
                    Usuario actual = db.buscarUsuarioPorUsername(username);
                    if (actual == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    if (email != null && !email.trim().isEmpty()) {
                        if (!SeguridadUtil.isValidEmail(email)) {
                            enviarError(exchange, 400, "El correo electrónico ingresado no tiene un formato válido.");
                            return;
                        }
                        Usuario otro = db.buscarUsuarioPorEmail(email);
                        if (otro != null && !otro.getId().equals(actual.getId())) {
                            enviarError(exchange, 409, "Ese correo electrónico ya pertenece a otra cuenta.");
                            return;
                        }
                    }
                    if (telefono != null && !telefono.trim().isEmpty() && !SeguridadUtil.isValidPhone(telefono)) {
                        enviarError(exchange, 400, "El número de teléfono debe tener entre 8 y 15 dígitos.");
                        return;
                    }

                    db.actualizarCuentaUsuario(actual.getUsername(), email, telefono);
                    Usuario actualizado = db.buscarUsuarioPorUsername(actual.getUsername());
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Datos privados de cuenta actualizados\",\"user\":" + usuarioPrivadoAJson(actualizado) + "}");

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/avatar")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String avatarUrl = datos.get("avatarUrl");

                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta identificar el usuario.");
                        return;
                    }
                    Usuario actual = db.buscarUsuarioPorUsername(username);
                    if (actual == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    if (!SeguridadUtil.isValidAvatarUrl(avatarUrl)) {
                        enviarError(exchange, 400, "Imagen no válida. Usa PNG, JPG, WebP, GIF (< 500 KB) o enlace HTTPS.");
                        return;
                    }
                    db.actualizarAvatarUsuario(actual.getUsername(), avatarUrl);
                    Usuario actualizado = db.buscarUsuarioPorUsername(actual.getUsername());
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Foto de perfil actualizada\",\"user\":" + usuarioPrivadoAJson(actualizado) + "}");

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/phone/request-code")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String telefono = datos.get("telefono");

                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta identificar el usuario.");
                        return;
                    }
                    Usuario actual = db.buscarUsuarioPorUsername(username);
                    if (actual == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    String telObjetivo = (telefono != null && !telefono.trim().isEmpty()) ? telefono.trim() : actual.getTelefono();
                    if (!SeguridadUtil.isValidPhone(telObjetivo)) {
                        enviarError(exchange, 400, "Ingresa un número de teléfono válido (8 a 15 dígitos) para verificar tu cuenta.");
                        return;
                    }
                    if (!telObjetivo.equals(actual.getTelefono())) {
                        db.actualizarCuentaUsuario(actual.getUsername(), null, telObjetivo);
                    }
                    String codigo = SeguridadUtil.generarCodigoVerificacion6Digitos();
                    String digits = telObjetivo.replaceAll("[^0-9]", "");
                    CODIGOS_TELEFONO.put(actual.getId(), new CodigoVerificacion(codigo, digits, System.currentTimeMillis() + 600_000L));
                    ConfiguracionDB cfgSms = db.getConfigDB();
                    if (cfgSms.isTwilioConfigured()) {
                        String sid = cfgSms.getTwilioAccountSid().trim();
                        String authTok = cfgSms.getTwilioAuthToken().trim();
                        String fromNum = cfgSms.getTwilioFromNumber().trim();
                        String toNum = telObjetivo.startsWith("+") ? telObjetivo : ("+" + digits);
                        String bodyStr = "To=" + URLEncoder.encode(toNum, "UTF-8") +
                                "&From=" + URLEncoder.encode(fromNum, "UTF-8") +
                                "&Body=" + URLEncoder.encode("PlayMatch: tu código de verificación es " + codigo, "UTF-8");
                        HttpURLConnection tw = (HttpURLConnection) URI.create(
                                "https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json").toURL().openConnection();
                        tw.setRequestMethod("POST");
                        String basic = Base64.getEncoder().encodeToString((sid + ":" + authTok).getBytes(StandardCharsets.UTF_8));
                        tw.setRequestProperty("Authorization", "Basic " + basic);
                        tw.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        tw.setDoOutput(true);
                        tw.setConnectTimeout(6000);
                        tw.setReadTimeout(6000);
                        try (OutputStream os = tw.getOutputStream()) {
                            os.write(bodyStr.getBytes(StandardCharsets.UTF_8));
                        }
                        if (tw.getResponseCode() >= 200 && tw.getResponseCode() < 300) {
                            enviarRespuestaJson(exchange, 200, "{\"message\":\"Código SMS enviado a tu teléfono vía Twilio.\"}");
                        } else {
                            enviarError(exchange, 502, "Twilio no pudo enviar el SMS. Verifica tu número o credenciales en .env.");
                        }
                    } else {
                        enviarRespuestaJson(exchange, 200, "{\"message\":\"Código de verificación generado (modo demo)\",\"demoCode\":\"" + codigo + "\"}");
                    }

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/phone/verify")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String code = datos.get("code");

                    Usuario actual = db.buscarUsuarioPorUsername(username);
                    if (actual == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    CodigoVerificacion pendiente = CODIGOS_TELEFONO.get(actual.getId());
                    if (pendiente == null || System.currentTimeMillis() > pendiente.expiraEnMs) {
                        CODIGOS_TELEFONO.remove(actual.getId());
                        enviarError(exchange, 400, "No hay un código activo o ya expiró. Solicita uno nuevo.");
                        return;
                    }
                    String limpio = code != null ? code.trim() : "";
                    if (!MessageDigest.isEqual(pendiente.codigo.getBytes(StandardCharsets.UTF_8), limpio.getBytes(StandardCharsets.UTF_8))) {
                        enviarError(exchange, 400, "Código incorrecto. Revisa los 6 dígitos e intenta nuevamente.");
                        return;
                    }
                    CODIGOS_TELEFONO.remove(actual.getId());
                    db.marcarTelefonoVerificado(actual.getUsername());
                    Usuario actualizado = db.buscarUsuarioPorUsername(actual.getUsername());
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Cuenta verificada por teléfono privado\",\"user\":" + usuarioPrivadoAJson(actualizado) + "}");

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/discord/connect")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String discordTag = datos.get("discordTag");
                    String discordId = datos.get("discordId");

                    Usuario actual = db.buscarUsuarioPorUsername(username);
                    if (actual == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    if (!SeguridadUtil.isValidDiscordTag(discordTag)) {
                        enviarError(exchange, 400, "Usuario de Discord inválido (usa entre 2 y 32 caracteres, ej: tu.usuario o usuario#0001).");
                        return;
                    }
                    if (!SeguridadUtil.isValidDiscordId(discordId)) {
                        enviarError(exchange, 400, "El Discord User ID debe tener entre 17 y 22 dígitos numéricos.");
                        return;
                    }
                    String tagNormalizado = SeguridadUtil.normalizarDiscordTag(discordTag);
                    String idLimpio = discordId != null ? discordId.trim() : "";
                    db.actualizarConexionDiscord(actual.getUsername(), tagNormalizado, idLimpio, true);
                    PerfilJugador actualizado = db.buscarPerfilPorUsername(actual.getUsername());
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Cuenta de Discord conectada a tu perfil\",\"profile\":" + perfilAJson(actualizado) + "}");

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/presence")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String status = datos.get("status");
                    String activity = datos.get("activity");
                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta el usuario.");
                        return;
                    }
                    actualizarPresencia(username.trim(), status, activity);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("ok", true);
                    resp.put("presence", snapshotPresencia().get(username.trim()));
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(resp));

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/discord/disconnect")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");

                    Usuario actual = db.buscarUsuarioPorUsername(username);
                    if (actual == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    db.actualizarConexionDiscord(actual.getUsername(), "", "", false);
                    PerfilJugador actualizado = db.buscarPerfilPorUsername(actual.getUsername());
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Cuenta de Discord desconectada\",\"profile\":" + perfilAJson(actualizado) + "}");
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando usuarios: " + e.getMessage());
            }
        }
    }

    static class ManejadorJugadores implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorJugadores(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    if (ruta.endsWith("/dossier")) {
                        String targetUser = params.get("username");
                        String viewer = params.get("viewer");
                        if (targetUser == null || targetUser.trim().isEmpty()) {
                            enviarError(exchange, 400, "Falta parámetro username.");
                            return;
                        }
                        Map<String, Object> dossier = db.obtenerDossierPublico(targetUser, viewer);
                        if (dossier == null) {
                            enviarError(exchange, 404, "Jugador no encontrado.");
                            return;
                        }
                        PresenciaJugador pj = PRESENCIA_JUGADORES.get(targetUser.trim().toLowerCase(Locale.ROOT));
                        boolean alive = pj != null && (System.currentTimeMillis() - pj.updatedAtMs) <= 90_000L;
                        dossier.put("presenceStatus", alive ? pj.status : "OFFLINE");
                        dossier.put("presenceActivity", alive ? pj.activity : "");
                        enviarRespuestaJson(exchange, 200, JsonUtils.toJson(dossier));
                        return;
                    }

                    if (ruta.endsWith("/leaderboard")) {
                        String viewer = params.get("viewer");
                        List<Map<String, Object>> board = db.obtenerLeaderboard(viewer);
                        long now = System.currentTimeMillis();
                        for (Map<String, Object> row : board) {
                            String un = String.valueOf(row.get("username"));
                            PresenciaJugador pj = PRESENCIA_JUGADORES.get(un.toLowerCase(Locale.ROOT));
                            boolean alive = pj != null && (now - pj.updatedAtMs) <= 90_000L;
                            row.put("presenceStatus", alive ? pj.status : "OFFLINE");
                            row.put("presenceActivity", alive ? pj.activity : "");
                        }
                        enviarRespuestaJson(exchange, 200, JsonUtils.toJson(board));
                        return;
                    }

                    String filtroJuego = params.get("game");
                    String filtroToxic = params.get("toxic");
                    String busqueda = params.get("q");
                    String viewer = params.get("viewer");

                    Set<String> blockedIds = (viewer != null && !viewer.trim().isEmpty())
                            ? db.obtenerIdsBloqueadosMutuos(viewer) : Collections.emptySet();
                    List<PerfilJugador> perfiles = db.buscarPerfiles(filtroJuego, filtroToxic, busqueda);
                    long now = System.currentTimeMillis();
                    StringBuilder sb = new StringBuilder("[");
                    int count = 0;
                    for (PerfilJugador p : perfiles) {
                        if (!blockedIds.isEmpty() && blockedIds.contains(p.getUsuarioId())) continue;
                        if (count > 0) sb.append(",");
                        String base = perfilAJson(p);
                        PresenciaJugador pj = PRESENCIA_JUGADORES.get(p.getUsername().toLowerCase(Locale.ROOT));
                        boolean alive = pj != null && (now - pj.updatedAtMs) <= 90_000L;
                        String st = alive ? pj.status : "OFFLINE";
                        String act = alive ? pj.activity : "";
                        sb.append(base, 0, base.length() - 1)
                          .append(",\"presenceStatus\":\"").append(JsonUtils.escape(st)).append("\",")
                          .append("\"presenceActivity\":\"").append(JsonUtils.escape(act)).append("\"}");
                        count++;
                    }
                    sb.append("]");
                    enviarRespuestaJson(exchange, 200, sb.toString());

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/rate")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String rater = datos.get("raterUsername");
                    String rated = datos.get("ratedUsername");
                    String starsStr = datos.get("stars");
                    String comment = datos.get("comment");

                    if (rater == null || rater.trim().isEmpty() || rated == null || rated.trim().isEmpty()) {
                        enviarError(exchange, 400, "Debes indicar el usuario que califica y el jugador evaluado.");
                        return;
                    }
                    int stars;
                    try {
                        stars = Integer.parseInt(starsStr != null ? starsStr.trim() : "0");
                    } catch (NumberFormatException ex) {
                        enviarError(exchange, 400, "Las estrellas deben ser un número entero entre 1 y 5.");
                        return;
                    }
                    if (stars < 1 || stars > 5) {
                        enviarError(exchange, 400, "La valoración debe estar entre 1 y 5 estrellas.");
                        return;
                    }
                    try {
                        PerfilJugador actualizado = db.calificarJugador(rater, rated, stars, comment);
                        if (actualizado == null) {
                            enviarError(exchange, 404, "Usuario no encontrado.");
                            return;
                        }
                        enviarRespuestaJson(exchange, 200,
                                "{\"message\":\"Valoración de " + stars + "★ guardada\",\"profile\":" + perfilAJson(actualizado) + "}");
                    } catch (IllegalArgumentException iae) {
                        enviarError(exchange, 400, iae.getMessage());
                    }

                } else if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta nombre de usuario.");
                        return;
                    }

                    PerfilJugador perfil = db.buscarPerfilPorUsername(username);
                    if (perfil == null) {
                        enviarError(exchange, 404, "Perfil de jugador no encontrado.");
                        return;
                    }

                    // Si envía email o teléfono desde configuración de perfil, validar y actualizar tabla users
                    String email = datos.get("email");
                    String telefono = datos.get("telefono");
                    if (email != null || telefono != null) {
                        Usuario actual = db.buscarUsuarioPorUsername(username);
                        if (email != null && !email.trim().isEmpty()) {
                            if (!SeguridadUtil.isValidEmail(email)) {
                                enviarError(exchange, 400, "Formato de correo electrónico inválido.");
                                return;
                            }
                            Usuario dup = db.buscarUsuarioPorEmail(email);
                            if (dup != null && !dup.getId().equals(actual.getId())) {
                                enviarError(exchange, 409, "El correo electrónico ya está registrado en otra cuenta.");
                                return;
                            }
                        }
                        if (telefono != null && !telefono.trim().isEmpty() && !SeguridadUtil.isValidPhone(telefono)) {
                            enviarError(exchange, 400, "Número de teléfono inválido (debe tener entre 8 y 15 dígitos).");
                            return;
                        }
                        db.actualizarCuentaUsuario(username, email, telefono);
                    }

                    if (datos.containsKey("avatarUrl")) {
                        String avatarUrl = datos.get("avatarUrl");
                        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
                            if (!SeguridadUtil.isValidAvatarUrl(avatarUrl)) {
                                enviarError(exchange, 400, "Imagen de perfil no válida. Usa PNG, JPG, WebP, GIF (< 500 KB) o enlace HTTPS.");
                                return;
                            }
                            db.actualizarAvatarUsuario(username, avatarUrl);
                        }
                    }

                    if (datos.containsKey("bio")) perfil.setBiografia(datos.get("bio"));
                    if (datos.containsKey("region")) perfil.setRegion(datos.get("region"));
                    if (datos.containsKey("game")) perfil.setJuego(datos.get("game"));
                    if (datos.containsKey("rank")) perfil.setRango(datos.get("rank"));
                    if (datos.containsKey("role")) perfil.setRol(datos.get("role"));
                    if (datos.containsKey("playstyle")) perfil.setEstiloJuego(datos.get("playstyle"));
                    if (datos.containsKey("steamId")) perfil.setSteamId(datos.get("steamId"));
                    if (datos.containsKey("riotId")) perfil.setRiotId(datos.get("riotId"));
                    if (datos.containsKey("preferredSchedule")) perfil.setHorarioPreferido(datos.get("preferredSchedule"));
                    if (datos.containsKey("toxicLevel")) perfil.setNivelToxicidad(datos.get("toxicLevel"));
                    if (datos.containsKey("favoriteGames")) perfil.setFavoriteGames(datos.get("favoriteGames"));
                    if (datos.containsKey("favoriteGenres")) perfil.setFavoriteGenres(datos.get("favoriteGenres"));
                    if (datos.containsKey("playPreferences")) perfil.setPlayPreferences(datos.get("playPreferences"));

                    db.actualizarPerfil(perfil);
                    PerfilJugador actualizado = db.buscarPerfilPorUsername(username);
                    Usuario uAct = db.buscarUsuarioPorUsername(username);
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Perfil y cuenta guardados en la base de datos\",\"profile\":" + perfilAJson(actualizado) + ",\"user\":" + usuarioPrivadoAJson(uAct) + "}");
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando perfiles: " + e.getMessage());
            }
        }
    }

    static class ManejadorRecomendaciones implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorRecomendaciones(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(metodo)) {
                enviarError(exchange, 405, "Método no permitido.");
                return;
            }
            try {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                String username = params.get("username");
                if (username == null || username.trim().isEmpty()) {
                    enviarError(exchange, 400, "Debes indicar el parámetro username.");
                    return;
                }
                PerfilJugador actual = db.buscarPerfilPorUsername(username);
                if (actual == null) {
                    enviarError(exchange, 404, "Perfil no encontrado.");
                    return;
                }
                List<PerfilJugador> todos = db.obtenerTodosLosPerfiles();
                Set<String> blockedIds = db.obtenerIdsBloqueadosMutuos(username);
                if (!blockedIds.isEmpty()) {
                    List<PerfilJugador> filtrados = new ArrayList<>();
                    for (PerfilJugador p : todos) {
                        if (!blockedIds.contains(p.getUsuarioId())) filtrados.add(p);
                    }
                    todos = filtrados;
                }
                List<Juego> juegos = db.obtenerTodosLosJuegos(true);
                String json = ServicioRecomendacionIA.recomendarParaUsuarioJson(actual, todos, juegos, db.getConfigDB());
                enviarRespuestaJson(exchange, 200, json);
            } catch (Exception e) {
                enviarError(exchange, 500, "Error generando recomendaciones IA: " + e.getMessage());
            }
        }
    }

    static class ManejadorAutenticacion implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorAutenticacion(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String ruta = exchange.getRequestURI().getPath();
            String metodo = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo) && ruta.endsWith("/discord/callback")) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String code = params.get("code");
                    String state = params.get("state");
                    String oauthErr = params.get("error");
                    ConfiguracionDB cfg = db.getConfigDB();
                    String targetUsername = state != null ? DISCORD_OAUTH_STATES.remove(state) : null;

                    if (oauthErr != null || code == null || targetUsername == null || !cfg.isDiscordOAuthConfigured()) {
                        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Discord OAuth · PlayMatch</title></head>" +
                                "<body style='background:#080B12;color:#F8FAFC;font-family:sans-serif;display:grid;place-items:center;height:100vh'>" +
                                "<div style='padding:28px;border:1px solid #1E293B;border-radius:14px;background:#0F141F;max-width:420px;text-align:center'>" +
                                "<h2>Vinculación Discord no completada</h2><p style='color:#94A3B8'>Regresando a PlayMatch...</p>" +
                                "<script>setTimeout(()=>window.location.href='/',1400);</script></div></body></html>";
                        byte[] b = html.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                        exchange.sendResponseHeaders(200, b.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
                        return;
                    }

                    String redir = cfg.getDiscordRedirectUri() != null ? cfg.getDiscordRedirectUri() : "http://localhost:8080/api/auth/discord/callback";
                    String body = "client_id=" + URLEncoder.encode(cfg.getDiscordClientId(), "UTF-8") +
                            "&client_secret=" + URLEncoder.encode(cfg.getDiscordClientSecret(), "UTF-8") +
                            "&grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8") +
                            "&redirect_uri=" + URLEncoder.encode(redir, "UTF-8");

                    HttpURLConnection tc = (HttpURLConnection) URI.create("https://discord.com/api/oauth2/token").toURL().openConnection();
                    tc.setRequestMethod("POST");
                    tc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    tc.setDoOutput(true);
                    tc.setConnectTimeout(7000);
                    tc.setReadTimeout(7000);
                    try (OutputStream os = tc.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    if (tc.getResponseCode() >= 200 && tc.getResponseCode() < 300) {
                        String tokResp = new String(tc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        Map<String, String> tokMap = JsonUtils.parseSimpleJsonObject(tokResp);
                        String accessToken = tokMap.get("access_token");
                        if (accessToken != null && !accessToken.isEmpty()) {
                            HttpURLConnection uc = (HttpURLConnection) URI.create("https://discord.com/api/users/@me").toURL().openConnection();
                            uc.setRequestProperty("Authorization", "Bearer " + accessToken);
                            uc.setConnectTimeout(7000);
                            uc.setReadTimeout(7000);
                            if (uc.getResponseCode() >= 200 && uc.getResponseCode() < 300) {
                                String uResp = new String(uc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                Map<String, String> dUser = JsonUtils.parseSimpleJsonObject(uResp);
                                String dId = dUser.get("id");
                                String dTag = dUser.get("username");
                                if (dTag != null && dId != null) {
                                    db.actualizarConexionDiscord(targetUsername, dTag, dId, true);
                                }
                            }
                        }
                    }
                    exchange.getResponseHeaders().set("Location", "/?discord=connected");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }

                if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);

                    if (ruta.endsWith("/register")) {
                        String username = datos.get("username");
                        String email = datos.get("email");
                        String password = datos.get("password");
                        String telefono = datos.get("telefono");
                        if (telefono == null) telefono = datos.get("phone");
                        if (telefono == null) telefono = "";
                        String gameCode = datos.get("game");

                        if (username == null || username.trim().isEmpty() ||
                            email == null || email.trim().isEmpty() ||
                            password == null || password.isEmpty()) {
                            enviarError(exchange, 400, "Todos los campos obligatorios deben ser completados.");
                            return;
                        }
                        if (!SeguridadUtil.isValidUsername(username)) {
                            enviarError(exchange, 400, "El usuario debe tener entre 3 y 32 caracteres válidos.");
                            return;
                        }
                        if (!SeguridadUtil.isValidEmail(email)) {
                            enviarError(exchange, 400, "Ingresa un correo electrónico válido.");
                            return;
                        }
                        if (password.length() < 6) {
                            enviarError(exchange, 400, "La contraseña debe tener al menos 6 caracteres.");
                            return;
                        }
                        if (!telefono.trim().isEmpty() && !SeguridadUtil.isValidPhone(telefono)) {
                            enviarError(exchange, 400, "El número de teléfono debe tener entre 8 y 15 dígitos.");
                            return;
                        }
                        if (db.buscarUsuarioPorUsername(username) != null) {
                            enviarError(exchange, 409, "El nombre de usuario ya está registrado.");
                            return;
                        }
                        if (db.buscarUsuarioPorEmail(email) != null) {
                            enviarError(exchange, 409, "El correo electrónico ya está registrado.");
                            return;
                        }

                        Usuario u = db.crearUsuario(username, email, password, telefono, gameCode);
                        PerfilJugador p = db.buscarPerfilPorUsername(u.getUsername());
                        String token = "tok_" + UUID.randomUUID();
                        SESIONES_ACTIVAS.put(token, u.getId());
                        enviarRespuestaJson(exchange, 201,
                            "{\"message\":\"Usuario registrado y persistido en la base de datos\",\"token\":\"" + token + "\",\"user\":" + usuarioPrivadoAJson(u) + ",\"profile\":" + (p != null ? perfilAJson(p) : "null") + "}");

                    } else if (ruta.endsWith("/login")) {
                        String identifier = datos.get("username");
                        String password = datos.get("password");

                        if (identifier == null || identifier.trim().isEmpty() || password == null || password.isEmpty()) {
                            enviarError(exchange, 400, "Debes ingresar usuario/correo y contraseña.");
                            return;
                        }

                        Usuario u = db.buscarUsuarioPorIdentificador(identifier);
                        if (u == null || !SeguridadUtil.verifyPassword(password, u.getPasswordHash())) {
                            enviarError(exchange, 401, "Credenciales inválidas. Verifica tu usuario y contraseña.");
                            return;
                        }

                        PerfilJugador p = db.buscarPerfilPorUsername(u.getUsername());
                        String token = "tok_" + UUID.randomUUID();
                        SESIONES_ACTIVAS.put(token, u.getId());
                        enviarRespuestaJson(exchange, 200,
                            "{\"token\":\"" + token + "\",\"user\":" + usuarioPrivadoAJson(u) + ",\"profile\":" + (p != null ? perfilAJson(p) : "null") + "}");
                    }
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error de autenticación: " + e.getMessage());
            }
        }
    }

    static class ManejadorTorneos implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorTorneos(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String ruta = exchange.getRequestURI().getPath();
            String metodo = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    String[] partes = ruta.split("/");
                    if (partes.length > 3 && !partes[3].isEmpty()) {
                        String id = partes[3];
                        Torneo t = db.obtenerTorneoPorId(id);
                        if (t != null) {
                            enviarRespuestaJson(exchange, 200, torneoAJson(t));
                        } else {
                            enviarError(exchange, 404, "Torneo no encontrado");
                        }
                        return;
                    }

                    List<Torneo> torneos = db.obtenerTodosLosTorneos();
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < torneos.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(torneoAJson(torneos.get(i)));
                    }
                    sb.append("]");
                    enviarRespuestaJson(exchange, 200, sb.toString());

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/checkin")) {
                    String[] partes = ruta.split("/");
                    if (partes.length < 4) {
                        enviarError(exchange, 400, "ID de torneo inválido.");
                        return;
                    }
                    String torneoId = partes[3];
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String teamName = datos.get("teamName");
                    String actor = datos.containsKey("actor") ? datos.get("actor") : datos.get("username");
                    try {
                        Torneo t = db.hacerCheckInTorneo(torneoId, teamName, actor);
                        if (t == null) {
                            enviarError(exchange, 404, "Torneo no encontrado.");
                            return;
                        }
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("tournamentId", torneoId);
                        emitirEventoSse("tournament_update", null, ev);
                        enviarRespuestaJson(exchange, 200, "{\"message\":\"Check-in confirmado para " + JsonUtils.escape(teamName) + "\",\"tournament\":" + torneoAJson(t) + "}");
                    } catch (SecurityException se) {
                        enviarError(exchange, 403, se.getMessage());
                    } catch (IllegalArgumentException iae) {
                        enviarError(exchange, 400, iae.getMessage());
                    }

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/generate-bracket")) {
                    String[] partes = ruta.split("/");
                    if (partes.length < 4) {
                        enviarError(exchange, 400, "ID de torneo inválido.");
                        return;
                    }
                    String torneoId = partes[3];
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String actor = datos.containsKey("actor") ? datos.get("actor") : datos.get("username");
                    try {
                        Torneo t = db.generarLlavesTorneo(torneoId, actor);
                        if (t == null) {
                            enviarError(exchange, 404, "Torneo no encontrado.");
                            return;
                        }
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("tournamentId", torneoId);
                        emitirEventoSse("tournament_update", null, ev);
                        enviarRespuestaJson(exchange, 200, "{\"message\":\"Llaves del torneo generadas\",\"tournament\":" + torneoAJson(t) + "}");
                    } catch (SecurityException se) {
                        enviarError(exchange, 403, se.getMessage());
                    } catch (IllegalArgumentException iae) {
                        enviarError(exchange, 400, iae.getMessage());
                    }

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/match-result")) {
                    String[] partes = ruta.split("/");
                    if (partes.length < 4) {
                        enviarError(exchange, 400, "ID de torneo inválido.");
                        return;
                    }
                    String torneoId = partes[3];
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String matchId = datos.get("matchId");
                    String actor = datos.containsKey("actor") ? datos.get("actor") : datos.get("username");
                    if (matchId == null || matchId.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta matchId.");
                        return;
                    }
                    int s1, s2;
                    try {
                        s1 = Integer.parseInt(String.valueOf(datos.get("scoreTeam1")).trim());
                        s2 = Integer.parseInt(String.valueOf(datos.get("scoreTeam2")).trim());
                    } catch (Exception ex) {
                        enviarError(exchange, 400, "Los puntajes deben ser números enteros válidos.");
                        return;
                    }
                    try {
                        Torneo t = db.registrarResultadoPartida(torneoId, matchId, actor, s1, s2);
                        if (t == null) {
                            enviarError(exchange, 404, "Torneo o partida no encontrada.");
                            return;
                        }
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("tournamentId", torneoId);
                        emitirEventoSse("tournament_update", null, ev);
                        enviarRespuestaJson(exchange, 200, "{\"message\":\"Resultado guardado y llaves actualizadas\",\"tournament\":" + torneoAJson(t) + "}");
                    } catch (SecurityException se) {
                        enviarError(exchange, 403, se.getMessage());
                    } catch (IllegalArgumentException iae) {
                        enviarError(exchange, 400, iae.getMessage());
                    }

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.contains("/join")) {
                    String[] partes = ruta.split("/");
                    if (partes.length < 4) {
                        enviarError(exchange, 400, "ID de torneo inválido.");
                        return;
                    }
                    String torneoId = partes[3];
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String nombreEquipo = datos.get("teamName");
                    String capitan = datos.get("captain");

                    if (nombreEquipo == null || nombreEquipo.trim().isEmpty()) {
                        enviarError(exchange, 400, "Debe ingresar el nombre del equipo.");
                        return;
                    }

                    boolean ok = db.inscribirEquipoEnTorneo(torneoId, nombreEquipo, capitan);
                    if (ok) {
                        enviarRespuestaJson(exchange, 200, "{\"message\":\"Equipo " + JsonUtils.escape(nombreEquipo.trim()) + " inscrito exitosamente en la base de datos\"}");
                    } else {
                        enviarError(exchange, 400, "No se pudo inscribir (cupos llenos, equipo duplicado o torneo inexistente).");
                    }
                } else if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String titulo = datos.get("title");
                    String game = datos.get("game");
                    String organizer = datos.get("organizer");
                    String prizePool = datos.get("prizePool");
                    String startDate = datos.get("startDate");
                    String rules = datos.get("rules");
                    int maxTeams = 8;
                    if (datos.get("maxTeams") != null) {
                        try {
                            maxTeams = Integer.parseInt(datos.get("maxTeams").trim());
                        } catch (NumberFormatException ignored) {}
                    }

                    if (titulo == null || titulo.trim().isEmpty() || game == null || game.trim().isEmpty()) {
                        enviarError(exchange, 400, "Título y juego del torneo son obligatorios.");
                        return;
                    }

                    Torneo creado = db.crearTorneo(titulo, game, organizer, prizePool, "SINGLE_ELIMINATION", maxTeams, startDate, rules);
                    if (creado == null) {
                        enviarError(exchange, 400, "El juego seleccionado no existe.");
                        return;
                    }
                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Torneo creado y persistido en la base de datos\",\"tournament\":" + torneoAJson(creado) + "}");
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando torneos: " + e.getMessage());
            }
        }
    }

    static class ManejadorChat implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorChat(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if (ruta.startsWith("/api/chat/dm")) {
                    manejarDirectos(exchange, metodo, ruta);
                    return;
                }

                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String channel = params.get("channel");
                    if (channel == null || channel.isEmpty()) channel = "global";

                    List<MensajeChat> msgs = db.obtenerMensajesChat(channel);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < msgs.size(); i++) {
                        if (i > 0) sb.append(",");
                        MensajeChat m = msgs.get(i);
                        sb.append("{\"id\":\"").append(JsonUtils.escape(m.getId())).append("\",")
                          .append("\"sender\":\"").append(JsonUtils.escape(m.getRemitenteUsername())).append("\",")
                          .append("\"avatar\":\"").append(JsonUtils.escape(m.getRemitenteAvatar())).append("\",")
                          .append("\"channel\":\"").append(JsonUtils.escape(m.getCanal())).append("\",")
                          .append("\"content\":\"").append(JsonUtils.escape(m.getContenido())).append("\",")
                          .append("\"timestamp\":\"").append(JsonUtils.escape(m.getHoraEnvio())).append("\"}");
                    }
                    sb.append("]");
                    enviarRespuestaJson(exchange, 200, sb.toString());

                } else if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String sender = datos.get("sender");
                    String content = datos.get("content");
                    String channel = datos.get("channel");

                    if (sender == null || sender.trim().isEmpty() || content == null || content.trim().isEmpty()) {
                        enviarError(exchange, 400, "Mensaje vacío o sin remitente.");
                        return;
                    }

                    MensajeChat msg = new MensajeChat(null, sender.trim(), null,
                            (channel != null && !channel.trim().isEmpty()) ? channel.trim() : "global",
                            content.trim(), null);
                    db.agregarMensajeChat(msg);
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("id", msg.getId());
                    ev.put("sender", msg.getRemitenteUsername());
                    ev.put("channel", msg.getCanal());
                    ev.put("content", msg.getContenido());
                    emitirEventoSse("chat", null, ev);
                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Mensaje guardado en la base de datos\",\"id\":\"" + msg.getId() + "\"}");
                }
            } catch (IllegalArgumentException iae) {
                enviarError(exchange, 400, iae.getMessage());
            } catch (SecurityException se) {
                enviarError(exchange, 403, se.getMessage());
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando chat: " + e.getMessage());
            }
        }

        private void manejarDirectos(HttpExchange exchange, String metodo, String ruta) throws IOException {
            if ("GET".equalsIgnoreCase(metodo) && ruta.endsWith("/inbox")) {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                String username = params.get("username");
                if (username == null || username.trim().isEmpty()) {
                    enviarError(exchange, 400, "Falta parámetro username.");
                    return;
                }
                Map<String, Object> inbox = db.obtenerBandejaAmigos(username);
                if (inbox == null) {
                    enviarError(exchange, 404, "Usuario no encontrado.");
                    return;
                }
                enviarRespuestaJson(exchange, 200, JsonUtils.toJson(inbox));
                return;
            }

            if ("GET".equalsIgnoreCase(metodo) && ruta.endsWith("/messages")) {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                String user = params.get("user");
                String friend = params.get("friend");
                if (user == null || user.trim().isEmpty() || friend == null || friend.trim().isEmpty()) {
                    enviarError(exchange, 400, "Debes indicar user y friend.");
                    return;
                }
                Map<String, Object> hilo = db.obtenerMensajesDirectos(user, friend, true);
                if (hilo == null) {
                    enviarError(exchange, 404, "Usuario o amigo no encontrado.");
                    return;
                }
                enviarRespuestaJson(exchange, 200, JsonUtils.toJson(hilo));
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/messages")) {
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String sender = datos.get("sender");
                String receiver = datos.get("receiver");
                String content = datos.get("content");
                String msgType = datos.get("msgType");
                String attachmentUrl = datos.get("attachmentUrl");
                String lobbyId = datos.get("lobbyId");
                if (sender == null || sender.trim().isEmpty() || receiver == null || receiver.trim().isEmpty()) {
                    enviarError(exchange, 400, "Debes indicar remitente y destinatario.");
                    return;
                }
                Map<String, Object> dm = db.enviarMensajeDirecto(sender, receiver, content, msgType, attachmentUrl, lobbyId);
                if (dm == null) {
                    enviarError(exchange, 404, "Usuario o destinatario no encontrado.");
                    return;
                }
                Map<String, Object> evDm = new LinkedHashMap<>();
                evDm.put("sender", dm.get("sender"));
                evDm.put("receiver", dm.get("receiver"));
                evDm.put("dm", dm);
                emitirEventoSse("dm", String.valueOf(dm.get("receiver")), evDm);
                emitirEventoSse("dm", String.valueOf(dm.get("sender")), evDm);

                Map<String, Object> notif = new LinkedHashMap<>();
                notif.put("type", "dm");
                notif.put("from", dm.get("sender"));
                notif.put("preview", dm.get("content"));
                emitirEventoSse("notification", String.valueOf(dm.get("receiver")), notif);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("message", "Mensaje directo enviado");
                resp.put("dm", dm);
                enviarRespuestaJson(exchange, 201, JsonUtils.toJson(resp));
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/read")) {
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String username = datos.get("username");
                String friend = datos.get("friend");
                int updated = db.marcarMensajesDirectosLeidos(username, friend);
                if (updated > 0 && friend != null && !friend.trim().isEmpty()) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("reader", username);
                    ev.put("friend", friend.trim());
                    emitirEventoSse("dm_read", friend.trim(), ev);
                }
                enviarRespuestaJson(exchange, 200, "{\"updated\":" + updated + "}");
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/typing")) {
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String sender = datos.get("sender");
                String receiver = datos.get("receiver");
                boolean typing = "true".equalsIgnoreCase(String.valueOf(datos.get("typing")));
                if (sender != null && !sender.trim().isEmpty() && receiver != null && !receiver.trim().isEmpty()) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("sender", sender.trim());
                    ev.put("receiver", receiver.trim());
                    ev.put("typing", typing);
                    emitirEventoSse("typing", receiver.trim(), ev);
                }
                enviarRespuestaJson(exchange, 200, "{\"ok\":true}");
                return;
            }

            enviarError(exchange, 404, "Ruta de chat directo no encontrada.");
        }
    }

    static class ManejadorAmigos implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorAmigos(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String username = params.get("username");
                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta parámetro username.");
                        return;
                    }
                    Map<String, Object> inbox = db.obtenerBandejaAmigos(username);
                    if (inbox == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(inbox));

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/request")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String sender = datos.containsKey("sender") ? datos.get("sender") : datos.get("requester");
                    String receiver = datos.containsKey("receiver") ? datos.get("receiver") : datos.get("addressee");
                    if (sender == null || sender.trim().isEmpty() || receiver == null || receiver.trim().isEmpty()) {
                        enviarError(exchange, 400, "Debes indicar el remitente y destinatario de la amistad.");
                        return;
                    }
                    Map<String, Object> res = db.enviarSolicitudAmistad(sender, receiver);
                    if (res == null) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    Map<String, Object> notif = new LinkedHashMap<>();
                    notif.put("type", "friend_request");
                    notif.put("from", sender.trim());
                    emitirEventoSse("notification", receiver.trim(), notif);
                    enviarRespuestaJson(exchange, 201, JsonUtils.toJson(res));

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/respond")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String requestId = datos.get("requestId");
                    String username = datos.get("username");
                    String status = datos.get("status");
                    Map<String, Object> res = db.responderSolicitudAmistad(requestId, username, status);
                    if (res == null) {
                        enviarError(exchange, 404, "Solicitud de amistad no encontrada.");
                        return;
                    }
                    Map<String, Object> notif = new LinkedHashMap<>();
                    notif.put("type", "friend_respond");
                    notif.put("status", res.get("status"));
                    notif.put("from", username);
                    emitirEventoSse("notification", null, notif);
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(res));

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/remove")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String username = datos.get("username");
                    String friendUsername = datos.get("friendUsername");
                    if (username == null || username.trim().isEmpty() || friendUsername == null || friendUsername.trim().isEmpty()) {
                        enviarError(exchange, 400, "Debes indicar ambos usuarios.");
                        return;
                    }
                    boolean removed = db.eliminarAmistad(username, friendUsername);
                    enviarRespuestaJson(exchange, 200, "{\"removed\":" + removed + ",\"message\":\"" +
                            (removed ? "Amistad o solicitud eliminada." : "No existía relación previa.") + "\"}");
                } else {
                    enviarError(exchange, 404, "Operación de amigos no soportada.");
                }
            } catch (IllegalArgumentException iae) {
                enviarError(exchange, 400, iae.getMessage());
            } catch (SecurityException se) {
                enviarError(exchange, 403, se.getMessage());
            } catch (Exception e) {
                enviarError(exchange, 500, "Error gestionando amigos: " + e.getMessage());
            }
        }
    }

    static class ManejadorEmparejamiento implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorEmparejamiento(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String user = params.get("user");
                    List<SolicitudEmparejamiento> reqs = db.obtenerSolicitudesPorUsuario(user);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < reqs.size(); i++) {
                        if (i > 0) sb.append(",");
                        SolicitudEmparejamiento s = reqs.get(i);
                        sb.append("{")
                          .append("\"id\":\"").append(JsonUtils.escape(s.getId())).append("\",")
                          .append("\"sender\":\"").append(JsonUtils.escape(s.getRemitenteUsername())).append("\",")
                          .append("\"receiver\":\"").append(JsonUtils.escape(s.getDestinatarioUsername())).append("\",")
                          .append("\"game\":\"").append(JsonUtils.escape(s.getJuego())).append("\",")
                          .append("\"message\":\"").append(JsonUtils.escape(s.getMensaje())).append("\",")
                          .append("\"status\":\"").append(JsonUtils.escape(s.getEstado())).append("\",")
                          .append("\"createdAt\":\"").append(JsonUtils.escape(s.getFechaCreacion())).append("\"")
                          .append("}");
                    }
                    sb.append("]");
                    enviarRespuestaJson(exchange, 200, sb.toString());

                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/respond")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String requestId = datos.get("requestId");
                    String status = datos.get("status");

                    if (requestId == null || status == null ||
                        (!"ACCEPTED".equalsIgnoreCase(status) && !"REJECTED".equalsIgnoreCase(status))) {
                        enviarError(exchange, 400, "Parámetros de respuesta inválidos.");
                        return;
                    }

                    boolean updated = db.actualizarEstadoSolicitud(requestId, status);
                    if (updated) {
                        enviarRespuestaJson(exchange, 200, "{\"message\":\"Estado de invitación actualizado\",\"status\":\"" + status.toUpperCase() + "\"}");
                    } else {
                        enviarError(exchange, 404, "Solicitud no encontrada.");
                    }

                } else if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String sender = datos.get("sender");
                    String receiver = datos.get("receiver");
                    String game = datos.get("game");
                    String message = datos.get("message");

                    if (sender == null || receiver == null || sender.equalsIgnoreCase(receiver)) {
                        enviarError(exchange, 400, "Remitente y destinatario válidos son requeridos.");
                        return;
                    }

                    SolicitudEmparejamiento req = new SolicitudEmparejamiento(null, sender, receiver, game, message, "PENDING");
                    boolean ok = db.agregarSolicitudEmparejamiento(req);
                    if (!ok) {
                        enviarError(exchange, 400, "No se pudo registrar la invitación (usuario o juego inválido).");
                        return;
                    }

                    MensajeChat alert = new MensajeChat(null, "PlayMatch Sistema",
                            "https://api.dicebear.com/7.x/bottts/svg?seed=SystemBot", "global",
                            "[Invitación] @" + sender + " invitó a @" + receiver + " a jugar " + game +
                            (message != null && !message.trim().isEmpty() ? ": \"" + message.trim() + "\"" : ""),
                            null);
                    db.agregarMensajeChat(alert);

                    Map<String, Object> notif = new LinkedHashMap<>();
                    notif.put("type", "match_invite");
                    notif.put("from", sender);
                    notif.put("game", game);
                    emitirEventoSse("notification", receiver, notif);
                    Map<String, Object> chatEv = new LinkedHashMap<>();
                    chatEv.put("channel", "global");
                    emitirEventoSse("chat", null, chatEv);

                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Invitación guardada en la base de datos\",\"requestId\":\"" + req.getId() + "\"}");
                }
            } catch (SecurityException se) {
                enviarError(exchange, 403, se.getMessage());
            } catch (IllegalArgumentException iae) {
                enviarError(exchange, 400, iae.getMessage());
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando solicitud: " + e.getMessage());
            }
        }
    }

    static class ManejadorLobbies implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorLobbies(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();
            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            try {
                if ("GET".equalsIgnoreCase(metodo)) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String game = params.get("game");
                    String viewer = params.get("viewer");
                    List<Map<String, Object>> list = db.obtenerLobbies(game, viewer);
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(list));
                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/join")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String lobbyId = datos.get("lobbyId");
                    String username = datos.get("username");
                    String roleNote = datos.get("roleNote");
                    if (lobbyId == null || username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Faltan lobbyId y username.");
                        return;
                    }
                    Map<String, Object> res = db.unirseALobby(lobbyId, username, roleNote);
                    if (res == null) {
                        enviarError(exchange, 404, "Lobby o usuario no encontrado.");
                        return;
                    }
                    emitirEventoSse("lobbies_update", null, res);
                    Map<String, Object> chEv = new LinkedHashMap<>();
                    chEv.put("channel", res.get("channelId"));
                    emitirEventoSse("chat", null, chEv);
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(res));
                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/leave")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String lobbyId = datos.get("lobbyId");
                    String username = datos.get("username");
                    if (lobbyId == null || username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Faltan lobbyId y username.");
                        return;
                    }
                    Map<String, Object> res = db.salirDeLobby(lobbyId, username);
                    if (res == null) {
                        enviarError(exchange, 404, "Lobby o usuario no encontrado.");
                        return;
                    }
                    emitirEventoSse("lobbies_update", null, res);
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(res));
                } else if ("POST".equalsIgnoreCase(metodo)) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String host = datos.containsKey("hostUsername") ? datos.get("hostUsername") : datos.get("username");
                    String game = datos.get("game");
                    String title = datos.get("title");
                    String mode = datos.get("mode");
                    String rolesNeeded = datos.get("rolesNeeded");
                    String scheduleLabel = datos.get("scheduleLabel");
                    int maxSlots = 5;
                    try {
                        if (datos.get("maxSlots") != null) {
                            maxSlots = Integer.parseInt(datos.get("maxSlots").trim());
                        }
                    } catch (NumberFormatException ignored) {}
                    if (host == null || host.trim().isEmpty() || game == null || game.trim().isEmpty()) {
                        enviarError(exchange, 400, "Anfitrión y juego son obligatorios.");
                        return;
                    }
                    Map<String, Object> created = db.crearLobby(host, game, title, mode, maxSlots, rolesNeeded, scheduleLabel);
                    if (created == null) {
                        enviarError(exchange, 404, "Usuario anfitrión no encontrado.");
                        return;
                    }
                    emitirEventoSse("lobbies_update", null, created);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("message", "Lobby creado con éxito");
                    resp.put("lobby", created);
                    enviarRespuestaJson(exchange, 201, JsonUtils.toJson(resp));
                } else {
                    enviarError(exchange, 404, "Ruta de lobby no encontrada.");
                }
            } catch (SecurityException se) {
                enviarError(exchange, 403, se.getMessage());
            } catch (IllegalArgumentException iae) {
                enviarError(exchange, 400, iae.getMessage());
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando lobbies: " + e.getMessage());
            }
        }
    }

    static class ManejadorModeracion implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorModeracion(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();
            String ruta = exchange.getRequestURI().getPath();
            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            try {
                if ("GET".equalsIgnoreCase(metodo) && ruta.endsWith("/blocks")) {
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String username = params.get("username");
                    if (username == null || username.trim().isEmpty()) {
                        enviarError(exchange, 400, "Falta parámetro username.");
                        return;
                    }
                    List<Map<String, Object>> blocked = db.obtenerUsuariosBloqueados(username);
                    enviarRespuestaJson(exchange, 200, JsonUtils.toJson(blocked));
                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/block")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String blocker = datos.containsKey("blocker") ? datos.get("blocker") : datos.get("blockerUsername");
                    String blocked = datos.containsKey("blocked") ? datos.get("blocked") : datos.get("blockedUsername");
                    if (blocker == null || blocked == null || blocker.trim().isEmpty() || blocked.trim().isEmpty()) {
                        enviarError(exchange, 400, "Debes indicar quién bloquea y a quién.");
                        return;
                    }
                    boolean ok = db.bloquearUsuario(blocker, blocked);
                    if (!ok) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("type", "moderation_update");
                    emitirEventoSse("notification", blocker, ev);
                    emitirEventoSse("notification", blocked, ev);
                    enviarRespuestaJson(exchange, 200, "{\"blocked\":true,\"message\":\"Usuario bloqueado correctamente\"}");
                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/unblock")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String blocker = datos.containsKey("blocker") ? datos.get("blocker") : datos.get("blockerUsername");
                    String blocked = datos.containsKey("blocked") ? datos.get("blocked") : datos.get("blockedUsername");
                    boolean ok = db.desbloquearUsuario(blocker, blocked);
                    enviarRespuestaJson(exchange, 200, "{\"unblocked\":" + ok + ",\"message\":\"" + (ok ? "Usuario desbloqueado" : "No estaba bloqueado") + "\"}");
                } else if ("POST".equalsIgnoreCase(metodo) && ruta.endsWith("/report")) {
                    String cuerpo = leerCuerpo(exchange);
                    Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                    String reporter = datos.containsKey("reporter") ? datos.get("reporter") : datos.get("reporterUsername");
                    String reported = datos.containsKey("reported") ? datos.get("reported") : datos.get("reportedUsername");
                    String reason = datos.get("reason");
                    String details = datos.get("details");
                    if (reporter == null || reported == null || reporter.trim().isEmpty() || reported.trim().isEmpty()) {
                        enviarError(exchange, 400, "Debes indicar el jugador reportado.");
                        return;
                    }
                    boolean ok = db.reportarJugador(reporter, reported, reason, details);
                    if (!ok) {
                        enviarError(exchange, 404, "Usuario no encontrado.");
                        return;
                    }
                    enviarRespuestaJson(exchange, 201, "{\"reported\":true,\"message\":\"Reporte enviado al sistema de moderación\"}");
                } else {
                    enviarError(exchange, 404, "Ruta de moderación no encontrada.");
                }
            } catch (IllegalArgumentException iae) {
                enviarError(exchange, 400, iae.getMessage());
            } catch (Exception e) {
                enviarError(exchange, 500, "Error en moderación: " + e.getMessage());
            }
        }
    }

    static class ManejadorArchivosEstaticos implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String ruta = exchange.getRequestURI().getPath();
            if (ruta == null || ruta.equals("/") || ruta.isEmpty()) {
                ruta = "/index.html";
            }

            if (ruta.contains("..")) {
                enviarRespuesta(exchange, 403, "403 Forbidden");
                return;
            }

            File archivo = new File(DIRECTORIO_PUBLICO, ruta.startsWith("/") ? ruta.substring(1) : ruta);
            if (!archivo.exists() || archivo.isDirectory()) {
                archivo = new File(DIRECTORIO_PUBLICO, "index.html");
                if (!archivo.exists()) {
                    enviarRespuesta(exchange, 404, "404 Not Found");
                    return;
                }
            }

            String mime = obtenerTipoMime(archivo.getName());
            exchange.getResponseHeaders().set("Content-Type", mime);
            if ("sw.js".equalsIgnoreCase(archivo.getName())) {
                exchange.getResponseHeaders().set("Service-Worker-Allowed", "/");
            }
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Cache-Control", mime.startsWith("video/") ? "public, max-age=3600" : "no-store, no-cache, must-revalidate, max-age=0");
            if (!mime.startsWith("video/")) {
                exchange.getResponseHeaders().set("Pragma", "no-cache");
            }

            byte[] bytes = Files.readAllBytes(archivo.toPath());
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && bytes.length > 0) {
                String spec = rangeHeader.substring("bytes=".length()).trim();
                int commaIdx = spec.indexOf(',');
                if (commaIdx > 0) spec = spec.substring(0, commaIdx).trim();
                int dashIdx = spec.indexOf('-');
                try {
                    long start = 0;
                    long end = bytes.length - 1L;
                    if (dashIdx == 0) {
                        long suffix = Long.parseLong(spec.substring(1));
                        start = Math.max(0L, bytes.length - suffix);
                    } else if (dashIdx > 0) {
                        start = Long.parseLong(spec.substring(0, dashIdx));
                        if (dashIdx < spec.length() - 1) {
                            end = Math.min(bytes.length - 1L, Long.parseLong(spec.substring(dashIdx + 1)));
                        }
                    }
                    if (start >= 0 && start <= end && end < bytes.length) {
                        int len = (int) (end - start + 1);
                        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
                        exchange.sendResponseHeaders(206, len);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(bytes, (int) start, len);
                        } catch (IOException ignored) {}
                        return;
                    }
                } catch (NumberFormatException ignored) {}
            }

            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            } catch (IOException ignored) {}
        }

        private String obtenerTipoMime(String nombreArchivo) {
            String lower = nombreArchivo.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
            if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
            if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".mp4")) return "video/mp4";
            if (lower.endsWith(".webm")) return "video/webm";
            if (lower.endsWith(".ico")) return "image/x-icon";
            return "text/plain; charset=UTF-8";
        }
    }

    private static String juegoAJson(Juego j) {
        return "{" +
                "\"id\":\"" + JsonUtils.escape(j.getId()) + "\"," +
                "\"code\":\"" + JsonUtils.escape(j.getCodigo()) + "\"," +
                "\"name\":\"" + JsonUtils.escape(j.getNombre()) + "\"," +
                "\"category\":\"" + JsonUtils.escape(j.getCategoria()) + "\"," +
                "\"platform\":\"" + JsonUtils.escape(j.getPlataforma()) + "\"," +
                "\"maxTeamSize\":" + j.getMaxTamanoEquipo() + "," +
                "\"description\":\"" + JsonUtils.escape(j.getDescripcion()) + "\"," +
                "\"accentColor\":\"" + JsonUtils.escape(j.getColorAcento()) + "\"," +
                "\"bannerUrl\":\"" + JsonUtils.escape(j.getBannerUrl()) + "\"," +
                "\"active\":" + j.isActivo() + "," +
                "\"playerCount\":" + j.getCantidadJugadores() + "," +
                "\"tournamentCount\":" + j.getCantidadTorneos() + "," +
                "\"createdAt\":\"" + JsonUtils.escape(j.getFechaCreacion()) + "\"" +
                "}";
    }

    private static String perfilAJson(PerfilJugador p) {
        return "{" +
                "\"id\":\"" + JsonUtils.escape(p.getId()) + "\"," +
                "\"userId\":\"" + JsonUtils.escape(p.getUsuarioId()) + "\"," +
                "\"username\":\"" + JsonUtils.escape(p.getUsername()) + "\"," +
                "\"bio\":\"" + JsonUtils.escape(p.getBiografia()) + "\"," +
                "\"region\":\"" + JsonUtils.escape(p.getRegion()) + "\"," +
                "\"toxicLevel\":\"" + JsonUtils.escape(p.getNivelToxicidad()) + "\"," +
                "\"reputationScore\":" + p.getPuntajeReputacion() + "," +
                "\"preferredSchedule\":\"" + JsonUtils.escape(p.getHorarioPreferido()) + "\"," +
                "\"steamId\":\"" + JsonUtils.escape(p.getSteamId()) + "\"," +
                "\"riotId\":\"" + JsonUtils.escape(p.getRiotId()) + "\"," +
                "\"discordTag\":\"" + JsonUtils.escape(p.getDiscordTag()) + "\"," +
                "\"discordId\":\"" + JsonUtils.escape(p.getDiscordId()) + "\"," +
                "\"discordConnected\":" + p.isDiscordConnected() + "," +
                "\"favoriteGames\":\"" + JsonUtils.escape(p.getFavoriteGames()) + "\"," +
                "\"favoriteGenres\":\"" + JsonUtils.escape(p.getFavoriteGenres()) + "\"," +
                "\"playPreferences\":\"" + JsonUtils.escape(p.getPlayPreferences()) + "\"," +
                "\"starRating\":" + String.format(Locale.US, "%.1f", p.getStarRating()) + "," +
                "\"ratingCount\":" + p.getRatingCount() + "," +
                "\"lowRatedPenalty\":" + (p.getStarRating() < 2.0) + "," +
                "\"game\":\"" + JsonUtils.escape(p.getJuego()) + "\"," +
                "\"gameName\":\"" + JsonUtils.escape(p.getNombreJuego()) + "\"," +
                "\"rank\":\"" + JsonUtils.escape(p.getRango()) + "\"," +
                "\"role\":\"" + JsonUtils.escape(p.getRol()) + "\"," +
                "\"playstyle\":\"" + JsonUtils.escape(p.getEstiloJuego()) + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(p.getAvatarUrl()) + "\"" +
                "}";
    }

    private static String usuarioPrivadoAJson(Usuario u) {
        return "{" +
                "\"id\":\"" + JsonUtils.escape(u.getId()) + "\"," +
                "\"username\":\"" + JsonUtils.escape(u.getUsername()) + "\"," +
                "\"email\":\"" + JsonUtils.escape(u.getEmail()) + "\"," +
                "\"telefono\":\"" + JsonUtils.escape(u.getTelefono() != null ? u.getTelefono() : "") + "\"," +
                "\"hasPhone\":" + (u.getTelefono() != null && !u.getTelefono().trim().isEmpty()) + "," +
                "\"phoneVerified\":" + u.isTelefonoVerificado() + "," +
                "\"role\":\"" + JsonUtils.escape(u.getRol()) + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(u.getAvatarUrl()) + "\"," +
                "\"createdAt\":\"" + JsonUtils.escape(u.getFechaCreacion()) + "\"" +
                "}";
    }

    private static String usuarioPublicoAJson(Usuario u) {
        return "{" +
                "\"id\":\"" + JsonUtils.escape(u.getId()) + "\"," +
                "\"username\":\"" + JsonUtils.escape(u.getUsername()) + "\"," +
                "\"email\":\"" + JsonUtils.escape(u.getEmail()) + "\"," +
                "\"telefono\":\"\"," +
                "\"hasPhone\":" + (u.getTelefono() != null && !u.getTelefono().trim().isEmpty()) + "," +
                "\"phoneVerified\":" + u.isTelefonoVerificado() + "," +
                "\"role\":\"" + JsonUtils.escape(u.getRol()) + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(u.getAvatarUrl()) + "\"," +
                "\"createdAt\":\"" + JsonUtils.escape(u.getFechaCreacion()) + "\"" +
                "}";
    }

    private static String torneoAJson(Torneo t) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":\"").append(JsonUtils.escape(t.getId())).append("\",");
        sb.append("\"title\":\"").append(JsonUtils.escape(t.getTitulo())).append("\",");
        sb.append("\"game\":\"").append(JsonUtils.escape(t.getJuego())).append("\",");
        sb.append("\"gameName\":\"").append(JsonUtils.escape(t.getNombreJuego())).append("\",");
        sb.append("\"organizer\":\"").append(JsonUtils.escape(t.getOrganizador())).append("\",");
        sb.append("\"prizePool\":\"").append(JsonUtils.escape(t.getPozoPremios())).append("\",");
        sb.append("\"format\":\"").append(JsonUtils.escape(t.getFormato())).append("\",");
        sb.append("\"maxTeams\":").append(t.getMaxEquipos()).append(",");
        sb.append("\"registeredTeamsCount\":").append(t.getCantidadEquiposInscritos()).append(",");
        sb.append("\"startDate\":\"").append(JsonUtils.escape(t.getFechaInicio())).append("\",");
        sb.append("\"status\":\"").append(JsonUtils.escape(t.getEstado())).append("\",");
        sb.append("\"rules\":\"").append(JsonUtils.escape(t.getReglas())).append("\",");
        sb.append("\"bannerUrl\":\"").append(JsonUtils.escape(t.getBannerUrl())).append("\",");

        sb.append("\"registeredTeams\":[");
        for (int i = 0; i < t.getEquiposInscritos().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(JsonUtils.escape(t.getEquiposInscritos().get(i))).append("\"");
        }
        sb.append("],");
        sb.append("\"registrations\":").append(JsonUtils.toJson(t.getInscripciones())).append(",");

        sb.append("\"matches\":[");
        for (int i = 0; i < t.getPartidas().size(); i++) {
            if (i > 0) sb.append(",");
            PartidaTorneo m = t.getPartidas().get(i);
            sb.append("{")
              .append("\"id\":\"").append(JsonUtils.escape(m.getId())).append("\",")
              .append("\"roundName\":\"").append(JsonUtils.escape(m.getNombreRonda())).append("\",")
              .append("\"roundIndex\":").append(m.getRoundIndex()).append(",")
              .append("\"order\":").append(m.getOrden()).append(",")
              .append("\"nextMatchOrder\":").append(m.getNextMatchOrder()).append(",")
              .append("\"nextSlot\":").append(m.getNextSlot()).append(",")
              .append("\"team1\":\"").append(JsonUtils.escape(m.getEquipo1())).append("\",")
              .append("\"team2\":\"").append(JsonUtils.escape(m.getEquipo2())).append("\",")
              .append("\"scoreTeam1\":").append(m.getPuntajeEquipo1()).append(",")
              .append("\"scoreTeam2\":").append(m.getPuntajeEquipo2()).append(",")
              .append("\"winner\":").append(m.getGanador() != null ? "\"" + JsonUtils.escape(m.getGanador()) + "\"" : "null").append(",")
              .append("\"status\":\"").append(JsonUtils.escape(m.getEstado())).append("\"")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return result;
        for (String param : rawQuery.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                try {
                    String k = URLDecoder.decode(param.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(param.substring(eq + 1), "UTF-8");
                    result.put(k, v);
                } catch (UnsupportedEncodingException ignored) {}
            }
        }
        return result;
    }

    private static String extraerTokenBearer(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || auth.trim().isEmpty()) return null;
        String trimmed = auth.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private static String leerCuerpo(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] datos = new byte[4096];
        int leido;
        int total = 0;
        final int MAX_BYTES = 2 * 1024 * 1024;
        while ((leido = is.read(datos, 0, datos.length)) != -1) {
            total += leido;
            if (total > MAX_BYTES) {
                throw new IOException("El cuerpo de la solicitud supera el límite de 2 MB.");
            }
            buffer.write(datos, 0, leido);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void enviarError(HttpExchange exchange, int codigoEstado, String mensaje) throws IOException {
        enviarRespuestaJson(exchange, codigoEstado, "{\"error\":\"" + JsonUtils.escape(mensaje) + "\"}");
    }

    private static void enviarRespuestaJson(HttpExchange exchange, int codigoEstado, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(codigoEstado, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void enviarRespuesta(HttpExchange exchange, int codigoEstado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(codigoEstado, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void agregarCabecerasCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
}
