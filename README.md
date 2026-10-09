# PlayMatch · Plataforma Web de Emparejamiento Competitivo y Torneos

Plataforma Web de Emparejamiento Social y Gestión de Torneos para Gamers en Chile.  
Desarrollada para la asignatura **Portafolio de Título / Proyecto APT**, Duoc UC.

---

##  Cómo Ejecutar la Plataforma

El proyecto está configurado para ejecutarse sin requerir instalaciones complejas externas.

### Opción 1: En Windows con 1 Clic
* Haz doble clic en el archivo `run.bat` ubicado en la raíz del proyecto.
* O abre PowerShell y ejecuta:
  ```powershell
  .\run.ps1
  ```
Esto compilará el código fuente Java con JDK 21 y abrirá automáticamente tu navegador en `http://localhost:8080`.

### Opción 2: En GitHub Codespaces (Nube)
En la terminal integrada de Codespaces, ejecuta:
```bash
javac -d bin $(find src -name "*.java") && java -cp bin com.playmatch.ServidorPlayMatch 8080
```
GitHub detectará el puerto 8080 y habilitará el botón **"Open in Browser"**.

### Opción 3: Desde VS Code con F5
1. Abre esta carpeta (`ProyectoFinal`) en **VS Code**.
2. Presiona la tecla **F5** o ve a **Ejecutar y Depurar** y selecciona `PlayMatch - Iniciar Servidor (Java 21)`.
3. Abre en tu navegador `http://localhost:`.

---

##  Estructura General del Repositorio

```text
ProyectoFinal/
├── database/                # Modelado relacional SQL
│   ├── schema.sql           # Esquema DDL en 3NF (Users, Profiles, Games, Tournaments, Chat)
│   └── seed_data.sql        # Poblado inicial de prueba con jugadores reales de la comunidad
├── public/                  # Frontend Web SPA
│   ├── index.html           # Dashboard principal con módulos LFG, Torneos y Chat
│   ├── login.html           # Ventana independiente de inicio de sesión con "Recordar contraseña"
│   ├── registro.html        # Ventana independiente de registro de usuario con teléfono
│   ├── css/style.css        # Hoja de estilos con diseño minimalista púrpura oscuro
│   └── js/
│       ├── api.js           # Cliente asíncrono para consumir la API REST
│       └── app.js           # Lógica del cliente, controladores y Auth Guard
├── src/com/playmatch/       # Backend en Java 21 SE
│   ├── ServidorPlayMatch.java # Servidor HTTP nativo multihilo en puerto 8080
│   ├── modelo/              # Clases de dominio POJO (Usuario, PerfilJugador, Torneo, etc.)
│   ├── repositorio/         # GestorBaseDatos: Almacén concurrente y persistencia
│   └── util/                # Utilidades JSON y sanitización
├── Fase_1/                  # Documentación y entregables formales de Fase 1
└── Fase_2/                  # Documentación y entregables formales de Fase 2
    ├── Informe_Avance_Proyecto_APT_Fase_2.docx
    ├── INFORME_AVANCE_PROYECTO_APT_FASE_2.md
    └── PlayMatch_Presentacion_Fase_2.pptx
```
