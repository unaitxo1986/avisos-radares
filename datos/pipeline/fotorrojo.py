"""Lector de las cámaras de semáforo en rojo (foto rojo) publicadas oficialmente por ayuntamientos de España.

Petición de Unai (19/11/2026): las cámaras de semáforo en rojo son muy importantes; se añaden las de fuentes
oficiales de toda España (además de las de Euskadi que ya entraban por Trafikoa, Vitoria-Gasteiz y OpenStreetMap).
Fuentes, halladas en el catálogo nacional datos.gob.es con `explorar_fotorrojo.py` (19/11):

- Madrid: conjunto "Tráfico. Semáforos con control foto-rojo" (CSV con latitud y longitud y campo `activo`).
- Málaga: conjunto "Foto Rojo" (CSV en EPSG:4326 con la geometría `POINT (lon lat)`). Trae alguna fila que no
  es una cámara (un corte de tráfico): solo valen las que se llaman "Foto-Rojo ...".
- Santa Cruz de Tenerife: conjunto "Foto Rojo" (GeoJSON). Su web no respondió desde GitHub Actions el 19/11: si
  nunca se ha leído, no hay copia y no entra (se anota en `avisos`).

La DGT tiene 2 cámaras de semáforo en Segovia (N-603 y N-VI) en su lista de equipos de detección de infracciones,
pero solo con carretera y punto kilométrico: no entran, con el mismo criterio que las cabinas del BOPV (habría
que deducir la posición del kilometraje).

Regla de todas las fuentes: se intenta descargar; si sale bien y las cifras cuadran, se renueva la copia
`datos/fuentes/fotorrojo.json`; si no, se usa la copia y se anota en `avisos`.
"""
import csv
import io
import json
import re
from pathlib import Path

from .dgt import ErrorLectura

FUENTE = "municipal"
RUTA_COPIA = Path(__file__).resolve().parents[1] / "fuentes" / "fotorrojo.json"

FUENTES = {
    "madrid": {
        "url": "https://datos.madrid.es/dataset/205193-0-semaforos-foto-rojo/resource/205193-1-semaforos-foto-rojo-csv/download/205193-1-semaforos-foto-rojo-csv.csv",
        "enlace": "https://datos.madrid.es/dataset/205193-0-semaforos-foto-rojo",
        "municipio": "Madrid", "provincia": "Madrid", "rango": (20, 120),
    },
    "malaga": {
        "url": "https://datosabiertos.malaga.eu/recursos/transporte/trafico/da_transporteFotoRojo-4326.csv",
        "enlace": "https://datosabiertos.malaga.eu/dataset/foto-rojo",
        "municipio": "Málaga", "provincia": "Málaga", "rango": (3, 40),
    },
    "santacruz": {
        "url": "https://www.santacruzdetenerife.es/opendata/dataset/3e0ec029-2efe-42e6-bbd2-5edc7fde283b/resource/c72047bc-3e4b-409a-8997-d1f1a1f11772/download/foto_rojo.geojson",
        "enlace": "https://www.santacruzdetenerife.es/opendata/dataset/3e0ec029-2efe-42e6-bbd2-5edc7fde283b",
        "municipio": "Santa Cruz de Tenerife", "provincia": "Santa Cruz de Tenerife", "rango": (2, 60),
    },
}


def _texto(datos: bytes) -> str:
    try:
        return datos.decode("utf-8-sig")
    except UnicodeDecodeError:
        return datos.decode("cp1252")


def _num(v):
    return float(str(v).strip().replace(",", "."))


def registros_madrid(datos: bytes):
    out = []
    for f in csv.DictReader(io.StringIO(_texto(datos)), delimiter=";"):
        if str(f.get("activo", "")).strip() != "1":
            continue
        out.append({"id": str(f["id"]).strip(), "nombre": " ".join(f["nombre"].split()),
                    "lat": round(_num(f["latitud"]), 6), "lon": round(_num(f["longitud"]), 6)})
    return sorted(out, key=lambda x: x["id"])


_PUNTO = re.compile(r"POINT\s*\(\s*(-?[\d.]+)\s+(-?[\d.]+)\s*\)", re.I)


def registros_malaga(datos: bytes):
    out = []
    for f in csv.DictReader(io.StringIO(_texto(datos))):
        nombre = " ".join((f.get("NOMBRE") or "").split())
        m = _PUNTO.search(f.get("SDOGEOMETRIA") or "")
        if not m or not nombre.lower().startswith("foto-rojo"):
            continue
        nombre = re.sub(r"(?i)^foto-rojo\s*", "", nombre)
        out.append({"id": str(f["ID"]).strip(), "nombre": nombre,
                    "lat": round(float(m.group(2)), 6), "lon": round(float(m.group(1)), 6)})
    return sorted(out, key=lambda x: x["id"])


def registros_geojson(datos: bytes):
    """GeoJSON de puntos (Santa Cruz de Tenerife): el nombre sale de la primera propiedad que lo parezca."""
    doc = json.loads(_texto(datos))
    out = []
    for i, f in enumerate(doc.get("features") or []):
        g = f.get("geometry") or {}
        if g.get("type") != "Point":
            continue
        lon, lat = g["coordinates"][:2]
        p = f.get("properties") or {}
        nombre = next((str(v) for k, v in p.items() if v and re.search(r"nombre|name|ubicaci|direcci|denomina|calle", k, re.I)), "")
        ident = next((str(v) for k, v in p.items() if v is not None and re.fullmatch(r"(?i)id|fid|objectid|codigo", k)), str(i + 1))
        out.append({"id": ident, "nombre": " ".join(nombre.split()) or f"Cámara {ident}",
                    "lat": round(float(lat), 6), "lon": round(float(lon), 6)})
    return sorted(out, key=lambda x: x["id"])


CONVERSORES = {"madrid": registros_madrid, "malaga": registros_malaga, "santacruz": registros_geojson}


def radares(copia):
    out = []
    base = {"fuente": FUENTE, "tipo": "fotorrojo", "lat_fin": None, "lon_fin": None, "carretera": None, "pk_m": None,
            "sentido_oficial": None, "destino": None, "limite_texto": None, "limite_oficial": None,
            "provincia_ine": None, "tipo_via": None}
    for clave, f in FUENTES.items():
        for c in copia.get(clave, {}).get("registros", []):
            ident = re.sub(r"[^a-z0-9]+", "-", c["id"].lower()).strip("-")
            out.append({**base, "id": f"fr-{clave}-{ident}", "nombre": f"Foto rojo {c['nombre']}",
                        "lat": c["lat"], "lon": c["lon"], "municipio": f["municipio"], "provincia": f["provincia"],
                        "enlace_oficial": f["enlace"]})
    return out


def leer(descargar=None, ruta_copia=RUTA_COPIA, hoy=None, avisos=None):
    """Lee las cámaras de semáforo oficiales. `descargar(url) -> bytes` (None = solo la copia).
    Devuelve {'radares', 'copia_usada': [...], 'por_fuente': {clave: n}}."""
    avisos = avisos if avisos is not None else []
    try:
        copia = json.loads(Path(ruta_copia).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ErrorLectura(f"foto rojo: no se puede leer la copia {ruta_copia}: {e}") from e
    usadas, cambio = [], False
    for clave, f in FUENTES.items():
        if descargar is None:
            usadas.append(clave)
            continue
        try:
            regs = CONVERSORES[clave](descargar(f["url"]))
            lo, hi = f["rango"]
            if not lo <= len(regs) <= hi:
                raise ErrorLectura(f"{len(regs)} cámaras, esperado entre {lo} y {hi}")
            if regs != copia.get(clave, {}).get("registros"):
                copia[clave] = {"leido": hoy, "registros": regs}
                cambio = True
        except (OSError, ValueError, KeyError, IndexError, ErrorLectura) as e:
            usadas.append(clave)
            leido = copia.get(clave, {}).get("leido")
            avisos.append(f"Foto rojo ({clave}) no se pudo leer ({str(e)[:200]}); "
                          + (f"se usa la copia del {leido}" if leido else "no hay copia: no entra"))
    for clave, f in FUENTES.items():
        regs = copia.get(clave, {}).get("registros", [])
        lo, hi = f["rango"]
        if regs and not lo <= len(regs) <= hi:
            raise ErrorLectura(f"foto rojo: {clave} con {len(regs)} cámaras, esperado entre {lo} y {hi}")
    if cambio and descargar is not None:
        Path(ruta_copia).write_text(json.dumps(copia, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    por_fuente = {k: len(copia.get(k, {}).get("registros", [])) for k in FUENTES}
    return {"radares": radares(copia), "copia_usada": usadas, "por_fuente": por_fuente}
