package com.playmatch.modelo;

public class PartidaTorneo {
    private String id;
    private String nombreRonda; // "Cuartos de Final", "Semifinal", "Gran Final"
    private int orden;
    private String equipo1;
    private String equipo2;
    private int puntajeEquipo1;
    private int puntajeEquipo2;
    private String ganador;
    private String estado;      // "FINISHED", "LIVE", "SCHEDULED"

    public PartidaTorneo() {}

    public PartidaTorneo(String id, String nombreRonda, int orden, String equipo1, String equipo2, 
                         int puntajeEquipo1, int puntajeEquipo2, String ganador, String estado) {
        this.id = id;
        this.nombreRonda = nombreRonda;
        this.orden = orden;
        this.equipo1 = equipo1;
        this.equipo2 = equipo2;
        this.puntajeEquipo1 = puntajeEquipo1;
        this.puntajeEquipo2 = puntajeEquipo2;
        this.ganador = ganador;
        this.estado = estado;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNombreRonda() { return nombreRonda; }
    public void setNombreRonda(String nombreRonda) { this.nombreRonda = nombreRonda; }
    public int getOrden() { return orden; }
    public void setOrden(int orden) { this.orden = orden; }
    public String getEquipo1() { return equipo1; }
    public void setEquipo1(String equipo1) { this.equipo1 = equipo1; }
    public String getEquipo2() { return equipo2; }
    public void setEquipo2(String equipo2) { this.equipo2 = equipo2; }
    public int getPuntajeEquipo1() { return puntajeEquipo1; }
    public void setPuntajeEquipo1(int puntajeEquipo1) { this.puntajeEquipo1 = puntajeEquipo1; }
    public int getPuntajeEquipo2() { return puntajeEquipo2; }
    public void setPuntajeEquipo2(int puntajeEquipo2) { this.puntajeEquipo2 = puntajeEquipo2; }
    public String getGanador() { return ganador; }
    public void setGanador(String ganador) { this.ganador = ganador; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
