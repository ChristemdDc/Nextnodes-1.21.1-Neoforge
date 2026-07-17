# Diseño — Mostrar el límite de jugadores en el selector de servidores

- **Fecha:** 2026-07-16
- **Estado:** Aprobado (brainstorming)
- **Componente:** Plugin de Velocity (`nextnode-plugin/`) únicamente. El mod NeoForge NO cambia.

## Problema

La feature de límite de jugadores (spec `2026-07-16-limite-jugadores-bypass-design.md`) exige subir el `max-players` real de Velocity/backends muy por encima del límite lógico (p. ej. 100), para que el tope duro de Minecraft no bloquee a los jugadores con rango bypass antes de que el plugin evalúe nada.

Efecto secundario: la lista de multijugador muestra `N/100`, un número que no significa nada. El jugador no puede saber cuál es el límite real (20).

## Solución

El ping de la lista de multijugador lo responde el **proxy**, y Velocity expone `ProxyPingEvent`, que permite reescribir la respuesta antes de enviarla al cliente. Un listener nuevo sobreescribe los dos números mostrados usando la configuración que ya vive en Mongo. Cero cambios en el mod y cero configuración nueva: reutiliza `limitSettings` y el flag `bypassPlayerLimit` por rango que el panel web ya gestiona.

### Alternativas descartadas

- **Bajar el `max-players` real a 20:** mostraría el número correcto pero reactivaría el tope duro que rechaza a los VIP — es exactamente el problema que la feature original evita.
- **Número fijo en `config.properties` del plugin:** duplicaría el límite en dos sitios (archivo + panel web) y se desincronizaría en cuanto se cambie uno.

## Decisión de diseño (brainstorming)

**Qué se muestra a la izquierda del límite: solo los jugadores que cuentan para el límite** (los que NO tienen rango bypass).

Razón: el número mostrado es exactamente el que decide si te dejan entrar — `20/20` significa inequívocamente "un jugador sin rango será rechazado". La alternativa (mostrar todos los conectados) permitiría que el número izquierdo supere al derecho (`30/20` con 20 normales + 10 VIP), lo que se lee como un error.

Contrapartida aceptada: los jugadores con rango bypass no aparecen en el conteo, así que el número no refleja la población total del servidor.

## Componente nuevo

### `PlayerLimitPingListener` (`@Subscribe` sobre `ProxyPingEvent`)

Registrado en `NextNodePlugin.onInit` junto al `PlayerLimitListener` que ya existe, compartiendo la misma instancia de `PlayerLimitMongo`. **No requiere métodos nuevos en `PlayerLimitMongo`** — reutiliza `loadSettings()`, `bypassRankNames()` y `countOnlineWithoutBypass()` tal cual.

Lógica por ping:
1. Si el ping original **no trae bloque de jugadores** → no toca nada (ver "Trampa del API" abajo).
2. Si el límite está desactivado → no toca el ping (se muestra el `max-players` real, como hoy).
3. Si está activado → reconstruye la respuesta con `maximumPlayers = settings.max` y `onlinePlayers = jugadores sin bypass`.
4. Ante cualquier excepción → **fail-open**: se envía el ping original sin modificar (misma filosofía que `PlayerLimitListener` y el sistema de baneos).

### Trampa del API (verificada en el código fuente de Velocity 3.3.0)

`ServerPing.asBuilder()` marca `nullOutPlayers = true` cuando el ping original tiene `players == null`, y `build()` hace:

```java
nullOutPlayers ? null : new Players(onlinePlayers, maximumPlayers, samplePlayers)
```

Es decir: con `nullOutPlayers` activo, las llamadas a `.onlinePlayers()`/`.maximumPlayers()` se **ignoran silenciosamente** y el límite nunca se mostraría, sin error ni log. Por eso el listener comprueba `ping.getPlayers().isEmpty()` y sale temprano en vez de construir un bloque de jugadores que el ping original no tenía.

### Caché (obligatoria, no optimización prematura)

A diferencia del login (raro), el ping se dispara constantemente: cada cliente con la pantalla de multijugador abierta refresca cada pocos segundos, más monitores de uptime. El javadoc de `ProxyPingEvent` lo dice explícitamente: *"you are urged to handle this event as quickly as possible ... due to the amount of ping packets a client can send"*.

El listener cachea los números calculados (`enabled`/`max`/`online`) durante **3 segundos** en un único snapshot inmutable tras una referencia `volatile`:

- Un snapshot único (en vez de campos sueltos) evita leer `enabled` de un refresco y `max` de otro.
- El refresco va dentro de un `synchronized` con doble comprobación, para que un pico de pings al expirar la caché no dispare una estampida de consultas a Mongo.
- La antigüedad se mide con `System.nanoTime()` (monótono), no `currentTimeMillis()`, que puede saltar con un ajuste de NTP y congelar la caché.

3 segundos de desfase es imperceptible en una lista de servidores, y acota las consultas a como mucho una cada 3s sin importar el volumen de pings.

## Testing

Sin tests nuevos. No hay lógica pura que extraer: los números son directamente `settings.max` y el conteo que `PlayerLimitMongo` ya calcula (y `PlayerLimitDecision` ya tiene sus tests). Los listeners de Velocity no se testean en este proyecto — igual que `PlayerLimitListener`, por la misma razón: requieren un proxy y una Mongo en vivo.

Verificación manual (usuario):
1. Con el límite **desactivado** en la web → el selector muestra el `max-players` real (p. ej. `N/100`), sin cambios respecto a hoy.
2. Activar el límite en 20 → el selector pasa a mostrar `N/20`.
3. Conectar un jugador **sin** rango bypass → el número izquierdo sube.
4. Conectar un jugador **con** rango bypass → el número izquierdo **no** sube (es la contrapartida decidida arriba).

## Fuera de alcance (YAGNI)

- Modificar el MOTD o el sample de jugadores del hover.
- Cualquier cambio en el panel web (reutiliza la config existente).
- Mostrar la población total además del conteo del límite (decisión ya tomada arriba).
- Hacer configurable el TTL de la caché (3s fijo; nadie necesita ajustarlo).
