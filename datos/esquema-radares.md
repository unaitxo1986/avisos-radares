# Esquema de los ficheros publicados

Formato definitivo de 3.5 (15/11/2026). Lo escribe `datos/pipeline/publicar.py` uniendo la lectura de fuentes (3.1), el sentido (3.2), el tramo de aproximación (3.3) y el límite (3.4).

La app **no usa el rumbo de la brújula**: ubica el coche sobre la carretera y deduce el sentido por cómo avanza a lo largo del tramo de aproximación de cada radar. La app solo lee `radares.json` (y `estado.json` para avisar al usuario).

## `salida/radares.json`

```json
{ "version": "2026-11-15T06:00:00Z", "radares": [ { "...": "ver campos" } ] }
```

`version` es el momento de la última publicación **con datos distintos**: si una ejecución no cambia ningún radar, se conserva. El fichero va compacto (sin sangrías).

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | texto | Identificador estable. Prefijo de la fuente: `dgt-`, `eus-`, `man-`, `mun-` (municipales, desde el 18/11/2026), `osm-` (cámaras de OpenStreetMap confirmadas por Unai), `cat-` (radares fijos del Servei Català de Trànsit, fuente `sct`, desde el 19/11/2026: Cataluña no está en el fichero de la DGT; `datos/pipeline/sct.py`), `mun-madrid-` (radares fijos y de tramo municipales de Madrid, fuente `municipal`, `datos/pipeline/madrid.py`), `fr-` (cámaras de semáforo en rojo de listas oficiales de ayuntamientos de España, desde el 19/11/2026: `fr-madrid-`, `fr-malaga-`, `fr-santacruz-`; fuente `municipal`, tipo `fotorrojo`, `datos/pipeline/fotorrojo.py`) |
| `tipo` | texto | `fijo`, `tramo` o `fotorrojo` (cámara de semáforo en rojo: no mide la velocidad; la app no enseña límite ni avisa de exceso en ellas, 19/11/2026). (`tramo_extremo` solo si un extremo de tramo de Trafikoa queda sin emparejar; desde la fase 5 los dos de Trabakua salen como dos `tramo`, uno por sentido, con el id del extremo por el que se entra) |
| `nombre` | texto | Nombre o ubicación |
| `fuente` | texto | `dgt`, `trafikoa`, `manual`, `municipal` (publicación de un ayuntamiento, ver abajo) u `osm` (solo en OpenStreetMap, confirmada por Unai: `datos/fuentes/osm_confirmados.json`) |
| `lat`, `lon` | número | WGS84 (en tramos, punto de inicio) |
| `lat_fin`, `lon_fin` | número o nulo | Fin del tramo; solo en `tramo` |
| `carretera` | texto o nulo | Por ejemplo `A-8`, `BI-633` |
| `pk` | número o nulo | Punto kilométrico en **km** |
| `sentido` | texto o nulo | Sentido oficial tal como lo da la fuente: destino (`CANTABRIA`) si lo hay; si no, `creciente`/`decreciente` |
| `sentido_km` | `creciente`, `decreciente` o nulo | Sentido en que crece o decrece el kilometraje, deducido en 3.2 sobre la carretera. Nulo si la carretera es de sentido único o no se pudo resolver. En tramos, sale del orden inicio → fin (`pk` → `pk_fin`). Informativo para la app |
| `aproximacion` | lista de `[lat, lon]` o nulo | Línea de la carretera por la que se llega al radar en el sentido que vigila (**500 m**, decisión de Unai del 15/11/2026), en el orden de la marcha; termina en el radar. Geometría de OpenStreetMap. Solo se publica si `aproximacion_fiab` es `alta` |
| `aproximacion_fiab` | texto o nulo | `alta`, `dudosa` o nulo (no calculada, por ejemplo en tramos). Con `dudosa` o nulo la app avisa por cercanía en cualquier sentido |
| `limite` | entero o nulo | km/h. Nulo si no se conoce: la app avisa sin límite |
| `limite_origen` | texto o nulo | `oficial`, `manual`, `osm` (calzada elegida en 3.2; en tramos, el valor de al menos el 80 % de los puntos muestreados), `osm_cercana` (vía próxima de la misma carretera) `osm_tramo_minimo` (tramo cuyo límite cambia en OpenStreetMap: el más bajo) u `osm_relacion` (tramo sin límite fiable en la carretera: el de su relación de radar de tramo de OpenStreetMap, `datos/fuentes/osm_tramos.json`). Prioridad: oficial, manual, OpenStreetMap |
| `limite_fiab` | texto o nulo | `alta` (oficial o manual), `media` (`osm`) o `baja` (`osm_cercana`). Qué hace la app con `baja` se decide en 4.3 (recomendación: no mostrar el límite) |
| `enlace` | texto o nulo | Enlace a la fuente oficial |
| `pk_fin` | número o nulo | **Solo en tramos.** Punto kilométrico del fin, en km |
| `tramo` | lista de `[lat, lon]` o nulo | **Solo en tramos.** Línea de la carretera desde la cámara de inicio hasta la de fin, en el orden de la marcha (ruta de OSRM sobre OpenStreetMap, simplificada a 3 m). Solo si `tramo_fiab` es `alta`. La app sigue al coche por ella para calcular la velocidad media (fase 5) |
| `tramo_largo` | entero o nulo | **Solo en tramos.** Longitud de esa línea en metros |
| `tramo_fiab` | texto o nulo | **Solo en tramos.** `alta` (la ruta cuadra con el kilometraje oficial), `dudosa` (no hay ruta o no cuadra: la app avisa por cercanía al inicio, sin velocidad media) o nulo (no calculado) |
| `sin_confirmar` | booleano | `true` si el radar solo está en OpenStreetMap y nadie lo ha confirmado (fuente `osm`; desde el 18/11/2026). Puede ser un radar pedagógico. La app los avisa por cercanía, en cualquier sentido, con el mismo criterio que el resto (500 m si tienen `carretera`, 150 m si no), con "sin confirmar" en la ventana (decisión de Unai, 18/11) |
| `rotatoria` | booleano | `true` si el ayuntamiento dice que los radares rotan entre cabinas (la cabina puede estar vacía). Hoy, las 16 cabinas de Vitoria-Gasteiz. La app las avisa como cualquier radar, con "rotatorio" en la ventana (decisión de Unai, 18/11) |

### Radares municipales (`fuente: municipal`, 18/11/2026)

Lector `datos/pipeline/municipales.py`, copia `datos/fuentes/municipales.json`. Solo entran publicaciones oficiales con coordenadas:

- **Vitoria-Gasteiz**, 16 cabinas de radar de velocidad (capa 36 `CABINA_RADAR` de GeoVitoria, leída con la operación `identify` porque `query` no da la geometría), con `rotatoria: true`, y 6 detectores de foto rojo (colección "Foto-rojo" de GeoVitoria, solo copia). Sin límite oficial.
- **Bilbao**, 4 cabinas (avenida Zumalakarregi, dos; Alameda Rekalde, dos). Que existen lo dicen las notas y contratos del Ayuntamiento (2017-2018), que no publican coordenadas: la posición sale de OpenStreetMap (nodos `highway=speed_camera`, campo crudo `posicion: osm`). Solo copia. Sin límite.
- **Donostia / San Sebastián**, 12 radares (capa 41 `Radarra` del servicio `GARRAIOA`, conjunto abierto "wms-transporte"), con límite oficial de 30 o 50 km/h.

No tienen carretera ni punto kilométrico: el sentido sale `no_aplica`, no llevan tramo de aproximación (`aproximacion_fiab` nulo) y la app avisa por cercanía en cualquier sentido.

## `salida/estado.json`

```json
{
  "generado": "2026-11-15T06:00:00Z",
  "ok": true,
  "avisos": [],
  "fuentes": {
    "dgt":      { "ok": true, "radares": 0, "fecha_fuente": null, "usada_copia": false },
    "trafikoa": { "ok": true, "radares": 0, "fecha_fuente": null, "usada_copia": false },
    "manual":   { "ok": true, "radares": 0 },
    "municipal": { "ok": true, "radares": 0, "copia_usada": [] }
  },
  "radares_publicados": 0,
  "contraste": { "trafikoa": 0, "detector_radar": 0 }
}
```

- `ok` es `false` si las cifras salieron raras y **no se publicó**: `radares.json` conserva la última versión buena y el motivo va en `avisos` (empieza por `NO PUBLICADO:`). Comprobaciones: mínimo de radares, variación de más del 10 % frente a lo publicado, ids repetidos, límites fuera de 20-130 km/h, aproximación calculada para al menos el 60 % de los radares y fiabilidad alta sin línea. Si falla la lectura de una fuente (o da cifras fuera de rango), `leer_fuentes.py` termina con error y el proceso conserva todo lo publicado.
- `usada_copia` indica que esa fuente no se pudo leer y se usó la última copia buena de `datos/fuentes/` (`dgt.xml.gz`, `trafikoa.txt`); el motivo va en `avisos`.
- `contraste` compara el número de radares de Trafikoa con el de detector-radar.com (no oficial, no se publican sus datos); una diferencia de más del 15 % añade un aviso. **[sin verificar]** el formato de su respuesta.
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
| `regla_vetada`, `motivo` | Desde la fase 5 (18/11): si la calzada elegida se aleja más de 150° de su destino oficial (en línea recta, destino en España a 15-300 km), el resultado pasa a `ambos` y `regla_vetada` guarda la regla que lo había resuelto. Además, el vecino que fija el eje kilométrico solo vale si la ruta hasta él cuadra con la diferencia de kilometraje (300 m o 20 %) |
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


## Radares de tramo (fase 5): `datos/cache/tramos.json`

Lo escribe `datos/pipeline/calcular_tramos.py` (algoritmo en `tramos.py`, 18/11/2026). Un objeto por tramo: los 47 de la DGT y los dos sentidos de Trabakua (con el id del extremo por el que se entra). Rutas de OSRM en `datos/cache/osrm_tramos.json`.

| Campo | Notas |
|---|---|
| `fiab` | `alta` o `dudosa` |
| `linea`, `largo_m` | Línea del inicio al fin en el orden de la marcha y su longitud (nulas si `dudosa`) |
| `largo_ref_m` | Longitud según el kilometraje oficial (`|pk_fin - pk|`), con la que se valida la ruta: tiene que diferir menos de 300 m o del 15 % |
| `lat_fin`, `lon_fin`, `pk_fin_m` | Fin del tramo (en Trabakua, el otro extremo) |
| `aprox` | Tramo de aproximación al inicio, mismo formato que `aproximacion.json` (3.3) |
| `limite` | `{limite, origen, fiab, muestras, valores}`: oficial si lo hay; si no, `maxspeed` de la misma carretera en un punto por kilómetro de la línea (`valores`: cuántos puntos dan cada valor). Con al menos el 80 % de acuerdo, `osm`; si cambia, el más bajo (`osm_tramo_minimo`). Fiabilidad `media`, o `baja` si menos de la mitad de los puntos tienen dato. Si no hay límite o es `baja`, se rellena con el `maxspeed` de la relación de OpenStreetMap del tramo (`osm_relacion`, `media`); si los dos existen y difieren, manda la carretera y `relacion` guarda el otro valor |
| `motivos` | Por qué es `dudosa` |

**Orden inicio → fin:** el de la geometría de la DGT (`from` → `to`), no el indicador creciente/decreciente, que en 10 de los 47 tramos no cuadra con el kilometraje (son parejas del mismo tramo en los dos sentidos; ver `tramos.py`).

## Límite de cada radar (3.4): `datos/cache/limites.json`

Lo escribe `datos/pipeline/calcular_limites.py` (reglas en `limites.py`), usando solo la caché de OpenStreetMap de 3.2. Un objeto por `id` de radar (los 835; los tramos de la DGT salen sin límite hasta la fase 5). Es la fuente de `limite` y `limite_origen` del fichero publicado (3.5). Campos (15/11/2026):

| Campo | Notas |
|---|---|
| `limite` | km/h, entero entre 20 y 130, o `null` (la app avisa sin límite) |
| `origen` | `oficial` (la fuente del radar), `manual` (revisado a mano), `osm` (`maxspeed` de la calzada que 3.2 eligió para el radar), `osm_cercana` (valor único de las vías a menos de 50 m de la misma carretera) o `null` |
| `fiab` | `alta` (oficial, manual), `media` (osm), `baja` (osm_cercana) o `null` |
| `osm`, `nivel_osm` | Lo que dice OpenStreetMap (`calzada` o `cercanas`) aunque no se use, para medir su fiabilidad |
| `texto` | Texto original de la fuente (`60/80 km/h`, `-`) |
| `nota` | Por ejemplo "la fuente oficial da 60/80 km/h: se usa el más bajo" |
| `discrepa` | `true` si hay límite oficial y OpenStreetMap da otro (el oficial manda) |

Prioridad: oficial, manual, osm, osm_cercana, sin límite. Solo valen `maxspeed` numéricos (los simbólicos como `ES:urban`, `none` y las millas se ignoran). Si dos vías cercanas discrepan no se usa ninguna.
