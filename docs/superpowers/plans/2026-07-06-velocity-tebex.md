# Plugin de Velocity para Tebex — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un plugin de Velocity que otorga/quita rangos escribiendo en la MongoDB del mod + un evento de sync, para que las compras de Tebex apliquen el rango en el servidor NeoForge (incluso offline).

**Architecture:** Proyecto Gradle standalone en `velocity-tebex/` (independiente del build NeoForge). Comandos `/nngrant` y `/nnrevoke` (que Tebex ejecuta como consola en Velocity) hacen un `updateOne` en la colección `users` (`$addToSet`/`$pull` de `ranks`) e insertan `{origin:"velocity-tebex",type:"user",key:uuid,ts}` en `sync_events`. El listener existente del mod recarga y aplica.

**Tech Stack:** Java 17 (bytecode) compilado con JDK 21, Velocity API 3.3.0-SNAPSHOT, MongoDB sync driver 5.1.1 (shaded), Gradle (reusando el wrapper del repo), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-07-06-velocity-tebex-design.md`

---

## Estructura de archivos (todo nuevo, bajo `velocity-tebex/`)

- `velocity-tebex/settings.gradle` — nombre del proyecto standalone.
- `velocity-tebex/build.gradle` — Velocity API + Mongo driver + shadow + JUnit.
- `src/main/java/com/nextnodes/tebex/NextNodesTebexPlugin.java` — clase `@Plugin`: en `ProxyInitializeEvent` carga config, conecta Mongo, registra comandos.
- `src/main/java/com/nextnodes/tebex/PluginConfig.java` — lee `mongoUri`/`database` de `config.properties`.
- `src/main/java/com/nextnodes/tebex/UuidUtil.java` — normaliza UUID a formato con guiones (puro).
- `src/main/java/com/nextnodes/tebex/RankNames.java` — normaliza nombre de rango (puro).
- `src/main/java/com/nextnodes/tebex/SyncEvents.java` — construye el `Document` del evento de sync (puro).
- `src/main/java/com/nextnodes/tebex/RankMongo.java` — conexión + `grant`/`revoke`/`ensureSyncCollection`/`publishSync`/`close`.
- `src/main/java/com/nextnodes/tebex/GrantCommand.java`, `RevokeCommand.java` — `SimpleCommand` de Velocity.
- `src/test/java/com/nextnodes/tebex/{UuidUtilTest,RankNamesTest,SyncEventsTest}.java` — tests de lógica pura.
- `velocity-tebex/README.md` — instalación, config, comandos de Tebex.

**Comando de build:** desde la raíz del repo, `./gradlew -p velocity-tebex <tarea>` (reusa el wrapper Gradle del repo con el build de la subcarpeta).

---

## Task 1: Scaffold + humo de build (de-riesgar dependencias)

**Files:**
- Create: `velocity-tebex/settings.gradle`, `velocity-tebex/build.gradle`
- Create: `velocity-tebex/src/main/java/com/nextnodes/tebex/NextNodesTebexPlugin.java` (mínimo)

- [ ] **Step 1: settings.gradle**

`velocity-tebex/settings.gradle`:
```groovy
rootProject.name = 'nextnodes-tebex'
```

- [ ] **Step 2: build.gradle**

`velocity-tebex/build.gradle`:
```groovy
plugins {
    id 'java'
    id 'com.gradleup.shadow' version '8.3.5'
}

group = 'com.nextnodes'
version = '1.0.0'

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
tasks.withType(JavaCompile).configureEach { options.release = 17 }

repositories {
    mavenCentral()
    maven { url = 'https://repo.papermc.io/repository/maven-public/' }
}

dependencies {
    compileOnly 'com.velocitypowered:velocity-api:3.3.0-SNAPSHOT'
    annotationProcessor 'com.velocitypowered:velocity-api:3.3.0-SNAPSHOT'
    implementation 'org.mongodb:mongodb-driver-sync:5.1.1'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testImplementation 'org.mongodb:bson:5.1.1'
}

test { useJUnitPlatform() }

shadowJar { archiveClassifier = '' }
tasks.named('build') { dependsOn 'shadowJar' }
```

- [ ] **Step 3: clase mínima del plugin**

`velocity-tebex/src/main/java/com/nextnodes/tebex/NextNodesTebexPlugin.java`:
```java
package com.nextnodes.tebex;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(id = "nextnodes-tebex", name = "NextNodes Tebex", version = "1.0.0", authors = {"NextNodes"})
public final class NextNodesTebexPlugin {
    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public NextNodesTebexPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.logger.info("NextNodes Tebex cargado.");
    }
}
```

- [ ] **Step 4: verificar que resuelve dependencias y compila**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex compileJava --console=plain`
Expected: BUILD SUCCESSFUL (descarga la Velocity API del repo de PaperMC y el driver de Mongo). **Si falla la versión `3.3.0-SNAPSHOT`**, probar con la última 3.3.x publicada en `https://repo.papermc.io/repository/maven-public/com/velocitypowered/velocity-api/` y anotar la versión usada. **Si `com.gradleup.shadow` falla**, alternativa: `id 'com.github.johnrengelman.shadow' version '8.1.1'`.

- [ ] **Step 5: Commit**

```bash
git add velocity-tebex/settings.gradle velocity-tebex/build.gradle velocity-tebex/src/main/java/com/nextnodes/tebex/NextNodesTebexPlugin.java
git commit -m "feat(tebex): scaffold del plugin de Velocity (build + clase base)"
```

---

## Task 2: UuidUtil + RankNames (lógica pura, TDD)

**Files:**
- Create: `velocity-tebex/src/main/java/com/nextnodes/tebex/UuidUtil.java`, `RankNames.java`
- Test: `velocity-tebex/src/test/java/com/nextnodes/tebex/UuidUtilTest.java`, `RankNamesTest.java`

- [ ] **Step 1: tests que fallan**

`UuidUtilTest.java`:
```java
package com.nextnodes.tebex;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UuidUtilTest {
    @Test void dashedStaysCanonical() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                UuidUtil.normalize("069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
    }
    @Test void undashedGetsDashes() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                UuidUtil.normalize("069a79f444e94726a5befca90e38aaf5"));
    }
    @Test void invalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> UuidUtil.normalize("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> UuidUtil.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> UuidUtil.normalize("069a79f4"));
    }
}
```

`RankNamesTest.java`:
```java
package com.nextnodes.tebex;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RankNamesTest {
    @Test void trimsAndLowercases() {
        assertEquals("vip", RankNames.normalize("  VIP "));
        assertEquals("diamond", RankNames.normalize("Diamond"));
    }
    @Test void nullBecomesEmpty() {
        assertEquals("", RankNames.normalize(null));
    }
}
```

- [ ] **Step 2: correr y ver fallar**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex test --console=plain`
Expected: FAIL de compilación (clases no existen).

- [ ] **Step 3: implementar**

`UuidUtil.java`:
```java
package com.nextnodes.tebex;

import java.util.UUID;

/** Normalizes a UUID (dashed or 32-char hex) to canonical lowercase dashed form (matches the mod's _id). */
public final class UuidUtil {
    private UuidUtil() {}

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("uuid nulo");
        String hex = raw.trim().replace("-", "");
        if (hex.length() != 32) throw new IllegalArgumentException("uuid inválido: " + raw);
        String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        return UUID.fromString(dashed).toString(); // valida hex y devuelve minúsculas canónicas
    }
}
```

`RankNames.java`:
```java
package com.nextnodes.tebex;

import java.util.Locale;

/** Mirrors PermissionModels.normalizeName: trim + lowercase. Rank names are ASCII. */
public final class RankNames {
    private RankNames() {}

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: correr y ver pasar**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex test --console=plain`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add velocity-tebex/src/main/java/com/nextnodes/tebex/UuidUtil.java velocity-tebex/src/main/java/com/nextnodes/tebex/RankNames.java velocity-tebex/src/test/java/com/nextnodes/tebex/UuidUtilTest.java velocity-tebex/src/test/java/com/nextnodes/tebex/RankNamesTest.java
git commit -m "feat(tebex): normalización pura de UUID y nombre de rango"
```

---

## Task 3: SyncEvents (puro, TDD) + PluginConfig

**Files:**
- Create: `velocity-tebex/src/main/java/com/nextnodes/tebex/SyncEvents.java`, `PluginConfig.java`
- Test: `velocity-tebex/src/test/java/com/nextnodes/tebex/SyncEventsTest.java`

- [ ] **Step 1: test que falla**

`SyncEventsTest.java`:
```java
package com.nextnodes.tebex;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncEventsTest {
    @Test void userEventShape() {
        Document d = SyncEvents.userEvent("069a79f4-44e9-4726-a5be-fca90e38aaf5", 1_700_000_000_000L);
        assertEquals("velocity-tebex", d.getString("origin"));
        assertEquals("user", d.getString("type"));
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", d.getString("key"));
        assertEquals(1_700_000_000_000L, d.getLong("ts"));
    }
}
```

- [ ] **Step 2: correr y ver fallar**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex test --console=plain`
Expected: FAIL de compilación.

- [ ] **Step 3: implementar SyncEvents**

`SyncEvents.java`:
```java
package com.nextnodes.tebex;

import org.bson.Document;

/** Builds the sync_events document the mod's tailable listener reacts to. */
public final class SyncEvents {
    public static final String ORIGIN = "velocity-tebex";
    private SyncEvents() {}

    public static Document userEvent(String uuid, long ts) {
        return new Document("origin", ORIGIN)
                .append("type", "user")
                .append("key", uuid)
                .append("ts", ts);
    }
}
```

- [ ] **Step 4: implementar PluginConfig** (sin test — I/O simple)

`PluginConfig.java`:
```java
package com.nextnodes.tebex;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads mongoUri/database from <dataDir>/config.properties (creates defaults on first run). */
public final class PluginConfig {
    public final String mongoUri;
    public final String database;

    private PluginConfig(String mongoUri, String database) {
        this.mongoUri = mongoUri;
        this.database = database;
    }

    public static PluginConfig loadOrCreate(Path dataDir, Logger logger) {
        String uri = "mongodb://localhost:27017";
        String db = "nextnodes_permissions";
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("config.properties");
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) { props.load(in); }
                uri = props.getProperty("mongoUri", uri);
                db = props.getProperty("database", db);
            } else {
                props.setProperty("mongoUri", uri);
                props.setProperty("database", db);
                try (var out = Files.newOutputStream(file)) {
                    props.store(out, "NextNodes Tebex — usa el MISMO mongoUri/database que el mod");
                }
                logger.info("Config creada en {} — ajústala al Mongo del mod y reinicia Velocity.", file);
            }
        } catch (IOException ex) {
            logger.warn("No se pudo leer la config, usando defaults: {}", ex.getMessage());
        }
        return new PluginConfig(uri, db);
    }
}
```

- [ ] **Step 5: correr tests + Commit**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex test --console=plain`
Expected: PASS (6 tests).

```bash
git add velocity-tebex/src/main/java/com/nextnodes/tebex/SyncEvents.java velocity-tebex/src/main/java/com/nextnodes/tebex/PluginConfig.java velocity-tebex/src/test/java/com/nextnodes/tebex/SyncEventsTest.java
git commit -m "feat(tebex): evento de sync (test) + carga de config"
```

---

## Task 4: RankMongo (grant/revoke/sync/capped)

**Files:**
- Create: `velocity-tebex/src/main/java/com/nextnodes/tebex/RankMongo.java`

- [ ] **Step 1: implementar RankMongo**

`RankMongo.java`:
```java
package com.nextnodes.tebex;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.ArrayList;

/** Writes rank changes to the mod's Mongo (users collection) and publishes a sync event. */
public final class RankMongo implements AutoCloseable {
    private static final String COL_USERS = "users";
    private static final String COL_SYNC = "sync_events";

    private final MongoClient client;
    private final MongoDatabase db;

    public RankMongo(String uri, String database) {
        this.client = MongoClients.create(uri);
        this.db = client.getDatabase(database);
        ensureSyncCollection();
    }

    /** Adds a rank to the user's ranks list (creates the user doc if missing). Mirrors /nn rank add. */
    public void grant(String rawUuid, String rawRank) {
        String uuid = UuidUtil.normalize(rawUuid);
        String rank = RankNames.normalize(rawRank);
        if (rank.isEmpty()) throw new IllegalArgumentException("rango vacío");
        db.getCollection(COL_USERS).updateOne(
                Filters.eq("_id", uuid),
                Updates.combine(
                        Updates.addToSet("ranks", rank),
                        Updates.setOnInsert("name", ""),
                        Updates.setOnInsert("tag", ""),
                        Updates.setOnInsert("primaryRank", ""),
                        Updates.setOnInsert("permissions", new ArrayList<Document>()),
                        Updates.setOnInsert("meta", new Document()),
                        Updates.setOnInsert("lastSeen", 0L),
                        Updates.setOnInsert("online", false)),
                new UpdateOptions().upsert(true));
        publishSync(uuid);
    }

    /** Removes a rank from the user's ranks list (and clears primaryRank if it was that). Mirrors /nn rank remove. */
    public void revoke(String rawUuid, String rawRank) {
        String uuid = UuidUtil.normalize(rawUuid);
        String rank = RankNames.normalize(rawRank);
        if (rank.isEmpty()) throw new IllegalArgumentException("rango vacío");
        MongoCollection<Document> users = db.getCollection(COL_USERS);
        users.updateOne(Filters.eq("_id", uuid), Updates.pull("ranks", rank));
        users.updateOne(Filters.and(Filters.eq("_id", uuid), Filters.eq("primaryRank", rank)),
                Updates.set("primaryRank", ""));
        publishSync(uuid);
    }

    private void publishSync(String uuid) {
        db.getCollection(COL_SYNC).insertOne(SyncEvents.userEvent(uuid, System.currentTimeMillis()));
    }

    /** The mod's tailable cursor requires a capped sync_events collection; create it identically if absent. */
    private void ensureSyncCollection() {
        try {
            boolean exists = false;
            for (String name : db.listCollectionNames()) {
                if (name.equals(COL_SYNC)) { exists = true; break; }
            }
            if (!exists) {
                db.createCollection(COL_SYNC, new CreateCollectionOptions()
                        .capped(true).sizeInBytes(1_048_576L).maxDocuments(10_000L));
            }
        } catch (Exception ignored) {
            // Otro proceso pudo crearla; seguro de ignorar.
        }
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: compilar**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex compileJava --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add velocity-tebex/src/main/java/com/nextnodes/tebex/RankMongo.java
git commit -m "feat(tebex): RankMongo (grant/revoke + sync + colección capped)"
```

---

## Task 5: Comandos + wiring del plugin

**Files:**
- Create: `velocity-tebex/src/main/java/com/nextnodes/tebex/GrantCommand.java`, `RevokeCommand.java`
- Modify: `velocity-tebex/src/main/java/com/nextnodes/tebex/NextNodesTebexPlugin.java`

- [ ] **Step 1: GrantCommand**

`GrantCommand.java`:
```java
package com.nextnodes.tebex;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class GrantCommand implements SimpleCommand {
    private final RankMongo mongo;
    private final Logger logger;

    public GrantCommand(RankMongo mongo, Logger logger) {
        this.mongo = mongo;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Uso: /nngrant <uuid> <rango>"));
            return;
        }
        try {
            mongo.grant(args[0], args[1]);
            invocation.source().sendMessage(Component.text("Rango '" + args[1] + "' otorgado a " + args[0]));
            logger.info("grant {} {}", args[0], args[1]);
        } catch (Exception ex) {
            invocation.source().sendMessage(Component.text("Error: " + ex.getMessage()));
            logger.warn("grant falló ({} {}): {}", args[0], args[1], ex.getMessage());
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("nextnodes.tebex");
    }
}
```

- [ ] **Step 2: RevokeCommand**

`RevokeCommand.java`:
```java
package com.nextnodes.tebex;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class RevokeCommand implements SimpleCommand {
    private final RankMongo mongo;
    private final Logger logger;

    public RevokeCommand(RankMongo mongo, Logger logger) {
        this.mongo = mongo;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Uso: /nnrevoke <uuid> <rango>"));
            return;
        }
        try {
            mongo.revoke(args[0], args[1]);
            invocation.source().sendMessage(Component.text("Rango '" + args[1] + "' quitado a " + args[0]));
            logger.info("revoke {} {}", args[0], args[1]);
        } catch (Exception ex) {
            invocation.source().sendMessage(Component.text("Error: " + ex.getMessage()));
            logger.warn("revoke falló ({} {}): {}", args[0], args[1], ex.getMessage());
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("nextnodes.tebex");
    }
}
```

- [ ] **Step 3: main class completa** (reemplaza el contenido de `NextNodesTebexPlugin.java`)

```java
package com.nextnodes.tebex;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "nextnodes-tebex", name = "NextNodes Tebex", version = "1.0.0", authors = {"NextNodes"})
public final class NextNodesTebexPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;
    private RankMongo mongo;

    @Inject
    public NextNodesTebexPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        PluginConfig config = PluginConfig.loadOrCreate(dataDir, logger);
        try {
            this.mongo = new RankMongo(config.mongoUri, config.database);
        } catch (Exception ex) {
            logger.error("No se pudo conectar a MongoDB ({}). Los comandos fallarán hasta corregir la config.",
                    ex.getMessage());
            return;
        }
        CommandManager cm = server.getCommandManager();
        cm.register(cm.metaBuilder("nngrant").plugin(this).build(), new GrantCommand(mongo, logger));
        cm.register(cm.metaBuilder("nnrevoke").plugin(this).build(), new RevokeCommand(mongo, logger));
        logger.info("NextNodes Tebex listo (base '{}').", config.database);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (this.mongo != null) this.mongo.close();
    }
}
```

- [ ] **Step 4: compilar**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex compileJava --console=plain`
Expected: BUILD SUCCESSFUL. (Si `metaBuilder(...).plugin(this)` no existe en esta versión de la API, usar `cm.metaBuilder("nngrant").build()`.)

- [ ] **Step 5: Commit**

```bash
git add velocity-tebex/src/main/java/com/nextnodes/tebex/GrantCommand.java velocity-tebex/src/main/java/com/nextnodes/tebex/RevokeCommand.java velocity-tebex/src/main/java/com/nextnodes/tebex/NextNodesTebexPlugin.java
git commit -m "feat(tebex): comandos /nngrant y /nnrevoke + wiring del plugin"
```

---

## Task 6: Jar shaded + README + cierre

**Files:**
- Create: `velocity-tebex/README.md`

- [ ] **Step 1: build completo + verificar el jar**

Run: `cd "D:/cretania/Nextnodes-1.21.1-Neoforge" && ./gradlew -p velocity-tebex build --console=plain`
Expected: BUILD SUCCESSFUL; jar en `velocity-tebex/build/libs/nextnodes-tebex-1.0.0.jar`.

Verificar que el jar incluye el driver de Mongo (shaded):
Run: `jar tf velocity-tebex/build/libs/nextnodes-tebex-1.0.0.jar | grep -c "com/mongodb"`
Expected: un número > 0 (el driver está dentro). Y que exista `velocity-plugin.json` (generado por el annotation processor):
Run: `jar tf velocity-tebex/build/libs/nextnodes-tebex-1.0.0.jar | grep velocity-plugin.json`
Expected: `velocity-plugin.json`.

- [ ] **Step 2: README**

`velocity-tebex/README.md`:
```markdown
# NextNodes Tebex (plugin de Velocity)

Otorga/quita rangos escribiendo en la MongoDB del mod NextNodes + un evento de sync.
El servidor NeoForge aplica el cambio automáticamente (incluso offline).

## Instalar
1. `./gradlew -p velocity-tebex build` → `velocity-tebex/build/libs/nextnodes-tebex-1.0.0.jar`.
2. Copiar el jar a `plugins/` de Velocity y arrancar Velocity una vez.
3. Editar `plugins/nextnodes-tebex/config.properties` con el MISMO `mongoUri` y `database` del mod. Reiniciar Velocity.

## Comandos (para Tebex, ejecutados como consola)
- `nngrant <uuid> <rango>` — agrega el rango.
- `nnrevoke <uuid> <rango>` — quita el rango.

## En Tebex
- Comando de compra: `nngrant {uuid} vip`
- Comando de expiración/reembolso: `nnrevoke {uuid} vip`
```

- [ ] **Step 3: Commit**

```bash
git add velocity-tebex/README.md
git commit -m "docs(tebex): README de instalación y uso"
```

- [ ] **Step 4: Verificación manual (usuario)**

Documentar para el usuario:
1. Instalar el jar en Velocity + config con el Mongo del mod.
2. En consola de Velocity: `nngrant <uuid-de-prueba> vip` → el jugador (aunque offline) debe tener el rango al conectarse.
3. `nnrevoke <uuid> vip` → se lo quita.
4. Configurar los comandos en Tebex y hacer una compra de prueba.

---

## Notas de riesgo

- **Build/deps:** el proyecto descarga la Velocity API (repo PaperMC) y el driver de Mongo. Si el entorno no tiene salida a internet para esos repos, el usuario compila en su máquina. Versiones alternativas anotadas en Task 1.
- **`ts` del evento:** usa el reloj del proxy; si el proxy y el backend están en máquinas con relojes muy desfasados, un evento con `ts` menor al `lastEventTs` del backend se ignoraría. Mismo host o NTP → sin problema.
- **Esquema:** el plugin replica el contrato de `users`/`sync_events`. Si el mod cambia el esquema, actualizar el plugin (ver spec).
- **No testeable aquí:** la escritura real a Mongo y el end-to-end con Velocity/Tebex los verifica el usuario; aquí se cubren compilación + lógica pura con tests.
