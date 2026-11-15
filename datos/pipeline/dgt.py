"""Lector del fichero de radares de la DGT (XML DATEX II).

Fuente: https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml
Licencia Creative Commons Attribution. Cubre la red estatal salvo País Vasco y Cataluña.

Dos listas dentro (comprobado el 15/11/2026, publicación del 18/12/2025):
- CabinasCinemometro: 690 radares fijos.
- CinemometrosVelocidadMedia: 47 radares de tramo (inicio y fin).

El fichero NO trae el límite de velocidad. El sentido viene como creciente o
decreciente (directionRelative positive/negative) y, en algunos, un destino.
"""
import re
import xml.etree.ElementTree as ET

URL = "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"
FUENTE = "dgt"
ENLACE_FICHA = "https://nap.dgt.es/dataset/radares-fijos-dgt"


class ErrorLectura(Exception):
    """El fichero no tiene la forma esperada."""


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _hijos(el, nombre):
    return [h for h in el if _local(h.tag) == nombre]


def _primero(el, nombre):
    """Primer descendiente (en cualquier nivel) con ese nombre local, o None."""
    for e in el.iter():
        if _local(e.tag) == nombre:
            return e
    return None


def _texto(el, nombre):
    e = _primero(el, nombre) if el is not None else None
    if e is None or e.text is None:
        return None
    t = e.text.strip()
    return t or None


def _coords(el):
    p = _primero(el, "pointCoordinates")
    return float(_texto(p, "latitude")), float(_texto(p, "longitude"))


def _descriptor(el, tipo):
    """Valor del descriptor con ese tpegDescriptorType (linkName, townName, other)."""
    for nombre in (e for e in el.iter() if _local(e.tag) == "name"):
        t = _texto(nombre, "tpegDescriptorType")
        if t == tipo:
            return _texto(nombre, "value")
    return None


def _id_y_nombre(loc):
    """El identificador único es el atributo `id` de la ubicación (GUID_...). El
    `predefinedLocationName` NO es único: en 149 de 737 es un nombre de lugar
    (TABAZA, MALIAÑO, CASTRO URDIALES...) que se repite; en el resto repite el id."""
    ident = (loc.get("id") or "").removeprefix("GUID_")
    if not ident:
        raise ErrorLectura("DGT: una ubicación no tiene atributo id")
    nombre = _texto(_primero(loc, "predefinedLocationName"), "value")
    if nombre and re.fullmatch(r"(CABINACINEMOMETRO|CVM)_\d+", nombre):
        nombre = None
    return f"dgt-{ident}", nombre


def _sentido(valor):
    return {"positive": "creciente", "negative": "decreciente"}.get(valor)


def _ref(ref):
    """Datos de un referencePoint: carretera, pk en metros, sentido, destino..."""
    pk = _texto(ref, "referencePointDistance")
    return {
        "carretera": _texto(ref, "roadNumber"),
        "pk_m": None if pk is None else round(float(pk)),
        "sentido_oficial": _sentido(_texto(ref, "directionRelative")),
        "destino": _texto(ref, "directionNamed"),
        "provincia_ine": _texto(ref, "provinceINEIdentifier"),
        "provincia": _texto(ref, "administrativeArea"),
        "tipo_via": _texto(ref, "singularity"),
    }


def leer(contenido: bytes | str) -> dict:
    """Lee el XML de la DGT. Devuelve {'publicado', 'radares': [...]}.

    Cada radar es un diccionario con las claves comunes de todas las fuentes
    (ver CLAVES en datos/leer_fuentes.py).
    """
    try:
        raiz = ET.fromstring(contenido)
    except ET.ParseError as e:
        raise ErrorLectura(f"XML de la DGT no válido: {e}") from e

    publicado = _texto(raiz, "publicationTime")
    conjuntos = [e for e in raiz.iter() if _local(e.tag) == "predefinedLocationSet"]
    if not conjuntos:
        raise ErrorLectura("No hay ninguna lista (predefinedLocationSet) en el fichero de la DGT")

    radares = []
    for conj in conjuntos:
        for loc in _hijos(conj, "predefinedLocation"):
            ident, nombre = _id_y_nombre(loc)
            if _primero(loc, "tpeglinearLocation") is not None:
                radares.append(_tramo(loc, ident, nombre))
            elif _primero(loc, "tpegpointLocation") is not None:
                radares.append(_fijo(loc, ident, nombre))
    if not radares:
        raise ErrorLectura("El fichero de la DGT no contiene ningún radar")
    return {"publicado": publicado, "radares": radares}


def _fijo(loc, ident, nombre):
    punto = _primero(loc, "tpegpointLocation")
    lat, lon = _coords(punto)
    d = _ref(_primero(loc, "referencePoint"))
    return {
        "id": ident,
        "fuente": FUENTE,
        "tipo": "fijo",
        "nombre": nombre,
        "lat": lat, "lon": lon,
        "lat_fin": None, "lon_fin": None,
        "municipio": _descriptor(punto, "townName"),
        "limite_oficial": None,
        "limite_texto": None,
        **d,
        "enlace_oficial": ENLACE_FICHA,
    }


def _tramo(loc, ident, nombre):
    lin = _primero(loc, "tpeglinearLocation")
    # `from` es el inicio y `to` el final según TPEG. [sin verificar] que coincida
    # con el inicio y el fin del kilometraje (referencePointPrimary/Secondary):
    # comprobarlo en 3.2 con rutas.
    lat, lon = _coords(_hijos(lin, "from")[0])
    lat_f, lon_f = _coords(_hijos(lin, "to")[0])
    ini = _ref(_primero(_primero(loc, "referencePointPrimaryLocation"), "referencePoint"))
    fin = _ref(_primero(_primero(loc, "referencePointSecondaryLocation"), "referencePoint"))
    return {
        "id": ident,
        "fuente": FUENTE,
        "tipo": "tramo",
        "nombre": nombre,
        "lat": lat, "lon": lon,
        "lat_fin": lat_f, "lon_fin": lon_f,
        "municipio": _descriptor(_hijos(lin, "from")[0], "townName"),
        "limite_oficial": None,
        "limite_texto": None,
        **ini,
        "pk_fin_m": fin["pk_m"],
        "enlace_oficial": ENLACE_FICHA,
    }
