# Disfraz de admin Fase 1 (TAB + chat + rango cosmético) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que un jugador (admin) aparezca con nombre y rango falsos en la lista TAB y el chat, manteniendo intactos todos sus permisos reales, configurable desde el panel web.

**Architecture:** Dos campos por jugador (`disguiseName`, `disguiseRank`). Los métodos de *display* del `PermissionResolver` usan el rango falso cuando está puesto; los de *permisos* no cambian. El nombre falso se aplica al chat (`composeFullName`) y a la lista TAB (evento `PlayerEvent.TabListNameFormat`). La decisión "apariencia disfrazada vs normal" se aísla en una clase pura `DisguiseResolver` con tests.

**Tech Stack:** Java 21, NeoForge 1.21.1 (mod), MongoDB sync driver, JUnit 5. Frontend vanilla JS (`app.js`). Solo se recompila el mod.

**Convención de testing:** solo las clases de lógica pura se testean (`Days`, `OfflineUuid`, `TabTeamNaming`, etc.). `PermissionResolver`/`PermissionStore`/`NextNodesPermissions` no tienen tests unitarios (requieren Mongo/servidor). Por eso la única unidad con TDD real es `DisguiseResolver`; el resto se verifica con compilación + navegador + checklist manual en vivo.

---

### Task 1: `DisguiseResolver` (lógica pura, TDD)

**Files:**
- Create: `src/main/java/com/nextnodes/permissions/DisguiseResolver.java`
- Test: `src/test/java/com/nextnodes/permissions/DisguiseResolverTest.java`

- [ ] **Step 1: Escribir el test (debe fallar — la clase no existe)**

```java
package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisguiseResolverTest {
    private Map<String, Rank> ranks() {
        Map<String, Rank> m = new LinkedHashMap<>();
        Rank vip = new Rank(); vip.name = "vip"; m.put("vip", vip);
        return m;
    }

    @Test void noDisguiseReturnsNull() {
        UserEntry u = new UserEntry();
        assertNull(DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void blankDisguiseReturnsNull() {
        UserEntry u = new UserEntry(); u.disguiseRank = "   ";
        assertNull(DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void unknownDisguiseRankReturnsNull() {
        UserEntry u = new UserEntry(); u.disguiseRank = "fantasma";
        assertNull(DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void existingDisguiseRankReturnsIt() {
        UserEntry u = new UserEntry(); u.disguiseRank = "vip";
        assertEquals("vip", DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void nullUserReturnsNull() {
        assertNull(DisguiseResolver.displayRank(null, ranks()));
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `./gradlew test --tests "com.nextnodes.permissions.DisguiseResolverTest"`
Expected: FAIL con "cannot find symbol: class DisguiseResolver"

- [ ] **Step 3: Implementación mínima**

```java
package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;

import java.util.Map;

/** Decide, para el DISPLAY (no permisos), qué rango debe mostrarse: el disfraz si está puesto y existe. */
public final class DisguiseResolver {
    private DisguiseResolver() {}

    /** @return el nombre del rango de disfraz si el usuario tiene uno no-vacío y existe en {@code ranks}; si no, null. */
    public static String displayRank(UserEntry user, Map<String, Rank> ranks) {
        if (user == null || user.disguiseRank == null || user.disguiseRank.isBlank() || ranks == null) {
            return null;
        }
        return ranks.containsKey(user.disguiseRank) ? user.disguiseRank : null;
    }
}
```

Nota: este código referencia `user.disguiseRank`, que se añade en la Task 2. Para que la Task 1 compile de forma aislada, **implementa primero el campo de la Task 2 Step 1** (solo el campo) antes de compilar esta. Si ejecutas el plan en orden con subagentes, indica al implementador de la Task 1 que añada el campo `public String disguiseRank = "";` a `UserEntry` como parte de esta tarea (la Task 2 lo formaliza junto con `disguiseName` y el sanitize).

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `./gradlew test --tests "com.nextnodes.permissions.DisguiseResolverTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/DisguiseResolver.java src/test/java/com/nextnodes/permissions/DisguiseResolverTest.java
git commit -m "feat(permissions): DisguiseResolver — decisión pura de rango de display + tests"
```

---

### Task 2: Modelo de datos (`UserEntry.disguiseName` + `disguiseRank`)

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionModels.java`

- [ ] **Step 1: Añadir los campos a `UserEntry`**

En la clase `UserEntry`, después de `public Map<String, Long> rankExpiries = new LinkedHashMap<>();`, añade:

```java
        public String disguiseName = "";
        public String disguiseRank = "";
```

- [ ] **Step 2: Saneado en `UserEntry.sanitize()`**

En `UserEntry.sanitize()`, después del guard de `rankExpiries` (`if (this.rankExpiries == null) { this.rankExpiries = new LinkedHashMap<>(); }`), añade:

```java
            if (this.disguiseName == null) {
                this.disguiseName = "";
            }
            this.disguiseRank = normalizeName(this.disguiseRank);
```

(`normalizeName` es un método estático existente en `PermissionModels`: `trim().toLowerCase()`, y devuelve "" si el argumento es null — seguro para `disguiseRank`.)

- [ ] **Step 3: Compilar**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionModels.java
git commit -m "feat(permissions): campos disguiseName/disguiseRank en UserEntry"
```

---

### Task 3: Persistencia en Mongo (`PermissionStore`)

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionStore.java`

- [ ] **Step 1: Escribir los campos en `userToDoc`**

En `userToDoc` (empieza en la línea ~765), añade dos `.append(...)` al final del `Document`, tras `.append("online", user.online)`:

```java
                .append("lastSeen",    user.lastSeen)
                .append("online",      user.online)
                .append("disguiseName", user.disguiseName)
                .append("disguiseRank", user.disguiseRank);
```

- [ ] **Step 2: Leerlos en `docToUser`**

En `docToUser` (empieza en ~780), después de `user.online = Boolean.TRUE.equals(doc.getBoolean("online"));`, añade:

```java
        user.disguiseName = doc.getString("disguiseName");
        user.disguiseRank = doc.getString("disguiseRank");
```

(Los null se sanean después: `readAll()`/`ensureDefaults()` llama a `UserEntry.sanitize()` sobre cada usuario, que convierte null en "".)

- [ ] **Step 3: Compilar**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionStore.java
git commit -m "feat(permissions): persistir disguiseName/disguiseRank en Mongo"
```

---

### Task 4: Override de display en `PermissionResolver`

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/PermissionResolver.java`

- [ ] **Step 1: `resolvePrefix` usa el rango de disfraz**

Reemplaza el cuerpo de `resolvePrefix` (líneas ~64-77) por:

```java
    public String resolvePrefix(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? "" : defaultRank.prefix;
        }
        String disguise = DisguiseResolver.displayRank(user, data.ranks);
        if (disguise != null) {
            Rank r = data.ranks.get(disguise);
            return r == null || r.prefix == null ? "" : r.prefix;
        }
        for (Rank rank : rankOrder(data, user)) {
            if (rank.prefix != null && !rank.prefix.isBlank()) {
                return rank.prefix;
            }
        }
        return "";
    }
```

- [ ] **Step 2: `resolveSuffix` usa el rango de disfraz**

Reemplaza el cuerpo de `resolveSuffix` (líneas ~79-92) por:

```java
    public String resolveSuffix(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? "" : defaultRank.suffix;
        }
        String disguise = DisguiseResolver.displayRank(user, data.ranks);
        if (disguise != null) {
            Rank r = data.ranks.get(disguise);
            return r == null || r.suffix == null ? "" : r.suffix;
        }
        for (Rank rank : rankOrder(data, user)) {
            if (rank.suffix != null && !rank.suffix.isBlank()) {
                return rank.suffix;
            }
        }
        return "";
    }
```

- [ ] **Step 3: `resolveTag` se oculta si hay disfraz**

Reemplaza el cuerpo de `resolveTag` (líneas ~94-98) por:

```java
    public String resolveTag(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null || user.tag == null) {
            return "";
        }
        // Con disfraz, no mostrar el tag personal real (delataría la identidad).
        if (DisguiseResolver.displayRank(user, data.ranks) != null) {
            return "";
        }
        return user.tag;
    }
```

- [ ] **Step 4: `resolveWeight` usa el peso del rango de disfraz**

Reemplaza el cuerpo de `resolveWeight` (líneas ~100-118) por:

```java
    public int resolveWeight(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? 0 : defaultRank.weight;
        }
        String disguise = DisguiseResolver.displayRank(user, data.ranks);
        if (disguise != null) {
            Rank r = data.ranks.get(disguise);
            return r == null ? 0 : r.weight;
        }
        List<Rank> order = rankOrder(data, user);
        // Sort by the weight of the rank that provides the DISPLAYED prefix, so the TAB order matches
        // the rank shown next to the name. Mirrors resolvePrefix(): a player whose visible prefix is
        // [ADMIN] — even if it is inherited from a parent or comes from a non-primary rank — sorts at
        // admin's level instead of at the weight of some lower root rank.
        for (Rank rank : order) {
            if (rank.prefix != null && !rank.prefix.isBlank()) {
                return rank.weight;
            }
        }
        return order.isEmpty() ? 0 : order.get(0).weight;
    }
```

- [ ] **Step 5: Compilar**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/PermissionResolver.java
git commit -m "feat(permissions): resolver de display usa el rango de disfraz (permisos intactos)"
```

---

### Task 5: Nombre falso en chat y en la lista TAB (`NextNodesPermissions`)

**Files:**
- Modify: `src/main/java/com/nextnodes/permissions/NextNodesPermissions.java`

- [ ] **Step 1: `composeFullName` usa el nombre de disfraz**

Reemplaza el método `composeFullName` (líneas ~391-397) por:

```java
    private Component composeFullName(UUID uuid, String baseName) {
        String shown = baseName;
        try {
            PermissionModels.UserEntry user = this.store.snapshot().users.get(uuid.toString());
            if (user != null && user.disguiseName != null && !user.disguiseName.isBlank()) {
                shown = user.disguiseName;
            }
        } catch (Exception ignored) {
        }
        return PrefixFormatter.composeName(
                this.resolver.resolvePrefix(uuid),
                shown,
                this.resolver.resolveSuffix(uuid),
                this.resolver.resolveTag(uuid));
    }
```

- [ ] **Step 2: Handler del nombre de TAB**

Justo después del método `composeFullName` (y antes del comentario `// Intentionally NOT overriding PlayerEvent.NameFormat`), añade un nuevo handler:

```java
    @net.neoforged.bus.api.SubscribeEvent
    public void onTabListNameFormat(net.neoforged.neoforge.event.entity.player.PlayerEvent.TabListNameFormat event) {
        if (this.store == null) {
            return;
        }
        UUID uuid = event.getEntity().getUUID();
        PermissionModels.UserEntry user = this.store.snapshot().users.get(uuid.toString());
        if (user != null && user.disguiseName != null && !user.disguiseName.isBlank()) {
            event.setDisplayName(composeFullName(uuid, user.disguiseName));
        }
    }
```

Notas:
- Este método se auto-registra: la clase ya está suscrita al event bus (los demás `@SubscribeEvent` de esta clase, como `onServerChat`, funcionan sin registro extra).
- `PlayerEvent.TabListNameFormat` está confirmado presente en NeoForge 21.1.172; `event.getEntity()` es el `Player`, `event.setDisplayName(Component)` fija el nombre del TAB.
- El refresco existente (`refreshPlayerName` → `player.refreshTabListName()`) redispara este evento cuando el disfraz cambia desde la web, así que no hace falta enviar paquetes a mano.

- [ ] **Step 3: Compilar**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/nextnodes/permissions/NextNodesPermissions.java
git commit -m "feat(tab): nombre de disfraz en chat y lista TAB"
```

---

### Task 6: UI del disfraz en el panel web (`app.js`)

**Files:**
- Modify: `src/main/resources/web/app.js`

- [ ] **Step 1: Sección "Disfraz" en el editor de jugador**

En `userEditor(u)`, después del cierre de la sección "Rangos asignados" (el `</div></div>` que cierra ese `editorSection`, justo antes del `<input type="hidden" id="user_command_rules_json" ...>`), inserta una sección nueva:

```javascript
        <div class="editorSection"><div class="sectionHeader"><h3><span class="sectionIcon"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></svg></span> Disfraz (solo visual)</h3><span class="muted">TAB y chat</span></div>
          <div class="sectionBody"><div class="muted" style="font-size:12px">Cambia cómo te ven otros en la lista TAB y el chat, sin tocar tus permisos. Sobre la cabeza sigue el nombre real.</div><div class="editorFields cols-2"><label>Nombre falso<input id="user_disguise_name" value="${escapeAttr(u.disguiseName||'')}" placeholder="(vacío = tu nombre real)"></label><label>Rango visible<select id="user_disguise_rank"><option value="">(ninguno — tu rango real)</option>${Object.values(state.ranks||{}).map(r=>`<option value="${escapeAttr(r.name)}" ${u.disguiseRank===r.name?'selected':''}>${escapeHtml(r.displayName||r.name)}</option>`).join('')}</select></label></div></div></div>
```

- [ ] **Step 2: Incluir los campos en `readUserEditor`**

En `readUserEditor(original)`, añade `disguiseName` y `disguiseRank` al objeto devuelto (después de `online:!!original.online`):

```javascript
      return {uuid:document.getElementById('user_uuid').value, name:document.getElementById('user_name').value, tag:document.getElementById('user_tag').value, primaryRank:primary, ranks, rankExpiries, permissions:[...userModalCommandRules(), ...readRules('user')], meta:readJsonBox('user_meta'), lastSeen:original.lastSeen||Date.now(), online:!!original.online, disguiseName:document.getElementById('user_disguise_name')?.value||'', disguiseRank:document.getElementById('user_disguise_rank')?.value||''};
```

- [ ] **Step 3: Estado de demo (`bootDemo`)**

En el objeto `state` de `bootDemo()`, en el usuario `'abc-123'` (Steve), añade tras `meta:{}` los campos de disfraz para probar la precarga (opcional pero útil para verificación):

```javascript
'abc-123': { uuid:'abc-123', name:'Steve', online:true, ranks:['admin'], primaryRank:'admin', permissions:[], meta:{}, disguiseName:'Herobrine', disguiseRank:'vip' },
```

(Reemplaza la línea existente de `'abc-123'` por esta; el resto del objeto demo queda igual.)

- [ ] **Step 4: Verificación de sintaxis**

Run: `node --check src/main/resources/web/app.js`
Expected: sin salida (sintaxis válida)

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/web/app.js
git commit -m "feat(web): sección de disfraz (nombre y rango visibles) en el editor de jugador"
```

---

### Task 7: Build final y verificación

**Files:** (ninguno nuevo)

- [ ] **Step 1: Build completo del mod (incluye tests)**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, `DisguiseResolverTest` en verde junto a los tests existentes.

- [ ] **Step 2: Confirmar recursos web embebidos en el jar**

Run: `unzip -p build/libs/nextnodes_permissions-1.0.0.jar web/app.js | grep -c "user_disguise_name"`
Expected: un número ≥ 1.

- [ ] **Step 3: Verificación en navegador (panel demo)**

Levantar el panel demo (`preview_start` con `nextnodes-web`), entrar en modo demo, abrir el editor del usuario Steve y confirmar por DOM: la sección "Disfraz" existe, el nombre falso ("Herobrine") y el rango ("vip") aparecen precargados, y `readUserEditor(state.users['abc-123'])` incluye `disguiseName:"Herobrine"` y `disguiseRank:"vip"` en su salida.

- [ ] **Step 4: Checklist de verificación en vivo (usuario, en su servidor)**

1. Desplegar el jar del mod (`compilar.bat` → `mods/`), reiniciar el servidor.
2. Desde el panel web: editar tu jugador, poner un nombre falso y un rango visible distinto, guardar.
3. En el juego: la **lista TAB** muestra el nombre y rango falsos, y bajas en el orden si el rango falso pesa menos. El **chat** sale con el nombre y rango falsos. Sobre tu **cabeza** sigue tu nombre real (Fase 1).
4. Confirmar que **conservas admin**: ejecuta un comando de op (ej. `/gamemode`) — debe funcionar.
5. Quitar el disfraz (vaciar el nombre y poner rango "(ninguno)"), guardar → vuelves a tu identidad real en TAB y chat.
6. **Nota Velocity:** si el nombre del TAB no cambia (el proxy gestiona su propia lista), avísame; el chat, el orden y el rango cosmético seguirán funcionando y ajustamos esa pieza.

---

## Self-review

- **Cobertura de la spec:** separación apariencia/función (Task 1 `DisguiseResolver` + Task 4 resolver de display, permisos intactos); campos de datos (Task 2); persistencia (Task 3); nombre falso en chat + TAB vía `TabListNameFormat` (Task 5); orden del TAB por peso falso (Task 4 `resolveWeight`); tag oculto con disfraz (Task 4 Step 3); UI web (Task 6); build + verificación incl. nota Velocity (Task 7). Todo cubierto.
- **Placeholders:** ninguno; cada paso trae el código completo.
- **Consistencia de tipos:** `DisguiseResolver.displayRank(UserEntry, Map<String,Rank>)` se usa con esa firma exacta en `PermissionResolver` (Task 4) y se testea igual (Task 1). Campos `disguiseName`/`disguiseRank` (String) coherentes entre `UserEntry` (Task 2), `PermissionStore` (Task 3), resolver/eventos (Task 4-5) y web (Task 6).
- **Dependencia de orden:** la Task 1 referencia `user.disguiseRank`; se anota explícitamente que el campo (Task 2 Step 1) debe existir para compilar. Al ejecutar en orden, el implementador de la Task 1 añade ese campo como parte de su tarea.
