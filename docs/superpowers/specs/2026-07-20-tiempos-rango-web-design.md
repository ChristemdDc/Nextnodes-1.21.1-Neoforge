# Diseño — Gestión de tiempos de rango por jugador desde la web

- **Fecha:** 2026-07-20
- **Estado:** Aprobado (brainstorming)
- **Componente:** Panel web del mod (`app.js`) únicamente. Cero cambios en Java y cero en el plugin.

## Problema

El sistema de expiración de rangos ya existe (`UserEntry.rankExpiries`: rango→epoch ms; barrido cada 60s que quita los vencidos; comando `nngrant <jugador> <rango> <días>`). Pero solo se maneja por comando: la web no permite ver ni ajustar el tiempo de un rango.

Además, hay un **bug de pérdida de datos latente**: `readUserEditor()` no incluye `rankExpiries`, y como el guardado reemplaza el documento completo del jugador, **editar cualquier jugador desde la web hoy le borra los tiempos de expiración existentes** (p. ej. los que puso Tebex).

## Solución

Exponer `rankExpiries` en el editor de jugador. El backend ya sabe persistirlo (`userToDoc`/`docToUser`, y GSON lo deserializa del PUT), así que es un cambio **solo de frontend** (`app.js`): renderizar los tiempos y enviarlos en el PUT. Esto arregla el bug de paso.

## Decisión (brainstorming)

**Formato del tiempo: ambas — duración o fecha.** Cada rango con tiempo se ajusta por duración desde ahora (cantidad + unidad días/horas) o cambiando a fecha/hora exacta. Se muestra siempre la fecha de vencimiento resultante y cuánto falta.

## UI (editor de jugador → "Rangos asignados")

Se reemplaza el campo de texto "Rangos adicionales (coma)" por una **lista gestionada**, una fila por rango:
- Nombre del rango.
- Selector **Permanente / Con tiempo**.
- Si es "con tiempo": subselector **Duración / Fecha**; en duración, cantidad + unidad (días/horas); en fecha, un `datetime-local`. Debajo, en gris, "Expira: <fecha> · <en N días>".
- Botón **X** para quitar el rango.
- Debajo de la lista: desplegable de rangos disponibles + "Añadir rango" (nace permanente).

El selector "Rango primario" se mantiene igual (solo marca cuál de los asignados es el principal). Si un rango asignado ya traía tiempo (Tebex/comando), la fila arranca en "Con tiempo" → modo Fecha con esa fecha precargada (es un valor absoluto guardado).

## Flujo de datos

`readUserEditor()` recorre las filas y arma:
- `ranks`: los rangos presentes (más el primario si no estuviera, como hoy).
- `rankExpiries`: solo los rangos con tiempo → epoch ms. Duración = `Date.now() + cantidad·unidad` (recalculado al guardar). Fecha = `new Date(valor).getTime()`.

Quitar un rango elimina su fila y por tanto su tiempo (sin entradas huérfanas). Al enviar `rankExpiries` en el PUT, editar un jugador ya no borra los tiempos.

## Qué NO cambia

El barrido de 60s, el comando del plugin, el modelo de datos y la persistencia siguen igual. Un rango vencido se comporta idéntico venga de la web, del comando o de Tebex.

## Pruebas

Verificación en el navegador (como en la pestaña Ajustes), interceptando el PUT:
- Una fila "3 días" produce `rankExpiries[rango] ≈ now + 3d`.
- Cambiar a modo Fecha usa esa fecha exacta.
- Quitar un rango elimina su entrada de `ranks` y de `rankExpiries`.
- **Regresión del bug:** editar un jugador con tiempos existentes y guardar los conserva.

Sin tests Java (solo `app.js`, sin lógica pura nueva del lado servidor).

## Fuera de alcance (YAGNI)

- Vista global de "rangos por vencer" (queda en el editor por jugador).
- Notificar al jugador al vencer (ya lo maneja el refresco del mod).
- Tiempo en el editor de rango (los tiempos son por jugador, no por definición de rango).
