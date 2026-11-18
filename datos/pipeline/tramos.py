"""Radares de tramo (fase 5): línea del tramo, aproximación al inicio y límite.

Un radar de tramo mide la velocidad media entre dos cámaras. Para que la app la calcule hace falta:
1. **La línea del tramo**: la carretera desde la cámara de inicio hasta la de fin, en el sentido de la
   marcha. Se pide una ruta a OSRM desde cada calzada candidata junto al inicio (las mismas candidatas
   que en 3.2, una por dirección) hasta el fin, y gana la más corta: desde la calzada equivocada la ruta
   tiene que dar la vuelta y sale más larga. Se acepta si su longitud cuadra con la del kilometraje
   oficial (diferencia de menos de `TOL_ABS_M` o de `TOL_REL`); si no, se repite fijando también la
   calzada de llegada. Si sigue sin cuadrar, el tramo es `dudosa` y la app avisa por cercanía al inicio
   sin velocidad media. Además, la ruta **no puede volver sobre sí misma** (pasar a menos de 30 m de un
   punto suyo que quedó más de 200 m atrás): eso es que OSRM ha puesto el fin en la calzada contraria y la
   ruta da la vuelta para llegar (pasó en Trabakua sentido Markina: 3.220 m en vez de unos 2.815). En ese
   caso también se repite fijando la calzada de llegada. Las tolerancias son criterios propios **[sin verificar]**.
2. **El tramo de aproximación al inicio**: el mismo cálculo de 3.3 (500 m hacia atrás desde la cámara
   de inicio), con la calzada elegida en el paso 1.
3. **El límite**: el oficial si la fuente lo da (Trabakua, 60); si no (la DGT no lo publica), el
   `maxspeed` de OpenStreetMap de la calzada en puntos repartidos cada `PASO_MUESTRA_M` a lo largo de la
   línea. Si al menos el 80 % de los puntos con dato dan el mismo valor, ese (`osm`); si varía, el más
   bajo (`osm_tramo_minimo`), que es el que deja del lado seguro **[sin verificar]** cuál aplica la DGT
   cuando el límite cambia dentro del tramo. Con menos de la mitad de los puntos con dato, fiabilidad
   `baja` (la app no lo muestra).

**Orden inicio → fin.** En la DGT se toma el orden de la geometría (`from` → `to`), no el indicador
creciente/decreciente: en 10 de los 47 tramos ese indicador no cuadra con el orden del kilometraje, y los
10 son parejas que cubren el mismo tramo en los dos sentidos; fiándose del indicador, cada pareja quedaría
con los dos tramos en el mismo sentido. La ruta por carretera confirma el orden (si fuera al revés, su
longitud no cuadraría con el kilometraje). En Trabakua (Trafikoa) la página da los dos extremos como dos
puntos (`tramo_extremo`), cada uno con el destino del sentido que entra por él; se emparejan en dos tramos.

Toda la red entra por `serv` (ver servicios.py), así que se prueba sin red.
"""
from collections import Counter

from . import aproximacion
from .geo import haversine, largo, proyectar, puntos_a_lo_largo, simplificar
from .limites import _misma_carretera, limite_oficial, num_maxspeed
from .sentido import candidatas
from .servicios import Pendiente

TOL_ABS_M = 300.0      # diferencia admitida entre la ruta y el kilometraje...
TOL_REL = 0.20         # ...o esta proporción del largo oficial, lo que sea mayor (N-629: +17 % sin dar vueltas)
VUELVE_LEJOS_M = 200.0 # la ruta "vuelve sobre sí misma" si pasa a menos de VUELVE_CERCA_M de un trozo
VUELVE_CERCA_M = 40.0  # suyo que quedó más de VUELVE_LEJOS_M atrás (vuelta para entrar por la otra calzada)
PASO_MUESTRA_M = 1000.0
DIST_VIA_MUESTRA_M = 25.0
MIN_ACUERDO = 0.8
MIN_COBERTURA = 0.5
SIMPLIFICAR_TRAMO_M = 3.0


def lista_tramos(crudos):
    """Tramos a calcular, cada uno con inicio (`lat`, `lon`), fin (`lat_fin`, `lon_fin`) y `largo_ref_m`.

    Los de la DGT tal cual; los extremos de Trafikoa emparejados por `tramo_grupo` (dos tramos, uno
    por sentido, con el id del extremo por el que se entra).
    """
    out, grupos = [], {}
    for r in crudos:
        if r["tipo"] == "tramo" and r.get("lat_fin") is not None:
            out.append(dict(r, largo_ref_m=_largo_ref(r.get("pk_m"), r.get("pk_fin_m"))))
        elif r["tipo"] == "tramo_extremo" and r.get("tramo_grupo"):
            grupos.setdefault(r["tramo_grupo"], []).append(r)
    for ex in grupos.values():
        if len(ex) != 2:
            continue
        a, b = sorted(ex, key=lambda e: e.get("tramo_extremo") or 0)
        for e, otro in ((a, b), (b, a)):
            out.append(dict(e, tipo="tramo", lat_fin=otro["lat"], lon_fin=otro["lon"], pk_fin_m=otro.get("pk_m"),
                            largo_ref_m=_largo_ref(e.get("pk_m"), otro.get("pk_m"))))
    return out


def _largo_ref(pk, pk_fin):
    return None if pk is None or pk_fin is None else abs(pk_fin - pk)


def _cuadra(dist, ref):
    return ref is None or abs(dist - ref) <= max(TOL_ABS_M, TOL_REL * ref)


def vuelve(linea, lejos=VUELVE_LEJOS_M, cerca=VUELVE_CERCA_M):
    """¿Pasa algún punto de la línea a menos de `cerca` m de un trozo suyo que quedó más de `lejos` m atrás?

    Se mide de punto a segmento, no de punto a punto: la línea está simplificada y en las rectas solo quedan
    los extremos."""
    acum = [0.0]
    for a, b in zip(linea, linea[1:]):
        acum.append(acum[-1] + haversine(a[0], a[1], b[0], b[1]))
    for i in range(len(linea)):
        for j in range(len(linea) - 1):
            if acum[j + 1] > acum[i] - lejos:
                break
            if proyectar(linea[i][0], linea[i][1], linea[j], linea[j + 1])[0] < cerca:
                return True
    return False


def _buena(ruta, ref):
    return _cuadra(ruta[0], ref) and not vuelve(ruta[2]["puntos"])


def _candidatas(punto, serv):
    """Calzadas candidatas junto a un punto. En rectas largas puede no haber ningún nodo de OpenStreetMap
    en el recuadro pequeño de 3.2 (la API solo devuelve vías con algún nodo dentro): se usa el grande de 3.3."""
    c = candidatas(punto, serv.vias_cerca(punto["lat"], punto["lon"]))
    return c or candidatas(punto, serv.vias_aprox(punto["lat"], punto["lon"]))


def _rutas(t, serv, cands, cands_fin):
    """[(dist, candidata de inicio, ruta)] de cada candidata de inicio (y de llegada, si se dan)."""
    fin = (t["lat_fin"], t["lon_fin"])
    res = []
    for c in cands:
        for cf in (cands_fin or [None]):
            if cf is None:
                g = serv.ruta_geom(tuple(c["p"]), c["rumbo"], fin)
            else:
                g = serv.ruta_geom(tuple(c["p"]), c["rumbo"], tuple(cf["p"]), cf["rumbo"])
            if g and len(g["puntos"]) >= 2:
                res.append((g["dist"], c, g))
    return res


def elegir_linea(t, serv):
    """(candidata de inicio, línea, motivos). Línea None si no hay una ruta buena."""
    cands = _candidatas(t, serv)
    if not cands:
        return None, None, ["sin carretera de OpenStreetMap a menos de 50 m del inicio"]
    ref = t.get("largo_ref_m")
    rutas = _rutas(t, serv, cands, None)
    buenas = [x for x in rutas if _buena(x, ref)]
    if not buenas:
        fin = {"lat": t["lat_fin"], "lon": t["lon_fin"], "carretera": t.get("carretera")}
        cands_fin = _candidatas(fin, serv)
        if cands_fin:
            rutas += _rutas(t, serv, cands, cands_fin)
            buenas = [x for x in rutas if _buena(x, ref)]
    if buenas:
        dist, c, g = min(buenas, key=lambda x: x[0])
        return c, simplificar(g["puntos"], SIMPLIFICAR_TRAMO_M), []
    if not rutas:
        return None, None, ["OSRM no da ruta entre el inicio y el fin"]
    dist, c, g = min(rutas, key=lambda x: x[0])
    if not _cuadra(dist, ref):
        return c, None, [f"la ruta mide {dist} m y el kilometraje oficial {ref} m"]
    return c, None, [f"la ruta ({dist} m) vuelve sobre sí misma"]


def muestras(linea, paso=PASO_MUESTRA_M):
    """Puntos repartidos a lo largo de la línea: en el centro de cada trozo de `paso` metros."""
    total = largo(linea)
    n = max(1, round(total / paso))
    return puntos_a_lo_largo(linea, [total * (i + 0.5) / n for i in range(n)])


def _maxspeed_en(t, lat, lon, vias):
    """`maxspeed` numérico de la vía de la misma carretera más cercana al punto (o None)."""
    mejor = None
    for v in vias:
        if not _misma_carretera(t, v):
            continue
        d = min(proyectar(lat, lon, a, b)[0] for a, b in zip(v["n"], v["n"][1:]))
        if d <= DIST_VIA_MUESTRA_M and (mejor is None or d < mejor[0]):
            mejor = (d, v)
    return num_maxspeed(mejor[1]["t"].get("maxspeed")) if mejor else None


def limite_tramo(t, linea, serv):
    """{'limite', 'origen', 'fiab', 'muestras'} del tramo."""
    oficial, nota = limite_oficial(t)
    if oficial:
        return {"limite": oficial, "origen": "oficial", "fiab": "alta", "nota": nota}
    if not linea:
        return {"limite": None, "origen": None, "fiab": None}
    pts = muestras(linea)
    valores = []
    for lat, lon in pts:
        vias = serv.vias_cerca(round(lat, 5), round(lon, 5)) or serv.vias_aprox(round(lat, 5), round(lon, 5))
        n = _maxspeed_en(t, lat, lon, vias)
        if n:
            valores.append(n)
    cuenta = Counter(valores)
    base = {"muestras": len(pts), "valores": dict(sorted(cuenta.items()))}
    if not valores:
        return dict(base, limite=None, origen=None, fiab=None)
    fiab = "media" if len(valores) >= MIN_COBERTURA * len(pts) else "baja"
    moda, n = cuenta.most_common(1)[0]
    if n >= MIN_ACUERDO * len(valores):
        return dict(base, limite=moda, origen="osm", fiab=fiab)
    return dict(base, limite=min(valores), origen="osm_tramo_minimo", fiab=fiab)


def calcular_uno(t, serv):
    """Resultado de un tramo (ver datos/esquema-radares.md, `datos/cache/tramos.json`)."""
    base = {"lat_fin": t["lat_fin"], "lon_fin": t["lon_fin"], "pk_fin_m": t.get("pk_fin_m"),
            "largo_ref_m": t.get("largo_ref_m")}
    try:
        c, linea, motivos = elegir_linea(t, serv)
        res = dict(base, fiab="alta" if linea else "dudosa", linea=linea, motivos=motivos,
                   largo_m=round(largo(linea)) if linea else None)
        res["aprox"] = None
        if c is not None and linea:
            res["aprox"] = aproximacion.construir(
                t, {"via": c["via"], "rumbo": c["rumbo"], "punto": c["p"]}, serv.vias_aprox(t["lat"], t["lon"]))
        res["limite"] = limite_tramo(t, linea, serv)
    except Pendiente as e:
        return dict(base, fiab="dudosa", linea=None, aprox=None, limite=limite_tramo(t, None, serv),
                    motivos=[f"falta en la caché: {e}"])
    except OSError as e:  # sin respuesta de un servicio: no se guarda en caché, se reintenta en la siguiente
        return dict(base, fiab="dudosa", linea=None, aprox=None, limite=limite_tramo(t, None, serv),
                    motivos=[f"servicio sin respuesta: {str(e)[:80]}"])
    return res


def calcular(crudos, serv, ids=None):
    return {t["id"]: calcular_uno(t, serv) for t in lista_tramos(crudos) if ids is None or t["id"] in ids}


def distancia_fin(res):
    """Distancia en metros entre el final de la línea y la cámara de fin (comprobación)."""
    if not res.get("linea"):
        return None
    p = res["linea"][-1]
    return round(haversine(p[0], p[1], res["lat_fin"], res["lon_fin"]))
