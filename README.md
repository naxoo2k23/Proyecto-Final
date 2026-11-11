# PlayMatch · Plataforma Web de Emparejamiento Competitivo y Torneos

Plataforma Web de Emparejamiento Social y Gestión de Torneos para Gamers en Chile.  
Desarrollada para la asignatura **Portafolio de Título (APT122)**, Duoc UC.

---

## Arquitectura y Base de Datos

- **Backend:** Java SE (`com.sun.net.httpserver.HttpServer` multihilo en puerto `8080`).
- **Base de Datos Principal (Producción / Nube):** **PostgreSQL Relacional vía JDBC (`org.postgresql`) + Pool de Conexiones HikariCP**, compatible con **Neon, Supabase, Railway, Render, Docker o PostgreSQL local**.
- **Modo Respaldo Automático (Desarrollo / Offline):** Si no se configura `DATABASE_URL` en `.env` o variables de entorno, la plataforma inicia automáticamente con **SQLite 3 (`org.xerial:sqlite-jdbc`)** en `data/playmatch.db` manteniendo el mismo esquema **3NF**, integridad referencial y modo WAL.
- **Seguridad:** Cifrado de contraseñas mediante **PBKDF2WithHmacSHA256** con sal aleatoria de 128 bits, *Auth Guard* en el cliente y sanitización XSS.
- **Frontend:** Diseño **Obsidian-Violet Glass** con App-Shell lateral, telemetría de base de datos en tiempo real, catálogo dinámico de juegos, LFG, torneos y chat multicanal.

---

## Configurar PostgreSQL (Nube o Docker)

El proyecto funciona sin configuración previa usando respaldo SQLite local, o conectado a **PostgreSQL real** en 2 pasos:

1. Copia `.env.example` como `.env` en la raíz del proyecto.
2. Configura `DATABASE_URL` con tu base de datos PostgreSQL:
   - **En la nube (Neon / Supabase / Render / Railway):**
     ```env
     DATABASE_URL=postgresql://usuario:clave@host.neon.tech/playmatch?sslmode=require
     ```
   - **En local con Docker (`docker-compose.yml` incluido):**
     ```bash
     docker compose up -d
     ```
     Y en `.env`:
     ```env
     DATABASE_URL=jdbc:postgresql://localhost:5432/playmatch
     DB_USER=playmatch
     DB_PASSWORD=playmatch_secret
     ```

---

## Cómo Ejecutar la Plataforma

El proyecto incluye todos los drivers JDBC en `lib/` (`postgresql-42.7.14.jar`, `HikariCP-4.0.3.jar`, `sqlite-jdbc-3.54.1.0.jar`) para ejecutarse sin instalar gestores de paquetes externos.

### Opción 1: En Windows con 1 Clic
* Haz doble clic en el archivo `run.bat` ubicado en la raíz del proyecto.
* O abre PowerShell y ejecuta:
  ```powershell
  .\run.ps1
  ```
Esto compilará el código fuente Java junto con `lib/*` y abrirá automáticamente tu navegador en `http://localhost:8080/login.html`.

### Opción 2: En GitHub Codespaces / Linux / macOS
En la terminal integrada, ejecuta:
```bash
mkdir -p bin && javac -encoding UTF-8 -cp "lib/*" -d bin -sourcepath src $(find src -name "*.java") && java -cp "bin:lib/*" com.playmatch.ServidorPlayMatch 8080
```
GitHub detectará el puerto `8080` y habilitará el botón **"Open in Browser"**.

### Opción 3: Desde VS Code con F5
1. Abre la carpeta `ProyectoFinal` en **VS Code**.
2. Presiona **F5** o selecciona `PlayMatch - Iniciar Servidor (Java 21)`.
3. Abre en tu navegador `http://localhost:8080/login.html`.

---

## Estructura General del Repositorio

```text
ProyectoFinal/
├── .env.example             # Plantilla de conexión PostgreSQL (Neon, Supabase, Docker, local)
├── docker-compose.yml       # Contenedor PostgreSQL 16 Alpine listo para desarrollo local
├── database/                # Modelado relacional SQL portable (PostgreSQL / SQLite, 3NF)
│   ├── schema.sql           # Esquema DDL (users, player_profiles, games, tournaments, teams, chat, match_requests)
│   └── seed_data.sql        # Referencia SQL de poblamiento inicial (ON CONFLICT DO NOTHING)
├── lib/                     # Drivers JDBC locales (postgresql, HikariCP, sqlite-jdbc, slf4j)
├── public/                  # Frontend Web SPA + Auth
│   ├── favicon.svg          # Logo emblema vectorial PlayMatch
│   ├── index.html           # Dashboard App-Shell con sidebar, LFG, Juegos, Torneos, Chat y Monitor DB
│   ├── login.html           # Inicio de sesión independiente con showcase de marca
│   ├── registro.html        # Registro independiente con usuario, correo, teléfono y juego
│   ├── css/style.css        # Sistema visual Obsidian-Violet Glass
│   └── js/
│       ├── api.js           # Cliente REST asíncrono
│       └── app.js           # Controlador SPA, Auth Guard, telemetría y sanitización XSS
├── src/com/playmatch/       # Backend Java + PostgreSQL (HikariCP) / SQLite JDBC
│   ├── ServidorPlayMatch.java # Servidor HTTP multihilo y endpoints REST
│   ├── modelo/              # POJOs (Usuario, Juego, PerfilJugador, Torneo, PartidaTorneo, etc.)
│   ├── repositorio/         # GestorBaseDatos: Repositorio dual PostgreSQL / SQLite transaccional
│   └── util/                # ConfiguracionDB (.env/URI), JsonUtils y SeguridadUtil (PBKDF2WithHmacSHA256)
├── Fase_1/                  # Entregables formales de Fase 1
├── Fase_2/                  # Entregables formales de Fase 2
├── run.bat                  # Script de inicio automático (Windows)
└── run.ps1                  # Script de inicio en PowerShell
```
