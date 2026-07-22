# Disfraz Fase 2 (nick sobre la cabeza) — Implementation Plan

> Ejecutado inline (superpowers:executing-plans). Solo mod NeoForge, fijado a 1.21.1. La primera tarea es un SPIKE que decide la viabilidad antes de escribir el interceptor.

**Goal:** Que el nombre falso del disfraz aparezca también sobre la cabeza del jugador.

**Arquitectura:** Interceptor netty por conexión reescribe el nombre del `GameProfile` en `ClientboundPlayerInfoUpdatePacket` para jugadores disfrazados; el equipo de scoreboard usa el nombre falso como miembro; refresco en vivo reenvía la info del jugador.

---

### Task 1: SPIKE — reconstrucción del paquete (BLOQUEANTE)
Antes de nada, verificar cómo reconstruir `ClientboundPlayerInfoUpdatePacket` con una entrada cuyo `GameProfile.name` cambia. Escribir una clase de prueba mínima o inspeccionar la API:
- ¿Existe constructor accesible `(EnumSet<Action>, List<Entry>)`? ¿`Entry` es record público con `(UUID, GameProfile, boolean, int, GameType, Component, RemoteChatSession.Data)`?
- Si sí → ruta directa. Si no → reflexión sobre el campo `entries` de una copia, o serializar/deserializar.
- **Salida del spike:** el snippet exacto de reconstrucción, confirmado que compila. Si resulta inviable sin Mixin, PARAR y consultar al usuario.

### Task 2: Lógica pura (`DisguiseName`) + test
```java
public final class DisguiseName {
    private DisguiseName(){}
    /** Nombre a mostrar de `target` para el observador `viewer`: el disfraz si target está disfrazado
     *  y no es el propio viewer; si no, null (usar el real). */
    public static String shownName(UserEntry target, boolean viewerIsTarget) {
        if (target == null || viewerIsTarget) return null;
        return (target.disguiseName != null && !target.disguiseName.isBlank()) ? target.disguiseName : null;
    }
}
```
Test: disfrazado + viewer distinto → nombre falso; viewerIsTarget → null; sin disfraz → null. Compilar, test, commit.

### Task 3: Interceptor netty (`DisguisePacketInterceptor`)
- `ChannelOutboundHandlerAdapter`; en `write`, si `msg instanceof ClientboundPlayerInfoUpdatePacket p` y contiene `ADD_PLAYER`, reconstruye entradas (usando el snippet del spike) reemplazando el `GameProfile` de los UUID disfrazados por uno con nombre falso (mismo UUID + properties/skin). Usa `DisguiseName.shownName` con `viewerIsTarget = (viewerUuid.equals(entryUuid))`. Necesita: acceso al store (snapshot) y el UUID del viewer (el jugador dueño de la conexión).
- Instalación: al `onPlayerLoggedIn`, obtener el canal por reflexión (`Connection.channel`) y `pipeline().addBefore("encoder", "nextnodes_disguise", new DisguisePacketInterceptor(...))`, con try/catch (degradado seguro). Nombre del handler encoder a confirmar en el spike.
- Compilar. Commit.

### Task 4: Equipo con nombre falso (`TabListManager`)
- En `assign(...)`: si el jugador está disfrazado (disguiseName no vacío), usar el nombre falso como `member` del equipo en vez de `getScoreboardName()`. El `desiredName`/prefijo/peso ya salen del rango falso (Fase 1). Ajustar `remove(...)` para quitar por el miembro correcto (guardar el miembro actual o limpiar por UUID).
- Compilar. Commit.

### Task 5: Aplicar en vivo (`NextNodesPermissions`)
- En el refresco por cambio de disfraz: para cada jugador online, reenviar a los demás `ClientboundPlayerInfoRemovePacket(List.of(uuid))` + `new ClientboundPlayerInfoUpdatePacket(ADD_PLAYER, player)` del jugador disfrazado, para que el interceptor reescriba y la cabeza se actualice. (Confirmar API de remove packet en 1.21.1.)
- Compilar. Commit.

### Task 6: Build + verificación
- `./gradlew build`, tests en verde, jar.
- Checklist en vivo (usuario): con disfraz puesto, otro jugador ve el nombre falso sobre la cabeza con el prefijo del rango falso; el orden del TAB cuadra; el propio disfrazado se ve real; quitar el disfraz revierte.
