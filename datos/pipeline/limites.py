"""Límite de velocidad de cada radar (3.4).

Prioridad (regla 1 del plan, "ante la duda prevalece lo oficial"):
  1. `oficial`: el que da la fuente del radar (Trafikoa; la DGT no publica ninguno). Si la fuente
     da dos límites ("60/80 km/h", Kukularra) se usa el más bajo y queda anotado.
  2. `manual`: el revisado a mano en `datos/manual/` (solo rellena lo que la fuente no da).
  3. `osm`: `maxspeed` numérico de la vía de OpenStreetMap que el sentido (3.2) eligió para el
     radar, es decir, la calzada por la que se llega a la cabina. Es "la carretera en el punto
     del radar" (decisión de Unai del 15/11/2026: el límite lo da la base de datos del radar
     y, si no lo trae, la carretera donde está).
  4. `osm_cercana`: si esa vía no tiene `maxspeed` (o el radar no tiene vía elegida), el valor
     único que comparten las vías a menos de 50 m que son de la misma carretera (o sin
     referencia). Si dos vías cercanas discrepan, no se usa ninguna.
  5. Sin límite (`None`): la app avisa sin límite.

Solo valen valores numéricos entre 20 y 130 km/h; los simbólicos (`ES:urban`, `none`, `walk`...)
y las millas se ignoran, porque no se deducen. Nunca se corrige un dato oficial con otro de
OpenStreetMap: si difieren solo se anota (`discrepa`), para medir la fiabilidad de OpenStreetMap.
Los radares de tramo de la DGT no se calculan aquí (fase 5).

Fiabilidad: `alta` (oficial o manual), `media` (osm), `baja` (osm_cercana).
"""
import re

from .geo import proyectar
from .servicios import Pendiente
from .sentido import norm_ref

LIM_MIN, LIM_MAX = 20, 130
RADIO_CERCANAS_M = 50.0
TIPOS_OSM = ("fijo", "fotorrojo")
FIAB = {"oficial": "alta", "manual": "alta", "osm": "media", "osm_cercana": "baja"}

_RE_MAXSPEED = re.compile(r"\s*(\d{2,3})\s*(?:km/h)?\s*", re.I)


def _valido(n):
    return isinstance(n, int) and not isinstance(n, bool) and LIM_MIN <= n <= LIM_MAX


def num_maxspeed(v):
    """Entero de una etiqueta `maxspeed` numérica ('80', '80 km/h'); None si no lo es."""
    if v is None:
        return None
    m = _RE_MAXSPEED.fullmatch(str(v))
    if not m:
        return None
    n = int(m.group(1))
    return n if _valido(n) else None


def limite_oficial(radar):
    """(límite, nota) del dato de la fuente; (None, None) si no hay número utilizable."""
    n = radar.get("limite_oficial")
    if _valido(n):
        return n, None
    texto = (radar.get("limite_texto") or "").strip()
    nums = sorted({int(x) for x in re.findall(r"\d+", texto) if _valido(int(x))})
    if len(nums) >= 2:
        return nums[0], f"la fuente oficial da {texto}: se usa el más bajo"
    return None, None


def _distancia_a_via(lat, lon, via):
    pts = via["n"]
    return min(proyectar(lat, lon, a, b)[0] for a, b in zip(pts, pts[1:]))


def _misma_carretera(radar, via):
    refs = {norm_ref(x) for x in (via["t"].get("ref") or "").split(";")} - {""}
    if not refs:
        return True
    carretera = norm_ref((radar.get("carretera") or "").split()[0]) if radar.get("carretera") else ""
    return not carretera or carretera in refs


def limite_osm(radar, res, vias):
    """(límite, nivel): nivel es 'calzada', 'cercanas' o None."""
    if res and res.get("resultado") == "resuelto":
        for v in vias:
            if v["id"] == res.get("via"):
                n = num_maxspeed(v["t"].get("maxspeed"))
                if n:
                    return n, "calzada"
    valores = set()
    for v in vias:
        if _distancia_a_via(radar["lat"], radar["lon"], v) > RADIO_CERCANAS_M or not _misma_carretera(radar, v):
            continue
        n = num_maxspeed(v["t"].get("maxspeed"))
        if n:
            valores.add(n)
    if len(valores) == 1:
        return valores.pop(), "cercanas"
    return None, None


def limite_de(radar, res, vias):
    """Resultado para un radar. `vias` es None si no hay datos de OpenStreetMap en la caché."""
    oficial, nota = limite_oficial(radar)
    osm, nivel = (None, None)
    if vias is not None and radar.get("tipo") in TIPOS_OSM:
        osm, nivel = limite_osm(radar, res, vias)
    manual = radar.get("limite_manual")
    manual = manual if _valido(manual) else None
    if oficial:
        limite, origen = oficial, "oficial"
    elif manual:
        limite, origen, nota = manual, "manual", None
    elif osm:
        limite, origen, nota = osm, ("osm" if nivel == "calzada" else "osm_cercana"), None
    else:
        limite, origen, nota = None, None, None
    out = {"limite": limite, "origen": origen, "fiab": FIAB.get(origen),
           "osm": osm, "nivel_osm": nivel}
    if radar.get("limite_texto"):
        out["texto"] = radar["limite_texto"]
    if nota:
        out["nota"] = nota
    if oficial and osm and oficial != osm:
        out["discrepa"] = True
    return out


def calcular(radares, sentido, serv):
    """{id: resultado} para todos los radares (los tramos de la DGT salen sin límite)."""
    salida = {}
    for r in radares:
        try:
            vias = serv.vias_cerca(r["lat"], r["lon"])
        except Pendiente:
            vias = None
        salida[r["id"]] = limite_de(r, sentido.get(r["id"]), vias)
    return salida
