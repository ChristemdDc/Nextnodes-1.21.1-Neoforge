# Diseño — Plugin de Velocity para Tebex (otorgar/quitar rangos)

- **Fecha:** 2026-07-06
- **Estado:** Aprobado (brainstorming) — pendiente de revisión de la spec
- **Componente:** Plugin de Velocity (proyecto standalone), NO el mod NeoForge.

## Problema

Tebex no tiene plugin para NeoForge. En una network con **Velocity**, el plugin de Tebex vive en el proxy y ejecuta los comandos de compra **en la consola de Velocity**. Un comando del mod (`/nn rank add`) no existe en Velocity → muere ahí y nunca llega al servidor NeoForge. Por eso las compras no aplican el rango.

## Solución

Un **plugin de Velocity** que Tebex ejecuta (ahí sí puede), que en vez de correr un comando de Minecraft **escribe directo en la misma MongoDB** (agrega/quita el rango del jugador) e **inserta un evento de sync**. El servidor NeoForge ya escucha `sync_events` (el sistema de sincronización existente) → recarga y aplica el cambio automáticamente, incluso con el jugador offline. **Cero cambios en el mod.**

Flujo: `Compra en Tebex → Velocity ejecuta /nngrant <uuid> <rango> → plugin escribe en Mongo + sync → NeoForge aplica`.

## Decisiones (brainstorming)

1. **Proxy:** Velocity.
2. **Efecto:** *agregar* rango (equivale a `/nn rank add`): el rango se suma a la lista del jugador, no reemplaza el principal.
3. **Agregar y quitar:** dos comandos — otorgar (compra) y revocar (expiración/reembolso).
4. **Identificador:** UUID (Tebex lo provee; robusto y funciona offline).

## Contrato de esquema (DEBE calcar el del mod)

### Colección `users` (documento por jugador, `_id` = UUID en formato con guiones)
```
{
  _id:         "<uuid con guiones>",
  name:        "<nombre o "">",
  tag:         "",
  primaryRank: "",
  ranks:       ["rango1", "rango2", ...],   // strings en minúscula
  permissions: [],
  meta:        {},
  lastSeen:    0,
  online:      false
}
```
Fuente de verdad: `PermissionStore.userToDoc` / `docToUser` y `PermissionModels.UserEntry`. Los nombres de rango se normalizan con `trim().toLowerCase()` (equivalente a `PermissionModels.normalizeName`).

### Colección `sync_events` (capped) — evento que dispara la recarga en el backend
```
{ origin: "velocity-tebex", type: "user", key: "<uuid>", ts: <epoch ms> }
```
El listener del backend (`PermissionStore.runSyncListener`) lee eventos con `ts > lastEventTs`, **ignora los de su propio `origin`** y los `seed`, y ante cualquier otro llama `reloadFromExternalChange()` (recarga TODO). Por eso basta con:
- `origin` distinto al `serverId` del backend (usamos la constante `"velocity-tebex"`),
- `ts` = ahora,
- `type`/`key` informativos (el backend recarga todo igual).

**Importante:** `sync_events` es **capped** (creada por el backend con `capped(true).sizeInBytes(1_048_576).maxDocuments(10_000)`). Un cursor tailable REQUIERE colección capped. Si el plugin la crea (porque el backend aún no arrancó), **debe crearla capped con los mismos parámetros**; si ya existe, solo inserta.

## Comandos del plugin (ejecutados por Tebex como consola en Velocity)

- **`/nngrant <uuid> <rango>`** — agrega el rango:
  - Normaliza `uuid` a formato con guiones; normaliza `rango` a minúscula.
  - `updateOne(_id=uuid, { $addToSet: { ranks: rango }, $setOnInsert: { name:"", tag:"", primaryRank:"", permissions:[], meta:{}, lastSeen:0, online:false } }, upsert=true)`.
  - Inserta el evento de sync.
- **`/nnrevoke <uuid> <rango>`** — quita el rango:
  - `updateOne(_id=uuid, { $pull: { ranks: rango } })`, y si `primaryRank == rango` → `$set primaryRank ""` (para no dejar un principal colgado).
  - Inserta el evento de sync.
- Ambos ejecutables por **consola** (así los corre Tebex). Loguean éxito/fallo.

## Configuración del plugin

Archivo en el data-dir del plugin (creado con defaults en el primer arranque):
```
mongoUri  = "mongodb://localhost:27017"   # el mismo del mod
database  = "nextnodes_permissions"       # el mismo del mod
```
Formato concreto (TOML/properties) se decide en el plan.

## Estructura del proyecto

Proyecto Gradle **standalone** en una subcarpeta del repo (p. ej. `velocity-tebex/`), independiente del build de NeoForge:
- `build.gradle` — Velocity API (repo de PaperMC) + MongoDB sync driver + shadow (para empaquetar el driver).
- Clase principal `@Plugin` (Velocity 3.3.x para MC 1.21.1) — en `ProxyInitializeEvent`: carga config, conecta Mongo, registra comandos.
- Repositorio Mongo (grant/revoke, ensureSyncCollection, publishSync) — **lógica pura testeable** para construir documentos, normalizar UUID/rango y armar el evento de sync.
- Comandos `nngrant` / `nnrevoke`.

## Config en Tebex

- Comando de compra: `nngrant {uuid} <rango>` (ej. `nngrant {uuid} vip`).
- Comando de expiración/reembolso: `nnrevoke {uuid} <rango>`.
- (Tebex provee `{uuid}`; si viniera sin guiones, el plugin lo normaliza.)

## Testing

Tests unitarios de la lógica pura (sin Velocity ni Mongo en vivo):
- Normalización de UUID (con/sin guiones → con guiones).
- Normalización de rango (minúscula/trim).
- Construcción del update de grant (`$addToSet` + `$setOnInsert`) y de revoke (`$pull` [+ limpiar primaryRank]).
- Construcción del documento de evento de sync (`origin/type/key/ts`).

Verificación end-to-end (compra real → rango aplicado en NeoForge) la hace el usuario. El build del plugin descarga la Velocity API + driver de Mongo; si el entorno no puede, el usuario lo compila.

## Fuera de alcance (YAGNI)

- Soporte para BungeeCord (solo Velocity).
- Resolver nombre→UUID vía Mojang (usamos el UUID que da Tebex).
- Actualizar `name` del jugador desde el plugin (el mod lo llena al conectarse; se puede añadir después).
- Validar que el rango exista (el plugin solo escribe el string; si el rango no existe, no rompe nada — simplemente no se muestra).

## Riesgo / dependencia

- El plugin **depende del esquema** de la BD del mod. Es estable, pero si cambia (`userToDoc`/`sync_events`), el plugin debe seguirlo. Documentado arriba como contrato.
- Empaquetado: el driver de Mongo debe ir **shaded** dentro del jar del plugin (Velocity no lo provee).
