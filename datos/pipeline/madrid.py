"""Lector de los radares municipales de Madrid (fijos y de tramo), de los datos abiertos del Ayuntamiento.

Conjunto "Radares" (`datos.madrid.es/dataset/300049-0-radares-fijos-moviles`, hallado el 19/11/2026 en el catálogo
datos.gob.es): CSV con el número de radar, la ubicación, la vía, el punto kilométrico, el sentido (en texto), el tipo
("Fijo" o "Radar de tramo"), las coordenadas del inicio del tramo y del radar (o del fin del tramo) y el límite.
Son de la M-30, la A-5 urbana y algunas calles; la DGT no los publica. Sin el sentido creciente/decreciente: el
cálculo del sentido (3.2) los deja en "ambos sentidos" salvo que solo haya una calzada junto a la cabina.

- `carretera` solo si la vía tiene código de carretera (M-30, A-5); en las calles queda vacía (aviso urbano a 150 m).
- Los tramos de la calle Sinesio Delgado vienen como tres tramos por sentido que se solapan (dos cortos y el entero):
  entran los seis, como los publica el Ayuntamiento.

Regla de todas las fuentes: se intenta descargar; si sale bien y las cifras cuadran, se renueva la copia
`datos/fuentes/madrid_radares.csv`; si no, se usa la copia y se anota en `avisos`.
"""
import csv
import io
import re
from pathlib import Path

from .dgt import ErrorLectura

FUENTE = "municipal"
URL = ("https://datos.madrid.es/dataset/300049-0-radares-fijos-moviles/resource/300049-1-radares-fijos-moviles-csv/"
       "download/300049-1-radares-fijos-moviles-csv.csv")
ENLACE = "https://datos.madrid.es/dataset/300049-0-radares-fijos-moviles"
RUTA_COPIA = Path(__file__).resolve().parents[1] / "fuentes" / "madrid_radares.csv"
RANGO_FIJOS = (15, 80)
RANGO_TRAMOS = (2, 40)
_CODIGO = re.compile(r"^([A-Z]{1,2})-?(\d{1,3})$")
_PK = re.compile(r"(\d+)\s*\+\s*(\d+)|(\d+(?:[.,]\d+)?)")


def _texto(datos: bytes) -> str:
    try:
        return datos.decode("utf-8-sig")
    except UnicodeDecodeError:
        return datos.decode("cp1252")


def _carretera(via):
    m = _CODIGO.match((via or "").strip().upper())
    return f"{m.group(1)}-{m.group(2)}" if m else None


def _pks(texto):
    """'0+500' -> [500]; '4' -> [4000]; 'P.K 4+000 ----- P.K. 5+750' -> [4000, 5750]."""
    out = []
    for m in _PK.finditer(texto or ""):
        if m.group(1):
            out.append(int(m.group(1)) * 1000 + int(m.group(2)))
        else:
            out.append(round(float(m.group(3).replace(",", ".")) * 1000))
    return out


def _f(v):
    try:
        return round(float(str(v).strip().replace(",", ".")), 6)
    except ValueError:
        return None


def leer_texto(texto: str):
    out = []
    for f in csv.DictReader(io.StringIO(texto), delimiter=";"):
        n = (f.get("Nº RADAR") or "").strip()
        tipo_t = (f.get("Tipo") or "").strip().lower()
        if not n or tipo_t not in ("fijo", "radar de tramo"):
            continue  # nada de móviles ni de filas vacías
        carretera = _carretera(f.get("Carretara o vial"))
        pks = _pks(f.get("PK")) if carretera else []
        lim = f.get("Velocidad límite", "").strip()
        limite = int(lim) if lim.isdigit() and 20 <= int(lim) <= 130 else None
        lat, lon = _f(f.get("Latitud")), _f(f.get("Longitud"))
        r = {"id": f"mun-madrid-{n}", "fuente": FUENTE, "nombre": " ".join((f.get("Ubicacion") or "").split()),
             "carretera": carretera, "sentido_oficial": None, "destino": None, "limite_oficial": limite,
             "limite_texto": lim or None, "municipio": "Madrid", "provincia": "Madrid", "provincia_ine": None,
             "tipo_via": None, "enlace_oficial": ENLACE}
        if tipo_t == "fijo":
            r.update(tipo="fijo", lat=lat, lon=lon, lat_fin=None, lon_fin=None, pk_m=pks[0] if pks else None)
        else:
            # Tramo: inicio en "Longitud/Latitud inicio tramo" y fin en "Longitud/Latitud".
            r.update(tipo="tramo", lat=_f(f.get("Latitud inicio tramo")), lon=_f(f.get("Longitud inicio tramo")),
                     lat_fin=lat, lon_fin=lon, pk_m=pks[0] if len(pks) == 2 else None,
                     pk_fin_m=pks[1] if len(pks) == 2 else None)
        if r["lat"] is None or r["lon"] is None:
            continue
        out.append(r)
    return out


def _bien(radares):
    fijos = sum(1 for r in radares if r["tipo"] == "fijo")
    tramos = len(radares) - fijos
    if not (RANGO_FIJOS[0] <= fijos <= RANGO_FIJOS[1] and RANGO_TRAMOS[0] <= tramos <= RANGO_TRAMOS[1]):
        raise ErrorLectura(f"{fijos} fijos y {tramos} tramos, esperado {RANGO_FIJOS} y {RANGO_TRAMOS}")
    if any(not (40.2 <= r["lat"] <= 40.7 and -3.95 <= r["lon"] <= -3.45) for r in radares):
        raise ErrorLectura("radares fuera de Madrid")
    return radares


def leer(descargar=None, ruta_copia=RUTA_COPIA, avisos=None):
    """Devuelve {'radares', 'usada_copia'}. `descargar(url) -> bytes` (None = solo la copia)."""
    avisos = avisos if avisos is not None else []
    if descargar is not None:
        try:
            datos = descargar(URL)
            radares = _bien(leer_texto(_texto(datos)))
            if Path(ruta_copia).read_bytes() != datos:
                Path(ruta_copia).write_bytes(datos)
            return {"radares": radares, "usada_copia": False}
        except (OSError, ValueError, ErrorLectura) as e:
            avisos.append(f"Radares municipales de Madrid no se pudieron leer ({str(e)[:200]}); se usa la copia guardada")
    try:
        return {"radares": _bien(leer_texto(_texto(Path(ruta_copia).read_bytes()))), "usada_copia": True}
    except OSError as e:
        raise ErrorLectura(f"Madrid: no se puede leer la copia {ruta_copia}: {e}") from e
