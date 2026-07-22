# Diseño — Catálogo de etiquetas (post-nombre) asignables por jugador

- **Fecha:** 2026-07-21
- **Estado:** Aprobado (brainstorming)
- **Componentes:** Mod NeoForge (modelo, persistencia, resolver, render) + panel web (`app.js`). Sin cambios en el plugin.

## Problema

El admin quiere **crear etiquetas reutilizables** y **asignárselas a un jugador sin modificar su rango**. La etiqueta aparece **después del nombre**: `[Rango] Nombre [sufijoRango] [Etiqueta]`.

Hoy existe un campo `tag` por jugador (texto libre después del nombre), pero no hay catálogo: hay que escribirlo a mano cada vez y no se reutiliza.

## Solución

Un **catálogo de etiquetas** con nombre + texto (con códigos de color), guardado en Mongo (colección `labels`, mismo patrón que `ranks`). En el editor de jugador se elige una etiqueta del catálogo (referencia por nombre), y se renderiza después del nombre. Cambiar el texto de una etiqueta en el catálogo se refleja en todos los jugadores que la tengan (es referencia, no copia).

## Decisiones (brainstorming)

- **Forma:** catálogo reutilizable + asignar desde una lista (no texto libre por jugador).
- **Posición:** después del nombre (tras el sufijo del rango y el `tag` existente): `[prefijo] Nombre [sufijoRango] [tag] [etiqueta]`.
- **El `tag` existente se conserva** (texto libre, compatibilidad); la etiqueta es un slot adicional alimentado por el catálogo.

## Modelo de datos

### `PermissionModels.Label` (nueva clase)
```java
public static final class Label {
    public String name = "";   // id en minúscula
    public String text = "";   // texto con códigos de color (& o &#RRGGBB)
    public void sanitize() { name = normalizeName(name); if (text == null) text = ""; }
}
```

### `PermissionData`
`public Map<String, Label> labels = new LinkedHashMap<>();` + guard/sanitize en `ensureDefaults()`.

### `UserEntry`
`public String label = "";` (referencia al nombre de una etiqueta del catálogo; "" = ninguna). Saneado en `sanitize()` con `normalizeName`.

## Persistencia (`PermissionStore`)

- Colección `labels`: `labelToDoc`/`docToLabel` (`{_id:name, text}`), leídos en `readAll()`.
- `saveLabel(Label)` / `deleteLabel(name)` (replaceOne/deleteOne, `fireChanged()`, `publishEvent("label", name)`), mismo patrón que `saveRank`/`deleteRank`.
- `userToDoc`/`docToUser`: persistir `user.label`.

## Resolver (`PermissionResolver`)

`resolveLabel(UUID)`: devuelve el `text` de la etiqueta referenciada por `user.label` si existe en `data.labels`; si no, "". **Con disfraz activo devuelve ""** (no revelar identidad, igual que `resolveTag`).

## Render (`NextNodesPermissions` + `TabListManager`)

La etiqueta se añade después del `tag`:
- `TabListManager.buildSuffix(...)`: hoy compone `sufijo + tag`; añadir `+ etiqueta` (resolver `resolveLabel`).
- `composeFullName(...)` (chat): incluir la etiqueta tras el tag.

## Web API (`WebPanelServer`)

- `GET /api/state` ya serializa `PermissionData` → el catálogo `labels` llega solo.
- `PUT /api/labels/<name>` (crear/editar) y `DELETE /api/labels/<name>`, con auditoría (`label.save`/`label.delete`), mismo patrón que `/api/ranks/<name>`.

## Web UI (`app.js`)

- **Nueva pestaña o sección "Etiquetas"** (en la pestaña Ajustes, junto al resto): lista de etiquetas con nombre + texto + vista previa de color, botones crear/editar/eliminar (editor simple: id + texto con la misma paleta de códigos de color que el prefijo de rango).
- **Editor de jugador:** un selector "Etiqueta" (desplegable del catálogo, vacío = ninguna) que fija `user.label`. Se incluye en `readUserEditor`.

## Pruebas

- **Unit (JUnit, pura):** `LabelResolverTest` o extender — la decisión pura "dado user.label + catálogo → texto o ''". (Si se puede aislar como en `DisguiseResolver`.)
- **Navegador:** la sección Etiquetas crea/edita/borra; el selector del editor de jugador fija `user.label` en el PUT.
- **En vivo (usuario):** crear una etiqueta, asignarla a un jugador, ver `[Rango] Nombre [Etiqueta]` en TAB y chat sin cambiar el rango.

## Fuera de alcance (YAGNI)

- Asignar etiqueta por comando (solo web).
- Varias etiquetas por jugador (una sola).
- Permisos para "comprar" etiquetas (eso lo haría Tebex vía el plugin, futuro).
