package com.playmatch.model;

import java.util.UUID;

public class User {
    private String id;
    private String username;
    private String email;
    private String passwordHash;
    private String role;
    private String avatarUrl;

    public User() {
        this.id = UUID.randomUUID().toString();
        this.role = "GAMER";
    }

    public User(String id, String username, String email, String passwordHash, String role, String avatarUrl) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role != null ? role : "GAMER";
        this.avatarUrl = avatarUrl != null ? avatarUrl : "https://api.dicebear.com/7.x/bottts/svg?seed=" + username;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
