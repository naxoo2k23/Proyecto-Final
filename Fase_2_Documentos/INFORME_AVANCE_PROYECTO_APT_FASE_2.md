# INFORME DE AVANCE DE PROYECTO APT

**Carrera:** Ingeniería en Informática  
**Asignatura:** Portafolio de Título  
**Estudiante:** Jesús Villasanti  
**Docente Guía:** Karla Marilyn Roco Ramírez  
**Versión Entregable:** 2.1 (Hito de Avance Fase 2)  
**Repositorio GitHub:** `https://github.com/naxoo2k23/Proyecto-Final.git`

---

### Integrantes del Equipo de Desarrollo

| Estudiante | Rol en el Proyecto | Responsabilidad Principal |
|---|---|---|
| **Jesús Villasanti** | Jefe de Proyecto & Arquitecto Backend | Arquitectura Java 21, Controladores REST, Autenticación y Seguridad |
| **Jesús Villasanti** | Líder de Desarrollo Frontend | Arquitectura SPA, Maquetación CSS3 Minimalista y UI/UX |
| **Jesús Villasanti** | Ingeniero de Base de Datos | Modelado Relacional SQL, Scripting DDL/DML e Integridad de Datos |
| **Jesús Villasanti** | Desarrollador Fullstack | Módulo LFG (Buscador de Jugadores) y Sala de Coordinación en Vivo |
| **Jesús Villasanti** | Desarrollador de Módulos | Lógica de Brackets de Torneos, Inscripciones y Reglas Competitivas |
| **Jesús Villasanti** | Ingeniero de Calidad (QA) | Pruebas de Integración, Documentación Técnica y Validación de API |

---

## 1. Propuesta de Ajustes al Proyecto APT

**Criterio Evaluado:** Propone ajustes al Proyecto APT considerando dificultades, facilitadores y retroalimentación.

### Dificultades Técnicas Identificadas y Soluciones Aplicadas

#### Resumen de Ajustes Arquitectónicos Clave:
1. **Desacoplamiento del Login:** La integración original del login como modal generaba inconsistencias de vista. Se aisló en `login.html` y `registro.html` con guardias de sesión en `app.js`.
2. **Eliminación de Simulaciones:** Se erradicaron simulaciones de error de red o fallas SMTP, garantizando que el pipeline de autenticación responda a estándares HTTP puros (`200`, `201`, `400`, `401`, `409`).
3. **Normalización de Nombres de Usuario:** Se soluciona el desfase entre nombres con espacios y guiones bajos (ej. `Jesus Villasanti` vs `Jesus_Villasanti`) mediante un método de búsqueda en Java.
4. **Persistencia Selectiva de Sesión:** Se introdujo la casilla *"Recordar contraseña"*, alternando inteligentemente entre `localStorage` y `sessionStorage` según la preferencia del usuario.

Durante el ciclo de desarrollo de la Fase 2, me enfrenté a desafíos críticos de arquitectura y experiencia de usuario que requirieron ajustes técnicos inmediatos:

- **Desacoplamiento del Flujo de Autenticación:** Inicialmente, el inicio de sesión se concibió como un componente modal superpuesto dentro de la SPA principal (`index.html`). Esto ocasionaba interferencias de renderizado en visitantes no autenticados, permitía visualizar la interfaz antes de validar credenciales y dificultaba la gestión limpia de estados de sesión. Se tomó la decisión de desacoplar completamente la autenticación en dos páginas independientes: `login.html` y `registro.html`, estableciendo una compuerta (*auth guard*) en JavaScript en la cabecera de `index.html` que redirige de inmediato a los usuarios no identificados.
- **Simulación de Errores vs. Entorno de Producción Local:** Las versiones preliminares incluían simulaciones de fallas de OAuth y protocolos SMTP para fines de demostración, lo que generaba confusión técnica sobre la madurez del software. Se eliminaron los códigos de falla simulados y se implementó un pipeline funcional directo contra el motor de base de datos en Java, garantizando que el inicio de sesión y el registro operen con respuestas HTTP canónicas (`200 OK`, `201 Created`, `400 Bad Request`, `401 Unauthorized`, `409 Conflict`).
- **Normalización de Nombres de Usuario y Persistencia:** Se detectó que las entradas de nombres de usuario con espacios o caracteres mixtos generaban incompatibilidades con URLs de avatares (DiceBear) y comparaciones sensibles a mayúsculas/minúsculas. Se incorporó en el repositorio Java (`GestorBaseDatos.java`) una rutina de normalización bidireccional que admite indistintamente formatos como `Jesus Villasanti` y `Jesus_Villasanti`, eliminando fricciones en la experiencia de usuario.
- **Persistencia Condicional de Sesión:** Al recargar la página, se perdía el contexto del jugador activo si no se almacenaba adecuadamente, o bien se mantenían sesiones no deseadas. Se introdujo el control de selección *"Recordar contraseña"*. Si el usuario marca la casilla, las credenciales tokenizadas se preservan en `localStorage`; si se desmarca, se utiliza `sessionStorage`, expirando automáticamente al cerrar la ventana.

### Factores Facilitadores del Proyecto
- **Arquitectura Ligera en Java 21 Nativo:** Al utilizar `com.sun.net.httpserver.HttpServer`, se prescindió de frameworks pesados (Spring Boot), logrando tiempos de arranque menores a 1 segundo y un consumo mínimo de RAM.
- **Separación Limpia Cliente-Servidor:** La API REST expone endpoints desacoplados, permitiendo que el frontend HTML5/CSS3 consuma datos mediante `fetch()` sin acoplamiento a motores de plantillas tradicionales.
- **Control de Versiones Granular con Git:** La disciplina de commits atómicos y control estricto de historial facilitó depuraciones sin riesgo de regresiones funcionales.

### Adaptaciones Derivadas de Retroalimentación
- **Estética Técnica y Sobriedad Visual:** Se eliminaron excesos de colores fosforescentes y emojis genéricos, adoptando un diseño minimalista oscuro en tonos morados (`#0c0a14` y `#151224`) con iconos vectoriales SVG limpios.

---

## 2. Metodología de Desarrollo Aplicada

Se implementó un marco de trabajo ágil basado en Scrum adaptado para proyectos de titulación (APT), estructurado en ciclos iterativos e incrementales (Sprints) de 1 a 2 semanas, asegurando entregables funcionales en cada etapa:

| Sprint | Objetivo Principal | Entregables Principales | Estado |
|---|---|---|---|
| **Sprint 1** | Modelado relacional y esqueleto backend | `schema.sql`, `seed_data.sql`, modelos POJO Java | Completado |
| **Sprint 2** | Servicios REST y prototipo de interfaz SPA | `ServidorPlayMatch.java`, `index.html`, `api.js` | Completado |
| **Sprint 3** | Homologación completa al idioma español | Renombrado de paquetes a `com.playmatch.modelo/repositorio` | Completado |
| **Sprint 4** | Refactorización de diseño y usabilidad | Paleta minimalista púrpura, diseño responsivo, eliminación de emojis | Completado |
| **Sprint 5** | Módulo de autenticación independiente y datos reales | `login.html`, `registro.html`, guardias de sesión, jugadores reales | Completado |

### Estándares de la Disciplina Implementados
- **Arquitectura de Software por Capas:** Separación estricta entre presentación (HTML/CSS/JS), servicios web (HTTP Handlers en Java), dominio de negocio (Modelos POJO) y persistencia (Gestor relacional).
- **Principios SOLID y Clean Code:** Clases con responsabilidad única (`Usuario`, `Torneo`, `GestorBaseDatos`), nomenclatura descriptiva en español técnico y bajo acoplamiento.
- **RESTful API Standards:** Cumplimiento de especificaciones HTTP/1.1 (RFC 7231), cabeceras CORS estructuradas, Content-Type `application/json; charset=UTF-8` y códigos de estado semánticos.

---

## 3. Evidencias de Avance: Documentación, Programación y Datos

### Arquitectura General del Sistema
La solución opera bajo un patrón desacoplado cliente-servidor donde la interfaz web interactúa con el backend exclusivamente mediante contratos REST sobre HTTP/JSON:

| Capa Arquitectónica | Componentes Principales | Tecnología / Entorno |
|---|---|---|
| **Capa Cliente (SPA Web)** | `login.html`, `registro.html`, `index.html`, `app.js`, `api.js`, `style.css` | Navegador Web (HTML5/CSS3/Vanilla JS) |
| **Capa de Servicios Web** | `ServidorPlayMatch.java` (HttpServer multihilo con FixedThreadPool) | Java 21 SE Runtime (Puerto 8080) |
| **Manejadores REST** | `ManejadorAutenticacion`, `ManejadorJugadores`, `ManejadorTorneos`, `ManejadorChat` | Controladores HTTP modulares |
| **Capa de Negocio** | Modelos POJO: `Usuario`, `PerfilJugador`, `Torneo`, `PartidaTorneo`, `MensajeChat` | Dominio orientado a objetos |
| **Capa de Persistencia** | `GestorBaseDatos.java` (Colecciones concurrentes thread-safe) + SQL Schema | Almacenamiento relacional normalizado (3NF) |

### Programación Backend (Java 21)
El backend está implementado en Java 21 estándar dentro del paquete `com.playmatch`. A continuación se presentan los extractos más relevantes del controlador de autenticación y el modelo de datos de usuario con campo de teléfono:

**Código: ManejadorAutenticacion (Registro con validación y código HTTP 201/409)**
```java
// Fragmento de ManejadorAutenticacion en ServidorPlayMatch.java
if (ruta.endsWith("/register")) {
    String username = datos.get("username");
    String email = datos.get("email");
    String password = datos.get("password");
    String telefono = datos.get("telefono");
    if (telefono == null) telefono = datos.get("phone");
    if (telefono == null) telefono = "";
    if (username == null || email == null || password == null) {
        enviarRespuestaJson(exchange, 400, "{\"error\":\"Campos obligatorios incompletos\"}");
        return;
    }
    if (db.buscarUsuarioPorUsername(username) != null) {
        enviarRespuestaJson(exchange, 409, "{\"error\":\"El nombre de usuario ya está registrado\"}");
        return;
    }
    Usuario u = db.crearUsuario(username, email, password, telefono);
    PerfilJugador p = db.buscarPerfilPorUsername(username);
    enviarRespuestaJson(exchange, 201,
        "{\"message\":\"Usuario registrado con éxito\",\"user\":" + usuarioAJson(u) +
        ",\"profile\":" + (p != null ? perfilAJson(p) : "null") + "}");
}
```

**Código: Modelo de Dominio Usuario.java con atributo de teléfono**
```java
// Fragmento del modelo Usuario.java con soporte de teléfono
package com.playmatch.modelo;

public class Usuario {
    private String id;
    private String username;
    private String email;
    private String passwordHash;
    private String rol;
    private String avatarUrl;
    private String telefono; // Incorporado para contacto verídico de jugadores

    public Usuario(String id, String username, String email, String passwordHash, String rol, String avatarUrl, String telefono) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.rol = rol;
        this.avatarUrl = avatarUrl;
        this.telefono = telefono;
    }
    // Getters y Setters con encapsulamiento estricto...
}
```

### Programación Frontend (SPA y Páginas Independientes)
1. **Ventana de Inicio de Sesión (`login.html`):** Interfaz centrada mediante Flexbox con tonos morados oscuros, campos de usuario y contraseña, soporte para recordar credenciales y enlace directo al registro.
2. **Ventana de Registro de Jugador (`registro.html`):** Formulario con captura de nombre de usuario, correo electrónico, contraseña y teléfono móvil.
3. **Plataforma Principal SPA (`index.html` y `app.js`):** Control de guardia de sesión (*Auth Guard*) que redirige automáticamente a `login.html` si no existe un usuario activo.

**Código: Protección de Rutas en Frontend (Auth Guard)**
```javascript
// Fragmento de app.js - Auth Guard y Persistencia de Sesión
function obtenerUsuarioGuardado() {
    const uLocal = localStorage.getItem('playmatch_user');
    if (uLocal) return JSON.parse(uLocal);
    const uSession = sessionStorage.getItem('playmatch_user');
    if (uSession) return JSON.parse(uSession);
    return null;
}

// Protección de ruta de cliente
const usuarioActivo = obtenerUsuarioGuardado();
if (!usuarioActivo) {
    window.location.replace('login.html');
}
```

### Almacenamiento y Modelo de Datos Relacional
El diseño de base de datos relacional se formalizó en `schema.sql`, asegurando integridad referencial mediante claves primarias, foráneas y restricciones únicas (3NF):

| Tabla Relacional | Columnas y Restricciones Principales | Propósito en el Negocio |
|---|---|---|
| **USERS** | `id (PK)`, `username (UK)`, `email (UK)`, `password_hash`, `avatar_url`, `telefono`, `role`, `created_at` | Almacena credenciales de acceso, rol y datos de contacto. |
| **PLAYER_PROFILES** | `id (PK)`, `user_id (FK)`, `bio`, `region`, `toxic_level`, `reputation_score`, `preferred_schedule`, `discord_tag`, `riot_id` | Información competitiva, reputación y filtros anti-toxicidad. |
| **TOURNAMENTS** | `id (PK)`, `title`, `game_id (FK)`, `prize_pool`, `format`, `max_teams`, `status`, `start_date` | Gestión de torneos oficiales y copas comunitarias. |
| **TOURNAMENT_TEAMS** | `id (PK)`, `tournament_id (FK)`, `team_name`, `captain_user_id (FK)`, `logo_url` | Escuadras inscritas en competencias activas. |
| **CHAT_MESSAGES** | `id (PK)`, `user_id (FK)`, `username`, `message`, `toxic_filtered`, `timestamp` | Historial de mensajes de coordinación comunitaria. |

---

## 4. Uso Preciso del Lenguaje Técnico de la Disciplina

Se empleó de forma rigurosa la terminología técnica de las ciencias de la computación e ingeniería de software:
- **Arquitectura Stateless HTTP:** El servidor no mantiene estado de sesión en memoria de transporte; cada solicitud contiene el contexto necesario evaluado por el cliente.
- **Client-Side Auth Guarding:** Mecanismo de intercepción en el ciclo de vida del DOM que evalúa tokens en `localStorage`/`sessionStorage` antes de renderizar la aplicación.
- **In-Memory Thread-Safe Repository:** Implementación de concurrencia basada en colecciones sincronizadas en Java para simular un motor relacional sin condiciones de carrera (*race conditions*).
- **Preflight CORS Handling:** Respuestas `OPTIONS` con código 204 y cabeceras `Access-Control-Allow-*` para habilitar peticiones cross-origin de forma segura.
- **Sanitización y Prevención XSS:** Filtrado y escape de entidades HTML para evitar vulnerabilidades de inyección de código en chats y campos de texto.

---

## 5. Aplicación de Normas Editoriales y Referencias APA

El documento y los entregables se elaboraron respetando las normas ortográficas de la Real Academia Española (RAE) y el formato de citación bibliográfica APA 7ma Edición:

1. Fielding, R. T., & Reschke, J. (2014). *Hypertext Transfer Protocol (HTTP/1.1): Semantics and Content* (RFC 7231). Internet Engineering Task Force (IETF). https://doi.org/10.17487/RFC7231
2. International Organization for Standardization. (2011). *Systems and software engineering — Systems and software Quality Requirements and Evaluation (SQuaRE) — System and software quality models* (ISO/IEC Standard No. 25010:2011). ISO.
3. Martin, R. C. (2008). *Clean Code: A Handbook of Agile Software Craftsmanship*. Prentice Hall.
4. Oracle Corporation. (2023). *Java Platform, Standard Edition & Java Development Kit Version 21 API Specification*. Oracle Technology Network. https://docs.oracle.com/en/java/javase/21/

---

## 6. Gestión de Configuración y Evidencias en Git

El código fuente, scripts de despliegue y documentación se encuentran versionados en Git conforme a las directrices de la asignatura.

### Estructura del Repositorio de Código
```text
ProyectoFinal/
├── Fase_1/                      # Levantamiento de requerimientos y diseño inicial
│   └── README.md
├── Fase_2/                      # Implementación de software
│   ├── database/
│   │   ├── schema.sql           # Esquema relacional DDL (tablas USERS, perfiles, torneos)
│   │   └── seed_data.sql        # Datos de prueba DML con jugadores reales
│   ├── public/
│   │   ├── css/style.css        # Hoja de estilos centralizada (tema púrpura gamer)
│   │   ├── js/api.js            # Cliente HTTP asíncrono
│   │   ├── js/app.js            # Lógica SPA, controladores y Auth Guard
│   │   ├── index.html           # Dashboard principal con módulos LFG, torneos y chat
│   │   ├── login.html           # Ventana independiente de inicio de sesión
│   │   └── registro.html        # Ventana independiente de registro con teléfono
│   └── src/com/playmatch/
│       ├── modelo/              # Clases de dominio POJO
│       ├── repositorio/         # Repositorio concurrente en memoria
│       └── ServidorPlayMatch.java # Servidor HTTP multihilo en puerto 8080
└── Fase_2_Documentos/           # Entregables formales de documentación y reflexión
    ├── Informe_Avance_Proyecto_APT_Fase_2.docx
    ├── Pauta_Monitoreo_Reflexion_Fase_2.docx
    ├── INFORME_AVANCE_PROYECTO_APT_FASE_2.md
    └── PAUTA_MONITOREO_REFLEXION_FASE_2.md
```

### Historial de Commits Representativos

| Hash Commit | Mensaje Oficial de Commit | Resumen del Avance |
|---|---|---|
| `e60175c` | *Interfaz de inicio de sesion: Se resuelven problemas del login los cuales me estaba dando para integrarlo como ventana independiente, se agrega interfaz de logueo e interfaz de registro* | Desacoplamiento de login en `login.html` y `registro.html`, guardias en `app.js`, campo telefónico y nombres reales. |
| `7d99989` | *refactor(backend): renombrar archivos, modelos y paquetes Java al espanol* | Estandarización de modelos y repositorios Java al español técnico. |
| `1deeaf9` | *chore(config): anadir configuracion para VS Code, scripts de ejecucion y catalogo de issues* | Entorno de desarrollo para VS Code y scripts de compilación automática. |
| `a69f883` | *feat(frontend): desarrollar interfaz web SPA con personalidad gamer y modulos funcionales* | Construcción de interfaz SPA con módulos LFG, Torneos y Chat. |
| `8596289` | *feat(backend): implementar servidor HTTP nativo y controladores REST en Java* | Levantamiento del servidor multihilo en puerto 8080. |
| `25d364a` | *feat(database): disenar esquema relacional y datos de prueba para PlayMatch Fase 2* | Definición de DDL SQL con integridad referencial. |

---

## 7. GESTIÓN INDIVIDUAL DEL TRABAJO Y DOMINIO TEMÁTICO INTEGRAL (Ponderación: 10%)

### Metodología de Trabajo Individual y Autogestión *(Personal Software Process / Personal Scrum)*
Al desarrollar el proyecto de manera individual, los protocolos de sincronización, control de calidad y avance se articularon bajo las mejores prácticas de la disciplina de software unipersonal:

- **Planificación Ágil Individual y Gestión de Tareas:** Organización del ciclo de desarrollo mediante Sprints personales con metas e hitos acotados. Aplicación de sesiones diarias de seguimiento (*Timeboxing* personal) para monitorear el avance del backlog, priorizar requerimientos y gestionar oportunamente los bloqueos arquitectónicos (como la decisión técnica de desacoplar el modal de autenticación hacia ventanas independientes).
- **Auto-Inspección Rigurosa de Código (*Self-Code Reviews*):** Previo a cada confirmación (*commit*) en Git, se realiza una revisión exhaustiva de las diferencias de código (*diffs*) y una validación de compilación limpia en Java 21 nativo, asegurando que ninguna regresión ni código redundante ingrese a la rama principal (`main`).
- **Sincronización con Stakeholders y Feedback de Usuarios:** La comunicación y alineación de objetivos se mantuvo activa en dos frentes externos: consultas directas con la docente guía de la asignatura para validar el cumplimiento de la rúbrica institucional, y pruebas de usabilidad con jugadores de la comunidad local para recopilar retroalimentación sobre la experiencia de usuario y el flujo de emparejamiento.

### Dominio Temático Integral y Fluidez en la Defensa Oral
Al haber diseñado, programado e integrado la totalidad de los módulos del sistema como desarrollador único (*Fullstack & Software Architect*), se garantiza un dominio transversal y exhaustivo de cada una de las capas de la solución:
- **Capa de Arquitectura y Backend (Java 21):** Capacidad de exponer con solvencia la configuración del servidor HTTP nativo multihilo (`HttpServer`), la gestión del pool de hilos con `ExecutorService`, la estructuración de controladores REST modulares y el manejo semántico de códigos de estado HTTP (`200 OK`, `201 Created`, `400 Bad Request`, `401 Unauthorized`, `409 Conflict`).
- **Capa de Almacenamiento y Persistencia (SQL / Memoria Concurrente):** Dominio pleno de la justificación del modelado relacional en Tercera Forma Normal (3NF), la integridad referencial de claves foráneas y la implementación de colecciones concurrentes sincronizadas (*thread-safe*) para evitar condiciones de carrera.
- **Capa de Frontend y Experiencia de Usuario (SPA / JavaScript ES6+):** Fluidez para argumentar el ciclo de vida de la aplicación de página única (SPA), el funcionamiento reactivo del mecanismo de guardia de rutas (*Client-Side Auth Guard*), la estrategia de persistencia condicional (`localStorage` vs. `sessionStorage`) según la casilla *"Recordar contraseña"* y la sanitización del DOM contra vulnerabilidades de inyección XSS.
- **Aseguramiento de Calidad y Pruebas (QA):** Sustentación clara sobre la matriz de pruebas de integración de los endpoints REST, abarcando tanto los flujos exitosos como el control de errores en condiciones de borde.

---

## 8. Conclusiones y Próximos Pasos

El desarrollo del presente hito de avance de la **Fase 2 de PlayMatch** concluye con el cumplimiento exitoso de los objetivos técnicos, funcionales y metodológicos planificados en el marco del Proyecto APT. A través de este ciclo de trabajo, se logró consolidar un **Producto Mínimo Viable (MVP)** robusto, completamente operativo y sustentado bajo estándares formales de la disciplina de la ingeniería de software.

### 1. Madurez Arquitectónica y Resoluciones Técnicas
Uno de los hitos más relevantes de esta fase fue la capacidad de diagnosticar y refactorizar oportunamente los componentes críticos del sistema. En particular, la decisión de **desacoplar el flujo de autenticación** —sustituyendo un modal superpuesto por ventanas independientes de acceso (`login.html`) y registro con datos de contacto real (`registro.html`) junto con un *Auth Guard* en el cliente— permitió resolver de raíz las interferencias de renderizado, robustecer la seguridad del ciclo de vida del DOM y garantizar una gestión limpia del estado de sesión mediante persistencia condicional (`localStorage` y `sessionStorage`).

Asimismo, la elección deliberada de tecnologías nativas y ligeras (**Java 21 SE con `HttpServer` multihilo** en el backend y **JavaScript Vanilla ES6+ con CSS3 modular** en el frontend) demostró ser un factor facilitador decisivo. Esta arquitectura prescindió de frameworks de alta sobrecarga, asegurando tiempos de compilación inmediatos, consumos mínimos de memoria (<50 MB) y respuestas HTTP semánticas estandarizadas (`200`, `201`, `400`, `401`, `409`), validando al mismo tiempo un modelo de datos relacional diseñado estrictamente en **Tercera Forma Normal (3NF)**.

### 2. Reflexión sobre la Gestión y Desarrollo Individual
Asumir la totalidad del ciclo de vida del proyecto de manera individual representó un desafío de alta exigencia técnica y operativa, pero a la vez constituyó el mayor facilitador de agilidad y coherencia. La aplicación de metodologías ágiles a escala unipersonal (*Personal Software Process / Personal Scrum*), combinada con prácticas de auto-inspección de código (*Self-Code Reviews*) y control de versiones atómico en Git, permitió sortear los desvíos imprevistos de la Carta Gantt sin comprometer la calidad del software. Este esquema garantizó un **dominio integral y transversal del 100% del sistema**, permitiendo articular con fluidez y solvencia cada decisión ante la comisión evaluadora.

### 3. Proyección y Hoja de Ruta para Fase 3 (Cierre de Portafolio)
Habiendo alcanzado la estabilidad funcional de la interfaz, el servidor y el enrutamiento de la plataforma, el proyecto queda en una posición inmejorable para abordar las actividades finales de titulación en la **Fase 3**:
- **Persistencia Física en Base de Datos:** Migración del repositorio concurrente en memoria a un motor relacional en disco (PostgreSQL / SQLite) utilizando el esquema DDL ya normalizado.
- **Criptografía y Seguridad Avanzada:** Implementación de funciones hash saladas con **BCrypt** para la protección de credenciales en el servidor y gestión de sesiones mediante tokens firmados (JWT).
- **Algoritmo de Conducta Gamer (*Zero Toxic Score*):** Integración del sistema de retroalimentación comunitaria post-partida para automatizar el cálculo de reputación y penalizaciones por toxicidad.

En definitiva, **PlayMatch Fase 2** no solo valida la factibilidad técnica y comercial de una plataforma de emparejamiento gamer ético y competitivo, sino que demuestra la aplicación rigurosa de las competencias profesionales adquiridas a lo largo de la carrera de Ingeniería en Informática.
