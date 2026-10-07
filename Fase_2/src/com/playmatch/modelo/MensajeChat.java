package com.playmatch.modelo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class MensajeChat {
    private String id;
    private String remitenteUsername;
    private String remitenteAvatar;
    private String canal; // "global", "valorant", "torneo-t-1"
    private String contenido;
    private String horaEnvio;

    public MensajeChat() {
        this.id = UUID.randomUUID().toString();
        this.horaEnvio = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public MensajeChat(String id, String remitenteUsername, String remitenteAvatar, String canal, String contenido, String horaEnvio) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.remitenteUsername = remitenteUsername;
        this.remitenteAvatar = remitenteAvatar != null ? remitenteAvatar : "https://api.dicebear.com/7.x/bottts/svg?seed=" + remitenteUsername;
        this.canal = canal != null ? canal : "global";
        this.contenido = contenido;
        this.horaEnvio = horaEnvio != null ? horaEnvio : LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRemitenteUsername() { return remitenteUsername; }
    public void setRemitenteUsername(String remitenteUsername) { this.remitenteUsername = remitenteUsername; }
    public String getRemitenteAvatar() { return remitenteAvatar; }
    public void setRemitenteAvatar(String remitenteAvatar) { this.remitenteAvatar = remitenteAvatar; }
    public String getCanal() { return canal; }
    public void setCanal(String canal) { this.canal = canal; }
    public String getContenido() { return contenido; }
    public void setContenido(String contenido) { this.contenido = contenido; }
    public String getHoraEnvio() { return horaEnvio; }
    public void setHoraEnvio(String horaEnvio) { this.horaEnvio = horaEnvio; }
}
