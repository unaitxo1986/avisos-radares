"""Lector de los radares fijos del Servei Català de Trànsit (Cataluña).

La DGT no publica los radares de Cataluña (tiene el tráfico transferido, como Euskadi): hasta el 19/11/2026 la app
no avisaba de ninguno. El Servei Català de Trànsit publica sus radares fijos en datos abiertos ("Radares fijos de
Cataluña", catálogo datos.gob.es `a09002970-radares-fijos-de-cataluna`): un texto de columnas fijas con la vía, el
punto kilométrico, la velocidad máxima del tramo y las coordenadas UTM ETRS89 (huso 31). No da el sentido: el
cálculo del sentido (3.2) los deja en "ambos sentidos" salvo que solo haya una calzada junto a la cabina.

Problemas del fichero (19/11/2026):
- Las filas con un intervalo de kilómetros ("37,79-41,0") son radares de tramo y traen las coordenadas rotas
  (números sin coma decimal o la coordenada Y del inicio y la del fin en lugar de X e Y): **no entran**, porque no
  se puede saber dónde está cada cámara.
- Algunos radares fijos traen las coordenadas sin la coma decimal ("42552972 457695385"): se pone la coma donde
  el número cae dentro de Cataluña (X entre 250.000 y 550.000, Y entre 4.470.000 y 4.760.000). Si no cae, no entra.

Regla de todas las fuentes: se intenta descargar; si sale bien y las cifras cuadran, se renueva la copia
`datos/fuentes/sct_radars.txt`; si no, se usa la copia y se anota en `avisos`.
"""
import re
from pathlib import Path

from .dgt import ErrorLectura
from .utm import utm_a_latlon

FUENTE = "sct"
URL = "https://transit.gencat.cat/web/.content/documents/seguretat_viaria/radars.txt"
ENLACE = "https://datos.gob.es/catalogo/a09002970-radares-fijos-de-cataluna"
RUTA_COPIA = Path(__file__).resolve().parents[1] / "fuentes" / "sct_radars.txt"
RANGO = (150, 450)
CAJA = (40.4, 42.95, 0.1, 3.4)
_NUM = re.compile(r"-?\d+(?:[.,]\d+)?")
_INTERVALO = re.compile(r"\d\s*-\s*\d")
RANGO_X = (250_000, 550_000)
RANGO_Y = (4_470_000, 4_760_000)


def _coordenada(t, rango):
    """Número UTM; si viene sin coma decimal y fuera de rango, se pone la coma donde cae en el rango."""
    t = (t or "").strip()
    v = _num(t)
    if v is None:
        return None
    if rango[0] <= v <= rango[1]:
        return v
    if t.isdigit():
        for k in range(1, 5):
            if rango[0] <= v / 10 ** k <= rango[1]:
                return v / 10 ** k
    return None


def _texto(datos: bytes) -> str:
    try:
        return datos.decode("utf-8-sig")
    except UnicodeDecodeError:
        return datos.decode("cp1252")


def _num(t):
    m = _NUM.search(t or "")
    return float(m.group(0).replace(",", ".")) if m else None


def leer_texto(texto: str):
    """Texto del fichero -> radares en el formato común de las fuentes."""
    lineas = texto.splitlines()
    cab = next((i for i, l in enumerate(lineas) if l.strip().startswith("Via") and "Velocitat" in l), None)
    if cab is None:
        raise ErrorLectura("SCT: no se encuentra la cabecera (Via, PK, Velocitat, X, Y)")
    h = lineas[cab]
    cols = [h.index(c) for c in ("Via", "PK", "Velocitat", "X", "Y")] + [None]
    out, vistos = [], {}
    descartes = []
    for l in lineas[cab + 1:]:
        if not l.strip():
            continue
        via, pk_t, vel_t, x_t, y_t = (l[cols[i]:cols[i + 1]].strip() for i in range(5))
        if not via:
            continue
        if _INTERVALO.search(pk_t):
            descartes.append(f"{via} pk {pk_t} (tramo)")
            continue
        x, y = _coordenada(x_t, RANGO_X), _coordenada(y_t, RANGO_Y)
        if x is None or y is None:
            descartes.append(f"{via} pk {pk_t} (coordenadas)")
            continue
        lat, lon = utm_a_latlon(x, y, huso=31)
        partes = via.split()
        carretera, extra = partes[0], " ".join(partes[1:])
        pk = _num(pk_t)
        vel = _num(vel_t)
        limite = int(vel) if vel is not None and vel == int(vel) and 20 <= vel <= 130 else None
        base = re.sub(r"[^a-z0-9]+", "-", f"cat-{via}-{pk_t}".lower()).strip("-")
        vistos[base] = vistos.get(base, 0) + 1
        ident = base if vistos[base] == 1 else f"{base}-{vistos[base]}"
        out.append({"id": ident, "fuente": FUENTE, "tipo": "fijo", "nombre": f"{via} pk {pk_t}",
                    "carretera": carretera, "pk_m": round(pk * 1000) if pk is not None else None,
                    "lat": round(lat, 6), "lon": round(lon, 6), "lat_fin": None, "lon_fin": None,
                    "sentido_oficial": None, "destino": None, "limite_oficial": limite, "limite_texto": vel_t or None,
                    "provincia": None, "provincia_ine": None, "municipio": None, "tipo_via": None,
                    "calzada": extra or None, "enlace_oficial": ENLACE})
    leer_texto.descartes = descartes
    return out


def _bien(radares):
    lo, hi = RANGO
    if not lo <= len(radares) <= hi:
        raise ErrorLectura(f"{len(radares)} radares, esperado entre {lo} y {hi}")
    fuera = [r["id"] for r in radares if not (CAJA[0] <= r["lat"] <= CAJA[1] and CAJA[2] <= r["lon"] <= CAJA[3])]
    if len(fuera) > 3:
        raise ErrorLectura(f"{len(fuera)} radares fuera de Cataluña (¿huso equivocado?): {fuera[:3]}")
    return radares


def leer(descargar=None, ruta_copia=RUTA_COPIA, avisos=None):
    """Devuelve {'radares', 'usada_copia'}. `descargar(url) -> bytes` (None = solo la copia)."""
    avisos = avisos if avisos is not None else []
    if descargar is not None:
        try:
            datos = descargar(URL)
            radares = _bien(leer_texto(_texto(datos)))
            _anotar_descartes(avisos)
            if Path(ruta_copia).read_bytes() != datos:
                Path(ruta_copia).write_bytes(datos)
            return {"radares": radares, "usada_copia": False}
        except (OSError, ValueError, ErrorLectura) as e:
            avisos.append(f"Servei Català de Trànsit no se pudo leer ({str(e)[:200]}); se usa la copia guardada")
    try:
        radares = _bien(leer_texto(_texto(Path(ruta_copia).read_bytes())))
    except OSError as e:
        raise ErrorLectura(f"SCT: no se puede leer la copia {ruta_copia}: {e}") from e
    _anotar_descartes(avisos)
    return {"radares": radares, "usada_copia": True}


def _anotar_descartes(avisos):
    d = getattr(leer_texto, "descartes", [])
    if d:
        avisos.append(f"Servei Català de Trànsit: {len(d)} filas no entran (tramos y coordenadas rotas): " + "; ".join(d[:25]))
