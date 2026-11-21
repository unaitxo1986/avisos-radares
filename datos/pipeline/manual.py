"""Lector de `datos/manual/anadidos.json`.

Dos usos (ver datos/esquema-radares.md):
- Radares de listas oficiales municipales que no vienen en formato legible por
  máquina. Cada uno lleva el enlace a la publicación oficial (`enlace_oficial`).
- Revisiones: un radar con `completa` (id de un radar oficial) rellena un dato que
  la fuente oficial no da, por ejemplo el límite. Nunca corrige un dato oficial.

Formato de cada entrada: el del esquema (id `man-...`, tipo, lat, lon, lat_fin,
lon_fin, carretera, pk en km, sentido, limite, nombre) más `enlace_oficial`
(obligatorio en radares nuevos, salvo los vistos por Unai), `completa` (opcional),
`visto` (21/11: cámara que ningún organismo publica y que Unai ha visto en persona:
texto con la fecha y lo que dijo; sustituye a `enlace_oficial`) y `aproximacion`
(opcional: línea de aproximación revisada a mano, lista de [lat, lon] en el orden de
la marcha que acaba en la cámara; se publica con fiabilidad `manual`). Si el fichero no existe
o `radares` está vacío, devuelve una lista vacía (los municipales son opcionales, 3.7).
"""
import json
from pathlib import Path

from .dgt import ErrorLectura

FUENTE = "manual"
TIPOS = {"fijo", "tramo", "fotorrojo"}
SENTIDOS = {"creciente", "decreciente"}


def _num(v, campo, n):
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ErrorLectura(f"manual, entrada {n}: '{campo}' no es un número ({v!r})")
    return v


def _linea(v, n):
    if v is None:
        return None
    if not isinstance(v, list) or len(v) < 2 or not all(
            isinstance(p, list) and len(p) == 2 and all(isinstance(x, (int, float)) and not isinstance(x, bool)
                                                       for x in p) for p in v):
        raise ErrorLectura(f"manual, entrada {n}: 'aproximacion' debe ser una lista de al menos dos [lat, lon]")
    return [[float(a), float(b)] for a, b in v]


def leer(ruta) -> dict:
    ruta = Path(ruta)
    if not ruta.exists():
        return {"radares": []}
    try:
        datos = json.loads(ruta.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise ErrorLectura(f"manual: JSON no válido ({e})") from e
    entradas = datos.get("radares") if isinstance(datos, dict) else None
    if not isinstance(entradas, list):
        raise ErrorLectura("manual: falta la lista 'radares'")

    radares = []
    for n, e in enumerate(entradas, 1):
        for c in ("id", "tipo", "lat", "lon"):
            if e.get(c) in (None, ""):
                raise ErrorLectura(f"manual, entrada {n}: falta '{c}'")
        if not str(e["id"]).startswith("man-"):
            raise ErrorLectura(f"manual, entrada {n}: el id debe empezar por 'man-'")
        if e["tipo"] not in TIPOS:
            raise ErrorLectura(f"manual, entrada {n}: tipo '{e['tipo']}' no válido")
        enlace = (e.get("enlace_oficial") or "").strip()
        visto = (e.get("visto") or "").strip()
        if not e.get("completa") and not enlace.startswith("http") and not visto:
            raise ErrorLectura(f"manual, entrada {n}: un radar nuevo necesita 'enlace_oficial' (URL) o 'visto'")
        linea = _linea(e.get("aproximacion"), n)
        sentido = e.get("sentido")
        pk = _num(e.get("pk"), "pk", n)
        radares.append({
            "id": e["id"],
            "fuente": FUENTE,
            "tipo": e["tipo"],
            "nombre": e.get("nombre"),
            "lat": _num(e["lat"], "lat", n), "lon": _num(e["lon"], "lon", n),
            "lat_fin": _num(e.get("lat_fin"), "lat_fin", n),
            "lon_fin": _num(e.get("lon_fin"), "lon_fin", n),
            "municipio": e.get("municipio"),
            "carretera": e.get("carretera"),
            "pk_m": None if pk is None else round(pk * 1000),
            "sentido_oficial": sentido if sentido in SENTIDOS else None,
            "destino": sentido if sentido and sentido not in SENTIDOS else None,
            "limite_oficial": None,
            "limite_texto": None,
            "limite_manual": _num(e.get("limite"), "limite", n),
            "provincia_ine": None,
            "provincia": e.get("provincia"),
            "tipo_via": None,
            "enlace_oficial": enlace or None,
            "completa": e.get("completa"),
            "visto": visto or None,
            "aproximacion_manual": linea,
        })
    return {"radares": radares}
