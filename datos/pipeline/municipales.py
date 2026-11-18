"""Lector de los radares municipales de Euskadi publicados oficialmente por cada ayuntamiento (3.7).

Fuentes (comprobadas con Chrome el 18/11/2026, detalle en el documento del proyecto
`radares-municipales-euskadi.md`):

- Vitoria-Gasteiz, cabinas de radar de velocidad: capa 36 `CABINA_RADAR` del servicio ArcGIS de
  GeoVitoria. La consulta normal (`query`) no devuelve la geometría; la operación `identify` sí.
  Coordenadas UTM huso 30 ETRS89 (EPSG:25830). Los radares **rotan** entre las cabinas (el
  Ayuntamiento lo dice en sus notas): cada una sale con `rotatoria: true`.
- Vitoria-Gasteiz, foto rojo: colección "Foto-rojo" del mapa de GeoVitoria (6 detectores de
  semáforo en rojo). Solo hay copia guardada: no se ha localizado una dirección estable de descarga.
- Donostia / San Sebastián: capa 41 `Radarra` del servicio ArcGIS `GARRAIOA` (conjunto abierto
  "wms-transporte", CC BY-SA), con el límite de cada radar (`Abiadura`).

- Bilbao: el Ayuntamiento dice en sus notas y contratos que tiene cabinas fijas en la avenida
  Zumalakarregi (Txurdinaga, dos) y en Alameda Rekalde (dos), pero no publica coordenadas. La
  posición y el sentido de cada una salen de OpenStreetMap (nodo `highway=speed_camera` con su
  relación `enforcement`); Unai confirmó haber visto las cuatro (18/11/2026). Solo copia.

Regla de todas las fuentes: se intenta descargar; si sale bien y las cifras cuadran, se renueva la
copia `datos/fuentes/municipales.json`; si no, se usa la copia y se anota en `avisos`.
Leioa y el resto no publican ubicaciones: no entran.
"""
import json
import urllib.parse
from pathlib import Path

from .dgt import ErrorLectura
from .utm import utm_a_latlon

FUENTE = "municipal"
RUTA_COPIA = Path(__file__).resolve().parents[1] / "fuentes" / "municipales.json"

VITORIA_MAPA = "https://www.vitoria-gasteiz.org/geovitoria/geo?lang=es&locale=es&idioma=es&#YWNjaW9uPXNob3cmaWQ9MTAwOTAmbj11bmRlZmluZWQ="
VITORIA_TRAFICO = "https://www.vitoria-gasteiz.org/wb021/was/contenidoAction.do?idioma=es&uid=u_30002b78_139e1eb2bd6__7ff8"
_VITORIA_EXT = "523000,4741500,530500,4747500"
VITORIA_IDENTIFY = (
    "https://www.vitoria-gasteiz.org/arcgis/rest/services/internet/10_capas_geovitoria/MapServer/identify?"
    + urllib.parse.urlencode({
        "geometry": _VITORIA_EXT, "geometryType": "esriGeometryEnvelope", "sr": 25830,
        "layers": "all:36", "tolerance": 0, "mapExtent": _VITORIA_EXT,
        "imageDisplay": "1000,800,96", "returnGeometry": "true", "f": "json"}))
BILBAO_ENLACES = {
    "z17": "https://www.bilbao.eus/cs/Satellite?c=BIO_Noticia_FA&cid=1279168068778&language=es&pageid=3000075248&pagename=Bilbaonet%2FBIO_Noticia_FA%2FBIO_Noticia",
    "r17": "https://www.bilbao.eus/cs/Satellite?c=BIO_Noticia_FA&cid=1279169578232&idiomaSesion=es_ES&p=100078707&pagename=Agenda21%2FAG21_Detalle_Noticia&titulo=Noticias",
}
DONOSTIA_DATOS = "https://www.donostia.eus/datosabiertos/catalogo/wms-transporte"
DONOSTIA_QUERY = (
    "https://www.donostia.eus/geozerbitzuak/rest/services/ext/GARRAIOA/MapServer/41/query?"
    + urllib.parse.urlencode({"where": "1=1", "outFields": "*", "returnGeometry": "true",
                              "outSR": 4326, "f": "json"}))

# Recuentos esperados por publicación; fuera de rango se considera rota y se usa la copia.
RANGOS = {"vitoria_cabinas": (10, 40), "vitoria_fotorrojo": (3, 20), "donostia_radares": (8, 30),
          "bilbao_cabinas": (2, 20)}


def registros_vitoria(doc):
    """Respuesta de `identify` -> registros de la copia."""
    out = []
    for r in doc.get("results") or []:
        a, g = r.get("attributes") or {}, r.get("geometry") or {}
        if r.get("layerId") != 36 or "x" not in g:
            continue
        out.append({"OBJECTID": int(a["OBJECTID"]), "x": round(g["x"], 2), "y": round(g["y"], 2),
                    "tipo_elemento": (a.get("Tipo_de_elemento") or "").strip() or None,
                    "calle": (a.get("Calle") or "").strip(), "descripcion": (a.get("Descripción") or "").strip(),
                    "control": (a.get("Control") or "").strip()})
    return sorted(out, key=lambda x: x["OBJECTID"])


def registros_donostia(doc):
    """Respuesta de `query` (outSR=4326) -> registros de la copia."""
    out = []
    for f in doc.get("features") or []:
        a, g = f.get("attributes") or {}, f.get("geometry") or {}
        if "x" not in g:
            continue
        out.append({"FID": int(a["FID"]), "IzenKalea": (a.get("IzenKalea") or "").strip(),
                    "Abiadura": a.get("Abiadura"), "lat": round(g["y"], 6), "lon": round(g["x"], 6)})
    return sorted(out, key=lambda x: x["FID"])


def _limpia(s):
    return " ".join((s or "").replace(" ,", ",").split())


def radares(copia):
    """Registros de la copia -> radares en el formato común de las fuentes."""
    out = []
    base = {"fuente": FUENTE, "lat_fin": None, "lon_fin": None, "carretera": None, "pk_m": None,
            "sentido_oficial": None, "destino": None, "limite_texto": None,
            "provincia_ine": None, "tipo_via": None}
    for c in copia["vitoria_cabinas"]["registros"]:
        lat, lon = utm_a_latlon(c["x"], c["y"])
        out.append({**base, "id": f"mun-vitoria-cabina-{c['OBJECTID']}", "tipo": "fijo",
                    "nombre": f"{_limpia(c['calle'])} {_limpia(c['descripcion'])}",
                    "lat": round(lat, 6), "lon": round(lon, 6), "municipio": "Vitoria-Gasteiz",
                    "provincia": "Araba", "limite_oficial": None, "rotatoria": True,
                    "control": c.get("control"), "enlace_oficial": VITORIA_MAPA})
    for c in copia["vitoria_fotorrojo"]["registros"]:
        out.append({**base, "id": f"mun-vitoria-fotorrojo-{c['n']}", "tipo": "fotorrojo",
                    "nombre": f"Foto rojo {_limpia(c['ubicacion'])}", "lat": c["lat"], "lon": c["lon"],
                    "municipio": "Vitoria-Gasteiz", "provincia": "Araba", "limite_oficial": None,
                    "enlace_oficial": VITORIA_TRAFICO})
    for c in copia["donostia_radares"]["registros"]:
        v = c.get("Abiadura")
        out.append({**base, "id": f"mun-donostia-{c['FID']}", "tipo": "fijo", "nombre": _limpia(c["IzenKalea"]),
                    "lat": c["lat"], "lon": c["lon"], "municipio": "Donostia / San Sebastián",
                    "provincia": "Gipuzkoa", "limite_oficial": v if isinstance(v, int) else None,
                    "enlace_oficial": DONOSTIA_DATOS})
    for c in copia.get("bilbao_cabinas", {}).get("registros", []):
        out.append({**base, "id": f"mun-bilbao-{c['osm']}", "tipo": "fijo",
                    "nombre": f"{c['calle']}, vigila {c['vigila']}", "lat": c["lat"], "lon": c["lon"],
                    "municipio": "Bilbao", "provincia": "Bizkaia", "limite_oficial": None,
                    "posicion": "osm", "enlace_oficial": BILBAO_ENLACES[c["enlace"]]})
    return out


def leer(descargar=None, ruta_copia=RUTA_COPIA, hoy=None, avisos=None):
    """Lee los municipales. `descargar(url) -> bytes` (None = no intentar descargar).
    Renueva en la copia cada publicación que se descargue bien. Devuelve {'radares', 'copia_usada': [...]}."""
    avisos = avisos if avisos is not None else []
    try:
        copia = json.loads(Path(ruta_copia).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ErrorLectura(f"municipales: no se puede leer la copia {ruta_copia}: {e}") from e
    usadas, cambio = [], False
    for clave, url, conv in (("vitoria_cabinas", VITORIA_IDENTIFY, registros_vitoria),
                             ("donostia_radares", DONOSTIA_QUERY, registros_donostia)):
        if descargar is None:
            usadas.append(clave)
            continue
        try:
            regs = conv(json.loads(descargar(url).decode("utf-8")))
            lo, hi = RANGOS[clave]
            if not lo <= len(regs) <= hi:
                raise ErrorLectura(f"{len(regs)} registros, esperado entre {lo} y {hi}")
            if regs != copia[clave]["registros"]:
                copia[clave] = {"leido": hoy, "registros": regs}
                cambio = True
        except (OSError, ValueError, KeyError, ErrorLectura) as e:
            usadas.append(clave)
            avisos.append(f"Municipales ({clave}) no se pudo leer ({str(e)[:200]}); se usa la copia del "
                          f"{copia[clave].get('leido')}")
    usadas += ["vitoria_fotorrojo", "bilbao_cabinas"]  # solo copia (ver el docstring)
    for clave, (lo, hi) in RANGOS.items():
        n = len(copia[clave]["registros"])
        if not lo <= n <= hi:
            raise ErrorLectura(f"municipales: {clave} con {n} registros, esperado entre {lo} y {hi}")
    if cambio and descargar is not None:
        Path(ruta_copia).write_text(json.dumps(copia, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return {"radares": radares(copia), "copia_usada": usadas}
