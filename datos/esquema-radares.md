# Esquema de los ficheros publicados

Borrador inicial (fase 3), alineado con el plan del 14/11/2026. Se ajusta cuando se escriba el proceso de datos y se anota el cambio en el plan.

La app **no usa el rumbo de la brújula**: ubica el coche sobre la carretera y deduce el sentido por cómo avanza a lo largo del tramo de aproximación de cada radar.

## `salida/radares.json`

```json
{
  "version": "2026-11-14T06:00:00Z",
  "radares": [ { "...": "ver campos" } ]
}
```

Campos de cada radar:

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | texto | Identificador estable. Prefijo de la fuente: `dgt-`, `eus-`, `man-` |
| `tipo` | texto | `fijo`, `tramo` o `fotorrojo` |
| `lat`, `lon` | número | Coordenadas WGS84 (en tramos, punto de inicio) |
| `lat_fin`, `lon_fin` | número o nulo | Fin del tramo; solo en `tramo` |
| `carretera` | texto | Por ejemplo `A-8`, `BI-633` |
| `pk` | número o nulo | Punto kilométrico, en km |
| `sentido` | texto o nulo | Sentido oficial tal como lo da la fuente (destino, por ejemplo `CANTABRIA`, o creciente/decreciente) |
| `aproximacion` | lista de `[lat, lon]` o nulo | Línea de la carretera por la que se llega al radar en el sentido que vigila (del orden de 1 a 2 km antes del radar), en el orden de la marcha. Termina en el radar. Geometría de OpenStreetMap |
| `aproximacion_fiab` | texto | `alta`, `dudosa` o `manual`. Con `dudosa` o sin `aproximacion`, la app avisa por cercanía en cualquier sentido |
| `limite` | entero o nulo | Límite en km/h. Nulo si no se conoce: la app avisa sin límite |
| `limite_origen` | texto o nulo | `oficial`, `manual` u `osm`. Prioridad: oficial, después manual, después OpenStreetMap |
| `nombre` | texto | Nombre o ubicación |
| `fuente` | texto | `dgt`, `trafikoa` o `manual` |

## `salida/estado.json`

```json
{
  "generado": "2026-11-14T06:00:00Z",
  "ok": true,
  "avisos": [],
  "fuentes": {
    "dgt":      { "ok": true, "radares": 0, "fecha_fuente": null, "usada_copia": false },
    "trafikoa": { "ok": true, "radares": 0, "fecha_fuente": null, "usada_copia": false },
    "manual":   { "ok": true, "radares": 0 }
  }
}
```

- `ok` es `false` si alguna fuente falló o dio cifras raras; el motivo va en `avisos`.
- La app avisa al usuario si `ok` es `false` o si `generado` tiene más de un mes.
- `generado` cambia en cada ejecución, así que cada ejecución deja un cambio en el repositorio.

## `manual/anadidos.json`

Radares que no están en las listas oficiales (por ejemplo, municipales) y revisiones a mano (tramo de aproximación dudoso confirmado, límite que falte). Mismo formato de radar que `radares.json`, más un campo opcional `completa` con el `id` del radar oficial al que rellena un dato.

**Regla (Unai, 14/11): ante la duda prevalece lo oficial.** Lo manual y OpenStreetMap solo rellenan lo que la fuente oficial no da; nunca corrigen un dato oficial. Si Unai quiere contradecir una fuente oficial, se decide y se anota en el plan.
