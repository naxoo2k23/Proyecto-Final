package com.playmatch.model;

import java.util.ArrayList;
import java.util.List;

public class Tournament {
    private String id;
    private String title;
    private String game;      // "valorant", "lol", "cs2"
    private String gameName;
    private String organizer;
    private String prizePool;
    private String format;    // "SINGLE_ELIMINATION"
    private int maxTeams;
    private int registeredTeamsCount;
    private String startDate;
    private String status;    // "OPEN_REGISTRATION", "IN_PROGRESS", "COMPLETED"
    private String rules;
    private String bannerUrl;
    private List<String> registeredTeams;
    private List<TournamentMatch> matches;

    public Tournament() {
        this.registeredTeams = new ArrayList<>();
        this.matches = new ArrayList<>();
    }

    public Tournament(String id, String title, String game, String gameName, String organizer, 
                      String prizePool, String format, int maxTeams, String startDate, 
                      String status, String rules, String bannerUrl) {
        this.id = id;
        this.title = title;
        this.game = game;
        this.gameName = gameName;
        this.organizer = organizer;
        this.prizePool = prizePool;
        this.format = format;
        this.maxTeams = maxTeams;
        this.startDate = startDate;
        this.status = status;
        this.rules = rules;
        this.bannerUrl = bannerUrl;
        this.registeredTeams = new ArrayList<>();
        this.matches = new ArrayList<>();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getGame() { return game; }
    public void setGame(String game) { this.game = game; }
    public String getGameName() { return gameName; }
    public void setGameName(String gameName) { this.gameName = gameName; }
    public String getOrganizer() { return organizer; }
    public void setOrganizer(String organizer) { this.organizer = organizer; }
    public String getPrizePool() { return prizePool; }
    public void setPrizePool(String prizePool) { this.prizePool = prizePool; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public int getMaxTeams() { return maxTeams; }
    public void setMaxTeams(int maxTeams) { this.maxTeams = maxTeams; }
    public int getRegisteredTeamsCount() { return registeredTeams != null ? registeredTeams.size() : 0; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRules() { return rules; }
    public void setRules(String rules) { this.rules = rules; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public List<String> getRegisteredTeams() { return registeredTeams; }
    public void setRegisteredTeams(List<String> registeredTeams) { this.registeredTeams = registeredTeams; }
    public List<TournamentMatch> getMatches() { return matches; }
    public void setMatches(List<TournamentMatch> matches) { this.matches = matches; }
}
