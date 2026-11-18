"""Lector de `datos/fuentes/osm_confirmados.json`: cámaras que solo están en OpenStreetMap y que Unai
ha confirmado (decisión del 18/11/2026: se puede usar OpenStreetMap para encontrar radares que no
tengamos, y entran los que Unai confirma). Fuente `osm`, ids `osm-<nodo>`.

Solo copia (se transcribe a mano desde la exploración de `osm_radares.py`). Sin límite: el valor de
OpenStreetMap no se usa como límite del radar. Si llevan carretera, pasan por el cálculo de sentido,
tramo de aproximación y límite como cualquier radar.
"""
import json
from pathlib import Path

from .dgt import ErrorLectura

FUENTE = "osm"
RUTA = Path(__file__).resolve().parents[1] / "fuentes" / "osm_confirmados.json"


def leer(ruta=RUTA):
    ruta = Path(ruta)
    if not ruta.exists():
        return {"radares": []}
    try:
        doc = json.loads(ruta.read_text(encoding="utf-8"))
    except ValueError as e:
        raise ErrorLectura(f"osm_confirmados: JSON no válido ({e})") from e
    out = []
    for e in doc.get("radares") or []:
        if not e.get("osm") or e.get("lat") is None or e.get("lon") is None:
            raise ErrorLectura(f"osm_confirmados: entrada incompleta {e}")
        pk = e.get("pk")
        out.append({
            "id": f"osm-{e['osm']}", "fuente": FUENTE, "tipo": "fijo", "nombre": e.get("nombre"),
            "lat": e["lat"], "lon": e["lon"], "lat_fin": None, "lon_fin": None,
            "municipio": e.get("municipio"), "carretera": e.get("carretera"),
            "pk_m": None if pk is None else round(pk * 1000), "sentido_oficial": None,
            "destino": e.get("destino"), "limite_oficial": None, "limite_texto": None,
            "provincia_ine": None, "provincia": None, "tipo_via": None,
            "enlace_oficial": f"https://www.openstreetmap.org/node/{e['osm']}",
            "confirmado": e.get("confirmado"),
        })
    return {"radares": out}
