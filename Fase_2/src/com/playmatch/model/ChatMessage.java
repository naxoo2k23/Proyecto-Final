package com.playmatch.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class ChatMessage {
    private String id;
    private String senderUsername;
    private String senderAvatar;
    private String channel; // "global", "valorant", "tournament-t-1"
    private String content;
    private String timestamp;

    public ChatMessage() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public ChatMessage(String id, String senderUsername, String senderAvatar, String channel, String content, String timestamp) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.senderUsername = senderUsername;
        this.senderAvatar = senderAvatar != null ? senderAvatar : "https://api.dicebear.com/7.x/bottts/svg?seed=" + senderUsername;
        this.channel = channel != null ? channel : "global";
        this.content = content;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }
    public String getSenderAvatar() { return senderAvatar; }
    public void setSenderAvatar(String senderAvatar) { this.senderAvatar = senderAvatar; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
