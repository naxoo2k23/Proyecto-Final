package com.playmatch.modelo;

import java.util.UUID;

public class Usuario {
    private String id;
    private String username;
    private String email;
    private String passwordHash;
    private String rol;
    private String avatarUrl;
    private String telefono;

    public Usuario() {
        this.id = UUID.randomUUID().toString();
        this.rol = "GAMER";
        this.telefono = "";
    }

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl) {
        this(id, username, email, passwordHash, rol, avatarUrl, "");
    }

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl, String telefono) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.rol = rol != null ? rol : "GAMER";
        this.avatarUrl = avatarUrl != null ? avatarUrl : "https://api.dicebear.com/7.x/bottts/svg?seed=" + username;
        this.telefono = telefono != null ? telefono : "";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRol() { return rol; }
    public void setRol(String rol) { this.rol = rol; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
}
