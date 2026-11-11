package com.playmatch.modelo;

public class PerfilJugador {
    private String id;
    private String usuarioId;
    private String username;
    private String biografia;
    private String pais;
    private String region;
    private String nivelToxicidad; // "ZERO_TOXIC", "FRIENDLY", "COMPETITIVE"
    private int puntajeReputacion;
    private String horarioPreferido;
    private String steamId;
    private String riotId;
    private String discordTag;
    private String discordId;
    private boolean discordConnected;
    private String favoriteGames;
    private String favoriteGenres;
    private String playPreferences;
    private double starRating = 4.8;
    private int ratingCount = 0;
    private String cardFrame = "NONE";
    private String juego;          // "valorant", "lol", "cs2"
    private String nombreJuego;    // "Valorant", "League of Legends", "CS2"
    private String rango;          // ej. "Inmortal 1", "Diamante IV"
    private String rol;            // ej. "Controlador", "Soporte", "Sniper"
    private String estiloJuego;    // "TRYHARD", "COMPETITIVE", "CASUAL"
    private String avatarUrl;

    public PerfilJugador() {}

    public PerfilJugador(String id, String usuarioId, String username, String biografia, String pais, 
                         String region, String nivelToxicidad, int puntajeReputacion, String horarioPreferido, 
                         String steamId, String riotId, String discordTag, String juego, 
                         String nombreJuego, String rango, String rol, String estiloJuego, String avatarUrl) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.username = username;
        this.biografia = biografia;
        this.pais = pais;
        this.region = region;
        this.nivelToxicidad = nivelToxicidad;
        this.puntajeReputacion = puntajeReputacion;
        this.horarioPreferido = horarioPreferido;
        this.steamId = steamId;
        this.riotId = riotId;
        this.discordTag = discordTag;
        this.juego = juego;
        this.nombreJuego = nombreJuego;
        this.rango = rango;
        this.rol = rol;
        this.estiloJuego = estiloJuego;
        this.avatarUrl = avatarUrl != null ? avatarUrl : "/api/avatar?u=" + username;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsuarioId() { return usuarioId; }
    public void setUsuarioId(String usuarioId) { this.usuarioId = usuarioId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getBiografia() { return biografia; }
    public void setBiografia(String biografia) { this.biografia = biografia; }
    public String getPais() { return pais; }
    public void setPais(String pais) { this.pais = pais; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getNivelToxicidad() { return nivelToxicidad; }
    public void setNivelToxicidad(String nivelToxicidad) { this.nivelToxicidad = nivelToxicidad; }
    public int getPuntajeReputacion() { return puntajeReputacion; }
    public void setPuntajeReputacion(int puntajeReputacion) { this.puntajeReputacion = puntajeReputacion; }
    public String getHorarioPreferido() { return horarioPreferido; }
    public void setHorarioPreferido(String horarioPreferido) { this.horarioPreferido = horarioPreferido; }
    public String getSteamId() { return steamId; }
    public void setSteamId(String steamId) { this.steamId = steamId; }
    public String getRiotId() { return riotId; }
    public void setRiotId(String riotId) { this.riotId = riotId; }
    public String getDiscordTag() { return discordTag; }
    public void setDiscordTag(String discordTag) { this.discordTag = discordTag; }
    public String getDiscordId() { return discordId; }
    public void setDiscordId(String discordId) { this.discordId = discordId; }
    public boolean isDiscordConnected() { return discordConnected; }
    public void setDiscordConnected(boolean discordConnected) { this.discordConnected = discordConnected; }
    public String getFavoriteGames() { return favoriteGames; }
    public void setFavoriteGames(String favoriteGames) { this.favoriteGames = favoriteGames; }
    public String getFavoriteGenres() { return favoriteGenres; }
    public void setFavoriteGenres(String favoriteGenres) { this.favoriteGenres = favoriteGenres; }
    public String getPlayPreferences() { return playPreferences; }
    public void setPlayPreferences(String playPreferences) { this.playPreferences = playPreferences; }
    public double getStarRating() { return starRating; }
    public void setStarRating(double starRating) { this.starRating = starRating; }
    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
    public String getCardFrame() { return cardFrame != null ? cardFrame : "NONE"; }
    public void setCardFrame(String cardFrame) { this.cardFrame = cardFrame; }
    public String getJuego() { return juego; }
    public void setJuego(String juego) { this.juego = juego; }
    public String getNombreJuego() { return nombreJuego; }
    public void setNombreJuego(String nombreJuego) { this.nombreJuego = nombreJuego; }
    public String getRango() { return rango; }
    public void setRango(String rango) { this.rango = rango; }
    public String getRol() { return rol; }
    public void setRol(String rol) { this.rol = rol; }
    public String getEstiloJuego() { return estiloJuego; }
    public void setEstiloJuego(String estiloJuego) { this.estiloJuego = estiloJuego; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
