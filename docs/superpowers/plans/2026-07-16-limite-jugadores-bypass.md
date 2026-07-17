# Límite de Jugadores con Bypass por Rango — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Limitar la network a N jugadores desde el proxy Velocity, sin contar contra ese cupo a quienes tengan un rango marcado "bypass" (configurable desde el panel web del mod).

**Architecture:** El mod NeoForge (rangos + Mongo + panel web) es la única fuente de datos: cada rango gana un flag `bypassPlayerLimit`, y hay una nueva configuración `limitSettings` (activado/máximo/mensaje) editable desde la web. El plugin de Velocity (`nextnode-plugin/`) lee esos datos directo de Mongo en un listener de `LoginEvent` y decide si deja pasar al jugador — es el único punto que aplica el límite; el mod nunca lo hace.

**Tech Stack:** Java 21, NeoForge 1.21.1 (mod), Velocity API 3.3.0-SNAPSHOT (plugin), MongoDB driver sync 5.1.1 (ambos), JUnit 5.

**Nota sobre testing:** en este repo las clases que tocan Mongo/HTTP/Minecraft (`PermissionStore`, `WebPanelServer`, `RankMongo`) no tienen tests unitarios — solo las clases de lógica pura (`Days`, `OfflineUuid`, `RankNames`, `BanDuration`, etc.) los tienen. Este plan sigue esa misma convención: la nueva lógica de decisión (`PlayerLimitDecision`) se desarrolla con TDD real; el resto de tareas (modelo de datos, persistencia, rutas HTTP, UI) se verifican con compilación exitosa + checklist manual al final, igual que se hizo con `tabSettings` y el sistema de baneos.

---

### Task 1: Mod — modelo de datos (`Rank.bypassPlayerLimit` + `LimitSettings`)

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionModels.java`

- [ ] **Step 1: Añadir el campo `bypassPlayerLimit` a `Rank`**

En la clase `Rank` (línea 49-82), justo después de `public Map<String, String> meta = new LinkedHashMap<>();` (línea 57), añade:

```java
        public boolean bypassPlayerLimit = false;
```

No requiere cambios en `Rank.sanitize()` — es un booleano primitivo, siempre tiene un valor válido.

- [ ] **Step 2: Añadir la clase `LimitSettings`**

Justo después de la clase `TabSettings` (línea 45-47), añade:

```java
    public static final class LimitSettings {
        public boolean enabled = false;
        public int max = 20;
        public String kickMessage =
                "El servidor está lleno ({online}/{max}). Los rangos con acceso preferente entran igual.";

        public void sanitize() {
            if (this.max <= 0) {
                this.max = 20;
            }
            if (this.kickMessage == null || this.kickMessage.isBlank()) {
                this.kickMessage =
                        "El servidor está lleno ({online}/{max}). Los rangos con acceso preferente entran igual.";
            }
        }
    }
```

- [ ] **Step 3: Añadir el campo `limitSettings` a `PermissionData` y sanearlo en `ensureDefaults()`**

En `PermissionData` (línea 12-43), añade el campo junto a `tabSettings` (línea 17):

```java
        public TabSettings tabSettings = new TabSettings();
        public LimitSettings limitSettings = new LimitSettings();
```

Y en `ensureDefaults()`, junto al guard de `tabSettings` (línea 29-31):

```java
            if (this.tabSettings == null) {
                this.tabSettings = new TabSettings();
            }
            if (this.limitSettings == null) {
                this.limitSettings = new LimitSettings();
            }
            this.limitSettings.sanitize();
```

- [ ] **Step 4: Compilar para verificar que el modelo compila**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionModels.java
git commit -m "feat(permissions): añadir bypassPlayerLimit por rango y modelo LimitSettings"
```

---

### Task 2: Mod — persistencia en Mongo (`PermissionStore`)

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionStore.java`

- [ ] **Step 1: Añadir la constante de clave de settings**

Junto a las otras `KEY_*` (línea 39-42), añade:

```java
    private static final String KEY_TAB_SETTINGS   = "tabSettings";
    private static final String KEY_LIMIT_SETTINGS = "limitSettings";
```

- [ ] **Step 2: Persistir `bypassPlayerLimit` en `rankToDoc`/`docToRank`**

En `rankToDoc` (línea 685-696), añade el campo al `Document`:

```java
    private static Document rankToDoc(Rank rank) {
        List<Document> perms = new ArrayList<>(rank.permissions.size());
        for (PermissionRule rule : rank.permissions) perms.add(ruleToDoc(rule));
        return new Document("_id", rank.name)
                .append("displayName", rank.displayName)
                .append("prefix",      rank.prefix)
                .append("suffix",      rank.suffix)
                .append("weight",      rank.weight)
                .append("parents",     rank.parents)
                .append("permissions", perms)
                .append("meta",        new Document(rank.meta))
                .append("bypassPlayerLimit", rank.bypassPlayerLimit);
    }
```

En `docToRank` (línea 698-717), justo después de `rank.parents = doc.getList(...)` (línea 705):

```java
        rank.parents     = doc.getList("parents", String.class, new ArrayList<>());
        rank.bypassPlayerLimit = Boolean.TRUE.equals(doc.getBoolean("bypassPlayerLimit", false));
```

- [ ] **Step 3: Leer `limitSettings` en `readAll()`**

Después del bloque de `tabSettingsDoc` (línea 614-624), añade:

```java
        // Player-limit settings
        Document limitSettingsDoc = col(COL_SETTINGS).find(Filters.eq("_id", KEY_LIMIT_SETTINGS)).first();
        if (limitSettingsDoc != null) {
            PermissionModels.LimitSettings ls = new PermissionModels.LimitSettings();
            ls.enabled = Boolean.TRUE.equals(limitSettingsDoc.getBoolean("enabled", false));
            ls.max = intFrom(limitSettingsDoc.get("max"), 20);
            String km = limitSettingsDoc.getString("kickMessage");
            if (km != null && !km.isBlank()) ls.kickMessage = km;
            pd.limitSettings = ls;
        }
```

(`intFrom` ya existe en esta clase — se usa en `docToRank` para `weight`.)

- [ ] **Step 4: Añadir `getLimitSettings()` / `saveLimitSettings()`**

Justo después de `saveTabSettings(...)` (línea 642-661), añade:

```java
    public PermissionModels.LimitSettings getLimitSettings() {
        this.lock.readLock().lock();
        try {
            return this.data.limitSettings != null ? this.data.limitSettings : new PermissionModels.LimitSettings();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void saveLimitSettings(PermissionModels.LimitSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        settings.sanitize();
        this.lock.writeLock().lock();
        try {
            col(COL_SETTINGS).replaceOne(
                    Filters.eq("_id", KEY_LIMIT_SETTINGS),
                    new Document("_id", KEY_LIMIT_SETTINGS)
                            .append("enabled", settings.enabled)
                            .append("max", settings.max)
                            .append("kickMessage", settings.kickMessage),
                    UPSERT);
            this.data.limitSettings = settings;
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            throw new IOException("Unable to save limit settings", ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        publishEvent("settings", "limit");
    }
```

- [ ] **Step 5: Compilar**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionStore.java
git commit -m "feat(permissions): persistir bypassPlayerLimit y limitSettings en Mongo"
```

---

### Task 3: Mod — API web (`/api/settings/limit`)

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/web/WebPanelServer.java`

- [ ] **Step 1: Añadir las rutas GET/PUT**

Justo después del bloque de `/api/settings/tab` PUT (línea 404-411, termina con `return; }` antes del comentario `// --- API key management ---`), añade:

```java
            // --- límite de jugadores ---
            if (path.equals("/api/settings/limit") && method.equals("GET")) {
                sendJson(exchange, 200, GSON.toJsonTree(this.store.getLimitSettings()).getAsJsonObject());
                return;
            }
            if (path.equals("/api/settings/limit") && method.equals("PUT")) {
                PermissionModels.LimitSettings ls = GSON.fromJson(readBody(exchange), PermissionModels.LimitSettings.class);
                if (ls == null) { sendJson(exchange, 400, error("JSON inválido")); return; }
                this.store.saveLimitSettings(ls);
                if (this.auditLog != null) {
                    this.auditLog.log("web-panel", "web-panel", "limit.save", "", "",
                            "enabled=" + ls.enabled + " max=" + ls.max);
                }
                sendJson(exchange, 200, ok());
                return;
            }
```

- [ ] **Step 2: Compilar**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/web/WebPanelServer.java
git commit -m "feat(web): endpoint /api/settings/limit para el límite de jugadores"
```

---

### Task 4: Mod — UI del panel web (`app.js`)

**Files:**
- Modify: `src/main/resources/web/app.js`

- [ ] **Step 1: Checkbox de bypass en el editor de rango**

En el bloque del editor de rango, después del input de sufijo (línea 371, `<label>Sufijo (después del nombre)<input id="rank_suffix" ...></label>`), añade una fila nueva antes de la fila de gradiente (línea 373):

```html
            <div class="row"><label class="miniCheck"><input id="rank_bypass_limit" type="checkbox" ${r.bypassPlayerLimit ? 'checked' : ''}>Sin límite de jugadores (bypass)</label></div>
```

- [ ] **Step 2: Incluir el campo al guardar el rango**

En `readRankEditor()` (línea 422), añade `bypassPlayerLimit` al objeto devuelto:

```javascript
    function readRankEditor(){const id=document.getElementById('rank_id').value.trim().toLowerCase(); const meta=readJsonBox('rank_meta'); meta.gradientStart=document.getElementById('rank_grad_a')?.value || meta.gradientStart; meta.gradientMiddle=document.getElementById('rank_grad_m')?.value || meta.gradientMiddle; meta.gradientEnd=document.getElementById('rank_grad_b')?.value || meta.gradientEnd; return {name:id, displayName:document.getElementById('rank_name').value, prefix:document.getElementById('rank_prefix').value, suffix:document.getElementById('rank_suffix').value, weight:Number(document.getElementById('rank_weight').value||0), parents:splitList(document.getElementById('rank_parents').value), permissions:[...modalCommandRules(), ...readRules('rank')], meta, bypassPlayerLimit:document.getElementById('rank_bypass_limit').checked};}
```

- [ ] **Step 3: Nueva sección "Límite de jugadores" en la vista general**

Después del `tabCard` de "Lista TAB" (línea 150-153, cierra en `</div>` de línea 153) y antes del `tableCard` de rangos (línea 154), añade:

```html
          <div class="tabCard">
            <div class="cardTitle"><h2>Límite de jugadores</h2></div>
            <div class="tabRow"><span class="tileIcon">${I_bolt}</span><div style="flex:1;min-width:0"><div class="t-name">Activar límite</div><div class="t-sub">Los rangos marcados "bypass" no cuentan contra el máximo</div></div><label class="miniCheck" style="margin:0"><input type="checkbox" id="limitEnabled" ${(state.limitSettings||{}).enabled ? 'checked' : ''} onchange="saveLimitSettings()"></label></div>
            <div class="editorFields cols-2" style="padding:0 20px 16px"><label>Máximo de jugadores<input id="limitMax" type="number" min="1" value="${escapeAttr((state.limitSettings||{}).max ?? 20)}" onchange="saveLimitSettings()"></label></div>
            <div class="sectionBody"><label>Mensaje al rechazar (usa {online} y {max})<textarea id="limitKickMessage" onchange="saveLimitSettings()">${escapeHtml((state.limitSettings||{}).kickMessage || 'El servidor está lleno ({online}/{max}). Los rangos con acceso preferente entran igual.')}</textarea></label></div>
          </div>
```

- [ ] **Step 4: Función `saveLimitSettings()`**

Después de `saveTabSettings()` (línea 160-166), añade:

```javascript
    async function saveLimitSettings() {
      const enabled = document.getElementById('limitEnabled')?.checked ?? false;
      const max = Number(document.getElementById('limitMax')?.value || 20);
      const kickMessage = document.getElementById('limitKickMessage')?.value || '';
      try {
        await api('/api/settings/limit', { method: 'PUT', body: { enabled, max, kickMessage } });
        toast('Límite de jugadores guardado');
      } catch(e) { toast('Error: ' + e.message); }
    }
```

- [ ] **Step 5: Estado de demo (`bootDemo`)**

En el objeto `state` de `bootDemo()` (línea 69-75), añade junto a `tabSettings` (línea 74):

```javascript
        tabSettings: { showPing: true },
        limitSettings: { enabled: false, max: 20, kickMessage: 'El servidor está lleno ({online}/{max}). Los rangos con acceso preferente entran igual.' }
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/web/app.js
git commit -m "feat(web): UI del límite de jugadores y bypass por rango"
```

---

### Task 5: Mod — build y verificación

**Files:** (ninguno nuevo — solo build)

- [ ] **Step 1: Build completo del mod**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (incluye los tests ya existentes en `src/test`, que no deben romperse).

- [ ] **Step 2: Confirmar que no quedan cambios sin commitear**

Run: `git status`
Expected: working tree limpio (todo lo de las Tasks 1-4 ya commiteado).

---

### Task 6: Plugin — lógica pura de decisión (TDD)

**Files:**
- Create: `nextnode-plugin/src/main/java/com/nextnodes/plugin/PlayerLimitDecision.java`
- Test: `nextnode-plugin/src/test/java/com/nextnodes/plugin/PlayerLimitDecisionTest.java`

- [ ] **Step 1: Escribir el test (debe fallar — la clase no existe aún)**

```java
package com.nextnodes.plugin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerLimitDecisionTest {
    @Test void disabledAlwaysAllows() {
        assertTrue(PlayerLimitDecision.shouldAllow(false, false, 999, 20));
    }

    @Test void bypassAlwaysAllowsEvenWhenFull() {
        assertTrue(PlayerLimitDecision.shouldAllow(true, true, 20, 20));
    }

    @Test void allowsUnderLimit() {
        assertTrue(PlayerLimitDecision.shouldAllow(true, false, 19, 20));
    }

    @Test void deniesAtLimit() {
        assertFalse(PlayerLimitDecision.shouldAllow(true, false, 20, 20));
    }

    @Test void deniesOverLimit() {
        assertFalse(PlayerLimitDecision.shouldAllow(true, false, 25, 20));
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `./gradlew -p nextnode-plugin test --tests "com.nextnodes.plugin.PlayerLimitDecisionTest"`
Expected: FAIL con "cannot find symbol: class PlayerLimitDecision"

- [ ] **Step 3: Implementación mínima**

```java
package com.nextnodes.plugin;

/** Lógica pura: decide si un jugador entra dado el estado del límite. Sin dependencias de Mongo/Velocity. */
public final class PlayerLimitDecision {
    private PlayerLimitDecision() {}

    public static boolean shouldAllow(boolean enabled, boolean joinerHasBypass, long nonBypassOnlineCount, int max) {
        if (!enabled) return true;
        if (joinerHasBypass) return true;
        return nonBypassOnlineCount < max;
    }
}
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `./gradlew -p nextnode-plugin test --tests "com.nextnodes.plugin.PlayerLimitDecisionTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add nextnode-plugin/src/main/java/com/nextnodes/plugin/PlayerLimitDecision.java nextnode-plugin/src/test/java/com/nextnodes/plugin/PlayerLimitDecisionTest.java
git commit -m "feat(plugin): lógica pura de decisión del límite de jugadores + tests"
```

---

### Task 7: Plugin — lectura de Mongo (`PlayerLimitMongo`)

**Files:**
- Modify: `nextnode-plugin/src/main/java/com/nextnodes/plugin/RankMongo.java`
- Create: `nextnode-plugin/src/main/java/com/nextnodes/plugin/PlayerLimitMongo.java`

- [ ] **Step 1: Exponer la conexión compartida desde `RankMongo`**

Añade este método justo antes de `close()` en `RankMongo.java`:

```java
    /** Expone la base compartida para que otros lectores (p. ej. PlayerLimitMongo) reusen esta conexión. */
    MongoDatabase database() {
        return db;
    }
```

- [ ] **Step 2: Crear `PlayerLimitMongo`**

```java
package com.nextnodes.plugin;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lee la configuración de límite de jugadores y el estado de rangos/online que gestiona el panel web del mod. */
public final class PlayerLimitMongo {
    private static final String COL_USERS = "users";
    private static final String COL_RANKS = "ranks";
    private static final String COL_SETTINGS = "settings";
    private static final String KEY_LIMIT_SETTINGS = "limitSettings";
    private static final String DEFAULT_KICK_MESSAGE = "El servidor está lleno ({online}/{max}).";

    private final MongoDatabase db;

    public PlayerLimitMongo(MongoDatabase db) {
        this.db = db;
    }

    public static final class Settings {
        public final boolean enabled;
        public final int max;
        public final String kickMessage;

        public Settings(boolean enabled, int max, String kickMessage) {
            this.enabled = enabled;
            this.max = max;
            this.kickMessage = kickMessage;
        }
    }

    /** Lee settings/limitSettings; si no existe, por defecto está desactivado (igual que en el mod). */
    public Settings loadSettings() {
        Document doc = db.getCollection(COL_SETTINGS).find(Filters.eq("_id", KEY_LIMIT_SETTINGS)).first();
        if (doc == null) {
            return new Settings(false, 20, DEFAULT_KICK_MESSAGE);
        }
        boolean enabled = Boolean.TRUE.equals(doc.getBoolean("enabled", false));
        int max = doc.getInteger("max", 20);
        String kickMessage = doc.getString("kickMessage");
        if (kickMessage == null || kickMessage.isBlank()) {
            kickMessage = DEFAULT_KICK_MESSAGE;
        }
        return new Settings(enabled, max, kickMessage);
    }

    /** Nombres de rango (ya en minúscula) marcados como bypass en el panel web. */
    public Set<String> bypassRankNames() {
        Set<String> names = new HashSet<>();
        for (Document doc : db.getCollection(COL_RANKS).find(Filters.eq("bypassPlayerLimit", true))) {
            Object id = doc.get("_id");
            if (id != null) names.add(id.toString());
        }
        return names;
    }

    /** True si los rangos guardados del jugador intersectan con el set de bypass. Jugador desconocido -> false. */
    public boolean hasBypassRank(String uuid, Set<String> bypassRanks) {
        if (bypassRanks.isEmpty()) return false;
        Document doc = db.getCollection(COL_USERS).find(Filters.eq("_id", uuid)).first();
        if (doc == null) return false;
        List<String> ranks = doc.getList("ranks", String.class);
        if (ranks == null) return false;
        for (String rank : ranks) {
            if (bypassRanks.contains(rank)) return true;
        }
        return false;
    }

    /** Cuenta, de los UUIDs dados, cuántos NO tienen rango de bypass. */
    public long countOnlineWithoutBypass(Collection<String> onlineUuids, Set<String> bypassRanks) {
        if (onlineUuids.isEmpty()) return 0;
        Set<String> withBypass = new HashSet<>();
        if (!bypassRanks.isEmpty()) {
            for (Document doc : db.getCollection(COL_USERS).find(Filters.in("_id", onlineUuids))) {
                List<String> ranks = doc.getList("ranks", String.class);
                if (ranks == null) continue;
                for (String rank : ranks) {
                    if (bypassRanks.contains(rank)) {
                        Object id = doc.get("_id");
                        if (id != null) withBypass.add(id.toString());
                        break;
                    }
                }
            }
        }
        return onlineUuids.size() - withBypass.size();
    }
}
```

- [ ] **Step 3: Compilar**

Run: `./gradlew -p nextnode-plugin compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add nextnode-plugin/src/main/java/com/nextnodes/plugin/RankMongo.java nextnode-plugin/src/main/java/com/nextnodes/plugin/PlayerLimitMongo.java
git commit -m "feat(plugin): lector Mongo del límite de jugadores (settings/bypass/online)"
```

---

### Task 8: Plugin — listener de `LoginEvent` y wiring

**Files:**
- Create: `nextnode-plugin/src/main/java/com/nextnodes/plugin/PlayerLimitListener.java`
- Modify: `nextnode-plugin/src/main/java/com/nextnodes/plugin/NextNodePlugin.java`

- [ ] **Step 1: Crear el listener**

```java
package com.nextnodes.plugin;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Rechaza logins que excedan el límite configurado, salvo que el jugador tenga un rango de bypass. */
public final class PlayerLimitListener {
    private final ProxyServer server;
    private final PlayerLimitMongo limitMongo;
    private final Logger logger;

    public PlayerLimitListener(ProxyServer server, PlayerLimitMongo limitMongo, Logger logger) {
        this.server = server;
        this.limitMongo = limitMongo;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        try {
            PlayerLimitMongo.Settings settings = limitMongo.loadSettings();
            if (!settings.enabled) return;

            Player joiner = event.getPlayer();
            String joinerUuid = joiner.getUniqueId().toString();
            Set<String> bypassRanks = limitMongo.bypassRankNames();
            boolean joinerHasBypass = limitMongo.hasBypassRank(joinerUuid, bypassRanks);

            List<String> onlineUuids = server.getAllPlayers().stream()
                    .map(Player::getUniqueId).map(UUID::toString).collect(Collectors.toList());
            long nonBypassOnline = limitMongo.countOnlineWithoutBypass(onlineUuids, bypassRanks);

            if (!PlayerLimitDecision.shouldAllow(true, joinerHasBypass, nonBypassOnline, settings.max)) {
                String message = settings.kickMessage
                        .replace("{online}", String.valueOf(nonBypassOnline))
                        .replace("{max}", String.valueOf(settings.max));
                event.setResult(ResultedEvent.ComponentResult.denied(Component.text(message)));
                logger.info("Login rechazado por límite ({}/{}): {}", nonBypassOnline, settings.max, joiner.getUsername());
            }
        } catch (Exception ex) {
            // Fail-open: un problema con Mongo no debe bloquear el acceso al servidor.
            logger.warn("No se pudo evaluar el límite de jugadores, se permite el acceso: {}", ex.getMessage());
        }
    }
}
```

- [ ] **Step 2: Registrar el listener en `NextNodePlugin.onInit`**

En `NextNodePlugin.java`, reemplaza el cuerpo de `onInit` (línea 30-43):

```java
    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        PluginConfig config = PluginConfig.loadOrCreate(dataDir, logger);
        try {
            this.mongo = new RankMongo(config.mongoUri, config.database, config.onlineMode);
        } catch (Exception ex) {
            logger.error("No se pudo conectar a MongoDB ({}). Los comandos fallarán hasta corregir la config.",
                    ex.getMessage());
            return;
        }
        CommandManager cm = server.getCommandManager();
        cm.register(cm.metaBuilder("nngrant").plugin(this).build(), new GrantCommand(mongo, logger));
        cm.register(cm.metaBuilder("nnrevoke").plugin(this).build(), new RevokeCommand(mongo, logger));
        PlayerLimitMongo limitMongo = new PlayerLimitMongo(mongo.database());
        server.getEventManager().register(this, new PlayerLimitListener(server, limitMongo, logger));
        logger.info("NextNode Plugin listo (base '{}').", config.database);
    }
```

- [ ] **Step 3: Compilar**

Run: `./gradlew -p nextnode-plugin compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add nextnode-plugin/src/main/java/com/nextnodes/plugin/PlayerLimitListener.java nextnode-plugin/src/main/java/com/nextnodes/plugin/NextNodePlugin.java
git commit -m "feat(plugin): aplicar el límite de jugadores en LoginEvent"
```

---

### Task 9: Plugin — build final y checklist de verificación manual

**Files:** (ninguno nuevo — solo build y verificación)

- [ ] **Step 1: Build completo del plugin (incluye todos los tests)**

Run: `./gradlew -p nextnode-plugin build`
Expected: `BUILD SUCCESSFUL`, todos los tests en verde (incluye `PlayerLimitDecisionTest` + los ya existentes).

- [ ] **Step 2: Confirmar el jar generado**

Run: `ls nextnode-plugin/build/libs/`
Expected: `nextnode-plugin-1.0.0.jar` presente y actualizado (fecha reciente).

- [ ] **Step 3: Checklist de verificación manual (usuario, en su servidor)**

1. Desplegar el mod (`compilar.bat` → `mods/`) y el plugin (`nextnode-plugin-1.0.0.jar` → `plugins/` del proxy) y reiniciar ambos.
2. Desde el panel web: sección "Límite de jugadores" → activar, poner máximo = 1, guardar.
3. Marcar el checkbox "Sin límite de jugadores (bypass)" en un rango (p. ej. `vip`) y guardar el rango.
4. Conectar un jugador SIN ese rango → debe entrar (ocupa el único cupo).
5. Conectar un segundo jugador SIN ese rango → debe ser rechazado con el mensaje configurado.
6. Conectar un jugador CON el rango bypass → debe entrar igual, aunque el server esté "lleno".
7. Desactivar el límite desde la web → todos vuelven a entrar sin restricción.
8. Recordar subir el `max-players` real de Velocity/backends por encima del límite lógico (p. ej. a 100), para que el tope duro de Minecraft no bloquee antes de que el plugin evalúe el bypass.

---

## Self-review

- **Cobertura de la spec:** decisión de bypass por rango (Task 1, 6, 7), configuración en Mongo vía web (Task 1-4), punto de corte en `LoginEvent` (Task 8), fail-open ante error de Mongo (Task 8), mensaje de kick editable (Task 3-4, 8), casos límite documentados en la spec están cubiertos por el diseño de `countOnlineWithoutBypass`/`shouldAllow` (Task 6-7) y el checklist manual (Task 9).
- **Placeholders:** ninguno — todo el código de cada paso está completo.
- **Consistencia de tipos:** `PlayerLimitMongo.Settings` (enabled/max/kickMessage) se usa igual en `PlayerLimitListener`; `PlayerLimitDecision.shouldAllow(enabled, joinerHasBypass, nonBypassOnlineCount, max)` con la misma firma en el test (Task 6) y en el listener (Task 8); `bypassPlayerLimit` como nombre de campo/clave Mongo es idéntico en `PermissionModels`, `PermissionStore` y `PlayerLimitMongo`.
