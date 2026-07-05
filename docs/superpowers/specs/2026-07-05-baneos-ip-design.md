# Diseño — Sistema de baneos por IP

- **Fecha:** 2026-07-05
- **Estado:** Aprobado (brainstorming) — pendiente de revisión de la spec
- **Mod:** NextNodes Permissions (NeoForge 1.21.1, Java 21, MongoDB)

## Objetivo

Sistema de baneos que impida que un jugador baneado vuelva a entrar con **otra cuenta**, baneando también su **IP**. Debe ser **observable y controlable desde el panel web** e incluir un **registro** (historial) de acciones e intentos bloqueados.

## Decisiones (brainstorming)

1. **Modelo:** *cuenta + IP automática*. Se banea por jugador; el sistema guarda su última IP conocida y la banea también. También se puede banear una IP suelta a mano.
2. **Tipos:** permanente **y** temporal (con duración), con **razón**, **autor** y **fecha**. Al expirar se levanta solo.
3. **Anti-alt:** el panel muestra **cuentas vinculadas por IP** (posibles alts).
4. **Enfoque:** sistema propio en MongoDB (no se usa el ban de vanilla), para que **sincronice** entre servidores, se **controle desde la web** y guarde **historial**.

## Modelo de datos (MongoDB — se sincroniza)

### Colección `bans` (un documento por baneo)
| Campo | Tipo | Notas |
|---|---|---|
| `id` | string (UUID) | Identificador único del baneo |
| `type` | `"account"` \| `"ip"` | Tipo de baneo |
| `targetUuid` | string \| null | UUID del jugador (baneos de cuenta) |
| `targetName` | string \| null | Nombre para mostrar (baneos de cuenta) |
| `ip` | string \| null | IP baneada. En baneos de cuenta = su última IP conocida (puede ser null si nunca entró) |
| `reason` | string | Razón (se muestra al jugador y en el registro) |
| `issuer` | string | Quién baneó: `panel web`, nombre del jugador, o `consola` |
| `createdAt` | long (epoch ms) | Fecha de creación |
| `expiresAt` | long \| null | `null` = permanente |
| `active` | boolean | `true` mientras esté vigente |
| `unbannedAt` | long \| null | Fecha en que se levantó |
| `unbannedBy` | string \| null | Quién lo levantó |
| `endReason` | `"unban"` \| `"expired"` \| null | Cómo terminó |

### Colección `player_ips` (un documento por jugador)
| Campo | Tipo | Notas |
|---|---|---|
| `uuid` | string | Clave |
| `name` | string | Último nombre conocido |
| `lastIp` | string | Última IP usada |
| `ips` | array | `[{ ip, firstSeen, lastSeen, count }]` |

Alimenta el auto-ban de IP y la vista de alts. Al ser compartida, funciona **entre servidores**: banear desde cualquier server puede consultar la última IP del jugador.

### Colección `ban_log` (append-only — el "registro")
| Campo | Tipo | Notas |
|---|---|---|
| `ts` | long | Momento del evento |
| `action` | `BAN` \| `UNBAN` \| `EXPIRE` \| `BLOCKED_JOIN` | Tipo de evento |
| `type` | `"account"` \| `"ip"` | |
| `target` | string | UUID o IP |
| `targetName` | string \| null | |
| `ip` | string \| null | |
| `reason` | string \| null | |
| `issuer` | string \| null | |

El panel muestra los últimos N y permite paginar / filtrar. (Opcionalmente `ban_log` puede ser *capped* para acotar tamaño.)

## Captura de IP + bloqueo

**En `PlayerNegotiationEvent`** (ocurre durante el login, antes de que el jugador entre):

1. Registrar la IP del que conecta en `player_ips` (upsert de `lastIp` + añadir a `ips`).
2. Comprobar si está baneado y **vigente**:
   - Baneo de cuenta activo con `targetUuid == uuid`, **o**
   - Cualquier baneo activo cuyo `ip == ipQueConecta` (baneo de IP directo o la IP auto-baneada de una cuenta).
3. Si está baneado → **rechazar la conexión** con un mensaje (razón + cuándo expira) y registrar `BLOCKED_JOIN`.
4. Banear a un jugador **conectado** → expulsarlo al instante (`connection.disconnect`).

**Rendimiento:** caché en memoria de baneos activos (set de UUIDs baneados + set de IPs baneadas), cargada al iniciar y refrescada en cada ban/unban/expiración y en cada evento de sync. La expiración se comprueba de forma perezosa al consultar (un baneo pasado su `expiresAt` se trata como inactivo) + un barrido periódico que los marca `expired` y registra `EXPIRE`.

**Riesgo conocido:** el punto exacto de la API de NeoForge para *rechazar* durante la negociación se confirma al implementar. Si `PlayerNegotiationEvent` no permite rechazar limpio, el fallback es **expulsar en `PlayerLoggedInEvent`** inmediatamente después (mismo efecto para el jugador). Se decide en el plan/implementación con una prueba real.

## Anti-alt (auto-ban de IP)

Al banear una cuenta:
1. Buscar su `lastIp` en `player_ips`.
2. Guardar esa IP en el campo `ip` del baneo de cuenta.
3. La comprobación de bloqueo usa ese `ip` → cualquier otra cuenta que conecte desde esa IP queda bloqueada.

Al quitar el baneo de la cuenta, se levanta también su IP (es el mismo documento). Si la cuenta nunca entró (sin IP conocida), se crea solo el baneo de cuenta.

## Panel web — sección "Baneos"

- **Baneos activos:** tabla (objetivo, tipo, razón, autor, fecha, expira / "permanente", botón *Quitar ban*).
- **Añadir ban:** formulario — por **jugador** (autocompletar con jugadores conocidos) o por **IP**; razón; duración (permanente / 1h / 1d / 7d / 30d / personalizada).
- **Registro:** historial filtrable de acciones (`BAN`/`UNBAN`/`EXPIRE`/`BLOCKED_JOIN`) con fecha, quién, qué y razón.
- **Alts por IP:** agrupa cuentas que comparten IP. Al ver un jugador se muestran sus IPs y qué otras cuentas las usaron. Al banear, aviso: *"esta IP la comparten también: X, Y"*.
- Sigue el diseño actual (bento + modo noche). Actualización **en vivo por SSE**.

**Endpoints nuevos** (auth por token, como el resto):
- `GET /api/bans` — baneos activos + registro (paginado).
- `POST /api/bans` — crear baneo (cuenta o IP).
- `DELETE /api/bans/{id}` — quitar baneo.
- `GET /api/alts?ip=…` (o incluido en el estado) — cuentas que comparten una IP.

## Comandos in-game (complemento, por si la web falla)

- `/nn ban <jugador> [duración] [razón…]` — banea cuenta + su última IP.
- `/nn ban-ip <ip> [duración] [razón…]` — banea IP directa.
- `/nn unban <jugador|ip>` — quita el baneo.
- `/nn bans` — lista baneos activos.
- `/nn alts <jugador|ip>` — cuentas que comparten IP.

`duración`: `perm` (o vacío) = permanente; `1h`, `1d`, `7d`, `30d`, `1w`, etc.

## Sincronización

Reusa el sync push existente: `publishEvent("ban", id)` tras cada ban/unban → los demás servidores refrescan su caché de baneos y aplican el bloqueo. `player_ips` es compartida, así que la IP del jugador está disponible desde cualquier servidor.

## Seguridad / privacidad

- Se almacenan las IPs de los jugadores (necesario para el sistema). Es estándar en sistemas de baneo; queda dentro de la base de datos del propio servidor.
- El jugador baneado ve la **razón** y la **expiración** en la pantalla de desconexión.

## Testing

Tests unitarios de funciones puras (estilo `TabTeamNamingTest`):
- Parseo de duración (`1h`/`1d`/`7d`/`perm` → `expiresAt`).
- Vigencia de un baneo dado `expiresAt` y el "ahora".
- Lógica de match de bloqueo (uuid o ip contra el conjunto de baneos activos).

## Fuera de alcance (YAGNI)

- Integración con el ban de vanilla (`banned-*.json`).
- Baneos por rango de IP / subred (por ahora IP exacta).
- Geolocalización o detección avanzada de VPN.
