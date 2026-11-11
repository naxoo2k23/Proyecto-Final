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
    private boolean telefonoVerificado;
    private String fechaCreacion;

    public Usuario() {
        this.id = UUID.randomUUID().toString();
        this.rol = "GAMER";
        this.telefono = "";
        this.telefonoVerificado = false;
    }

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl) {
        this(id, username, email, passwordHash, rol, avatarUrl, "", false, "");
    }

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl, String telefono) {
        this(id, username, email, passwordHash, rol, avatarUrl, telefono, false, "");
    }

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl, String telefono, String fechaCreacion) {
        this(id, username, email, passwordHash, rol, avatarUrl, telefono, false, fechaCreacion);
    }

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl, String telefono, boolean telefonoVerificado, String fechaCreacion) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.rol = rol != null ? rol : "GAMER";
        this.avatarUrl = avatarUrl != null ? avatarUrl : "/api/avatar?u=" + username;
        this.telefono = telefono != null ? telefono : "";
        this.telefonoVerificado = telefonoVerificado;
        this.fechaCreacion = fechaCreacion != null ? fechaCreacion : "";
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

    public boolean isTelefonoVerificado() { return telefonoVerificado; }
    public void setTelefonoVerificado(boolean telefonoVerificado) { this.telefonoVerificado = telefonoVerificado; }

    public String getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(String fechaCreacion) { this.fechaCreacion = fechaCreacion; }
}
