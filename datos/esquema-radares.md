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
| `aproximacion` | lista de `[lat, lon]` o nulo | Línea de la carretera por la que se llega al radar en el sentido que vigila (**500 m** antes del radar, decisión de Unai del 15/11/2026), en el orden de la marcha. Termina en el radar. Geometría de OpenStreetMap |
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

## Formato intermedio: salida de la lectura de fuentes (3.1)

`datos/pipeline/leer_fuentes.py` escribe un JSON "crudo" (`radares_crudos.json`, aún no publicado) con las tres fuentes unidas, **sin sentido resuelto, sin tramo de aproximación y sin límite de OpenStreetMap**. Lo consumen 3.2 a 3.5, que generan `radares.json` con el formato de arriba. Campos de cada radar (15/11/2026):

| Campo | Notas |
|---|---|
| `id`, `fuente`, `tipo`, `nombre` | `id` con prefijo `dgt-`, `eus-` o `man-`; `fuente` `dgt`, `trafikoa` o `manual`. `tipo`: `fijo`, `tramo`, `fotorrojo` y, solo en Trafikoa, `tramo_extremo` (los dos puntos de Trabakua, con `tramo_grupo` y `tramo_extremo` 1 o 2; emparejarlos en un tramo queda para la fase 5) |
| `lat`, `lon`, `lat_fin`, `lon_fin` | WGS84. En Trafikoa vienen de UTM huso 30 ETRS89. En tramos de la DGT, `from` es el inicio y `to` el fin (el 15/11 se comprobó con el kilometraje: 25 tramos con fin > inicio y 22 con fin < inicio; en los 12 ejemplos mirados coincide con `sentido_oficial`, pero hay 29 crecientes frente a 25 con fin > inicio, así que **4 tramos quedan por revisar** en la fase 5) |
| `carretera`, `pk_m` | `pk_m` en **metros** (entero); en `pk` del fichero publicado serán km. En tramos DGT, `pk_fin_m` es el del fin |
| `sentido_oficial` | `creciente` o `decreciente` (DGT: `positive`/`negative`). En Trafikoa es `null`: da el destino |
| `destino` | Destino oficial (DGT en un tercio de los fijos, Trafikoa siempre) |
| `limite_oficial`, `limite_texto` | Trafikoa: entero si el texto trae un solo número; `60/80` y `-` dan `null` con el texto original en `limite_texto`. DGT: siempre `null` (no publica el límite). `limite_manual` solo en entradas manuales |
| `municipio`, `provincia`, `provincia_ine`, `tipo_via` | Datos de la fuente, tal cual |
| `enlace_oficial` | Enlace a la fuente oficial |
| `completa` | Solo manual: id del radar oficial al que rellena un dato |

Comprobaciones al leer (si falla alguna, no se escribe nada): recuentos esperados (DGT 500-900 fijos y 30-70 tramos; Trafikoa 77-130), coordenadas dentro de la zona, identificadores únicos y estructura de cada fuente.

## Resultado del sentido (3.2): `datos/cache/sentido.json`

Lo escribe `datos/pipeline/calcular_sentido.py` (algoritmo en `sentido.py`, descrito en `prueba-sentido-radares.md` del proyecto). Un objeto por `id` de radar. Las respuestas de los servicios externos se guardan en `datos/cache/` (`osm.json`, `osrm.json`, `nominatim.json`, `cartociudad.json`) y se suben al repositorio: solo se piden los datos nuevos. Campos (15/11/2026):

| Campo | Notas |
|---|---|
| `resultado` | `resuelto`, `ambos` (sin garantías: la app avisa en cualquier sentido), `no_aplica` (los tramos, fase 5) o `pendiente` (solo en modo sin red) |
| `regla` | Por qué se da por bueno: `a` oficial creciente/decreciente con eje conocido; `b` grupo coherente de 2 o más radares; `c` margen de 1 km o más entre las dos calzadas; `d` calzada única; `e` carretera de doble sentido y destino a más de 2 km |
| `origen` | `oficial`, `destino` o `unica` |
| `via`, `rumbo`, `punto` | Vía de OpenStreetMap elegida, rumbo de marcha (grados, solo informativo: la app no compara rumbos) y punto de la calzada más cercano a la cabina |
| `sentido_km` | `creciente` o `decreciente` si se pudo fijar el eje kilométrico; si no, `null` |
| `margen_m`, `destino_a_m`, `candidatas`, `corregido`, `grupo`, `motivo` | Diagnóstico: diferencia de ruta entre calzadas, distancia al destino, número de calzadas candidatas, si la votación de grupo lo corrigió, el grupo y el motivo del "ambos" |

El eje kilométrico se saca de un radar vecino de la misma carretera y territorio; si no lo hay, de un punto de CartoCiudad (IGN) en el kilómetro entero anterior o posterior, validado por carretera y distancia. El tramo de aproximación (3.3, siguiente apartado) y el formato publicado se construyen a partir de este resultado.

## Tramo de aproximación (3.3): `datos/cache/aproximacion.json`

Lo escribe `datos/pipeline/calcular_aproximacion.py` (algoritmo en `aproximacion.py`). Un objeto por `id` de radar fijo o fotorrojo (los tramos y los que no aplican no salen). Es la fuente de `aproximacion` y `aproximacion_fiab` del fichero publicado (3.5). Campos (15/11/2026):

| Campo | Notas |
|---|---|
| `fiab` | `alta` o `dudosa`. Con `dudosa` (o sin `puntos`) la app avisa por cercanía en cualquier sentido |
| `puntos` | Lista `[lat, lon]` en el orden de la marcha, **termina en el punto de la calzada junto a la cabina** (el `punto` de `sentido.json`) y empieza 500 m antes siguiendo la carretera. Simplificada (tolerancia de 2 m). `null` si no hay línea (radar sin sentido fiable o sin datos) |
| `largo_m` | Metros recorridos hacia atrás (500 si la carretera continúa lo suficiente) |
| `tunel_m` | Metros de la línea que van en túnel (etiqueta `tunnel` de OpenStreetMap) |
| `cruces`, `ambiguos` | Cruces con más de una entrada, y cuántos no tenían una continuación clara |
| `motivos` | Por qué es `dudosa` (lista de textos); vacía si es `alta` |

Cómo se construye: se parte de la vía, el rumbo y el punto de `sentido.json` y se retrocede por la red de OpenStreetMap (respetando los sentidos únicos) hasta 500 m. En cada cruce se elige la entrada que mejor continúa la carretera: primero la que comparte la referencia (por ejemplo `A-8`), después la del mismo tipo (enlace o no) y después la de menor giro. **Es `dudosa`** si: hay menos de 450 m de carretera continua (acaba o vuelve sobre sí misma, por ejemplo una rotonda), un cruce tiene dos entradas equivalentes (misma referencia y tipo y giros que difieren menos de 25°) o una que gira más de 100°, o más de 200 m de la línea van en túnel (el GPS no sirve dentro). Los umbrales son criterios propios **[sin verificar]** con uso real (se ajustan en 4.5). Si el rumbo de la calzada no cuadra con el de 3.2 (más de 60°) se anota en `motivos`.

Datos de entrada: recuadro de unos 1,2 km por radar de la API de OpenStreetMap (`osm_aprox.json`, una petición por radar). **Esa respuesta cruda no se versiona** (unos 28 KB por radar, unos 20 MB en total; está en `.gitignore`): vive en la caché de Actions (`actions/cache`), y si se pierde se vuelve a pedir (unos 45 minutos). Lo que se versiona es `aproximacion.json` (unos 175 KB). Las vías se guardan en trozos continuos y con el primer nodo de fuera del radio a cada lado, porque en rectas y túneles los nodos están muy separados.

