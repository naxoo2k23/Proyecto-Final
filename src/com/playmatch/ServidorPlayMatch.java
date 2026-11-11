package com.playmatch;

import com.playmatch.modelo.*;
import com.playmatch.repositorio.GestorBaseDatos;
import com.playmatch.servicio.ServicioRecomendacionIA;
import com.playmatch.util.JsonUtils;
import com.playmatch.util.SeguridadUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class ServidorPlayMatch {
    private static final int PUERTO_POR_DEFECTO = 8080;
    private static final String DIRECTORIO_PUBLICO = "public";
    private static final Map<String, String> SESIONES_ACTIVAS = new ConcurrentHashMap<>();
    private static final Map<String, CodigoVerificacion> CODIGOS_TELEFONO = new ConcurrentHashMap<>();

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
        servidor.createContext("/api/games", new ManejadorJuegos(db));
        servidor.createContext("/api/users", new ManejadorUsuarios(db));
        servidor.createContext("/api/players", new ManejadorJugadores(db));
        servidor.createContext("/api/recommendations", new ManejadorRecomendaciones(db));
        servidor.createContext("/api/auth", new ManejadorAutenticacion(db));
        servidor.createContext("/api/tournaments", new ManejadorTorneos(db));
        servidor.createContext("/api/chat", new ManejadorChat(db));
        servidor.createContext("/api/match", new ManejadorEmparejamiento(db));
        servidor.createContext("/", new ManejadorArchivosEstaticos());

        servidor.setExecutor(Executors.newFixedThreadPool(12));
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
                        "\"matchRequests\":" + stats.get("matchRequests") +
                        "}";
                enviarRespuestaJson(exchange, 200, json);
            } catch (Exception e) {
                enviarError(exchange, 500, "Error obteniendo estadísticas de base de datos");
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
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Código de verificación generado (modo demo)\",\"demoCode\":\"" + codigo + "\"}");

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
                    String filtroJuego = params.get("game");
                    String filtroToxic = params.get("toxic");
                    String busqueda = params.get("q");

                    List<PerfilJugador> perfiles = db.buscarPerfiles(filtroJuego, filtroToxic, busqueda);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < perfiles.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(perfilAJson(perfiles.get(i)));
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

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            try {
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
                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Mensaje guardado en la base de datos\",\"id\":\"" + msg.getId() + "\"}");
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando chat: " + e.getMessage());
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

                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Invitación guardada en la base de datos\",\"requestId\":\"" + req.getId() + "\"}");
                }
            } catch (Exception e) {
                enviarError(exchange, 500, "Error procesando solicitud: " + e.getMessage());
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
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            byte[] bytes = Files.readAllBytes(archivo.toPath());
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String obtenerTipoMime(String nombreArchivo) {
            if (nombreArchivo.endsWith(".html")) return "text/html; charset=UTF-8";
            if (nombreArchivo.endsWith(".css")) return "text/css; charset=UTF-8";
            if (nombreArchivo.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (nombreArchivo.endsWith(".json")) return "application/json; charset=UTF-8";
            if (nombreArchivo.endsWith(".svg")) return "image/svg+xml";
            if (nombreArchivo.endsWith(".png")) return "image/png";
            if (nombreArchivo.endsWith(".jpg") || nombreArchivo.endsWith(".jpeg")) return "image/jpeg";
            if (nombreArchivo.endsWith(".ico")) return "image/x-icon";
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

        sb.append("\"matches\":[");
        for (int i = 0; i < t.getPartidas().size(); i++) {
            if (i > 0) sb.append(",");
            PartidaTorneo m = t.getPartidas().get(i);
            sb.append("{")
              .append("\"id\":\"").append(JsonUtils.escape(m.getId())).append("\",")
              .append("\"roundName\":\"").append(JsonUtils.escape(m.getNombreRonda())).append("\",")
              .append("\"order\":").append(m.getOrden()).append(",")
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
