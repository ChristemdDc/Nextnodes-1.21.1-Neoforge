# Catálogo de etiquetas — Implementation Plan

> Ejecutado inline en esta sesión (superpowers:executing-plans). Solo mod + app.js; recompila el mod.

**Goal:** Crear etiquetas reutilizables y asignarlas por jugador (aparecen después del nombre) sin tocar el rango.

**Arquitectura:** Colección Mongo `labels` (id + texto). `UserEntry.label` referencia una etiqueta. El resolver la resuelve a texto y se renderiza tras el `tag` en TAB y chat. Web: sección de catálogo + selector en el editor de jugador.

---

### Task 1: Modelo (`Label`, `PermissionData.labels`, `UserEntry.label`)
- `PermissionModels.java`: clase `Label {String name=""; String text=""; void sanitize(){name=normalizeName(name); if(text==null)text="";}}`. En `PermissionData`: `public Map<String,Label> labels = new LinkedHashMap<>();` + en `ensureDefaults()` guard `if(labels==null)labels=new LinkedHashMap<>();` y `labels.values().forEach(Label::sanitize);`. En `UserEntry`: `public String label="";` + en `sanitize()`: `this.label = normalizeName(this.label);`.
- Compilar. Commit.

### Task 2: Persistencia (`PermissionStore`)
- `COL_LABELS="labels"`. `labelToDoc(Label)` = `new Document("_id",l.name).append("text",l.text)`. `docToLabel(Document)` inverso.
- `readAll()`: `for(Document d: col(COL_LABELS).find()){ Label l=docToLabel(d); pd.labels.put(l.name,l); }`.
- `saveLabel(Label l)`: sanitize, writeLock, `col(COL_LABELS).replaceOne(eq("_id",l.name), labelToDoc(l), UPSERT)`, `data.labels.put`, cache=null, unlock, `fireChanged()`, `publishEvent("label",l.name)`. `deleteLabel(String name)`: `col(COL_LABELS).deleteOne(eq("_id",name))`, `data.labels.remove`, fireChanged, publishEvent.
- `userToDoc`: `.append("label", user.label)`. `docToUser`: `user.label = doc.getString("label");`.
- Compilar. Commit.

### Task 3: Resolver (`PermissionResolver.resolveLabel`)
```java
public String resolveLabel(UUID uuid) {
    PermissionData data = this.store.snapshot();
    UserEntry user = data.users.get(uuid.toString());
    if (user == null || user.label == null || user.label.isBlank()) return "";
    if (DisguiseResolver.displayRank(user, data.ranks) != null) return ""; // oculto con disfraz
    Label l = data.labels.get(user.label);
    return l == null || l.text == null ? "" : l.text;
}
```
Compilar. Commit.

### Task 4: Render (composeName + buildSuffix)
- `PrefixFormatter.composeName`: añadir 5º parámetro `String label`, y tras el bloque del tag, un bloque idéntico para `label` (space + format(label) si no blank).
- `NextNodesPermissions.composeFullName`: pasar `this.resolver.resolveLabel(uuid)` como 5º arg (respetando el disguiseName ya existente para `shown`).
- `TabListManager.buildSuffix(suffix, tag)` → añadir param `label`; tras el tag, append label. `assign(...)` pasa `this.resolver.resolveLabel(uuid)`.
- Compilar. Commit.

### Task 5: Web API (`WebPanelServer`)
- Tras las rutas de `/api/ranks/`: `PUT /api/labels/<name>` (GSON→Label, `store.saveLabel`, audit `label.save`) y `DELETE /api/labels/<name>` (`store.deleteLabel`, audit `label.delete`). `/api/state` ya incluye `labels`.
- Compilar. Commit.

### Task 6: Web UI (`app.js`)
- Sección "Etiquetas" en la pestaña Ajustes (`renderSettings`): lista de `state.labels` con nombre + vista previa (`prefixHtml`), botón crear/editar (modal simple: id + texto con paleta de color reutilizada) y eliminar (`api('/api/labels/'+name, DELETE)`).
- Editor de jugador: selector `user_label` (desplegable de `state.labels`, vacío="(ninguna)") → `readUserEditor` añade `label:document.getElementById('user_label')?.value||''`.
- Demo state: añadir `labels:{ 'vip_plus':{name:'vip_plus',text:'&b[VIP+]'} }` y a un usuario `label:'vip_plus'`.
- `node --check`. Verificación navegador. Commit.

### Task 7: Build + verificación
- `./gradlew build`. Confirmar jar. Checklist manual: crear etiqueta, asignarla, ver `[Rango] Nombre [Etiqueta]` en TAB/chat sin tocar rango.
