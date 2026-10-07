# PAUTA DE MONITOREO Y REFLEXIÓN DEL PROYECTO APT (FASE 2)

**Estudiante:** Jesús Villasanti  
**Carrera:** Ingeniería en Informática  
**Asignatura:** Portafolio de Título / Proyecto APT  
**Docente Guía:** Karla Marilyn Roco Ramírez  
**Modalidad:** Desarrollo Individual (Autor y Ejecutor Único)  
**Versión:** 2.1 (Hito de Avance Fase 2)  
**Repositorio GitHub:** `https://github.com/naxoo2k23/Proyecto-Final.git`

---

> Esta pauta tiene como objetivo ayudarte a monitorear el desarrollo de tu Proyecto APT, reflexionando sobre tus avances de acuerdo con lo planificado en la fase anterior y recibiendo retroalimentación de tus pares y docentes que te permita hacer los ajustes necesarios para cumplir con los objetivos de tu proyecto. Esta pauta debe ser respondida con tu grupo. Puedes completar esta guía y, posteriormente, cargarla en la sección de reflexión de la Fase 2, para retroalimentación de tu docente.

---

### 1. Mira tu carta Gantt y reflexiona sobre los avances de tu Proyecto APT

**¿Has podido cumplir todas las actividades en los tiempos definidos? ¿Qué factores han facilitado o dificultado el desarrollo de las actividades de tu plan de trabajo?**

Sí, en términos generales he logrado cumplir con las metas planificadas para este hito de la Fase 2, alcanzando un Producto Mínimo Viable (MVP) completamente funcional que integra base de datos relacional, backend multihilo en Java 21 y frontend SPA con módulos de búsqueda de jugadores (LFG), torneos y chat. No obstante, se requirió un ajuste menor de holgura en los tiempos para absorber una refactorización arquitectónica no contemplada inicialmente.

**Factores facilitadores:**
- **Tecnologías nativas y ligeras:** Al desarrollar con `HttpServer` nativo de Java y JavaScript Vanilla (ES6+), prescindí de frameworks pesados (Spring Boot o React). Esto evitó tiempos muertos de configuración y permitió compilaciones instantáneas.
- **Coherencia y velocidad de decisión:** Al trabajar de forma individual, no existieron fricciones de comunicación ni desalineación de criterios técnicos; las decisiones de diseño se ejecutaron de manera ágil y directa.
- **Control de versiones con Git:** El uso riguroso de commits atómicos facilitó iterar y probar cambios sin riesgo de regresiones.

**Factores que dificultaron:**
- **Sobrecarga de roles simultáneos:** Asumir en solitario todas las disciplinas de ingeniería (arquitectura, backend, frontend, base de datos, QA y documentación) demandó una alta inversión de tiempo.
- **El desacoplamiento del flujo de acceso:** El inicio de sesión se concibió inicialmente como un modal dentro del dashboard principal, lo que generó conflictos de renderizado e inconsistencias de sesión que me obligaron a rediseñar y separar el flujo en ventanas independientes (`login.html` y `registro.html`).

---

### 2. Respecto a las dificultades

**¿De qué manera has enfrentado y/o planeas enfrentar las dificultades que han afectado el desarrollo de tu Proyecto APT?**

Las dificultades se abordaron aplicando principios de ingeniería de software y resolución ágil de problemas:

1. **Frente al problema del inicio de sesión:** En lugar de parchar el modal existente, tomé la decisión arquitectónica de desacoplar completamente la autenticación en dos páginas web independientes (`login.html` y `registro.html`) e implementar un mecanismo de guardia en el cliente (*Client-Side Auth Guard*) que protege `index.html` redirigiendo de inmediato a usuarios no autenticados.
2. **Frente a la normalización de usuarios:** Para evitar errores al comparar nombres con espacios versus guiones bajos (ej. `Jesus Villasanti` vs `Jesus_Villasanti`), implementé una rutina de búsqueda tolerante en Java que limpia y homologa los caracteres.
3. **Gestión del tiempo unipersonal:** Apliqué la técnica de *Timeboxing* diario, priorizando siempre la estabilidad de la lógica de negocio y los servicios REST antes de pasar al diseño estético.

**Plan para futuras dificultades (Fase 3):**  
Para la migración a base de datos persistente física y la incorporación de cifrado criptográfico (BCrypt), desarrollaré primero una prueba de concepto (PoC) aislada para validar el rendimiento antes de integrarla al flujo principal del sistema.

---

### 3. Hasta el momento

**¿Cómo evalúas tu trabajo? ¿Qué destacas y qué podrías hacer para mejorar tu trabajo?**

Evalúo mi desempeño de manera muy positiva y satisfactoria. Logré construir de forma autónoma una plataforma cliente-servidor robusta, rápida, estética y técnicamente justificada bajo estándares de la industria.

**Aspectos que destaco:**
- **Autonomía y dominio integral:** Demostré capacidad para resolver el ciclo de vida completo del software, dominando desde la gestión de sockets multihilo y respuestas HTTP semánticas en Java 21, hasta la manipulación reactiva del DOM y la protección contra ataques XSS en frontend.
- **Calidad del código (Clean Code):** Mantuve una separación clara de responsabilidades (3NF en base de datos, controladores desacoplados y estilos CSS modulares).

**Aspectos a mejorar:**
- **Holgura en la estimación de tiempos:** Debo proyectar márgenes de contingencia más realistas en mi Carta Gantt para imprevistos de refactorización arquitectónica.
- **Automatización de pruebas:** En la siguiente fase planeo incorporar una suite formal de pruebas unitarias automatizadas (con JUnit) para no depender exclusivamente de pruebas de integración manuales.

---

### 4. Después de reflexionar sobre el avance de tu Proyecto APT

**¿Qué inquietudes te quedan sobre cómo proceder? ¿Qué pregunta te gustaría hacerle a tu docente o a tus pares?**

**Inquietudes sobre el procedimiento:**  
Mi principal inquietud se centra en el alcance prioritario que la comisión evaluadora espera para el cierre de la Fase 3: si se ponderará más el despliegue del sistema en infraestructura cloud (como AWS o Render) o la sofisticación matemática del algoritmo de evaluación de conducta gamer (*Zero Toxic Score*).

**Pregunta para la docente:**  
> *"Profesora: considerando que el diseño relacional en 3NF ya está formalizado y funcionando en memoria concurrente, ¿para la entrega final de Fase 3 es indispensable migrar a un motor gestor en disco como PostgreSQL, o se valida la arquitectura actual complementándola con cifrado de contraseñas BCrypt y pruebas de carga multihilo?"*

**Pregunta para mis pares:**  
> *"¿Qué estrategias o librerías han implementado ustedes para gestionar la expiración automática y renovación segura de tokens de sesión entre cliente y servidor?"*

---

### 5. A partir de esta instancia de monitoreo de su Proyecto APT

**¿Consideran que las actividades deben ser redistribuidas entre los miembros del grupo? ¿Hay nuevas actividades que deban ser asignadas a algún miembro del grupo?**

*Nota: Al desarrollar el proyecto bajo modalidad individual, la redistribución no aplica a otras personas, sino a la reasignación de mi propia carga de trabajo personal:*

1. **Reorganización de esfuerzos personales:** Dado que el diseño de interfaces, la maquetación CSS3 y el flujo de autenticación independiente ya quedaron completamente resueltos y estabilizados, libero esas horas de mi cronograma y las reasigno prioritariamente a las tareas del núcleo backend y seguridad.
2. **Nuevas actividades a incorporar en mi plan de trabajo:**
   - Implementación de algoritmo hash con sal aleatoria (*BCrypt*) para el almacenamiento seguro de credenciales.
   - Conexión del repositorio a un motor de base de datos relacional persistente en disco (PostgreSQL / SQLite).
   - Elaboración de un plan de pruebas automatizadas de endpoints.

---

### 6. APT grupal

**¿Cómo evalúan el trabajo en grupo? ¿Qué aspectos positivos destacan? ¿Qué aspectos podrían mejorar?**

*Nota: Dado que estoy trabajando de forma individual como autor único del proyecto, evalúo esta dimensión desde la perspectiva de autogestión, autonomía y vinculación con el entorno:*

**Aspectos positivos destacados:**
- **Máxima agilidad y coherencia técnica:** No existieron tiempos muertos por falta de coordinación o esperas de entregas ajenas. La arquitectura, los nombres de variables, los contratos JSON y los estilos visuales mantienen una coherencia total al haber un único criterio de desarrollo.
- **Dominio absoluto del código:** Conozco con total profundidad cada línea del backend, del frontend y del esquema SQL, lo que me permite depurar errores en minutos y defender oralmente cualquier parte del sistema con fluidez.

**Aspectos que podrían mejorar:**
- **Mayor carga operativa unipersonal:** Al no contar con compañeros de equipo, no es posible delegar tareas de soporte (como diseño gráfico exhaustivo o redacción secundaria), lo que exige una disciplina de trabajo más estricta.
- **Validación de código cruzada (*Peer Review*):** Al no tener pares dentro del desarrollo directo, debo suplir la revisión cruzada solicitando retroalimentación activa a la docente guía y realizando pruebas de usabilidad directamente con usuarios reales de la comunidad gamer.
