# Diseño — Disfraz Fase 2: nombre falso sobre la cabeza (nivel paquetes)

- **Fecha:** 2026-07-21
- **Estado:** Aprobado (brainstorming)
- **Componente:** Mod NeoForge únicamente. Fijado a 1.21.1 (el servidor no sube de versión).

## Problema

La Fase 1 disfraza el nombre en TAB y chat, pero **sobre la cabeza** el cliente sigue mostrando el nombre real. El cliente dibuja ese nombre desde el `GameProfile.name` de la entrada del jugador en su lista interna (por UUID), formateado por el equipo de scoreboard — NO usa el `listName` que cambia la Fase 1. Para ir 100% de incógnito hay que cambiar ese nombre de perfil que llega al cliente.

Contexto que lo habilita: la skin es genérica para todos (offline sin restaurador), así que un nombre falso basta — no hace falta skin falsa. Y Velocity NO pisa la lista de tab (confirmado: el nombre falso del TAB de la Fase 1 sí se ve), así que se puede hacer en el backend.

## Solución

Interceptar los paquetes salientes `ClientboundPlayerInfoUpdatePacket` en la conexión de cada jugador y, para las entradas cuyo UUID sea un jugador disfrazado, **reescribir el nombre del `GameProfile`** (real → falso), conservando UUID y skin. Así la cabeza muestra el nombre falso en todos los clientes. Para que el formato/orden cuadre, el equipo de scoreboard usa el **nombre falso** como miembro.

## Componentes

### 1. Interceptor netty por conexión (`DisguisePacketInterceptor`)
- Un `ChannelOutboundHandlerAdapter` insertado en el pipeline de cada jugador al conectar, **antes** del encoder (para ver objetos `Packet`, no bytes).
- En `write(...)`: si el mensaje es `ClientboundPlayerInfoUpdatePacket` con acción `ADD_PLAYER`, reconstruye la lista de entradas: por cada entrada cuyo UUID sea un jugador disfrazado (según el store), sustituye su `GameProfile` por uno nuevo con **mismo UUID + skin, nombre = disguiseName**. Reenvía el paquete reconstruido.
- **Excepción de self:** al propio jugador disfrazado no se le reescribe su entrada (sigue viéndose real).
- Acceso al canal: `Connection` no lo expone; se obtiene por **reflexión** del campo `channel` (nombre estable en 1.21.1 con mapeos oficiales), con degradado seguro (si falla, no se instala el interceptor y la Fase 1 sigue intacta).

### 2. Equipo de scoreboard con el nombre falso (`TabListManager`)
- Cuando el jugador está disfrazado, se le mete al equipo por su **nombre falso** en vez del nombre real (`getScoreboardName()`). Como el cliente ahora conoce a ese jugador por el nombre falso, el equipo le aplica: sobre la cabeza sale **[prefijo del rango falso] NombreFalso**, y el orden del TAB por el peso del rango falso cuadra. (Sin esto, la cabeza saldría sin prefijo y el orden se rompería.)

### 3. Aplicar en vivo (`NextNodesPermissions`)
- Al cambiar el disfraz desde la web (refresco existente), reenviar la info del jugador a todos los que lo ven: `ClientboundPlayerInfoRemovePacket` + `ClientboundPlayerInfoUpdatePacket(ADD_PLAYER)` para ese jugador. El interceptor reescribe el ADD → la cabeza se actualiza sin reconectar.
- Reasignar el equipo (ya lo hace `refreshPlayerName`).

### 4. Lógica pura (`DisguiseName`)
- Decisión testeable: dado un UUID y el snapshot, ¿qué nombre mostrar? (disguiseName si el jugador está disfrazado y no es el propio observador; si no, el real). Aislada para test unitario.

## Riesgo de implementación (SPIKE — primera tarea)

Reconstruir `ClientboundPlayerInfoUpdatePacket` con entradas modificadas: `Entry` y `GameProfile` son inmutables. Hay que verificar si existe un constructor accesible `(EnumSet<Action>, List<Entry>)`; si no, reemplazar el campo `entries` por **reflexión** sobre una copia, o serializar/deserializar una versión modificada. **La primera tarea del plan es un spike que confirma el camino de reconstrucción antes de escribir el interceptor.** Si resultara inviable sin Mixin, se decide con el usuario (añadir Mixin, fijado a 1.21.1).

## Interacción con la Fase 1

- TAB (nombre): la Fase 1 pone `listName` (fake) → el TAB ya muestra el nombre falso. Al reescribir también el perfil, el TAB seguiría mostrando el `listName` (prioritario). Consistente (ambos falsos).
- Chat: Fase 1, sin cambios.
- Equipo: pasa a usar el nombre falso como miembro (cambio de la Fase 2).

## Pruebas

- **Unit (JUnit, pura):** `DisguiseNameTest` — el nombre a mostrar según disfraz/observador.
- **Compilación** del interceptor y la reflexión.
- **En vivo (usuario):** con un disfraz puesto, otro jugador ve el nombre falso sobre la cabeza (con prefijo del rango falso), el orden del TAB cuadra, y el propio disfrazado se ve real. No hay forma de testear netty+cliente sin servidor real.

## Fuera de alcance (YAGNI)

- Skin falsa (no hace falta: todos tienen skin genérica).
- Compatibilidad entre versiones de Minecraft (fijado a 1.21.1).
- Ocultar el nametag (era la alternativa 2a, descartada a favor del nick).
