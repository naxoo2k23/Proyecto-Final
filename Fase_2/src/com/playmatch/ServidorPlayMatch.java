package com.playmatch;

import com.playmatch.modelo.*;
import com.playmatch.repositorio.GestorBaseDatos;
import com.playmatch.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

public class ServidorPlayMatch {
    private static final int PUERTO_POR_DEFECTO = 8080;
    private static final String DIRECTORIO_PUBLICO = "public";

    public static void main(String[] args) throws IOException {
        int puerto = PUERTO_POR_DEFECTO;
        if (args.length > 0) {
            try {
                puerto = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        GestorBaseDatos db = GestorBaseDatos.getInstancia();

        // Enrutador de APIs
        servidor.createContext("/api/health", new ManejadorSalud());
        servidor.createContext("/api/players", new ManejadorJugadores(db));
        servidor.createContext("/api/auth", new ManejadorAutenticacion(db));
        servidor.createContext("/api/tournaments", new ManejadorTorneos(db));
        servidor.createContext("/api/chat", new ManejadorChat(db));
        servidor.createContext("/api/match", new ManejadorEmparejamiento(db));
        servidor.createContext("/", new ManejadorArchivosEstaticos());

        servidor.setExecutor(Executors.newFixedThreadPool(10));
        servidor.start();

        System.out.println("===============================================================");
        System.out.println("   PLAYMATCH - Plataforma Web Gamers (Servidor Java Iniciado)  ");
        System.out.println("===============================================================");
        System.out.println(" > Servidor activo en: http://localhost:" + puerto);
        System.out.println(" > Directorio publico: " + new File(DIRECTORIO_PUBLICO).getAbsolutePath());
        System.out.println(" > Base de datos: Gestor relacional simulado en memoria");
        System.out.println(" > Presione Ctrl+C para detener el servidor.");
        System.out.println("===============================================================");
    }

    static class ManejadorSalud implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarRespuesta(exchange, 204, "");
                return;
            }
            String json = "{\"status\":\"UP\",\"project\":\"PlayMatch\",\"phase\":\"Fase 2 - MVP\",\"time\":\"" + new Date() + "\"}";
            enviarRespuestaJson(exchange, 200, json);
        }
    }

    static class ManejadorJugadores implements HttpHandler {
        private final GestorBaseDatos db;
        public ManejadorJugadores(GestorBaseDatos db) { this.db = db; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCabecerasCors(exchange);
            String metodo = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            if ("GET".equalsIgnoreCase(metodo)) {
                String query = exchange.getRequestURI().getQuery();
                String filtroJuego = null;
                String filtroToxic = null;

                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] par = param.split("=");
                        if (par.length == 2) {
                            if ("game".equalsIgnoreCase(par[0])) filtroJuego = URLDecoder.decode(par[1], "UTF-8");
                            if ("toxic".equalsIgnoreCase(par[0])) filtroToxic = URLDecoder.decode(par[1], "UTF-8");
                        }
                    }
                }

                StringBuilder sb = new StringBuilder("[");
                boolean primero = true;
                for (PerfilJugador p : db.obtenerTodosLosPerfiles()) {
                    if (filtroJuego != null && !filtroJuego.isEmpty() && !filtroJuego.equalsIgnoreCase("all") && !p.getJuego().equalsIgnoreCase(filtroJuego)) {
                        continue;
                    }
                    if (filtroToxic != null && !filtroToxic.isEmpty() && !p.getNivelToxicidad().equalsIgnoreCase(filtroToxic)) {
                        continue;
                    }
                    if (!primero) sb.append(",");
                    sb.append(perfilAJson(p));
                    primero = false;
                }
                sb.append("]");
                enviarRespuestaJson(exchange, 200, sb.toString());

            } else if ("POST".equalsIgnoreCase(metodo)) {
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String username = datos.get("username");
                if (username == null || username.isEmpty()) {
                    enviarRespuestaJson(exchange, 400, "{\"error\":\"Falta nombre de usuario\"}");
                    return;
                }
                PerfilJugador perfil = db.buscarPerfilPorUsername(username);
                if (perfil == null) {
                    perfil = new PerfilJugador();
                    perfil.setUsername(username);
                }
                if (datos.containsKey("bio")) perfil.setBiografia(datos.get("bio"));
                if (datos.containsKey("region")) perfil.setRegion(datos.get("region"));
                if (datos.containsKey("game")) perfil.setJuego(datos.get("game"));
                if (datos.containsKey("rank")) perfil.setRango(datos.get("rank"));
                if (datos.containsKey("role")) perfil.setRol(datos.get("role"));
                if (datos.containsKey("playstyle")) perfil.setEstiloJuego(datos.get("playstyle"));
                if (datos.containsKey("steamId")) perfil.setSteamId(datos.get("steamId"));
                if (datos.containsKey("riotId")) perfil.setRiotId(datos.get("riotId"));
                if (datos.containsKey("discordTag")) perfil.setDiscordTag(datos.get("discordTag"));
                if (datos.containsKey("preferredSchedule")) perfil.setHorarioPreferido(datos.get("preferredSchedule"));
                if (datos.containsKey("toxicLevel")) perfil.setNivelToxicidad(datos.get("toxicLevel"));

                db.actualizarPerfil(perfil);
                enviarRespuestaJson(exchange, 200, "{\"message\":\"Perfil actualizado con exito\",\"profile\":" + perfilAJson(perfil) + "}");
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

                    if (username == null || email == null || password == null) {
                        enviarRespuestaJson(exchange, 400, "{\"error\":\"Campos incompletos\"}");
                        return;
                    }
                    if (db.buscarUsuarioPorUsername(username) != null) {
                        enviarRespuestaJson(exchange, 409, "{\"error\":\"El nombre de usuario ya existe\"}");
                        return;
                    }
                    Usuario u = db.crearUsuario(username, email, password, telefono);
                    PerfilJugador p = db.buscarPerfilPorUsername(username);
                    enviarRespuestaJson(exchange, 201, "{\"message\":\"Usuario registrado\",\"user\":" + usuarioAJson(u) + ",\"profile\":" + (p != null ? perfilAJson(p) : "null") + "}");

                } else if (ruta.endsWith("/login")) {
                    String username = datos.get("username");
                    String password = datos.get("password");

                    Usuario u = db.buscarUsuarioPorUsername(username);
                    if (u != null) {
                        PerfilJugador p = db.buscarPerfilPorUsername(username);
                        enviarRespuestaJson(exchange, 200, "{\"token\":\"tok_" + UUID.randomUUID() + "\",\"user\":" + usuarioAJson(u) + ",\"profile\":" + (p != null ? perfilAJson(p) : "null") + "}");
                    } else {
                        enviarRespuestaJson(exchange, 401, "{\"error\":\"Credenciales invalidas\"}");
                    }
                }
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

            if ("GET".equalsIgnoreCase(metodo)) {
                String[] partes = ruta.split("/");
                if (partes.length > 3) {
                    String id = partes[3];
                    Torneo t = db.obtenerTorneoPorId(id);
                    if (t != null) {
                        enviarRespuestaJson(exchange, 200, torneoAJson(t));
                        return;
                    } else {
                        enviarRespuestaJson(exchange, 404, "{\"error\":\"Torneo no encontrado\"}");
                        return;
                    }
                }

                StringBuilder sb = new StringBuilder("[");
                boolean primero = true;
                for (Torneo t : db.obtenerTodosLosTorneos()) {
                    if (!primero) sb.append(",");
                    sb.append(torneoAJson(t));
                    primero = false;
                }
                sb.append("]");
                enviarRespuestaJson(exchange, 200, sb.toString());

            } else if ("POST".equalsIgnoreCase(metodo) && ruta.contains("/join")) {
                String[] partes = ruta.split("/");
                String torneoId = partes[3];
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String nombreEquipo = datos.get("teamName");

                if (nombreEquipo == null || nombreEquipo.isEmpty()) {
                    enviarRespuestaJson(exchange, 400, "{\"error\":\"Debe ingresar el nombre del equipo\"}");
                    return;
                }

                boolean ok = db.inscribirEquipoEnTorneo(torneoId, nombreEquipo);
                if (ok) {
                    enviarRespuestaJson(exchange, 200, "{\"message\":\"Equipo " + nombreEquipo + " inscrito exitosamente\"}");
                } else {
                    enviarRespuestaJson(exchange, 400, "{\"error\":\"No se pudo inscribir (cupos llenos o torneo no existe)\"}");
                }
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

            if ("GET".equalsIgnoreCase(metodo)) {
                StringBuilder sb = new StringBuilder("[");
                boolean primero = true;
                for (MensajeChat m : db.obtenerMensajesChat()) {
                    if (!primero) sb.append(",");
                    sb.append("{\"id\":\"").append(m.getId()).append("\",")
                      .append("\"sender\":\"").append(JsonUtils.escape(m.getRemitenteUsername())).append("\",")
                      .append("\"avatar\":\"").append(JsonUtils.escape(m.getRemitenteAvatar())).append("\",")
                      .append("\"channel\":\"").append(JsonUtils.escape(m.getCanal())).append("\",")
                      .append("\"content\":\"").append(JsonUtils.escape(m.getContenido())).append("\",")
                      .append("\"timestamp\":\"").append(m.getHoraEnvio()).append("\"}");
                    primero = false;
                }
                sb.append("]");
                enviarRespuestaJson(exchange, 200, sb.toString());

            } else if ("POST".equalsIgnoreCase(metodo)) {
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String sender = datos.get("sender");
                String content = datos.get("content");
                String channel = datos.get("channel");

                if (sender == null || content == null || content.trim().isEmpty()) {
                    enviarRespuestaJson(exchange, 400, "{\"error\":\"Mensaje vacio o sin remitente\"}");
                    return;
                }

                MensajeChat msg = new MensajeChat(null, sender, null, channel != null ? channel : "global", content, null);
                db.agregarMensajeChat(msg);
                enviarRespuestaJson(exchange, 201, "{\"message\":\"Mensaje enviado\",\"id\":\"" + msg.getId() + "\"}");
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

            if ("OPTIONS".equalsIgnoreCase(metodo)) {
                enviarRespuesta(exchange, 204, "");
                return;
            }

            if ("POST".equalsIgnoreCase(metodo)) {
                String cuerpo = leerCuerpo(exchange);
                Map<String, String> datos = JsonUtils.parseSimpleJsonObject(cuerpo);
                String sender = datos.get("sender");
                String receiver = datos.get("receiver");
                String game = datos.get("game");
                String message = datos.get("message");

                SolicitudEmparejamiento req = new SolicitudEmparejamiento(null, sender, receiver, game, message, "PENDING");
                db.agregarSolicitudEmparejamiento(req);

                MensajeChat alert = new MensajeChat(null, "PlayMatch Sistema", "https://api.dicebear.com/7.x/bottts/svg?seed=SystemBot", "global",
                        "[Notificacion] @" + sender + " envio una invitacion a @" + receiver + " para jugar " + game + ": \"" + message + "\"", null);
                db.agregarMensajeChat(alert);

                enviarRespuestaJson(exchange, 201, "{\"message\":\"Solicitud de emparejamiento enviada exitosamente\",\"requestId\":\"" + req.getId() + "\"}");
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

    private static String perfilAJson(PerfilJugador p) {
        return "{" +
                "\"id\":\"" + p.getId() + "\"," +
                "\"userId\":\"" + p.getUsuarioId() + "\"," +
                "\"username\":\"" + JsonUtils.escape(p.getUsername()) + "\"," +
                "\"bio\":\"" + JsonUtils.escape(p.getBiografia()) + "\"," +
                "\"region\":\"" + JsonUtils.escape(p.getRegion()) + "\"," +
                "\"toxicLevel\":\"" + p.getNivelToxicidad() + "\"," +
                "\"reputationScore\":" + p.getPuntajeReputacion() + "," +
                "\"preferredSchedule\":\"" + JsonUtils.escape(p.getHorarioPreferido()) + "\"," +
                "\"steamId\":\"" + JsonUtils.escape(p.getSteamId()) + "\"," +
                "\"riotId\":\"" + JsonUtils.escape(p.getRiotId()) + "\"," +
                "\"discordTag\":\"" + JsonUtils.escape(p.getDiscordTag()) + "\"," +
                "\"game\":\"" + JsonUtils.escape(p.getJuego()) + "\"," +
                "\"gameName\":\"" + JsonUtils.escape(p.getNombreJuego()) + "\"," +
                "\"rank\":\"" + JsonUtils.escape(p.getRango()) + "\"," +
                "\"role\":\"" + JsonUtils.escape(p.getRol()) + "\"," +
                "\"playstyle\":\"" + p.getEstiloJuego() + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(p.getAvatarUrl()) + "\"" +
                "}";
    }

    private static String usuarioAJson(Usuario u) {
        return "{" +
                "\"id\":\"" + u.getId() + "\"," +
                "\"username\":\"" + JsonUtils.escape(u.getUsername()) + "\"," +
                "\"email\":\"" + JsonUtils.escape(u.getEmail()) + "\"," +
                "\"telefono\":\"" + JsonUtils.escape(u.getTelefono() != null ? u.getTelefono() : "") + "\"," +
                "\"role\":\"" + u.getRol() + "\"," +
                "\"avatarUrl\":\"" + JsonUtils.escape(u.getAvatarUrl()) + "\"" +
                "}";
    }

    private static String torneoAJson(Torneo t) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":\"").append(t.getId()).append("\",");
        sb.append("\"title\":\"").append(JsonUtils.escape(t.getTitulo())).append("\",");
        sb.append("\"game\":\"").append(t.getJuego()).append("\",");
        sb.append("\"gameName\":\"").append(JsonUtils.escape(t.getNombreJuego())).append("\",");
        sb.append("\"organizer\":\"").append(JsonUtils.escape(t.getOrganizador())).append("\",");
        sb.append("\"prizePool\":\"").append(JsonUtils.escape(t.getPozoPremios())).append("\",");
        sb.append("\"format\":\"").append(t.getFormato()).append("\",");
        sb.append("\"maxTeams\":").append(t.getMaxEquipos()).append(",");
        sb.append("\"registeredTeamsCount\":").append(t.getCantidadEquiposInscritos()).append(",");
        sb.append("\"startDate\":\"").append(JsonUtils.escape(t.getFechaInicio())).append("\",");
        sb.append("\"status\":\"").append(t.getEstado()).append("\",");
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
              .append("\"id\":\"").append(m.getId()).append("\",")
              .append("\"roundName\":\"").append(JsonUtils.escape(m.getNombreRonda())).append("\",")
              .append("\"order\":").append(m.getOrden()).append(",")
              .append("\"team1\":\"").append(JsonUtils.escape(m.getEquipo1())).append("\",")
              .append("\"team2\":\"").append(JsonUtils.escape(m.getEquipo2())).append("\",")
              .append("\"scoreTeam1\":").append(m.getPuntajeEquipo1()).append(",")
              .append("\"scoreTeam2\":").append(m.getPuntajeEquipo2()).append(",")
              .append("\"winner\":").append(m.getGanador() != null ? "\"" + JsonUtils.escape(m.getGanador()) + "\"" : "null").append(",")
              .append("\"status\":\"").append(m.getEstado()).append("\"")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String leerCuerpo(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] datos = new byte[1024];
        int leido;
        while ((leido = is.read(datos, 0, datos.length)) != -1) {
            buffer.write(datos, 0, leido);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
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
