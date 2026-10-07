package com.playmatch.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class MatchRequest {
    private String id;
    private String senderUsername;
    private String receiverUsername;
    private String game;
    private String message;
    private String status; // "PENDING", "ACCEPTED", "DECLINED"
    private String createdAt;

    public MatchRequest() {
        this.id = UUID.randomUUID().toString();
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public MatchRequest(String id, String senderUsername, String receiverUsername, String game, String message, String status) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.senderUsername = senderUsername;
        this.receiverUsername = receiverUsername;
        this.game = game;
        this.message = message;
        this.status = status != null ? status : "PENDING";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }
    public String getReceiverUsername() { return receiverUsername; }
    public void setReceiverUsername(String receiverUsername) { this.receiverUsername = receiverUsername; }
    public String getGame() { return game; }
    public void setGame(String game) { this.game = game; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
