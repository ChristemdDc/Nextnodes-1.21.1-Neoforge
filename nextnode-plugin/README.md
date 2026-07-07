# NextNode Plugin (Velocity)

Otorga o quita rangos de NextNodes escribiendo en la **misma MongoDB** del mod + un evento de sync.
El servidor NeoForge aplica el cambio automáticamente (incluso con el jugador desconectado).

Pensado para que un sistema externo (tienda web, panel, etc.) dé rangos automáticamente
ejecutando un comando de consola en Velocity al procesar una compra.

## Compilar
Desde la raíz del repo:
```
./gradlew -p nextnode-plugin build
```
Genera `nextnode-plugin/build/libs/nextnode-plugin-1.0.0.jar` (con el driver de Mongo incluido).

## Instalar
1. Copia el jar a la carpeta `plugins/` de **Velocity** y arranca Velocity una vez (crea la config).
2. Edita `plugins/nextnode-plugin/config.properties`:
   ```properties
   mongoUri=mongodb://IP_DEL_SERVIDOR_MONGO:27017
   database=nextnodes_permissions
   onlineMode=false
   ```
   - `mongoUri` / `database`: los **MISMOS** que usa el mod. Si el proxy y el servidor están en máquinas distintas, `mongoUri` debe apuntar a la **IP** donde corre Mongo (no `localhost`).
   - `onlineMode`: **false** si tu servidor es **offline** (el UUID se deriva del nombre → usa `{username}` en la tienda). **true** si es **premium** (usa `{uuid}`).
3. Reinicia Velocity.

## Comandos (ejecutables por consola)
- `nngrant <jugador> <rango>` — agrega el rango.
- `nnrevoke <jugador> <rango>` — se lo quita.

`<jugador>` = el **nombre** si `onlineMode=false`, o el **UUID** si `onlineMode=true`.
Los jugadores necesitan el permiso `nextnode.plugin` (la consola siempre puede).

## Configuración en tu tienda / sistema externo
Servidor **offline** (`onlineMode=false`, lo normal en una network con proxy):
- Compra: `nngrant {username} vip`
- Expiración/reembolso: `nnrevoke {username} vip`

Servidor **premium** (`onlineMode=true`):
- Compra: `nngrant {uuid} vip`
- Expiración/reembolso: `nnrevoke {uuid} vip`

(Reemplaza `vip` por el rango.)

## Cómo funciona
- Resuelve el jugador a su `_id`: en offline **deriva el UUID del nombre** (`OfflinePlayer:<nombre>`, igual que el servidor); en premium usa el UUID dado.
- `nngrant`: `updateOne` en `users` con `$addToSet` de `ranks` (crea el doc si no existe, y rechaza rangos inexistentes) + inserta `{origin:"nextnode-plugin", type:"user", key:uuid, ts}` en `sync_events`.
- El mod NeoForge escucha `sync_events` (cursor tailable en la colección capped) → recarga y aplica.

Requiere que `mongoUri`/`database` coincidan con los del mod, y que la MongoDB sea accesible desde el proxy.
