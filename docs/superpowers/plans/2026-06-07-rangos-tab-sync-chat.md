# Plan de implementación: rangos en TAB/nombre/chat + sync entre servidores

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ordenar el TAB por rango, mostrar prefijo+nombre+sufijo+tag sobre la cabeza/TAB/chat, y sincronizar cambios de rango entre servidores por push.

**Architecture:** Se añaden `Rank.suffix` y `UserEntry.tag` al modelo. Un `TabListManager` crea un scoreboard team por jugador (nombre del team = peso+nombre para ordenar; prefix/suffix del team = prefijo/sufijo/tag sobre la cabeza). El chat se reescribe cancelando el evento y reenviando el nombre compuesto. La sincronización usa una colección *capped* `sync_events` y un *cursor tailable* (push, funciona en MongoDB standalone).

**Tech Stack:** Java 21, NeoForge 1.21.1, MongoDB (driver sync 5.1.1), Gradle (NeoGradle userdev), JUnit 5 (sólo para lógica pura), JS vanilla (panel web).

**Spec:** [docs/superpowers/specs/2026-06-07-rangos-tab-sync-chat-design.md](../specs/2026-06-07-rangos-tab-sync-chat-design.md)

**Convención de verificación:** la mayoría es código de integración con Minecraft que se verifica **compilando** (`./gradlew compileJava --console=plain`) y **en juego** (checklist en la Tarea 10). Sólo la Tarea 5 (orden del TAB) usa una prueba unitaria real porque es lógica pura.

---

### Task 1: Modelo — añadir `Rank.suffix` y `UserEntry.tag`

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionModels.java`

- [ ] **Step 1: Añadir el campo `suffix` al `Rank`**

En la clase `Rank`, justo debajo de `public String prefix = "";` (línea ~52), añadir:

```java
        public String suffix = "";
```

- [ ] **Step 2: Sanear `suffix` en `Rank.sanitize()`**

Dentro de `Rank.sanitize()`, justo debajo del bloque que hace `if (this.prefix == null) { this.prefix = ""; }`, añadir:

```java
            if (this.suffix == null) {
                this.suffix = "";
            }
```

- [ ] **Step 3: Añadir el campo `tag` al `UserEntry`**

En la clase `UserEntry`, justo debajo de `public String name = "";` (línea ~82), añadir:

```java
        public String tag = "";
```

- [ ] **Step 4: Sanear `tag` en `UserEntry.sanitize()`**

Dentro de `UserEntry.sanitize()`, justo debajo del bloque `if (this.name == null) { this.name = ""; }`, añadir:

```java
            if (this.tag == null) {
                this.tag = "";
            }
```

- [ ] **Step 5: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionModels.java
git commit -m "feat: add Rank.suffix and UserEntry.tag fields"
```

---

### Task 2: Persistencia — serializar `suffix` y `tag` en MongoDB

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionStore.java`

- [ ] **Step 1: Serializar `suffix` en `rankToDoc`**

En `rankToDoc(Rank rank)`, añadir `.append("suffix", rank.suffix)` justo después de `.append("prefix", rank.prefix)`:

```java
        return new Document("_id", rank.name)
                .append("displayName", rank.displayName)
                .append("prefix",      rank.prefix)
                .append("suffix",      rank.suffix)
                .append("weight",      rank.weight)
                .append("parents",     rank.parents)
                .append("permissions", perms)
                .append("meta",        new Document(rank.meta));
```

- [ ] **Step 2: Deserializar `suffix` en `docToRank`**

En `docToRank(Document doc)`, añadir después de `rank.prefix = doc.getString("prefix");`:

```java
        rank.suffix      = doc.getString("suffix");
```

- [ ] **Step 3: Serializar `tag` en `userToDoc`**

En `userToDoc(UserEntry user)`, añadir `.append("tag", user.tag)` después de `.append("name", user.name)`:

```java
        return new Document("_id", user.uuid)
                .append("name",        user.name)
                .append("tag",         user.tag)
                .append("primaryRank", user.primaryRank)
                .append("ranks",       user.ranks)
                .append("permissions", perms)
                .append("meta",        new Document(user.meta))
                .append("lastSeen",    user.lastSeen)
                .append("online",      user.online);
```

- [ ] **Step 4: Deserializar `tag` en `docToUser`**

En `docToUser(Document doc)`, añadir después de `user.name = doc.getString("name");`:

```java
        user.tag         = doc.getString("tag");
```

(Documentos antiguos sin estos campos devuelven `null`; `ensureDefaults()`/`sanitize()` los normaliza a `""`. No hay migración.)

- [ ] **Step 5: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionStore.java
git commit -m "feat: persist rank suffix and user tag in MongoDB"
```

---

### Task 3: Resolver — `resolveSuffix`, `resolveTag`, `resolveWeight`

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionResolver.java`

- [ ] **Step 1: Añadir los tres métodos**

Justo después del método `resolvePrefix(UUID uuid)` (termina en la línea ~77), añadir:

```java
    public String resolveSuffix(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? "" : defaultRank.suffix;
        }
        for (Rank rank : rankOrder(data, user)) {
            if (rank.suffix != null && !rank.suffix.isBlank()) {
                return rank.suffix;
            }
        }
        return "";
    }

    public String resolveTag(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        return user == null || user.tag == null ? "" : user.tag;
    }

    public int resolveWeight(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? 0 : defaultRank.weight;
        }
        List<Rank> order = rankOrder(data, user);
        return order.isEmpty() ? 0 : order.get(0).weight;
    }
```

(`List` ya está importado en este archivo.)

- [ ] **Step 2: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionResolver.java
git commit -m "feat: resolve suffix, tag and display weight"
```

---

### Task 4: Nombre compuesto — `composeName` + usar en NameFormat y TabListName

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PrefixFormatter.java`
- Modify: `src/main/java/com/nextnodes/permissions/NextNodesPermissions.java`

- [ ] **Step 1: Añadir `composeName` a `PrefixFormatter`**

En `PrefixFormatter`, justo después del método `prefixedName(...)` (línea ~21), añadir:

```java
    public static Component composeName(String prefix, String name, String suffix, String tag) {
        MutableComponent result = Component.empty();
        Component prefixComponent = format(prefix);
        if (!prefixComponent.getString().isBlank()) {
            result.append(prefixComponent);
        }
        result.append(Component.literal(name).withStyle(ChatFormatting.RESET));
        Component suffixComponent = format(suffix);
        if (!suffixComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(suffixComponent);
        }
        Component tagComponent = format(tag);
        if (!tagComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(tagComponent);
        }
        return result;
    }
```

- [ ] **Step 2: Añadir el helper `composeFullName` en `NextNodesPermissions`**

En `NextNodesPermissions`, añadir este método privado (p. ej. justo encima de `onNameFormat`, línea ~251):

```java
    private Component composeFullName(UUID uuid, String baseName) {
        return PrefixFormatter.composeName(
                this.resolver.resolvePrefix(uuid),
                baseName,
                this.resolver.resolveSuffix(uuid),
                this.resolver.resolveTag(uuid));
    }
```

- [ ] **Step 3: Reescribir `onNameFormat`**

Reemplazar el cuerpo del método `onNameFormat` por:

```java
    @net.neoforged.bus.api.SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            event.setDisplayname(composeFullName(player.getUUID(), player.getGameProfile().getName()));
        }
    }
```

- [ ] **Step 4: Reescribir `onTabListName`**

Reemplazar el método `onTabListName` completo (líneas ~258-273) por:

```java
    @net.neoforged.bus.api.SubscribeEvent
    public void onTabListName(PlayerEvent.TabListNameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            event.setDisplayName(composeFullName(player.getUUID(), player.getGameProfile().getName()));
        }
    }
```

- [ ] **Step 5: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PrefixFormatter.java src/main/java/com/nextnodes/permissions/NextNodesPermissions.java
git commit -m "feat: show prefix+name+suffix+tag in display name and tab text"
```

---

### Task 5: Orden del TAB (lógica pura `TabTeamNaming`) — con prueba unitaria

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/nextnodes/permissions/TabTeamNaming.java`
- Test: `src/test/java/com/nextnodes/permissions/TabTeamNamingTest.java`

- [ ] **Step 1: Añadir dependencias JUnit 5 a `build.gradle`**

Dentro del bloque `dependencies { ... }` (línea ~99), añadir al final (antes del `}`):

```gradle
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

Y al final del archivo `build.gradle`, añadir:

```gradle
test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Escribir el test que falla**

Crear `src/test/java/com/nextnodes/permissions/TabTeamNamingTest.java`:

```java
package com.nextnodes.permissions;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TabTeamNamingTest {
    @Test
    void higherWeightSortsFirst() {
        String admin = TabTeamNaming.teamName(100, "alice", "11111111-1111-1111-1111-111111111111");
        String member = TabTeamNaming.teamName(0, "alice", "11111111-1111-1111-1111-111111111111");
        assertTrue(admin.compareTo(member) < 0, "higher weight must sort before lower weight");
    }

    @Test
    void sameWeightSortsAlphabeticallyByName() {
        String alice = TabTeamNaming.teamName(50, "alice", "aaaaaaaa-0000-0000-0000-000000000000");
        String bob = TabTeamNaming.teamName(50, "bob", "bbbbbbbb-0000-0000-0000-000000000000");
        assertTrue(alice.compareTo(bob) < 0, "same weight must sort alphabetically by name");
    }

    @Test
    void zeroWeightSortsBeforeNegativeWeight() {
        String zero = TabTeamNaming.teamName(0, "alice", "11111111-1111-1111-1111-111111111111");
        String negative = TabTeamNaming.teamName(-100, "alice", "11111111-1111-1111-1111-111111111111");
        assertTrue(zero.compareTo(negative) < 0, "weight 0 must sort before negative weight");
    }

    @Test
    void differentPlayersGetUniqueTeamNames() {
        String a = TabTeamNaming.teamName(50, "alice", "aaaaaaaa-0000-0000-0000-000000000000");
        String b = TabTeamNaming.teamName(50, "alice", "bbbbbbbb-0000-0000-0000-000000000000");
        assertNotEquals(a, b, "distinct UUIDs must yield distinct team names");
    }

    @Test
    void nameIsSanitizedAndLowercased() {
        String name = TabTeamNaming.teamName(0, "Bad Name!", "11111111-1111-1111-1111-111111111111");
        assertTrue(name.startsWith("nn_"), "team name must start with nn_");
        assertTrue(name.contains("badname"), "name must be lowercased and stripped of invalid chars");
    }
}
```

- [ ] **Step 3: Ejecutar el test para verificar que falla**

Run: `./gradlew test --tests "com.nextnodes.permissions.TabTeamNamingTest" --console=plain`
Expected: FAIL — compilación falla porque `TabTeamNaming` no existe (`cannot find symbol`).

- [ ] **Step 4: Crear `TabTeamNaming` (implementación mínima)**

Crear `src/main/java/com/nextnodes/permissions/TabTeamNaming.java`:

```java
package com.nextnodes.permissions;

import java.util.Locale;

/**
 * Pure helper that builds the internal scoreboard-team name used to order the TAB list.
 * Vanilla sorts the TAB by (gamemode, team-name, profile-name); by encoding the rank weight
 * (descending) and then the player name into the team name, players sort by rank and then
 * alphabetically. Free of Minecraft types so it can be unit-tested in isolation.
 */
public final class TabTeamNaming {
    public static final String TEAM_PREFIX = "nn_";

    private TabTeamNaming() {
    }

    public static String teamName(int weight, String playerName, String uuid) {
        long key = (long) Integer.MAX_VALUE - (long) weight; // higher weight -> smaller key -> sorts first
        String weightKey = String.format(Locale.ROOT, "%010d", key);
        return TEAM_PREFIX + weightKey + "_" + sanitizeName(playerName) + "_" + sanitizeUuid(uuid);
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        return lower.length() > 16 ? lower.substring(0, 16) : lower;
    }

    private static String sanitizeUuid(String uuid) {
        if (uuid == null) {
            return "00000000";
        }
        String compact = uuid.replace("-", "");
        return compact.length() > 8 ? compact.substring(0, 8) : compact;
    }
}
```

- [ ] **Step 5: Ejecutar el test para verificar que pasa**

Run: `./gradlew test --tests "com.nextnodes.permissions.TabTeamNamingTest" --console=plain`
Expected: PASS (5 tests).

> Nota: si NeoGradle no logra configurar la tarea `test` (problemas de classpath con Minecraft), no bloquees la feature: `TabTeamNaming` es lógica pura y correcta por construcción; deja el test escrito y continúa. Resuelve el wiring de gradle aparte.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/java/com/nextnodes/permissions/TabTeamNaming.java src/test/java/com/nextnodes/permissions/TabTeamNamingTest.java
git commit -m "feat: TAB team-name ordering by weight then name (unit tested)"
```

---

### Task 6: `TabListManager` — teams por jugador (orden TAB + nombre sobre la cabeza)

**Files:**
- Create: `src/main/java/com/nextnodes/permissions/integration/TabListManager.java`
- Modify: `src/main/java/com/nextnodes/permissions/NextNodesPermissions.java`

- [ ] **Step 1: Crear `TabListManager`**

Crear `src/main/java/com/nextnodes/permissions/integration/TabListManager.java`:

```java
package com.nextnodes.permissions.integration;

import com.nextnodes.permissions.PermissionResolver;
import com.nextnodes.permissions.PrefixFormatter;
import com.nextnodes.permissions.TabTeamNaming;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Manages one scoreboard team per online player so Minecraft (a) orders the TAB list by rank
 * weight and (b) renders prefix/suffix/tag on the floating name above the player's head. The
 * vanilla client sorts the TAB by team name and draws the over-head name from the team, so teams
 * are the only reliable mechanism for both.
 */
public final class TabListManager {
    private final PermissionResolver resolver;

    public TabListManager(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    /** Creates/updates the player's team and moves them into it. Call on join and on rank/tag change. */
    public void apply(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        UUID uuid = player.getUUID();
        String desiredName = TabTeamNaming.teamName(
                this.resolver.resolveWeight(uuid),
                player.getGameProfile().getName(),
                player.getStringUUID());

        PlayerTeam previous = scoreboard.getPlayersTeam(player.getScoreboardName());

        PlayerTeam team = scoreboard.getPlayerTeam(desiredName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(desiredName);
        }
        team.setPlayerPrefix(PrefixFormatter.format(this.resolver.resolvePrefix(uuid)));
        team.setPlayerSuffix(buildSuffix(this.resolver.resolveSuffix(uuid), this.resolver.resolveTag(uuid)));

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);

        if (previous != null
                && previous.getName().startsWith(TabTeamNaming.TEAM_PREFIX)
                && !previous.getName().equals(desiredName)
                && previous.getPlayers().isEmpty()) {
            scoreboard.removePlayerTeam(previous);
        }
    }

    /** Removes the player from their managed team and deletes it if empty. Call on logout. */
    public void remove(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam current = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (current != null && current.getName().startsWith(TabTeamNaming.TEAM_PREFIX)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), current);
            if (current.getPlayers().isEmpty()) {
                scoreboard.removePlayerTeam(current);
            }
        }
    }

    /** Removes every managed team. Call on server stop. */
    public void cleanupAll(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            if (team.getName().startsWith(TabTeamNaming.TEAM_PREFIX)) {
                scoreboard.removePlayerTeam(team);
            }
        }
    }

    private static Component buildSuffix(String suffix, String tag) {
        MutableComponent result = Component.empty();
        Component suffixComponent = PrefixFormatter.format(suffix);
        if (!suffixComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(suffixComponent);
        }
        Component tagComponent = PrefixFormatter.format(tag);
        if (!tagComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(tagComponent);
        }
        return result;
    }
}
```

- [ ] **Step 2: Añadir el import y el campo en `NextNodesPermissions`**

Añadir el import junto a los demás de `com.nextnodes.permissions.integration`:

```java
import com.nextnodes.permissions.integration.TabListManager;
```

Añadir el campo junto a los demás (p. ej. después de `private final RankHistoryLog rankHistoryLog;`):

```java
    private TabListManager tabListManager;
```

- [ ] **Step 3: Instanciar `tabListManager` en el constructor (rama servidor)**

En el constructor, en la rama de servidor (después de `this.resolver = new PermissionResolver(this.store);`), añadir:

```java
        this.tabListManager = new TabListManager(this.resolver);
```

- [ ] **Step 4: Aplicar el team en `refreshPlayerName`**

Reemplazar el método `refreshPlayerName` por:

```java
    private void refreshPlayerName(ServerPlayer player) {
        player.refreshDisplayName();
        player.refreshTabListName();
        if (this.tabListManager != null) {
            this.tabListManager.apply(player);
        }
    }
```

- [ ] **Step 5: Quitar el team al salir, en `onPlayerLoggedOut`**

Al inicio del cuerpo de `onPlayerLoggedOut`, antes de `UUID uuid = event.getEntity().getUUID();`, añadir:

```java
        if (event.getEntity() instanceof ServerPlayer player && this.tabListManager != null) {
            this.tabListManager.remove(player);
        }
```

- [ ] **Step 6: Limpiar teams al apagar, en `onServerStopping`**

En `onServerStopping`, dentro del `if (this.server != null) { ... }` que limpia el ping (justo después de obtener `Scoreboard sb = this.server.getScoreboard();` y antes/después de quitar el objetivo de ping), añadir la limpieza de teams:

```java
            if (this.tabListManager != null) {
                this.tabListManager.cleanupAll(this.server);
            }
```

- [ ] **Step 7: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/integration/TabListManager.java src/main/java/com/nextnodes/permissions/NextNodesPermissions.java
git commit -m "feat: per-player scoreboard teams for TAB order and over-head name"
```

---

### Task 7: Chat — mostrar nombre compuesto sin `< >`

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/NextNodesPermissions.java`

- [ ] **Step 1: Reescribir `onServerChat`**

Reemplazar el método `onServerChat` completo (líneas ~323-326) por:

```java
    @net.neoforged.bus.api.SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer currentServer = player.getServer();
        if (currentServer == null) {
            return;
        }
        Component line = Component.empty()
                .append(composeFullName(player.getUUID(), player.getGameProfile().getName()))
                .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                .append(event.getMessage());
        event.setCanceled(true);
        currentServer.getPlayerList().broadcastSystemMessage(line, false);
        LOGGER.info("[CHAT] {}: {}", player.getGameProfile().getName(), event.getMessage().getString());
    }
```

(Imports ya presentes: `ServerChatEvent`, `Component`, `ChatFormatting`, `MinecraftServer`, `ServerPlayer`, `LOGGER`.)

- [ ] **Step 2: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/NextNodesPermissions.java
git commit -m "feat: render rank prefix/suffix/tag in chat"
```

---

### Task 8: Sincronización push — colección capped + cursor tailable

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionStore.java`

- [ ] **Step 1: Añadir imports**

Junto a los imports de `com.mongodb.*`, añadir:

```java
import com.mongodb.CursorType;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CreateCollectionOptions;
```

- [ ] **Step 2: Añadir constante y campos de estado**

Junto a las constantes `COL_*` añadir:

```java
    private static final String COL_SYNC = "sync_events";
```

Junto a los campos de instancia (después de `private volatile PermissionData cachedSnapshot;`), añadir:

```java
    private final String serverId = UUID.randomUUID().toString();
    private volatile boolean syncClosed = false;
    private volatile long lastEventTs = 0L;
    private Thread syncThread;
```

- [ ] **Step 3: Crear la colección capped en `load()` y arrancar el listener**

En `load()`, añadir `ensureSyncCollection();` justo después de `writeDefaultsIfMissing();` (dentro del try, aún con el lock). Y añadir `startSyncListener();` justo después del bloque `finally` (fuera del lock). El método queda así:

```java
    public void load() throws IOException {
        this.lock.writeLock().lock();
        try {
            closeClient();
            this.mongoClient = MongoClients.create(this.connectionUri);
            this.database    = this.mongoClient.getDatabase(this.databaseName);
            this.data        = readAll();
            this.data.ensureDefaults();
            writeDefaultsIfMissing();
            ensureSyncCollection();
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            throw new IOException("Unable to load MongoDB permission store at " + this.connectionUri, ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        startSyncListener();
    }
```

- [ ] **Step 4: Detener el listener en `close()`**

Reemplazar el método `close()` por:

```java
    public void close() {
        this.syncClosed = true;
        if (this.syncThread != null) {
            this.syncThread.interrupt();
            this.syncThread = null;
        }
        this.lock.writeLock().lock();
        try {
            closeClient();
        } finally {
            this.lock.writeLock().unlock();
        }
    }
```

- [ ] **Step 5: Añadir los métodos de sync**

Añadir estos métodos privados (p. ej. justo antes de `private MongoCollection<Document> col(String name)`):

```java
    private void ensureSyncCollection() {
        try {
            boolean exists = false;
            for (String name : this.database.listCollectionNames()) {
                if (name.equals(COL_SYNC)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                this.database.createCollection(COL_SYNC,
                        new CreateCollectionOptions().capped(true).sizeInBytes(1_048_576L).maxDocuments(10_000L));
            }
            // Seed one document so the tailable cursor always has a valid anchor point.
            col(COL_SYNC).insertOne(new Document("origin", this.serverId)
                    .append("seed", true)
                    .append("ts", System.currentTimeMillis()));
        } catch (Exception ignored) {
            // Another server may have created it concurrently, or it already exists; safe to ignore.
        }
    }

    private void publishEvent(String type, String key) {
        try {
            if (this.database == null) {
                return;
            }
            col(COL_SYNC).insertOne(new Document("origin", this.serverId)
                    .append("type", type)
                    .append("key", key)
                    .append("ts", System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
    }

    private void startSyncListener() {
        if (this.syncThread != null) {
            return;
        }
        this.lastEventTs = System.currentTimeMillis();
        this.syncThread = new Thread(this::runSyncListener, "nextnodes-sync");
        this.syncThread.setDaemon(true);
        this.syncThread.start();
    }

    private void runSyncListener() {
        while (!this.syncClosed) {
            try (MongoCursor<Document> cursor = col(COL_SYNC)
                    .find(Filters.gt("ts", this.lastEventTs))
                    .cursorType(CursorType.TailableAwait)
                    .noCursorTimeout(true)
                    .iterator()) {
                while (!this.syncClosed && cursor.hasNext()) {
                    Document event = cursor.next();
                    this.lastEventTs = Math.max(this.lastEventTs, longFrom(event.get("ts"), this.lastEventTs));
                    if (this.serverId.equals(event.getString("origin"))) {
                        continue; // ignore our own writes
                    }
                    if (Boolean.TRUE.equals(event.getBoolean("seed"))) {
                        continue;
                    }
                    reloadFromExternalChange();
                }
            } catch (Exception ex) {
                if (this.syncClosed) {
                    return;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private void reloadFromExternalChange() {
        this.lock.writeLock().lock();
        try {
            if (this.database == null) {
                return;
            }
            PermissionData fresh = readAll();
            fresh.ensureDefaults();
            this.data = fresh;
            this.cachedSnapshot = null;
        } catch (Exception ignored) {
            return;
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }
```

- [ ] **Step 6: Publicar eventos tras cada mutación de configuración**

Añadir la llamada a `publishEvent(...)` inmediatamente después de cada `fireChanged();` en estos métodos:

- `saveRank`: `publishEvent("rank", rank.name);`
- `deleteRank`: `publishEvent("rank", normalized);`
- `saveUser`: `publishEvent("user", user.uuid);` (después de `fireUserSaved(user);`)
- `deleteUser`: `publishEvent("user", uuid);`
- `saveTabSettings`: `publishEvent("settings", "tab");` (después de `fireTabSettingsChanged();`)
- `importAll`: `publishEvent("import", "");`

(No se publica en `touchPlayer`/`setOnline`: son login/logout transitorios y generarían ruido innecesario entre servidores.)

- [ ] **Step 7: Compilar**

Run: `./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionStore.java
git commit -m "feat: push cross-server sync via capped collection + tailable cursor"
```

---

### Task 9: Panel web — campos Sufijo (rango) y Tag (jugador)

**Files:**
- Modify: `src/main/resources/web/app.js`

- [ ] **Step 1: Añadir el input de Sufijo en `rankEditor`**

En `rankEditor(r)`, dentro de la sección "Prefijo y gradiente", justo después del `<label>` del `rank_prefix` (el que tiene `id="rank_prefix"`), añadir:

```javascript
            <label>Sufijo (después del nombre)<input id="rank_suffix" value="${escapeAttr(r.suffix||'')}" oninput="syncSuffixPreview()"></label>
            <div class="prefixLive"><span class="prefixLabel">Sufijo</span><span class="prefixPreview" id="suffixPreview"></span></div>
```

- [ ] **Step 2: Añadir `syncSuffixPreview` y llamarla al abrir el modal**

Añadir esta función (p. ej. junto a `syncPrefixPreview`):

```javascript
    function syncSuffixPreview(){const el=document.getElementById('suffixPreview'); if(el)el.innerHTML=prefixHtml(document.getElementById('rank_suffix')?.value||'');}
```

En `openModal`, en la rama `if (isRank) { syncPrefixPreview(); renderModalCommands(); }`, añadir la llamada:

```javascript
      if (isRank) { syncPrefixPreview(); syncSuffixPreview(); renderModalCommands(); } else renderUserModalCommands();
```

- [ ] **Step 3: Guardar `suffix` en `readRankEditor`**

En `readRankEditor()`, en el objeto retornado, añadir `suffix` después de `prefix`:

```javascript
        prefix:document.getElementById('rank_prefix').value, suffix:document.getElementById('rank_suffix').value,
```

- [ ] **Step 4: Default de `suffix` en `newRank`**

En `newRank()`, añadir `suffix:''` al objeto:

```javascript
    function newRank(){openModal('rank',{name:'nuevo',displayName:'Nuevo',prefix:'&7[Nuevo] ',suffix:'',weight:0,parents:[],permissions:[],meta:{gradientStart:'#4ff0ff',gradientMiddle:'#9c6bff',gradientEnd:'#ff4f9b'}})}
```

- [ ] **Step 5: Añadir el input de Tag en `userEditor`**

En `userEditor(u)`, dentro de la sección "Rangos asignados" (`sectionBody`), después del `<datalist id="userRankOptions">...</datalist>`, añadir un nuevo campo dentro del mismo `sectionBody` (o una fila nueva):

```javascript
          <div class="editorFields cols-2"><label>Tag personal (después del sufijo)<input id="user_tag" value="${escapeAttr(u.tag||'')}" placeholder="[pepsi]"></label></div>
```

- [ ] **Step 6: Guardar `tag` en `readUserEditor`**

En `readUserEditor(original)`, añadir `tag` al objeto retornado (después de `name`):

```javascript
    function readUserEditor(original){return {uuid:document.getElementById('user_uuid').value, name:document.getElementById('user_name').value, tag:document.getElementById('user_tag').value, primaryRank:document.getElementById('user_primary').value, ranks:splitList(document.getElementById('user_ranks').value), permissions:[...userModalCommandRules(), ...readRules('user')], meta:readJsonBox('user_meta'), lastSeen:original.lastSeen||Date.now(), online:!!original.online};}
```

- [ ] **Step 7: Default de `tag` en `newUser`**

En `newUser()`, añadir `tag:''` al objeto:

```javascript
    function newUser(){const uuid=prompt('UUID del jugador offline'); if(!uuid)return; openModal('user',{uuid,name:'Offline',tag:'',primaryRank:state.defaultRank||'default',ranks:[state.defaultRank||'default'],permissions:[],meta:{},online:false,lastSeen:Date.now()});}
```

- [ ] **Step 8: Empaquetar y commit**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL (el jar incluye los assets web actualizados).

```bash
git add src/main/resources/web/app.js
git commit -m "feat: web panel inputs for rank suffix and player tag"
```

---

### Task 10: Build final + verificación en juego

**Files:** (ninguno — verificación)

- [ ] **Step 1: Build completo**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL. El jar queda en `build/libs/`.

- [ ] **Step 2: Configurar sufijo/tag de prueba**

Arrancar el servidor de dev (`./gradlew runServer`) o instalar el jar. En el panel web:
- Crear/editar un rango "admin" con peso 100, prefijo `&cSteampunk ` y sufijo `&7el Grande`.
- Crear/editar un rango "miembro" con peso 10 y prefijo `&a[Miembro] `.
- A tu jugador asignarle el rango admin y tag `&b[pepsi]`.

- [ ] **Step 3: Checklist en juego**

Verificar:
- [ ] **TAB ordenado:** admin (peso alto) aparece **arriba**; miembro debajo; jugadores sin rango al final. Dos jugadores del mismo rango salen en orden alfabético.
- [ ] **Nombre sobre la cabeza:** `Steampunk <nombre> el Grande [pepsi]`.
- [ ] **TAB (texto):** mismo nombre compuesto.
- [ ] **Chat:** `Steampunk <nombre> el Grande [pepsi]: mensaje` (sin `< >`).
- [ ] **Ping en TAB:** sigue mostrándose a la derecha (no se rompió).
- [ ] **Cambio en vivo:** cambiar el rango/tag desde el panel y ver que el TAB/cabeza/chat se actualizan sin reconectar.

- [ ] **Step 4: Verificar sync entre servidores (si hay 2 servidores con la misma BD)**

- [ ] En el servidor A (o el panel), dar un rango a un jugador conectado al servidor B.
- [ ] Confirmar que en el servidor B el jugador se actualiza (TAB/cabeza/chat) en pocos segundos, sin reiniciar.
- [ ] Revisar la consola del servidor B: sin errores del hilo `nextnodes-sync`.

- [ ] **Step 5: Verificar el panel web**

- [ ] Abrir el panel (`/nn open web`), editar un rango → aparece el campo **Sufijo** con vista previa.
- [ ] Editar un jugador → aparece el campo **Tag**.
- [ ] Guardar y confirmar que persiste (recargar el panel).

- [ ] **Step 6: Commit final (si hubo ajustes)**

```bash
git add -A
git commit -m "chore: finalize rank display + sync feature"
```

---

## Self-Review (cobertura del spec)

- **TAB orden** → Tareas 5 (lógica) + 6 (teams). ✓
- **Nombre sobre la cabeza** → Tarea 6 (team prefix/suffix). ✓
- **Sufijo (rango) + Tag (jugador)** → Tareas 1, 2, 3, 9. ✓
- **composeFullName en nombre/TAB/chat** → Tareas 4 + 7. ✓
- **Sync push (capped + tailable)** → Tarea 8. ✓
- **Panel web sufijo/tag** → Tarea 9. ✓
- **Sin placeholders**: todo el código está completo; las notas "verificar en juego" son pasos de prueba reales.
- **Consistencia de tipos**: `TabTeamNaming.teamName(int,String,String)`, `resolveWeight/resolveSuffix/resolveTag(UUID)`, `composeName(String,String,String,String)`, `composeFullName(UUID,String)`, `TabListManager.apply/remove/cleanupAll` — usados con las mismas firmas en todas las tareas. ✓

## Notas / riesgos

- **Longitud del nombre de team** (`nn_` + 10 + nombre≤16 + uuid8 ≈ ≤39 chars): si en juego un team no se crea, acortar `uuid8`/nombre en `TabTeamNaming`.
- **`./gradlew test` con NeoGradle**: si la tarea `test` no se configura por el classpath de Minecraft, no bloquear; `TabTeamNaming` es pura. Resolver el wiring de gradle aparte.
- **Colección `sync_events` preexistente no capped**: si alguien creó antes una colección con ese nombre sin `capped`, el cursor tailable fallará; borrarla en Mongo para que `ensureSyncCollection` la recree capped.
- **Chat por `broadcastSystemMessage`**: omite otros mods de chat (consecuencia aceptada de quitar `< >`).
