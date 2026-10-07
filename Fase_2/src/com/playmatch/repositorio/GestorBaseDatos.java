package com.playmatch.repositorio;

import com.playmatch.modelo.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class GestorBaseDatos {
    private static GestorBaseDatos instancia;

    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final Map<String, PerfilJugador> perfilesJugador = new ConcurrentHashMap<>();
    private final Map<String, Torneo> torneos = new ConcurrentHashMap<>();
    private final List<MensajeChat> mensajesChat = new CopyOnWriteArrayList<>();
    private final List<SolicitudEmparejamiento> solicitudesEmparejamiento = new CopyOnWriteArrayList<>();

    private final File archivoDatos = new File("data/almacen_datos.json");

    private GestorBaseDatos() {
        sembrarDatosIniciales();
    }

    public static synchronized GestorBaseDatos getInstancia() {
        if (instancia == null) {
            instancia = new GestorBaseDatos();
        }
        return instancia;
    }

    private void sembrarDatosIniciales() {
        // 1. Usuarios base
        Usuario u1 = new Usuario("u-1", "Naxoo_Viper", "jesus.villasanti@playmatch.cl", "hash_secret_123", "ADMIN", "https://api.dicebear.com/7.x/bottts/svg?seed=Naxoo_Viper");
        Usuario u2 = new Usuario("u-2", "ChiloeGamer", "cristobal@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=ChiloeGamer");
        Usuario u3 = new Usuario("u-3", "Valkyria_CL", "valeria.gonzalez@gmail.com", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=Valkyria_CL");
        Usuario u4 = new Usuario("u-4", "ShadowStrike", "matias.perez@live.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=ShadowStrike");
        Usuario u5 = new Usuario("u-5", "AndesEsports", "contacto@andesesports.cl", "hash_secret_123", "ORGANIZER", "https://api.dicebear.com/7.x/bottts/svg?seed=AndesEsports");

        usuarios.put(u1.getId(), u1);
        usuarios.put(u2.getId(), u2);
        usuarios.put(u3.getId(), u3);
        usuarios.put(u4.getId(), u4);
        usuarios.put(u5.getId(), u5);

        // 2. Perfiles de jugador (Contexto gamer chileno, Zero Toxic)
        PerfilJugador p1 = new PerfilJugador(
            "p-1", u1.getId(), u1.getUsername(),
            "Main Controller en Valorant. Busco equipo serio para competir en torneos de la comunidad chilena.",
            "Chile", "Región Metropolitana (San Joaquín)", "ZERO_TOXIC", 100,
            "Noches (21:00 - 01:00)", "76561198000000001", "Naxoo#CHI", "naxoo_dev#0001",
            "valorant", "Valorant", "Inmortal 1", "Controlador (Omen / Viper)", "TRYHARD", u1.getAvatarUrl()
        );

        PerfilJugador p2 = new PerfilJugador(
            "p-2", u2.getId(), u2.getUsername(),
            "Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Cero toxicidad.",
            "Chile", "Los Lagos (Castro)", "ZERO_TOXIC", 98,
            "Tardes y Noches (19:00 - 23:30)", "76561198000000002", "Chiloe#SUR", "chiloe_cl#1234",
            "valorant", "Valorant", "Ascendente 2", "Iniciador (Fade / Sova)", "COMPETITIVE", u2.getAvatarUrl()
        );

        PerfilJugador p3 = new PerfilJugador(
            "p-3", u3.getId(), u3.getUsername(),
            "Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta season.",
            "Chile", "Valparaíso (Viña del Mar)", "ZERO_TOXIC", 99,
            "Fines de semana y noches", "76561198000000003", "Valkyria#LAS", "valky#5544",
            "lol", "League of Legends", "Diamante IV", "Support (Thresh / Lulu)", "COMPETITIVE", u3.getAvatarUrl()
        );

        PerfilJugador p4 = new PerfilJugador(
            "p-4", u4.getId(), u4.getUsername(),
            "AWPer en CS2. Juego competitivo, comunicación clara por Discord. Disponible para scrims.",
            "Chile", "Biobío (Concepción)", "FRIENDLY", 95,
            "Noches (22:00 - 02:00)", "76561198000000004", "Shadow#AWP", "shadow#8899",
            "cs2", "Counter-Strike 2", "Nivel 8 Faceit (16.500 ELO)", "Sniper / Entry", "TRYHARD", u4.getAvatarUrl()
        );

        perfilesJugador.put(p1.getId(), p1);
        perfilesJugador.put(p2.getId(), p2);
        perfilesJugador.put(p3.getId(), p3);
        perfilesJugador.put(p4.getId(), p4);

        // 3. Torneos
        Torneo t1 = new Torneo(
            "t-1", "Copa Universitaria Valorant Santiago 2026", "valorant", "Valorant",
            "Andes Esports", "$200.000 CLP + Skins Riot", "SINGLE_ELIMINATION", 8,
            "15 de Noviembre, 18:00 hrs", "OPEN_REGISTRATION",
            "Torneo 5v5 al mejor de 1 (Bo1). Final Bo3. Anticheat obligatorio. Tolerancia 10 minutos.",
            "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80"
        );
        t1.getEquiposInscritos().add("Andes Raptors (Cap. Naxoo_Viper)");
        t1.getEquiposInscritos().add("Cordillera Clan (Cap. ChiloeGamer)");
        t1.getEquiposInscritos().add("Valpo Storm (Cap. Valkyria_CL)");
        t1.getEquiposInscritos().add("Biobío Phoenix (Cap. ShadowStrike)");

        Torneo t3 = new Torneo(
            "t-3", "Master Series CS2 - Red Bull Cup", "cs2", "Counter-Strike 2",
            "Red Bull Gaming Sphere", "$300.000 CLP + Periféricos HyperX", "SINGLE_ELIMINATION", 4,
            "25 de Noviembre, 17:00 hrs", "IN_PROGRESS",
            "Servidores 128 tickrate en Santiago. Eliminación directa. Overtime en MR3.",
            "https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80"
        );
        t3.getEquiposInscritos().add("Andes Raptors");
        t3.getEquiposInscritos().add("Cordillera Clan");
        t3.getEquiposInscritos().add("Valpo Storm");
        t3.getEquiposInscritos().add("Biobío Phoenix");

        t3.getPartidas().add(new PartidaTorneo("m-1", "Semifinal 1", 1, "Andes Raptors", "Cordillera Clan", 13, 9, "Andes Raptors", "FINISHED"));
        t3.getPartidas().add(new PartidaTorneo("m-2", "Semifinal 2", 2, "Valpo Storm", "Biobío Phoenix", 11, 13, "Biobío Phoenix", "FINISHED"));
        t3.getPartidas().add(new PartidaTorneo("m-3", "Gran Final", 3, "Andes Raptors", "Biobío Phoenix", 0, 0, null, "SCHEDULED"));

        Torneo t2 = new Torneo(
            "t-2", "Liga Amateur League of Legends Chile - Season 1", "lol", "League of Legends",
            "Comunidad Gamer Chile", "$150.000 CLP", "SINGLE_ELIMINATION", 8,
            "20 de Noviembre, 19:00 hrs", "OPEN_REGISTRATION",
            "Modo torneo reclutamiento. Servidor LAS. Prohibido conducta antideportiva.",
            "https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80"
        );
        t2.getEquiposInscritos().add("Fenix Gaming");
        t2.getEquiposInscritos().add("Santiago Knights");

        torneos.put(t1.getId(), t1);
        torneos.put(t2.getId(), t2);
        torneos.put(t3.getId(), t3);

        // 4. Mensajes de chat iniciales
        mensajesChat.add(new MensajeChat("c-1", "Naxoo_Viper", u1.getAvatarUrl(), "global", "¡Bienvenidos a la beta de PlayMatch! Buscando 2 jugadores para scrim de Valorant hoy a las 22:00.", "22:10"));
        mensajesChat.add(new MensajeChat("c-2", "ChiloeGamer", u2.getAvatarUrl(), "global", "Buena bro, yo me sumo de iniciador. Cero toxicidad.", "22:12"));
        mensajesChat.add(new MensajeChat("c-3", "Valkyria_CL", u3.getAvatarUrl(), "global", "¿Alguien para dúo LoL en LAS? Estoy en Diamante IV.", "22:15"));
    }

    public Collection<Usuario> obtenerTodosLosUsuarios() { return usuarios.values(); }

    public Usuario buscarUsuarioPorUsername(String username) {
        for (Usuario u : usuarios.values()) {
            if (u.getUsername().equalsIgnoreCase(username)) return u;
        }
        return null;
    }

    public Usuario buscarUsuarioPorEmail(String email) {
        for (Usuario u : usuarios.values()) {
            if (u.getEmail().equalsIgnoreCase(email)) return u;
        }
        return null;
    }

    public synchronized Usuario crearUsuario(String username, String email, String password) {
        Usuario u = new Usuario(UUID.randomUUID().toString(), username, email, "hash_" + password, "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=" + username);
        usuarios.put(u.getId(), u);

        PerfilJugador p = new PerfilJugador(
            UUID.randomUUID().toString(), u.getId(), u.getUsername(),
            "Nuevo jugador en PlayMatch. ¡Listo para jugar!",
            "Chile", "Región Metropolitana", "ZERO_TOXIC", 100,
            "Noches (21:00 - 01:00)", "", username + "#CHI", username,
            "valorant", "Valorant", "Platino I", "Flex", "COMPETITIVE", u.getAvatarUrl()
        );
        perfilesJugador.put(p.getId(), p);
        return u;
    }

    public Collection<PerfilJugador> obtenerTodosLosPerfiles() { return perfilesJugador.values(); }

    public PerfilJugador buscarPerfilPorUsername(String username) {
        for (PerfilJugador p : perfilesJugador.values()) {
            if (p.getUsername().equalsIgnoreCase(username)) return p;
        }
        return null;
    }

    public synchronized void actualizarPerfil(PerfilJugador actualizado) {
        for (Map.Entry<String, PerfilJugador> entry : perfilesJugador.entrySet()) {
            if (entry.getValue().getUsername().equalsIgnoreCase(actualizado.getUsername())) {
                actualizado.setId(entry.getKey());
                perfilesJugador.put(entry.getKey(), actualizado);
                return;
            }
        }
        perfilesJugador.put(actualizado.getId(), actualizado);
    }

    public Collection<Torneo> obtenerTodosLosTorneos() { return torneos.values(); }

    public Torneo obtenerTorneoPorId(String id) { return torneos.get(id); }

    public synchronized boolean inscribirEquipoEnTorneo(String torneoId, String nombreEquipo) {
        Torneo t = torneos.get(torneoId);
        if (t != null && t.getEquiposInscritos().size() < t.getMaxEquipos()) {
            t.getEquiposInscritos().add(nombreEquipo);
            return true;
        }
        return false;
    }

    public List<MensajeChat> obtenerMensajesChat() { return mensajesChat; }

    public synchronized void agregarMensajeChat(MensajeChat msg) {
        mensajesChat.add(msg);
        if (mensajesChat.size() > 100) {
            mensajesChat.remove(0);
        }
    }

    public List<SolicitudEmparejamiento> obtenerSolicitudesEmparejamiento() { return solicitudesEmparejamiento; }

    public synchronized void agregarSolicitudEmparejamiento(SolicitudEmparejamiento req) {
        solicitudesEmparejamiento.add(req);
    }
}
