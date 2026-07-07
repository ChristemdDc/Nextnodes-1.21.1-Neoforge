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
2. Edita `plugins/nextnode-plugin/config.properties` con el **MISMO** `mongoUri` y `database` que usa el mod:
   ```properties
   mongoUri=mongodb://TU_HOST:27017
   database=nextnodes_permissions
   ```
3. Reinicia Velocity.

## Comandos (ejecutables por consola)
- `nngrant <uuid> <rango>` — agrega el rango al jugador.
- `nnrevoke <uuid> <rango>` — se lo quita.

Los jugadores necesitan el permiso `nextnode.plugin` (la consola siempre puede).

## Configuración en tu tienda / sistema externo
- Comando de **compra**: `nngrant {uuid} vip`
- Comando de **expiración/reembolso**: `nnrevoke {uuid} vip`

(Reemplaza `vip` por el rango. `{uuid}` = el UUID del jugador que provee tu sistema; el plugin acepta UUID con o sin guiones.)

## Cómo funciona
- `nngrant`: `updateOne` en la colección `users` con `$addToSet` de `ranks` (crea el documento si no existe, y rechaza rangos inexistentes) + inserta `{origin:"nextnode-plugin", type:"user", key:uuid, ts}` en `sync_events`.
- El mod NeoForge escucha `sync_events` (cursor tailable en la colección capped) → recarga y aplica.

Requiere que `mongoUri`/`database` coincidan con los del mod, y que la MongoDB sea accesible desde el proxy.
