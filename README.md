# PlayMatch · Plataforma Web de Emparejamiento Competitivo y Torneos

Plataforma Web de Emparejamiento Social y Gestión de Torneos para Gamers en Chile.  
Desarrollada para la asignatura **Portafolio de Título (APT122)**, Duoc UC.

---

## Arquitectura y Base de Datos

- **Backend:** Java 21 SE Nativo (`com.sun.net.httpserver.HttpServer` multihilo en puerto `8080`).
- **Base de Datos:** **SQLite 3 Relacional vía JDBC (`org.xerial:sqlite-jdbc`)** almacenada en `data/playmatch.db`, cumpliendo Tercera Forma Normal (3NF), integridad referencial (`PRAGMA foreign_keys = ON`) y modo concurrente WAL.
- **Seguridad:** Cifrado de contraseñas mediante **PBKDF2WithHmacSHA256** con sal aleatoria de 128 bits, *Auth Guard* en el cliente y sanitización XSS.
- **Frontend:** Diseño **Obsidian-Violet Glass** con App-Shell lateral, telemetría SQLite en tiempo real, catálogo dinámico de juegos, LFG, torneos y chat multicanal.

---

## Cómo Ejecutar la Plataforma

El proyecto incluye el driver JDBC en `lib/` para ejecutarse sin configuraciones externas.

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
├── database/                # Modelado relacional SQL (3NF)
│   ├── schema.sql           # Esquema DDL (users, player_profiles, games, tournaments, teams, chat, match_requests)
│   └── seed_data.sql        # Referencia SQL de poblamiento inicial
├── lib/                     # Dependencias JDBC locales (sqlite-jdbc + slf4j)
├── public/                  # Frontend Web SPA + Auth
│   ├── favicon.svg          # Logo emblema vectorial PlayMatch
│   ├── index.html           # Dashboard App-Shell con sidebar, LFG, Juegos, Torneos, Chat y Monitor SQLite
│   ├── login.html           # Inicio de sesión independiente con showcase de marca
│   ├── registro.html        # Registro independiente con usuario, correo, teléfono y juego
│   ├── css/style.css        # Sistema visual Obsidian-Violet Glass
│   └── js/
│       ├── api.js           # Cliente REST asíncrono
│       └── app.js           # Controlador SPA, Auth Guard, telemetría y sanitización XSS
├── src/com/playmatch/       # Backend Java + SQLite JDBC
│   ├── ServidorPlayMatch.java # Servidor HTTP multihilo y endpoints REST
│   ├── modelo/              # POJOs (Usuario, Juego, PerfilJugador, Torneo, PartidaTorneo, etc.)
│   ├── repositorio/         # GestorBaseDatos: Repositorio SQLite JDBC transaccional
│   └── util/                # JsonUtils y SeguridadUtil (PBKDF2WithHmacSHA256)
├── Fase_1/                  # Entregables formales de Fase 1
├── Fase_2/                  # Entregables formales de Fase 2
├── run.bat                  # Script de inicio automático (Windows)
└── run.ps1                  # Script de inicio en PowerShell
```
