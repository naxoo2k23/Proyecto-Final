package com.playmatch.model;

public class TournamentMatch {
    private String id;
    private String roundName; // "Cuartos de Final", "Semifinal", "Gran Final"
    private int order;
    private String team1;
    private String team2;
    private int scoreTeam1;
    private int scoreTeam2;
    private String winner;
    private String status;    // "FINISHED", "LIVE", "SCHEDULED"

    public TournamentMatch() {}

    public TournamentMatch(String id, String roundName, int order, String team1, String team2, 
                           int scoreTeam1, int scoreTeam2, String winner, String status) {
        this.id = id;
        this.roundName = roundName;
        this.order = order;
        this.team1 = team1;
        this.team2 = team2;
        this.scoreTeam1 = scoreTeam1;
        this.scoreTeam2 = scoreTeam2;
        this.winner = winner;
        this.status = status;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRoundName() { return roundName; }
    public void setRoundName(String roundName) { this.roundName = roundName; }
    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }
    public String getTeam1() { return team1; }
    public void setTeam1(String team1) { this.team1 = team1; }
    public String getTeam2() { return team2; }
    public void setTeam2(String team2) { this.team2 = team2; }
    public int getScoreTeam1() { return scoreTeam1; }
    public void setScoreTeam1(int scoreTeam1) { this.scoreTeam1 = scoreTeam1; }
    public int getScoreTeam2() { return scoreTeam2; }
    public void setScoreTeam2(int scoreTeam2) { this.scoreTeam2 = scoreTeam2; }
    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
