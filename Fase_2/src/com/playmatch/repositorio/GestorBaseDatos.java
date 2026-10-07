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
        // 1. Usuarios base con nombres reales de jugadores
        Usuario u1 = new Usuario("u-1", "Jesus_Villasanti", "jesus.villasanti@playmatch.cl", "hash_secret_123", "ADMIN", "https://api.dicebear.com/7.x/bottts/svg?seed=Jesus_Villasanti", "+56 9 8123 4567");
        Usuario u2 = new Usuario("u-2", "Datrikk", "datrikk@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=Datrikk", "+56 9 7234 5678");
        Usuario u3 = new Usuario("u-3", "dididong", "dididong@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=dididong", "+56 9 6345 6789");
        Usuario u4 = new Usuario("u-4", "luchi", "luchi@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=luchi", "+56 9 5456 7890");
        Usuario u5 = new Usuario("u-5", "jaeliug", "jaeliug@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=jaeliug", "+56 9 4567 8901");
        Usuario u6 = new Usuario("u-6", "pepitogamer67", "pepitogamer67@playmatch.cl", "hash_secret_123", "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=pepitogamer67", "+56 9 3678 9012");
        Usuario u7 = new Usuario("u-7", "AndesEsports", "contacto@andesesports.cl", "hash_secret_123", "ORGANIZER", "https://api.dicebear.com/7.x/bottts/svg?seed=AndesEsports", "+56 9 2111 2222");

        usuarios.put(u1.getId(), u1);
        usuarios.put(u2.getId(), u2);
        usuarios.put(u3.getId(), u3);
        usuarios.put(u4.getId(), u4);
        usuarios.put(u5.getId(), u5);
        usuarios.put(u6.getId(), u6);
        usuarios.put(u7.getId(), u7);

        // 2. Perfiles de jugador (Comunidad competitiva chilena, Zero Toxic)
        PerfilJugador p1 = new PerfilJugador(
            "p-1", u1.getId(), u1.getUsername(),
            "Main Controller en Valorant. Busco escuadra seria para competir en torneos comunitarios y subir elo.",
            "Chile", "Región Metropolitana (San Joaquín)", "ZERO_TOXIC", 100,
            "Noches (21:00 - 01:00)", "76561198000000001", "Jesus#CHI", "jesus_villasanti#0001",
            "valorant", "Valorant", "Inmortal 1", "Controlador (Omen / Viper)", "TRYHARD", u1.getAvatarUrl()
        );

        PerfilJugador p2 = new PerfilJugador(
            "p-2", u2.getId(), u2.getUsername(),
            "Jugador de CS2 y Valorant. Chill, buena onda y comunicativo. Excelente sinergia de equipo.",
            "Chile", "Los Lagos (Castro)", "ZERO_TOXIC", 98,
            "Tardes y Noches (19:00 - 23:30)", "76561198000000002", "Datrikk#SUR", "datrikk_cl#1234",
            "valorant", "Valorant", "Ascendente 2", "Iniciador (Fade / Sova)", "COMPETITIVE", u2.getAvatarUrl()
        );

        PerfilJugador p3 = new PerfilJugador(
            "p-3", u3.getId(), u3.getUsername(),
            "Soporte y Midlaner en League of Legends. Busco dúo para subir a Diamante esta temporada.",
            "Chile", "Valparaíso (Viña del Mar)", "ZERO_TOXIC", 99,
            "Fines de semana y noches", "76561198000000003", "dididong#LAS", "dididong#5544",
            "lol", "League of Legends", "Diamante IV", "Support (Thresh / Lulu)", "COMPETITIVE", u3.getAvatarUrl()
        );

        PerfilJugador p4 = new PerfilJugador(
            "p-4", u4.getId(), u4.getUsername(),
            "AWPer en CS2. Comunicación clara por Discord, juego limpio y competitivo. Disponible para scrims.",
            "Chile", "Biobío (Concepción)", "FRIENDLY", 96,
            "Noches (22:00 - 02:00)", "76561198000000004", "luchi#AWP", "luchi#8899",
            "cs2", "Counter-Strike 2", "Nivel 8 Faceit (16.500 ELO)", "Sniper / Entry", "TRYHARD", u4.getAvatarUrl()
        );

        PerfilJugador p5 = new PerfilJugador(
            "p-5", u5.getId(), u5.getUsername(),
            "Main Duelista en Valorant. Rango Inmortal 2, busco compañeros para torneos 5v5 con micro activo.",
            "Chile", "Región Metropolitana (La Florida)", "ZERO_TOXIC", 97,
            "Tardes (18:00 - 22:30)", "76561198000000005", "jaeliug#CL1", "jaeliug#7711",
            "valorant", "Valorant", "Inmortal 2", "Duelista (Jett / Reyna)", "COMPETITIVE", u5.getAvatarUrl()
        );

        PerfilJugador p6 = new PerfilJugador(
            "p-6", u6.getId(), u6.getUsername(),
            "Toplaner en League of Legends. Buen macrogame, splitpush y rotaciones a objetivos. Cero flameo.",
            "Chile", "Región Metropolitana (Maipú)", "FRIENDLY", 95,
            "Noches (21:30 - 01:30)", "76561198000000006", "pepito#LAS", "pepitogamer67#3322",
            "lol", "League of Legends", "Platino I", "Toplaner (Aatrox / Jax)", "COMPETITIVE", u6.getAvatarUrl()
        );

        perfilesJugador.put(p1.getId(), p1);
        perfilesJugador.put(p2.getId(), p2);
        perfilesJugador.put(p3.getId(), p3);
        perfilesJugador.put(p4.getId(), p4);
        perfilesJugador.put(p5.getId(), p5);
        perfilesJugador.put(p6.getId(), p6);

        // 3. Torneos
        Torneo t1 = new Torneo(
            "t-1", "Copa Universitaria Valorant Santiago 2026", "valorant", "Valorant",
            "Andes Esports", "$200.000 CLP + Skins Riot", "SINGLE_ELIMINATION", 8,
            "15 de Noviembre, 18:00 hrs", "OPEN_REGISTRATION",
            "Torneo 5v5 al mejor de 1 (Bo1). Final Bo3. Anticheat obligatorio. Tolerancia 10 minutos.",
            "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80"
        );
        t1.getEquiposInscritos().add("Andes Raptors (Cap. Jesus_Villasanti)");
        t1.getEquiposInscritos().add("Cordillera Clan (Cap. Datrikk)");
        t1.getEquiposInscritos().add("Valpo Storm (Cap. dididong)");
        t1.getEquiposInscritos().add("Biobío Phoenix (Cap. luchi)");

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
    }

    public Collection<Usuario> obtenerTodosLosUsuarios() { return usuarios.values(); }

    public Usuario buscarUsuarioPorUsername(String username) {
        if (username == null) return null;
        String normalizado = username.trim().replace(" ", "_");
        for (Usuario u : usuarios.values()) {
            if (u.getUsername().equalsIgnoreCase(username) ||
                u.getUsername().equalsIgnoreCase(normalizado) ||
                u.getUsername().replace("_", " ").equalsIgnoreCase(username.trim())) {
                return u;
            }
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
        return crearUsuario(username, email, password, "");
    }

    public synchronized Usuario crearUsuario(String username, String email, String password, String telefono) {
        Usuario u = new Usuario(UUID.randomUUID().toString(), username, email, "hash_" + password, "GAMER", "https://api.dicebear.com/7.x/bottts/svg?seed=" + username, telefono);
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
        if (username == null) return null;
        String normalizado = username.trim().replace(" ", "_");
        for (PerfilJugador p : perfilesJugador.values()) {
            if (p.getUsername().equalsIgnoreCase(username) ||
                p.getUsername().equalsIgnoreCase(normalizado) ||
                p.getUsername().replace("_", " ").equalsIgnoreCase(username.trim())) {
                return p;
            }
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
