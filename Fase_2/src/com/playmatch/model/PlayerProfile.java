package com.playmatch.model;

public class PlayerProfile {
    private String id;
    private String userId;
    private String username;
    private String bio;
    private String country;
    private String region;
    private String toxicLevel; // "ZERO_TOXIC", "FRIENDLY", "COMPETITIVE"
    private int reputationScore;
    private String preferredSchedule;
    private String steamId;
    private String riotId;
    private String discordTag;
    private String game;      // "valorant", "lol", "cs2"
    private String gameName;  // "Valorant", "League of Legends", "CS2"
    private String rank;      // ej. "Inmortal 1", "Diamante IV"
    private String role;      // ej. "Controlador", "Soporte", "Sniper"
    private String playstyle; // "TRYHARD", "COMPETITIVE", "CASUAL"
    private String avatarUrl;

    public PlayerProfile() {}

    public PlayerProfile(String id, String userId, String username, String bio, String country, 
                         String region, String toxicLevel, int reputationScore, String preferredSchedule, 
                         String steamId, String riotId, String discordTag, String game, 
                         String gameName, String rank, String role, String playstyle, String avatarUrl) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.bio = bio;
        this.country = country;
        this.region = region;
        this.toxicLevel = toxicLevel;
        this.reputationScore = reputationScore;
        this.preferredSchedule = preferredSchedule;
        this.steamId = steamId;
        this.riotId = riotId;
        this.discordTag = discordTag;
        this.game = game;
        this.gameName = gameName;
        this.rank = rank;
        this.role = role;
        this.playstyle = playstyle;
        this.avatarUrl = avatarUrl != null ? avatarUrl : "https://api.dicebear.com/7.x/bottts/svg?seed=" + username;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getToxicLevel() { return toxicLevel; }
    public void setToxicLevel(String toxicLevel) { this.toxicLevel = toxicLevel; }
    public int getReputationScore() { return reputationScore; }
    public void setReputationScore(int reputationScore) { this.reputationScore = reputationScore; }
    public String getPreferredSchedule() { return preferredSchedule; }
    public void setPreferredSchedule(String preferredSchedule) { this.preferredSchedule = preferredSchedule; }
    public String getSteamId() { return steamId; }
    public void setSteamId(String steamId) { this.steamId = steamId; }
    public String getRiotId() { return riotId; }
    public void setRiotId(String riotId) { this.riotId = riotId; }
    public String getDiscordTag() { return discordTag; }
    public void setDiscordTag(String discordTag) { this.discordTag = discordTag; }
    public String getGame() { return game; }
    public void setGame(String game) { this.game = game; }
    public String getGameName() { return gameName; }
    public void setGameName(String gameName) { this.gameName = gameName; }
    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPlaystyle() { return playstyle; }
    public void setPlaystyle(String playstyle) { this.playstyle = playstyle; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
