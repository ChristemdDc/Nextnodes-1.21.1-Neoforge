# NextNodes Permissions

Mod de permisos **server-side** para NeoForge 1.21.1. Gestiona rangos, jugadores, permisos de comandos y permisos de nodos al estilo LuckPerms, con herencia por peso, reglas por contexto y un panel web en tiempo real.

---

## ¿Qué hace?

- **Rangos** con peso, herencia, prefijos de color/gradiente y reglas de permisos propias.
- **Jugadores** con rangos asignados, rango primario y permisos directos.
- **Permisos de comandos**: permite o bloquea comandos del servidor por jugador o rango. Los comandos bloqueados también desaparecen del autocompletado (Tab).
- **Permisos de nodos**: nodos de permisos para mods que usen la API de NeoForge (`IPermissionHandler`). Soporta nodos literales, comodines (`*`) y expresiones regulares (`regex:`).
- **Prefijos en el chat y en la lista de jugadores**: el prefijo del rango con mayor peso se muestra en el chat, en el nombre del jugador y en el Tab-list.
- **Panel web**: interfaz gráfica en el navegador para gestionar todo sin escribir comandos.
- **Persistencia en SQLite**: todos los datos se guardan en `config/nextnodes-permissions.sqlite`.

---

## Instalación

1. Coloca el archivo `nextnodes_permissions-1.0.0-all.jar` en la carpeta `mods/` del servidor.
2. Arranca el servidor. El mod crea automáticamente la base de datos en:
   ```
   config/nextnodes-permissions.sqlite
   ```
3. El panel web **no se inicia automáticamente**. Ábrelo desde el chat del servidor (ver sección de comandos).

### Compilar desde el código fuente

```powershell
.\gradlew.bat build
```

El JAR distribuible queda en:
```
build/libs/nextnodes_permissions-1.0.0-all.jar
```

Para compilar y copiar directamente a la carpeta de mods del servidor:
```powershell
.\gradlew.bat build copyToServerMods
```

### Cambiar el puerto del panel web

Inicia el servidor con la propiedad de sistema:
```
-Dnextnodes.web.port=8765
```
El puerto por defecto es **8765**.

---

## Comandos en el servidor

Todos los comandos requieren nivel de operador 4 (`/op`).

### `/nextnodes` — Administración general

| Comando | Descripción |
|---------|-------------|
| `/nextnodes reload` | Recarga todos los datos desde SQLite sin reiniciar el servidor. |
| `/nextnodes check <jugador> <permiso>` | Consulta si un jugador tiene un nodo de permiso concreto y muestra el resultado (`true` / `false` / `undefined`). |

### `/nn` — Comandos rápidos

#### Panel web

| Comando | Descripción |
|---------|-------------|
| `/nn open web` | Inicia el panel web y muestra la URL y la contraseña de acceso (sesión de 15 minutos). Si ya estaba activo, muestra la URL directamente. |
| `/nn close web` | Detiene el panel web inmediatamente. |

La contraseña se genera de forma **aleatoria** en cada sesión. Al hacer clic en ella en el chat se copia al portapapeles.

#### Gestión de rangos desde el servidor

| Comando | Descripción |
|---------|-------------|
| `/nn rank list` | Lista todos los rangos definidos con su peso y herencia. |
| `/nn rank info <jugador>` | Muestra el rango principal y todos los rangos asignados a un jugador. |
| `/nn rank add <jugador> <rango>` | Añade un rango a la lista de rangos del jugador. Si no tenía rango principal, lo establece automáticamente. |
| `/nn rank remove <jugador> <rango>` | Quita un rango de la lista del jugador. Si era el rango principal, asigna automáticamente el siguiente disponible o el rango por defecto. |
| `/nn rank set <jugador> <rango>` | Establece el rango **principal** del jugador. Si el jugador no tenía ese rango en su lista, lo añade también. |

> **Jugadores offline:** Los comandos de rango aceptan nombre de usuario o UUID. Para jugadores que nunca han conectado, no es posible asignar rangos por nombre; usa su UUID directamente.

> **Autocompletado (Tab):** El argumento `<jugador>` sugiere jugadores online y todos los almacenados en la base de datos. El argumento `<rango>` sugiere los rangos existentes.

---

## Panel web

Accede desde el navegador a `http://localhost:8765/` (o el IP del servidor si estás en una red local). La contraseña se obtiene ejecutando `/nn open web` en el servidor.

### Funcionalidades del panel

- **Resumen**: estadísticas generales y accesos rápidos.
- **Jugadores**: lista de jugadores registrados con su estado (online/offline), rangos y avatar. Permite editar, añadir jugadores offline y eliminar jugadores.
- **Rangos**: tarjetas con prefijo, peso, herencia y reglas. Permite crear, editar y eliminar rangos.
- **Editor de rango**: configura identidad (ID, nombre visible, peso, herencia), prefijo con códigos de color Minecraft y generador de gradientes, permisos de comandos por rango, y reglas de permisos personalizadas.
- **Editor de jugador**: gestiona rangos asignados, permisos de comandos directos y reglas de permisos directas.
- **Comandos**: vista global de todos los comandos detectados del servidor, filtrable por mod y rango.

### Sesión

La sesión dura **15 minutos** desde el inicio o desde la última extensión. Dos minutos antes de expirar, aparece un aviso con opción de extender. Al expirar, la sesión se cierra automáticamente.

---

## Sistema de permisos

### Reglas de permiso

Cada regla tiene:
- **Nodo**: el identificador del permiso (por ejemplo `create.train.build`).
- **Valor**: `true` (permitir) o `false` (bloquear).
- **Modo**: cómo se evalúa el nodo.
- **Contextos** *(opcional)*: pares clave-valor para limitar la regla a un contexto específico (por ejemplo `{"world": "overworld"}`).
- **Expiración** *(opcional)*: timestamp Unix en ms. Las reglas expiradas se ignoran automáticamente.

#### Modos de evaluación

| Modo | Ejemplo | Descripción |
|------|---------|-------------|
| `literal` | `create.train.build` | Coincidencia exacta del nodo. |
| `wildcard` | `create.*` | Cualquier nodo que empiece por `create.`. También acepta `*` para todo. |
| `regex` | `regex:^create\\.train\\..+$` | Expresión regular Java completa. El prefijo `regex:` es obligatorio. |

### Orden de resolución

Para resolver si un jugador tiene un permiso:

1. **Permisos directos del jugador** — se revisan primero.
2. **Rangos del jugador**, ordenados por peso de mayor a menor, incluyendo el rango primario.
3. **Rangos padre** de cada rango, resueltos recursivamente por peso.
4. **Resolver por defecto de NeoForge** — si ninguna regla define el nodo, se delega al comportamiento nativo del juego.

La **primera regla que coincida** gana. Si ninguna regla coincide, el resultado es `undefined` (el mod no interfiere y NeoForge decide).

### Permisos de comandos

Los comandos se registran como nodos con el prefijo `command.`:

```
command.give           → controla /give
command.gamemode       → controla /gamemode
command.tp.jugador     → controla la subforma /tp <jugador>
minecraft.command.say  → nodo vanilla alternativo para /say
```

Un comando **bloqueado** (`false`) también se oculta del autocompletado del jugador.  
Un comando **permitido** (`true`) se ejecuta con nivel de op 4, aunque el jugador no sea operador.

---

## Rangos

### Estructura de un rango

| Campo | Descripción |
|-------|-------------|
| `name` | Identificador único (minúsculas, sin espacios). |
| `displayName` | Nombre visible en el panel y en el autocompletado. |
| `prefix` | Prefijo de chat con códigos de color Minecraft (`&a`, `&l`, `&#RRGGBB`). |
| `weight` | Número entero. Mayor peso = mayor prioridad en la resolución. |
| `parents` | Lista de nombres de rangos de los que este rango hereda permisos. |
| `permissions` | Lista de reglas de permiso propias del rango. |
| `meta` | Mapa de metadatos arbitrarios (clave-valor de texto). |

### Herencia

Un rango puede heredar de múltiples rangos padre. Los permisos se resuelven siguiendo el orden de peso. La herencia es **recursiva**: si A hereda de B y B hereda de C, A también hereda de C.

### Prefijos con gradiente

El editor web incluye un generador de gradiente que crea automáticamente un prefijo con transición de color entre dos o tres colores usando los códigos de color hexadecimales de Minecraft (`&#RRGGBB`).

---

## Jugadores

### Estructura de un jugador

| Campo | Descripción |
|-------|-------------|
| `uuid` | UUID del jugador (clave primaria). |
| `name` | Nombre de usuario (se actualiza automáticamente al conectar). |
| `primaryRank` | Rango principal (determina el prefijo visible). |
| `ranks` | Lista de todos los rangos asignados. |
| `permissions` | Permisos directos del jugador (tienen prioridad sobre los del rango). |
| `meta` | Metadatos arbitrarios. |
| `lastSeen` | Timestamp de la última conexión. |
| `online` | Estado de conexión actual (actualizado en tiempo real). |

Al conectarse por primera vez, el jugador se registra automáticamente con el **rango por defecto** (`default`).

---

## Configuración avanzada

### Puerto del panel web

```
-Dnextnodes.web.port=PUERTO
```

### Base de datos

El archivo SQLite se crea en:
```
config/nextnodes-permissions.sqlite
```

Se puede hacer backup copiando ese archivo mientras el servidor está apagado o el panel web detenido.

---

## Compatibilidad

- **NeoForge**: 21.1.228 (Minecraft 1.21.1)
- **Lado**: solo servidor (`server-only`). Se puede instalar en un cliente pero los servicios solo se activan en el lado servidor.
- **Mods compatibles**: cualquier mod que use la API de permisos de NeoForge (`IPermissionHandler`) recibe los resultados del resolver de NextNodes automáticamente.
