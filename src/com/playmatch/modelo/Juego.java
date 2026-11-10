package com.playmatch.modelo;

import java.util.UUID;

public class Juego {
    private String id;
    private String codigo;
    private String nombre;
    private String categoria;
    private String plataforma;
    private int maxTamanoEquipo;
    private String descripcion;
    private String colorAcento;
    private String bannerUrl;
    private boolean activo;
    private int cantidadJugadores;
    private int cantidadTorneos;
    private String fechaCreacion;

    public Juego() {
        this.id = UUID.randomUUID().toString();
        this.maxTamanoEquipo = 5;
        this.colorAcento = "#8b5cf6";
        this.activo = true;
    }

    public Juego(String id, String codigo, String nombre, String categoria, String plataforma,
                 int maxTamanoEquipo, String descripcion, String colorAcento, String bannerUrl, boolean activo) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.codigo = codigo;
        this.nombre = nombre;
        this.categoria = categoria != null ? categoria : "Competitivo";
        this.plataforma = plataforma != null ? plataforma : "PC / Multiplataforma";
        this.maxTamanoEquipo = maxTamanoEquipo > 0 ? maxTamanoEquipo : 5;
        this.descripcion = descripcion != null ? descripcion : "";
        this.colorAcento = (colorAcento != null && !colorAcento.isEmpty()) ? colorAcento : "#8b5cf6";
        this.bannerUrl = (bannerUrl != null && !bannerUrl.isEmpty())
                ? bannerUrl
                : "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80";
        this.activo = activo;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }

    public String getPlataforma() { return plataforma; }
    public void setPlataforma(String plataforma) { this.plataforma = plataforma; }

    public int getMaxTamanoEquipo() { return maxTamanoEquipo; }
    public void setMaxTamanoEquipo(int maxTamanoEquipo) { this.maxTamanoEquipo = maxTamanoEquipo; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getColorAcento() { return colorAcento; }
    public void setColorAcento(String colorAcento) { this.colorAcento = colorAcento; }

    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }

    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }

    public int getCantidadJugadores() { return cantidadJugadores; }
    public void setCantidadJugadores(int cantidadJugadores) { this.cantidadJugadores = cantidadJugadores; }

    public int getCantidadTorneos() { return cantidadTorneos; }
    public void setCantidadTorneos(int cantidadTorneos) { this.cantidadTorneos = cantidadTorneos; }

    public String getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(String fechaCreacion) { this.fechaCreacion = fechaCreacion; }
}
