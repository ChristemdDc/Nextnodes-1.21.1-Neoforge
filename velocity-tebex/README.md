# NextNodes Tebex (plugin de Velocity)

Otorga/quita rangos escribiendo en la **misma MongoDB** del mod NextNodes + un evento de sync.
El servidor NeoForge aplica el cambio automáticamente (incluso con el jugador desconectado).

Sirve para que las compras de **Tebex** (cuyo plugin corre en Velocity, no en NeoForge) apliquen el rango:
Tebex ejecuta el comando en Velocity → este plugin escribe en Mongo → el mod sincroniza y aplica.

## Compilar
Desde la raíz del repo:
```
./gradlew -p velocity-tebex build
```
Genera `velocity-tebex/build/libs/nextnodes-tebex-1.0.0.jar` (con el driver de Mongo incluido).

## Instalar
1. Copia el jar a la carpeta `plugins/` de **Velocity** y arranca Velocity una vez (crea la config).
2. Edita `plugins/nextnodes-tebex/config.properties` con el **MISMO** `mongoUri` y `database` que usa el mod:
   ```properties
   mongoUri=mongodb://TU_HOST:27017
   database=nextnodes_permissions
   ```
3. Reinicia Velocity.

## Comandos (ejecutables por consola)
- `nngrant <uuid> <rango>` — agrega el rango al jugador.
- `nnrevoke <uuid> <rango>` — se lo quita.

Los jugadores necesitan el permiso `nextnodes.tebex` (la consola siempre puede).

## Configuración en Tebex
- Comando de **compra**: `nngrant {uuid} vip`
- Comando de **expiración/reembolso**: `nnrevoke {uuid} vip`

(Reemplaza `vip` por el rango que corresponda. Tebex provee `{uuid}`; el plugin acepta UUID con o sin guiones.)

## Cómo funciona
- `nngrant`: `updateOne` en la colección `users` con `$addToSet` de `ranks` (crea el documento si no existe) + inserta `{origin:"velocity-tebex", type:"user", key:uuid, ts}` en `sync_events`.
- El mod NeoForge escucha `sync_events` (cursor tailable en la colección capped) → recarga y aplica.

Requiere que el `mongoUri`/`database` coincidan con los del mod, y que la MongoDB sea accesible desde el proxy.
