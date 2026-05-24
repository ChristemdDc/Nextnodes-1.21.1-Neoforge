# Pendientes

## Bug: Inconsistencia entre tab list y tableta para jugadores con primaryRank="default"

### Descripción
`rankOrder()` en `PermissionResolver.java` empieza siempre por el campo `primaryRank` de la DB antes de ordenar por peso. Si ese campo sigue siendo `"default"` (porque la web/comandos añaden rangos a `user.ranks` sin actualizar `primaryRank`), el stable sort con peso igual deja a `default` primero → el tab muestra el prefix de `default` aunque el jugador tenga un rango mejor.

`getPlayerPrimaryRank()` en `NextNodesAPI.java` sí ignora `primaryRank` y devuelve el de mayor peso no-default. Ambos métodos son inconsistentes entre sí.

### Ejemplo
- camapruebas: `primaryRank="default"`, `ranks=["default","ingeniero"]`, ambos `weight=0`
- `getPlayerPrimaryRank()` → `"ingeniero"` ✅ (se ve bien en tableta)
- `resolvePrefix()` → prefix de `"default"` ❌ (se ve mal en tab list)

### Solución propuesta
En `rankOrder()`, si `primaryRank` es igual al rango por defecto (`data.defaultRank`) pero existen otros rangos en `user.ranks`, no darle prioridad especial — simplemente incluirlo como un rango más y dejar que el peso decida. Esto haría que ambos métodos sean consistentes.

Alternativa más robusta: al asignar un rango desde la web o comandos, actualizar también el campo `primaryRank` en MongoDB al rango de mayor peso.

### Archivos relevantes
- `PermissionResolver.java` → método `rankOrder()` (línea ~170)
- `NextNodesAPI.java` → método `getPlayerPrimaryRank()` (línea ~76)
