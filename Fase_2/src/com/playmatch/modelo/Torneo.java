package com.playmatch.modelo;

import java.util.ArrayList;
import java.util.List;

public class Torneo {
    private String id;
    private String titulo;
    private String juego;          // "valorant", "lol", "cs2"
    private String nombreJuego;
    private String organizador;
    private String pozoPremios;
    private String formato;        // "SINGLE_ELIMINATION"
    private int maxEquipos;
    private String fechaInicio;
    private String estado;         // "OPEN_REGISTRATION", "IN_PROGRESS", "COMPLETED"
    private String reglas;
    private String bannerUrl;
    private List<String> equiposInscritos;
    private List<PartidaTorneo> partidas;

    public Torneo() {
        this.equiposInscritos = new ArrayList<>();
        this.partidas = new ArrayList<>();
    }

    public Torneo(String id, String titulo, String juego, String nombreJuego, String organizador, 
                  String pozoPremios, String formato, int maxEquipos, String fechaInicio, 
                  String estado, String reglas, String bannerUrl) {
        this.id = id;
        this.titulo = titulo;
        this.juego = juego;
        this.nombreJuego = nombreJuego;
        this.organizador = organizador;
        this.pozoPremios = pozoPremios;
        this.formato = formato;
        this.maxEquipos = maxEquipos;
        this.fechaInicio = fechaInicio;
        this.estado = estado;
        this.reglas = reglas;
        this.bannerUrl = bannerUrl;
        this.equiposInscritos = new ArrayList<>();
        this.partidas = new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public String getJuego() { return juego; }
    public void setJuego(String juego) { this.juego = juego; }
    public String getNombreJuego() { return nombreJuego; }
    public void setNombreJuego(String nombreJuego) { this.nombreJuego = nombreJuego; }
    public String getOrganizador() { return organizador; }
    public void setOrganizador(String organizador) { this.organizador = organizador; }
    public String getPozoPremios() { return pozoPremios; }
    public void setPozoPremios(String pozoPremios) { this.pozoPremios = pozoPremios; }
    public String getFormato() { return formato; }
    public void setFormato(String formato) { this.formato = formato; }
    public int getMaxEquipos() { return maxEquipos; }
    public void setMaxEquipos(int maxEquipos) { this.maxEquipos = maxEquipos; }
    public int getCantidadEquiposInscritos() { return equiposInscritos != null ? equiposInscritos.size() : 0; }
    public String getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(String fechaInicio) { this.fechaInicio = fechaInicio; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getReglas() { return reglas; }
    public void setReglas(String reglas) { this.reglas = reglas; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public List<String> getEquiposInscritos() { return equiposInscritos; }
    public void setEquiposInscritos(List<String> equiposInscritos) { this.equiposInscritos = equiposInscritos; }
    public List<PartidaTorneo> getPartidas() { return partidas; }
    public void setPartidas(List<PartidaTorneo> partidas) { this.partidas = partidas; }
}
