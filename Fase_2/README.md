# PlayMatch · Fase 2: Desarrollo del MVP (Full-Stack)

Plataforma Web de Emparejamiento Social y Gestión de Torneos para Gamers en Chile.  
Desarrollada para la asignatura **Portafolio de Título (APT122) / Capstone**, Duoc UC.

---

## 🚀 Cómo Ejecutar la Mini Demo en VS Code

El proyecto está 100% configurado para ejecutarse sin requerir instalaciones complejas externas.

### Opción 1: Con 1 Clic (Script de inicio)
* Haz doble clic en el archivo `run.bat` ubicado en la raíz del proyecto.
* O abre PowerShell y ejecuta:
  ```powershell
  .\run.ps1
  ```
Esto compilará el código fuente Java con el JDK 21 y abrirá automáticamente tu navegador en `http://localhost:8080`.

### Opción 2: Desde VS Code con F5
1. Abre esta carpeta (`ProyectoFinal`) en **VS Code**.
2. Presiona la tecla **F5** o ve a la pestaña de **Ejecutar y Depurar** y selecciona `PlayMatch - Iniciar Servidor (Java 21)`.
3. Abre en tu navegador `http://localhost:8080`.

---

## 📂 Estructura de la Fase 2

```
Fase_2/
├── database/
│   ├── schema.sql           # Esquema relacional DDL (Users, Profiles, Games, Tournaments, Brackets, Chat)
│   └── seed_data.sql        # Datos iniciales para pruebas (gamers, torneos y brackets de CS2/Valorant)
├── src/
│   └── com/playmatch/
│       ├── ServidorPlayMatch.java     # Servidor HTTP nativo y controladores REST
│       ├── modelo/                    # Entidades: Usuario, PerfilJugador, Torneo, PartidaTorneo, MensajeChat
│       ├── repositorio/               # GestorBaseDatos: Almacén relacional simulado y persistencia
│       └── util/                      # Utilitarios JSON y sanitización
├── public/
│   ├── index.html           # SPA con vistas de Matchmaking LFG, Torneos & Brackets, Chat y Perfil
│   ├── css/style.css        # Diseño moderno Dark Esports Cyberpunk
│   └── js/
│       ├── api.js           # Cliente HTTP conectado con la API REST de Java
│       └── app.js           # Lógica interactiva de filtros, brackets y mensajería en vivo
├── ISSUES_ROADMAP.md        # Catálogo formal de issues para seguimiento en GitHub
└── bin/                     # Clases compiladas (.class)
```

---

## 🎯 Características y Módulos Implementados en la Demo

1. **Matchmaking Social (LFG):**
   * Filtrado en tiempo real por videojuego (Valorant, League of Legends, Counter-Strike 2).
   * Filtro de comportamiento *Zero-Toxic* (100% karma).
   * Envío de invitaciones a dúo/escuadra con mensaje personalizado.
2. **Gestión de Torneos y Brackets Interactivos:**
   * Catálogo de torneos con pozo de premios, fechas y organizadores.
   * Visualizador interactivo de llaves de eliminación directa (*Semifinales* ➔ *Gran Final* ➔ *Campeón*).
   * Formulario de inscripción para equipos.
3. **Chat de Coordinación en Tiempo Real:**
   * Mensajería en vivo para acordar partidas y compartir Discord.
   * Auto-scroll y avisos automáticos de bots del sistema cuando se envían invitaciones.
4. **Perfil Gamer:**
   * Configuración de nicks oficiales (Riot ID, Steam ID, Discord Tag), horarios de juego y roles competitivos.
5. **Base de Datos Relacional:**
   * Esquema SQL completamente normalizado en `database/schema.sql` listo para ser desplegado en PostgreSQL o MySQL durante la Fase 3 con Docker.
