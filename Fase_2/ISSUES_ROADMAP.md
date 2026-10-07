# PlayMatch · Catálogo de Issues y Roadmap Técnico (Fase 2)

**Proyecto:** Plataforma Web de Emparejamiento Social y Gestión de Torneos para Gamers  
**Autor:** Jesús Ignacio Villasanti Farías · Ingeniería Informática (Duoc UC)  
**Semestre / Asignatura:** Capstone / Portafolio de Título (APT122)  

---

## 1. Personalidad y Filosofía de Diseño

* **Estética Visual:** *Dark Mode Esports Cyberpunk*. Inspirado en plataformas de alto nivel competitivo como Faceit, VCT (Valorant Champions Tour) y Discord.
* **Paleta de Colores:**
  * Fondo primario: `#090D16` (Deep Void Carbon).
  * Superficies y Tarjetas: `#111827` y `#1F293D` con bordes sutiles `#27354F`.
  * Neón Violeta: `#8B5CF6` (Identidad y botones de acción principal).
  * Neón Cian Eléctrico: `#06B6D4` (Estadísticas y estados activos).
  * Esmeralda Cero Toxicidad: `#10B981` (Insignia de Karma y reputación intachable).
* **Voz y Tono:** Dinámico, cercano a la comunidad gamer chilena y latinoamericana, pero enfocado en erradicar el aislamiento y la toxicidad en partidas multijugador.

---

## 2. Catálogo de Issues de Desarrollo (Fase 2)

A continuación se detallan las *issues* técnicas estructuradas para el seguimiento del proyecto en GitHub / Tablero Kanban:

### 🎮 [ISSUE-01] Modelo Relacional de Base de Datos y Persistencia
* **Etiquetas:** `database`, `backend`, `fase-2`
* **Descripción:** Diseñar e implementar el esquema entidad-relación en SQL para soportar perfiles de jugador, juegos soportados (Valorant, LoL, CS2), solicitudes de match, torneos y brackets.
* **Criterios de Aceptación:**
  - Archivo `database/schema.sql` normalizado con claves foráneas e integridad referencial.
  - Archivo `database/seed_data.sql` con datos representativos de prueba.
  - Capa de persistencia compatible para migrar fluidamente a PostgreSQL/MySQL mediante Docker en la Fase 3.

---

### 🛡️ [ISSUE-02] Seguridad desde el Diseño (Security by Design) y Autenticación
* **Etiquetas:** `security`, `backend`
* **Descripción:** Implementar la lógica de gestión de credenciales con hashing seguro, protegiendo las contraseñas y sanitizando todas las entradas del servidor para mitigar inyecciones SQL y ataques XSS.
* **Criterios de Aceptación:**
  - Prevención de directory traversal en el servidor de archivos estáticos.
  - Validación de campos obligatorios en el registro de usuarios.
  - Endpoints REST `/api/auth/register` y `/api/auth/login`.

---

### 👥 [ISSUE-03] Módulo de Perfiles Gamer y Vinculación de Plataformas
* **Etiquetas:** `backend`, `frontend`, `user-experience`
* **Descripción:** Permitir al jugador personalizar su biografía, región (foco en Chile), rango competitivo oficial, rol preferido, horarios de juego y nicks verificables (Riot ID, Steam ID, Discord Tag).
* **Criterios de Aceptación:**
  - Endpoint `POST /api/players/profile` que actualiza los metadatos del jugador.
  - Insignia de reputación "Zero Toxic" visible en el perfil.
  - Formulario interactivo en el frontend para guardar cambios en tiempo real.

---

### ⚔️ [ISSUE-04] Sistema de Emparejamiento Social Inteligente (LFG)
* **Etiquetas:** `frontend`, `algorithm`, `matchmaking`
* **Descripción:** Crear el buscador de compañeros con filtros por juego, rango competitivo, horario y nivel de toxicidad. Habilitar el envío de solicitudes de match directas.
* **Criterios de Aceptación:**
  - Filtro dinámico en el frontend para alternar entre Valorant, LoL y CS2.
  - Filtro de conducta "Cero Toxicidad" (100% reputación).
  - Modal interactivo para enviar invitación con mensaje personalizado.
  - Registro de la solicitud en `/api/match/request`.

---

### 🏆 [ISSUE-05] Módulo de Gestión de Torneos y Visualizador de Brackets
* **Etiquetas:** `tournaments`, `frontend`, `backend`
* **Descripción:** Sistema de torneos amateur con registro de equipos, premios auspiciados y un visualizador gráfico de llaves de eliminación directa (tipo Challonge/Battlefy).
* **Criterios de Aceptación:**
  - Listado de torneos activos con formato, fecha y pozo de premios.
  - Bracket en árbol interactivo mostrando Semifinales, Gran Final y Campeón.
  - Modal de inscripción rápida para registrar rosters o equipos independientes.

---

### 💬 [ISSUE-06] Chat Interno y Coordinación de Dúos en Tiempo Real
* **Etiquetas:** `realtime`, `chat`, `social`
* **Descripción:** Sala de mensajería interactiva para coordinar partidas, compartir enlaces de Discord y avisar sobre la formación de escuadras antes de ingresar al cliente del juego.
* **Criterios de Aceptación:**
  - Notificaciones automáticas cuando un jugador invita a otro a un match.
  - Visualización de avatares, hora de envío y mensajes con auto-scroll.
  - Capacidad de enviar mensajes con tecla Enter o botón de envío.

---

## 3. Hoja de Ruta hacia la Fase 3 (Infraestructura y Cierre)

1. **Sprint 5 (Semana 15): Contenerización con Docker:**
   - Creación de `Dockerfile` para empaquetar el servidor Java.
   - Creación de `docker-compose.yml` para orquestar la aplicación junto con una base de datos PostgreSQL de producción.
2. **Sprint 6 (Semana 16): Pruebas de Calidad (QA):**
   - Ejecución de pruebas funcionales y pruebas de estrés de concurrencia.
   - Verificación de políticas CORS y encabezados de seguridad HTTP.
3. **Sprint 7 (Semanas 17-18): Despliegue y Documentación Final:**
   - Despliegue en servidor cloud / simulado.
   - Manual de usuario y entrega formal del Portafolio de Título.
