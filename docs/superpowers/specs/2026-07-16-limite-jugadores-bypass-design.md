# Diseño — Límite de jugadores con bypass por rango

- **Fecha:** 2026-07-16
- **Estado:** Aprobado (brainstorming) — pendiente de revisión de la spec
- **Componentes:** Plugin de Velocity (`nextnode-plugin/`) para la aplicación del límite; mod NeoForge (rangos + panel web) como fuente de datos/configuración.

## Problema

Se quiere limitar el servidor a **20 jugadores**, pero los jugadores con un rango comprado no deben contar contra ese cupo: ni ocupan uno de los 20 slots, ni deben quedarse fuera si los 20 slots normales ya están llenos.

## Solución

El límite se aplica **en el proxy Velocity**, en `LoginEvent`, porque el proxy es la única puerta de entrada a todos los backends — así el conteo es de jugadores conectados a la network completa, no por servidor individual. El mod NeoForge no aplica ningún límite: solo aporta los datos (qué rangos hacen bypass, cuál es el máximo, el mensaje de kick) a través de Mongo y del panel web, igual que ya hace con rangos y demás configuración.

Flujo: `Jugador se conecta → Velocity autentica (LoginEvent) → plugin consulta Mongo (¿tiene rango bypass? ¿cuántos conectados sin bypass?) → deja pasar o rechaza`.

## Decisiones (brainstorming)

1. **Qué cuenta como "tiene rango" para el bypass:** rangos específicos marcados con un interruptor en la web (`bypassPlayerLimit`), no "cualquier rango distinto del default". Permite dejar rangos cosméticos gratuitos sin bypass si se quiere.
2. **Dónde vive la configuración (máximo, rangos con bypass, mensaje):** en MongoDB, editable desde el panel web existente del mod — no en un archivo de config del proxy. El proxy la lee en vivo en cada login; cambios en la web aplican sin reiniciar nada.
3. **Punto de corte:** `LoginEvent` de Velocity (antes de reenviar al backend).
4. **Mensaje de kick:** editable desde la web (no fijo en código).

## Cambios en el mod (fuente de datos + panel web)

### `PermissionModels.Rank`
Nuevo campo:
```java
public boolean bypassPlayerLimit = false;
```
Se persiste en `rankToDoc`/`docToRank` (`PermissionStore.java`) igual que `weight`/`prefix`/`suffix`.

### `PermissionModels` — nueva clase `LimitSettings`
```java
public static final class LimitSettings {
    public boolean enabled = false;
    public int max = 20;
    public String kickMessage = "El servidor está lleno ({online}/{max}). Los rangos con acceso preferente entran igual.";
}
```
Placeholders `{online}`/`{max}` sustituidos por el proxy al construir el mensaje de kick.

### `PermissionStore`
Sigue el mismo patrón que `tabSettings`:
- `KEY_LIMIT_SETTINGS = "limitSettings"` en `COL_SETTINGS`, guardado como JSON (`GSON.toJson(settings)`) bajo `{_id, value}`.
- `getLimitSettings()` / `saveLimitSettings(LimitSettings)` (lock, replaceOne upsert, `fireChanged()`, `publishEvent("settings", "limit")`).
- Se incluye en `readAll()` igual que `tabSettingsDoc`.

### `WebPanelServer`
- `GET /api/settings/limit` → devuelve `LimitSettings` actual.
- `PUT /api/settings/limit` → guarda, audita (`"limit.save"`).
- Editor de rango (formulario existente de prefijo/sufijo/peso): nuevo checkbox **"Sin límite de jugadores (bypass)"** ligado a `bypassPlayerLimit`.
- Nueva sección **"Límite de jugadores"** en la web: activar/desactivar, campo numérico de máximo, textarea de mensaje de kick. Mismo estilo visual que la sección de Tab settings ya existente.

## Cambios en el plugin de Velocity (`nextnode-plugin/`)

### Nueva clase `PlayerLimitMongo` (lectura, separada de `RankMongo` que escribe grants/revokes)
Recibe la `MongoDatabase` ya conectada (se expone un getter en `RankMongo` o se comparte la conexión desde `NextNodePlugin`). Métodos:
- `LimitSettings loadSettings()` — lee `settings/limitSettings` (mismo JSON que escribe el mod) con defaults seguros si falta o el documento no existe.
- `Set<String> bypassRankNames()` — `ranks.find({bypassPlayerLimit: true})`, proyecta `_id`.
- `boolean hasBypassRank(String uuid, Set<String> bypassRanks)` — lee `users` por `_id`, intersecta su `ranks` con `bypassRanks`. Si no existe el doc → `false`.
- `long countOnlineWithoutBypass(Collection<String> onlineUuids, Set<String> bypassRanks)` — una sola consulta `users.find({_id: {$in: onlineUuids}}, {ranks:1})`; cuenta cuántos de `onlineUuids` **no** tienen intersección con `bypassRanks` (los UUIDs sin documento también cuentan como "sin bypass").

### Función pura de decisión (testeable sin Mongo)
```java
static boolean shouldAllow(boolean joinerHasBypass, long nonBypassOnlineCount, LimitSettings settings)
```
- `!settings.enabled` → `true`.
- `joinerHasBypass` → `true`.
- `nonBypassOnlineCount < settings.max` → `true`.
- si no → `false`.

### Listener `PlayerLimitListener` (`@Subscribe` sobre `LoginEvent`)
1. `settings = loadSettings()`; si `!settings.enabled` → no hace nada (deja pasar).
2. `joinerUuid = event.getPlayer().getUniqueId().toString()` (el UUID ya resuelto por Velocity — en modo offline coincide con el `_id` que usa el mod, sin necesidad de derivarlo del nombre).
3. `bypassRanks = bypassRankNames()`.
4. `joinerHasBypass = hasBypassRank(joinerUuid, bypassRanks)`.
5. Si `joinerHasBypass` → deja pasar sin más.
6. Si no: `onlineUuids = server.getAllPlayers().stream().map(Player::getUniqueId).map(UUID::toString)`; `count = countOnlineWithoutBypass(onlineUuids, bypassRanks)`.
7. `shouldAllow(false, count, settings)` decide; si `false` → `event.setResult(ResultedEvent.ComponentResult.denied(Component.text(mensajeFormateado)))`.
8. Cualquier excepción de Mongo en este flujo → **fail-open** (deja pasar), igual que el sistema de baneos (`findBlockingBan` ya sigue esta filosofía).

### Registro
`NextNodePlugin.onInit`: instanciar `PlayerLimitMongo` (comparte conexión con `RankMongo` o recibe la misma `MongoDatabase`), registrar el listener en el `EventManager` de Velocity.

## Casos límite

- **Carrera entre logins simultáneos:** dos jugadores evaluados en el mismo instante podrían pasar ambos justo en el borde del límite (conteo leído antes de que el otro termine de conectar). Es un límite de cortesía, no una garantía dura — no se resuelve con locks distribuidos (fuera de alcance).
- **Jugador nunca antes conectado:** sin documento en `users` → sin rango bypass → cuenta como "sin bypass" (correcto).
- **Mongo caído en el momento del login:** fail-open, se deja pasar (prioriza disponibilidad del servidor sobre el límite).
- **`enabled=false`:** el límite queda completamente desactivado; solo rige el `max-players` real de Velocity/backends.
- **Operativo (recordatorio para el usuario, no código):** hay que subir el `max-players` de Velocity y de los backends por encima de 20 (p. ej. 100), porque si no, el límite duro de Minecraft/Velocity actuaría primero y bloquearía también a los jugadores con rango bypass.

## Testing

- Unitarios (JUnit, mismo estilo que `Days`/`OfflineUuid`/`RankNames`):
  - `shouldAllow(...)`: deshabilitado → true; joiner con bypass → true; conteo por debajo del máximo → true; conteo en el máximo sin bypass → false.
  - Parseo/defaults de `LimitSettings` cuando el documento no existe o es inválido.
- Verificación manual (usuario): con `max=1`, un jugador sin rango entra y ocupa el cupo; un segundo jugador sin rango es rechazado con el mensaje configurado; un jugador con un rango marcado bypass entra igual estando "lleno"; al desactivar el límite desde la web, todos entran de nuevo.

## Fuera de alcance (YAGNI)

- Comando en el juego para editar el límite o el bypass por rango (queda solo en el panel web, como se decidió).
- Resolver el conteo repartido por servidor individual (el conteo es siempre a nivel proxy/network).
- Locks distribuidos o conteo estrictamente atómico contra condiciones de carrera en logins simultáneos.
- Mostrar el límite/cupo en el TAB o MOTD (posible mejora futura, no pedida).
