# Diseño — Disfraz de admin, Fase 1 (TAB + chat + rango cosmético)

- **Fecha:** 2026-07-20
- **Estado:** Aprobado (brainstorming)
- **Componentes:** Mod NeoForge (modelo, persistencia, resolver, nombre de TAB) + panel web (`app.js`). **Sin cambios en el plugin.**

## Problema

El admin quiere entrar de incógnito: cambiar su **nombre visible** y mostrarse con un **rango cosmético**, para que otros no sepan que es él, **manteniendo todos sus permisos de admin**.

## Constraint aceptado

El nombre **sobre la cabeza** en el mundo es el usuario real y no se puede renombrar server-side sin reescritura de paquetes (nick completo). Eso es **Fase 2** (subproyecto aparte, frágil, además requiere skin falsa para no delatar). Esta Fase 1 cubre TAB y chat, que es donde se ve quién está conectado y quién habla.

## Idea central: separar apariencia de función

Dos campos nuevos por jugador:
- `disguiseName` (String, "" = sin disfraz de nombre): nombre falso mostrado en TAB y chat.
- `disguiseRank` (String, "" = sin disfraz de rango): nombre de un rango cuya apariencia (prefijo/sufijo/peso) se muestra.

Los métodos de **display** del resolver usan el rango falso; los de **permisos** NO se tocan → se conservan todos los permisos reales. Los dos campos son independientes: puedes poner solo nombre, solo rango, o ambos.

## Superficies

1. **Lista TAB — completa.** Vía `PlayerEvent.TabListNameFormat` (evento estándar de NeoForge 21.1.x, confirmado presente): si el jugador está disfrazado, `event.setDisplayName(...)` con la línea compuesta "[prefijo falso] NombreFalso [sufijo]". El cliente muestra eso en el TAB. `refreshTabListName()` (ya se llama en el refresco existente) lo redispara al cambiar el disfraz. El **orden** del TAB usa el peso del rango falso (`resolveWeight`), así que un admin disfrazado de jugador normal baja en la lista.
2. **Chat — completa.** `composeFullName` usa `disguiseName` si está puesto y el aspecto del rango falso.
3. **Sobre la cabeza — parcial (la pared).** El equipo pinta el prefijo del rango falso, pero el nombre en medio sigue siendo el real. Es Fase 2.

## Componentes y cambios

### `PermissionModels.UserEntry`
Campos `disguiseName`, `disguiseRank` (default ""); `sanitize()` los null-guarda (disguiseRank normalizado a minúscula, disguiseName tal cual).

### `PermissionStore`
`userToDoc`/`docToUser` persisten ambos campos. Sin más cambios (el guardado y el refresco ya existen).

### `DisguiseResolver` (nueva clase, lógica pura testeable)
`static String displayRank(UserEntry user, Map<String,Rank> ranks)`: devuelve `user.disguiseRank` si está no-vacío y existe en `ranks`; si no, `null`. Es el único punto de decisión "apariencia disfrazada vs normal", y se testea aislado.

### `PermissionResolver` (solo métodos de display)
`resolvePrefix`/`resolveSuffix`/`resolveWeight`: si `DisguiseResolver.displayRank(...)` != null, resuelven desde ESE rango único (su prefix/suffix/weight), no desde los rangos reales. `resolveTag`: si hay disfraz, devuelve "" (no mostrar el tag real, que delataría). Los métodos de **permisos** (`resolveBoolean`, `resolveMeta`, etc.) quedan **intactos**.

### `NextNodesPermissions`
- `composeFullName(uuid, baseName)`: si el usuario tiene `disguiseName`, usar ese en vez de `baseName` (afecta chat; el TAB va por el evento).
- Nuevo handler `@SubscribeEvent onTabListNameFormat(PlayerEvent.TabListNameFormat)`: si disfrazado, `setDisplayName(composeFullName(uuid, disguiseName))`.
- El log de consola del chat sigue mostrando el **nombre real** (auditoría; la consola es solo de admins).
- `refreshPlayerName` ya reasigna equipo + `refreshTabListName()`, así que un cambio de disfraz desde la web se aplica en vivo sin código nuevo.

### Web (`app.js`)
Sección "Disfraz" en el editor de jugador: input de nombre falso (`user_disguise_name`) + selector de rango visible (`user_disguise_rank`, vacío = ninguno). `readUserEditor` los incluye en el PUT. El backend ya persiste vía el PUT genérico de usuario.

## Datos que NO cambian

Permisos, UUID, identidad real, comando del plugin, sincronización. El disfraz es puramente de presentación.

## Riesgo / honestidad técnica

- **Velocity y el TAB.** El evento produce el paquete estándar de nombre de TAB, que el proxy debería reenviar sin tocar. Si el Velocity del usuario gestiona su propia lista de tab y lo pisa, el nombre falso en TAB podría no aplicarse; en ese caso chat + orden + rango cosmético siguen funcionando y se ajusta esa pieza. Se verifica en vivo al desplegar.
- **Sobre la cabeza sigue el nombre real** (Fase 1 por diseño).

## Pruebas

- **Unit (JUnit, pura):** `DisguiseResolverTest` — con `disguiseRank` existente devuelve ese nombre; con vacío o inexistente devuelve null. Documenta la separación apariencia/función.
- **Navegador:** la sección "Disfraz" del editor renderiza y `readUserEditor` incluye `disguiseName`/`disguiseRank` en el PUT.
- **En vivo (usuario):** ponerse un disfraz desde la web y verificar en el juego que el TAB muestra nombre+rango falsos, que baja en el orden, que el chat sale disfrazado, y que los permisos de admin siguen (ej. abrir un comando de op).

## Fuera de alcance (YAGNI / Fase 2)

- Nick sobre la cabeza (reescritura de paquetes) y skin falsa.
- Toggle rápido en el juego (por ahora se pone/quita desde la web).
- Disfraz de skin.
