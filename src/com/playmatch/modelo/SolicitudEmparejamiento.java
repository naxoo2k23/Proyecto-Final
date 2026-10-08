package com.playmatch.modelo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class SolicitudEmparejamiento {
    private String id;
    private String remitenteUsername;
    private String destinatarioUsername;
    private String juego;
    private String mensaje;
    private String estado; // "PENDING", "ACCEPTED", "DECLINED"
    private String fechaCreacion;

    public SolicitudEmparejamiento() {
        this.id = UUID.randomUUID().toString();
        this.estado = "PENDING";
        this.fechaCreacion = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public SolicitudEmparejamiento(String id, String remitenteUsername, String destinatarioUsername, String juego, String mensaje, String estado) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.remitenteUsername = remitenteUsername;
        this.destinatarioUsername = destinatarioUsername;
        this.juego = juego;
        this.mensaje = mensaje;
        this.estado = estado != null ? estado : "PENDING";
        this.fechaCreacion = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRemitenteUsername() { return remitenteUsername; }
    public void setRemitenteUsername(String remitenteUsername) { this.remitenteUsername = remitenteUsername; }
    public String getDestinatarioUsername() { return destinatarioUsername; }
    public void setDestinatarioUsername(String destinatarioUsername) { this.destinatarioUsername = destinatarioUsername; }
    public String getJuego() { return juego; }
    public void setJuego(String juego) { this.juego = juego; }
    public String getMensaje() { return mensaje; }
    public void setMensaje(String mensaje) { this.mensaje = mensaje; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(String fechaCreacion) { this.fechaCreacion = fechaCreacion; }
}
