# Diseño: Correcciones de visualización de rangos, sincronización, sufijo/tag y chat

**Fecha:** 2026-06-07
**Mod:** NextNodes Permissions (NeoForge 1.21.1)
**Estado:** Aprobado, pendiente de plan de implementación

## Contexto

NextNodes es un mod de permisos/rangos para servidor dedicado con un panel web y
almacenamiento en MongoDB. Se reportaron cuatro problemas que este diseño resuelve.

## Objetivos

1. **TAB:** ordenar el TAB por rango (mayor peso arriba; con rango por encima de sin rango)
   y mostrar correctamente prefijo/sufijo/tag.
2. **Sincronización entre servidores:** que un cambio de rango hecho en un servidor (o en el
   panel) se propague a los demás servidores **por push** (sin polling en bucle).
3. **Nombre:** mostrar `prefijo(rango) + nombre + sufijo(rango) + tag(jugador)` sobre la cabeza
   y en el TAB.
4. **Chat:** mostrar ese mismo nombre compuesto en el chat.

## Modelo de visualización (decidido con el usuario)

Estructura única usada en los tres lugares (sobre la cabeza, TAB, chat):

```
<prefijo(rango)> <nombre> <sufijo(rango)> <tag(jugador)>
Ej:  Steampunk Camaroncin2 el Grande [pepsi]
```

- **prefijo**: campo del **rango**. Ya existe (`Rank.prefix`). Va antes del nombre.
- **sufijo**: campo **nuevo** del **rango** (`Rank.suffix`). Va después del nombre.
- **tag**: campo **nuevo** del **jugador** (`UserEntry.tag`). Personal, va después del sufijo.

Convención de espacios: el `prefijo` incluye su propio espacio final (como hoy). El `sufijo`
y el `tag` se anteponen con un espacio automático sólo si no están en blanco. Soportan códigos
de color `&`, `&#RRGGBB`, etc. (vía `PrefixFormatter.format`).

## Decisiones tomadas

| Tema | Decisión |
|------|----------|
| Formato de chat | Sin `< >`: `Steampunk Camaroncin2 el Grande [pepsi]: mensaje`. Se cancela el evento de chat y se reenvía el mensaje ya formateado a todos. Se pierde la firma de chat 1.19+ (irrelevante en servidor privado). |
| Mecanismo de sync | **Push** vía *cursor tailable* sobre una colección *capped* `sync_events`. Funciona en MongoDB standalone (el usuario no puede pasar a replica set, así que Change Streams no aplica). |
| Tag | Por jugador (`UserEntry.tag`). |
| Sufijo | Por rango (`Rank.suffix`), separado del tag. |

## Diseño detallado

### 1. Cambios de modelo (`PermissionModels.java`)

- `Rank`: añadir `public String suffix = "";`. En `sanitize()`, normalizar `null -> ""`.
- `UserEntry`: añadir `public String tag = "";`. En `sanitize()`, normalizar `null -> ""`.

### 2. Serialización Mongo (`PermissionStore.java`)

- `rankToDoc`: `.append("suffix", rank.suffix)`. `docToRank`: `rank.suffix = doc.getString("suffix");`
- `userToDoc`: `.append("tag", user.tag)`. `docToUser`: `user.tag = doc.getString("tag");`
- `sanitize()` cubre documentos antiguos sin estos campos (quedan `""`). Sin migración necesaria.

### 3. Resolución (`PermissionResolver.java`)

Métodos nuevos (siguen el mismo patrón que `resolvePrefix`, que recorre `rankOrder`):

- `String resolveSuffix(UUID uuid)` — primer `suffix` no vacío en el orden de rangos; si no hay
  usuario, el del rango por defecto.
- `String resolveTag(UUID uuid)` — `user.tag` (per-jugador); `""` si no hay usuario.
- `int resolveWeight(UUID uuid)` — peso del rango de mayor prioridad del jugador
  (`rankOrder(...).get(0).weight`), usado para ordenar el TAB. Es coherente con el prefijo
  mostrado (mismo rango "principal").

### 4. Helper de nombre compuesto (`PrefixFormatter.java`)

```java
public static Component composeName(String prefix, String name, String suffix, String tag) {
    // prefix(format) + name(RESET) + (" "+suffix(format) si no vacío) + (" "+tag(format) si no vacío)
}
```

`NextNodesPermissions` tendrá un helper privado:
```java
private Component composeFullName(UUID uuid, String baseName) {
    return PrefixFormatter.composeName(
        resolver.resolvePrefix(uuid), baseName,
        resolver.resolveSuffix(uuid), resolver.resolveTag(uuid));
}
```

Se usa en `onNameFormat`, `onTabListName` y en el chat. Reemplaza el uso actual de
`PrefixFormatter.prefixedName` (que sólo añadía prefijo).

### 5. Orden del TAB + nombre sobre la cabeza — `TabListManager` (clase nueva)

Minecraft ordena el TAB por `(gamemode, nombre-del-team, nombre-del-jugador)` y dibuja el
nombre flotante sobre la cabeza usando el **team** (no el evento `NameFormat`). Por eso se usa
un **scoreboard team por jugador**.

- **Nombre interno del team** (controla el orden): `nn_<weightKey>_<nombreMinus>_<uuid8>`
  - `weightKey = String.format("%010d", (long)Integer.MAX_VALUE - peso)` → mayor peso ⇒ string
    menor ⇒ aparece **arriba**.
  - `nombreMinus` = nombre del jugador en minúsculas, saneado → desempate **alfabético** entre
    jugadores del mismo peso (necesario porque cada jugador tiene su propio team y el desempate
    nativo por nombre de perfil no se aplicaría).
  - `uuid8` = primeros 8 chars del UUID → unicidad.
  - *Verificar en implementación* que la longitud del nombre del team es aceptable (acortar
    `uuid8`/`nombre` si hiciera falta).
- **`team.setPlayerPrefix(format(prefix))`** y **`team.setPlayerSuffix(" "+suffix + " "+tag)`**
  (componentes formateados) → muestran prefijo/sufijo/tag **sobre la cabeza**. Los setters de
  `PlayerTeam` ya disparan el paquete de actualización al cliente.
- **`apply(player)`**: calcula el team deseado; si el jugador está en un team `nn_` distinto, lo
  saca (y borra ese team si queda vacío); crea/obtiene el team deseado; fija prefix/suffix;
  añade al jugador.
- **`remove(player)`**: lo saca de su team `nn_` y borra el team si queda vacío.
- **`cleanupAll(server)`**: borra todos los teams `nn_` (al apagar).

El texto del TAB se sigue fijando con `TabListNameFormat = composeFullName` (control exacto del
texto); el team sólo aporta **orden** y **nombre sobre la cabeza**. El objetivo de ping
(`DisplaySlot.LIST`) es independiente y no cambia.

Integración en `NextNodesPermissions`:
- Instanciar `tabListManager = new TabListManager(resolver)` (lado servidor).
- `refreshPlayerName(player)` añade `tabListManager.apply(player)` tras refrescar nombre/tab.
- `onPlayerLoggedOut` → `tabListManager.remove(player)`.
- `onServerStopping` → `tabListManager.cleanupAll(server)` (junto a la limpieza del ping).

### 6. Sincronización push — cursor tailable sobre colección capped (`PermissionStore.java`)

- **Identidad de servidor**: `serverId = UUID.randomUUID().toString()` por proceso.
- **Colección** `sync_events` *capped* (p. ej. 1 MB / 10 000 docs). Se crea en `load()` si no
  existe (`createCollection(capped=true)`); carrera entre servidores → capturar y continuar.
  Las colecciones capped y los cursores tailable **no requieren replica set**.
- **Publicar**: tras cada mutación de **configuración** se inserta un doc
  `{ origin: serverId, type, key, ts }`. Se publica en: `saveRank`, `deleteRank`, `saveUser`,
  `deleteUser`, `saveTabSettings`, `importAll`. **No** se publica en `touchPlayer`/`setOnline`
  (login/logout transitorio) para evitar ruido entre servidores.
- **Escuchar**: hilo daemon con cursor `CursorType.TailableAwait`, `noCursorTimeout(true)`,
  filtrando `ts > startupTs` (y `> lastProcessedTs` al reconectar). El cursor **se bloquea**
  esperando; MongoDB le **envía** los nuevos docs (push, sin polling en bucle).
  - Por cada evento con `origin != serverId`: recargar todo (`readAll` + `ensureDefaults`),
    swap de `data` bajo `writeLock`, invalidar `cachedSnapshot`, y `fireChanged()` fuera del
    lock (refresca jugadores online en el hilo principal vía `server.execute`).
  - Eventos con `origin == serverId` se ignoran (ya se aplicaron localmente) → no hace falta
    comparar firmas.
  - Si el cursor muere o hay error de conexión: pausa breve y reabrir (esto **no** es polling
    en estado estable; sólo ocurre ante caída del cursor).
- **Ciclo de vida**: arrancar el hilo al final de `load()`; en `close()` marcar `syncClosed`,
  interrumpir el hilo y cerrar cliente (la excepción del cursor bloqueado sale del bucle).

### 7. Chat (`NextNodesPermissions.onServerChat`)

```java
ServerPlayer p = event.getPlayer();
Component line = Component.empty()
    .append(composeFullName(p.getUUID(), p.getGameProfile().getName()))
    .append(Component.literal(": "))
    .append(event.getMessage());      // contenido del mensaje
event.setCanceled(true);
p.getServer().getPlayerList().broadcastSystemMessage(line, false);
LOGGER.info("[CHAT] {}: {}", p.getGameProfile().getName(), event.getMessage().getString());
```

Reemplaza el `refreshDisplayName()` actual. *Verificar en implementación* las firmas exactas
de `ServerChatEvent.getMessage()/setCanceled()` en NeoForge 1.21.1.

### 8. Panel web (`src/main/resources/web/app.js`)

El backend (`WebPanelServer`) deserializa con `GSON.fromJson(..., Rank.class / UserEntry.class)`
y serializa `snapshotJson()` completo, así que **no necesita cambios**. Sólo la UI:

- `rankEditor`: input nuevo **"Sufijo"** (con vista previa `prefixHtml`, junto al prefijo).
  `readRankEditor`: incluir `suffix`. `newRank`: `suffix:''`.
- `userEditor`: input nuevo **"Tag"**. `readUserEditor`: incluir `tag`. `newUser`: `tag:''`.
- (Opcional) `bootDemo` y `rankCard`/`userRow` para reflejar sufijo/tag.

## Componentes y responsabilidades

| Unidad | Responsabilidad |
|--------|-----------------|
| `PermissionModels` | Datos: + `Rank.suffix`, `UserEntry.tag` |
| `PermissionStore` | Persistencia + **sync push** (capped collection + tailable cursor) |
| `PermissionResolver` | `resolveSuffix`, `resolveTag`, `resolveWeight` |
| `PrefixFormatter` | `composeName(prefix,name,suffix,tag)` |
| `TabListManager` (nuevo) | Teams por jugador: **orden del TAB** + **nombre sobre la cabeza** |
| `NextNodesPermissions` | Cableado de eventos: NameFormat, TabListName, chat, login/logout, stopping |
| `web/app.js` | UI: campo Sufijo (rango), campo Tag (jugador) |

## Casos límite

- Jugador sin rango → prefijo/sufijo del rango por defecto (posiblemente vacíos), sin tag; peso
  por defecto (más bajo) ⇒ aparece abajo en el TAB. Correcto.
- Documentos Mongo antiguos sin `suffix`/`tag` → `sanitize()` los deja en `""`.
- Creación concurrente de `sync_events` entre servidores → capturar excepción.
- Si ya existiera una colección `sync_events` **no** capped → *verificar/registrar* en
  implementación (recrear o usar otro nombre).
- Longitud del nombre de team → verificar límite y acortar si necesario.
- El chat por `broadcastSystemMessage` omite otros mods de chat (consecuencia aceptada de no usar `< >`).
- Estado online entre servidores: best-effort (no se sincroniza por push); el foco es el rango.

## Estrategia de pruebas

- **Unitarias (lógica pura):**
  - `PrefixFormatter.composeName`: estructura del componente con/sin sufijo/tag y espacios.
  - `TabListManager.teamNameFor` (extraer a método testeable): mayor peso ⇒ nombre de team
    lexicográficamente menor; mismo peso ⇒ orden alfabético por nombre.
  - `PermissionResolver.resolveSuffix/resolveTag/resolveWeight` con datos en memoria.
- **Manual en juego (checklist):**
  - TAB: rangos ordenados por peso; con-rango por encima de sin-rango; prefijo/sufijo/tag visibles.
  - Nombre sobre la cabeza: prefijo + nombre + sufijo + tag.
  - Chat: `prefijo nombre sufijo [tag]: mensaje`.
  - Sync: cambiar rango en servidor A (o panel) y ver que el jugador en servidor B se actualiza
    sin reinicio, en ~tiempo de propagación del evento.

## Fuera de alcance

- Comandos in-game para fijar sufijo/tag (se configuran por panel, igual que el prefijo). Se
  puede añadir `/nn tag <jugador> <texto>` más adelante si se desea.
- Color del nombre del jugador según rango (se deja el nombre por defecto).
- Sincronización push del estado online entre servidores.
