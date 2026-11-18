"""Tramo de aproximación de cada radar (3.3).

A partir de la calzada y el sentido ya elegidos en 3.2 (`sentido.json`: vía de OpenStreetMap,
rumbo de marcha y punto de la calzada junto a la cabina), recorre la carretera **hacia atrás**
desde el radar hasta `LARGO_M` metros (500, decisión de Unai del 15/11/2026) y devuelve esa
línea en el orden de la marcha, terminando en el radar.

La app no compara rumbos: comprueba si el coche está sobre esta línea y avanza a lo largo
de ella hacia el radar (ver plan, "Cómo decide la app").

Toda la red entra por la lista de vías que da `Servicios.vias_aprox`, así que se prueba sin red.

Fiabilidad:
- `alta`: se recorrieron al menos `MIN_ALTA_M` metros, todos los cruces se resolvieron sin
  ambigüedad y no hay un tramo largo de túnel.
- `dudosa`: lo contrario; la app avisa entonces por cercanía en cualquier sentido.
Los umbrales son criterios propios, **[sin verificar]** contra uso real (se ajustan en 4.5).
"""
import math

from .geo import dif_ang, haversine, proyectar, rumbo as rumbo_entre, simplificar
from .sentido import norm_ref, sentido_unico

LARGO_M = 500.0        # longitud del tramo de aproximación
MIN_ALTA_M = 450.0     # menos que esto de carretera continua: dudosa
GIRO_AMBIGUO = 25.0    # dos entradas equivalentes cuyo giro difiere menos de esto: ambiguo
GIRO_MAX = 100.0       # entrada con un giro mayor: tampoco es una continuación creíble
TUNEL_DUDOSO_M = 200.0 # más túnel que esto en la línea: dudosa (el GPS no sirve dentro)
SIMPLIFICAR_M = 2.0    # tolerancia de la simplificación de la línea
RUMBO_TOL = 60.0       # diferencia máxima entre el rumbo de 3.2 y el de la arista elegida


class _Arista:
    __slots__ = ("o", "d", "via")

    def __init__(self, o, d, via):
        self.o, self.d, self.via = o, d, via


def _aristas_que_entran(vias):
    """{nodo: [aristas que terminan en él]}, respetando los sentidos únicos."""
    entran = {}
    for v in vias:
        u = sentido_unico(v["t"])
        pts = [tuple(p) for p in v["n"]]
        for a, b in zip(pts, pts[1:]):
            if a == b:
                continue
            if u >= 0:
                entran.setdefault(b, []).append(_Arista(a, b, v))
            if u <= 0:
                entran.setdefault(a, []).append(_Arista(b, a, v))
    return entran


def _refs(v):
    return {norm_ref(x) for x in (v["t"].get("ref") or "").split(";")} - {""}


def _es_enlace(v):
    return (v["t"].get("highway") or "").endswith("_link")


def _es_tunel(v):
    return (v["t"].get("tunnel") or "no") not in ("no", "")


def _arista_inicial(punto, rumbo, via_id, vias):
    """Arista (origen, destino, vía) de la calzada elegida, en el sentido de la marcha."""
    mejor = None
    for v in vias:
        if v["id"] != via_id:
            continue
        pts = [tuple(p) for p in v["n"]]
        for a, b in zip(pts, pts[1:]):
            if a == b:
                continue
            d, p, rb = proyectar(punto[0], punto[1], a, b)
            if mejor is None or d < mejor[0]:
                mejor = (d, p, a, b, v)
    if mejor is None:
        return None
    d, p, a, b, v = mejor
    u = sentido_unico(v["t"])
    if u == 1:
        adelante = True
    elif u == -1:
        adelante = False
    else:
        adelante = dif_ang(rumbo, rumbo_entre(a[0], a[1], b[0], b[1])) <= 90
    o, dst = (a, b) if adelante else (b, a)
    return {"o": o, "d": dst, "via": v, "p": p, "dist_punto": d}


def _elegir_predecesor(nodo, actual_d, via_actual, entran, carretera_ref):
    """Entre las aristas que llegan a `nodo`, la que continúa la carretera actual.

    Devuelve (arista o None, ambiguo, n_candidatas). Puntuación: misma referencia de carretera,
    mismo tipo (enlace o no) y menor giro. Es ambigua si hay dos entradas equivalentes en
    referencia y tipo cuyo giro se parece, o si la mejor gira demasiado.
    """
    cands = [e for e in entran.get(nodo, []) if e.o != actual_d]
    if not cands:
        return None, False, 0
    refs_act = _refs(via_actual) or ({carretera_ref} if carretera_ref else set())
    rb_act = rumbo_entre(nodo[0], nodo[1], actual_d[0], actual_d[1])
    punt = []
    for e in cands:
        refs = _refs(e.via)
        mal_ref = 1 if (refs_act and refs and not (refs_act & refs)) else 0
        mal_tipo = 1 if _es_enlace(e.via) != _es_enlace(via_actual) else 0
        giro = dif_ang(rumbo_entre(e.o[0], e.o[1], nodo[0], nodo[1]), rb_act)
        punt.append(((mal_ref, mal_tipo, giro), e))
    punt.sort(key=lambda t: t[0])
    mejor = punt[0]
    ambiguo = mejor[0][2] > GIRO_MAX
    if len(punt) > 1:
        seg = punt[1]
        if seg[0][:2] == mejor[0][:2] and seg[0][2] - mejor[0][2] < GIRO_AMBIGUO:
            ambiguo = True
    return mejor[1], ambiguo, len(cands)


def _simplificar(pts, tol=SIMPLIFICAR_M):
    """Douglas-Peucker sobre coordenadas planas locales; conserva primero y último."""
    return simplificar(pts, tol)


def construir(radar, res, vias, largo=LARGO_M):
    """Tramo de aproximación de un radar resuelto.

    `res` es la entrada de `sentido.json` (necesita `via`, `rumbo`, `punto`). Devuelve un dict:
    `fiab` ('alta' o 'dudosa'), `puntos` (lista [lat, lon] en el orden de la marcha, o None),
    `largo_m`, `tunel_m`, `cruces`, `ambiguos` y `motivos` (por qué es dudosa).
    """
    base = {"puntos": None, "largo_m": 0, "tunel_m": 0, "cruces": 0, "ambiguos": 0}
    punto = res["punto"]
    ini = _arista_inicial(punto, res["rumbo"], res["via"], vias)
    if ini is None:
        return dict(base, fiab="dudosa", motivos=["la vía elegida no está en los datos descargados"])
    motivos = []
    rb_ini = rumbo_entre(ini["o"][0], ini["o"][1], ini["d"][0], ini["d"][1])
    if dif_ang(rb_ini, res["rumbo"]) > RUMBO_TOL:
        motivos.append(f"el rumbo de la calzada ({round(rb_ini)}°) no cuadra con el de 3.2 ({res['rumbo']}°)")

    entran = _aristas_que_entran(vias)
    ref_carretera = norm_ref((radar.get("carretera") or "").split()[0]) if radar.get("carretera") else ""
    p = tuple(ini["p"])
    pts_rev = [p]
    recorrido = tunel = 0.0
    cruces = ambiguos = 0
    corte = None
    nodo, destino, via = ini["o"], ini["d"], ini["via"]
    visitados = {nodo, destino}
    leg = haversine(p[0], p[1], nodo[0], nodo[1])
    while True:
        if recorrido + leg >= largo:
            resto = largo - recorrido
            f = resto / leg if leg > 0 else 0.0
            prev = pts_rev[-1]
            pts_rev.append((prev[0] + (nodo[0] - prev[0]) * f, prev[1] + (nodo[1] - prev[1]) * f))
            if _es_tunel(via):
                tunel += resto
            recorrido = largo
            break
        recorrido += leg
        if _es_tunel(via):
            tunel += leg
        pts_rev.append(nodo)
        e, ambiguo, n = _elegir_predecesor(nodo, destino, via, entran, ref_carretera)
        if e is None:
            corte = f"la carretera deja de estar en los datos a {round(recorrido)} m del radar"
            break
        if n > 1:
            cruces += 1
        if ambiguo:
            ambiguos += 1
        if e.o in visitados:
            corte = "la carretera vuelve sobre sí misma (rotonda o bucle)"
            break
        visitados.add(e.o)
        leg = haversine(nodo[0], nodo[1], e.o[0], e.o[1])
        destino, nodo, via = nodo, e.o, e.via

    puntos = _simplificar(list(reversed(pts_rev)))
    if recorrido < MIN_ALTA_M:
        motivos.append(corte or f"solo {round(recorrido)} m de recorrido")
    if ambiguos:
        motivos.append(f"{ambiguos} cruce(s) sin una continuación clara")
    if tunel > TUNEL_DUDOSO_M:
        motivos.append(f"{round(tunel)} m de túnel")
    return {
        "fiab": "dudosa" if motivos else "alta",
        "puntos": [[round(a, 6), round(b, 6)] for a, b in puntos],
        "largo_m": round(recorrido), "tunel_m": round(tunel),
        "cruces": cruces, "ambiguos": ambiguos, "motivos": motivos,
    }
