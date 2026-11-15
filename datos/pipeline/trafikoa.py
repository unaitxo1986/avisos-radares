"""Lector de la página oficial de Trafikoa (radares fijos de Euskadi).

Fuente: https://apps.trafikoa.euskadi.eus/lfr/web/trafikoa/cabinas-de-radar-fijo
Los datos van dentro del HTML, en un bloque de JavaScript por radar (comprobado
el 15/11/2026: 98 radares, Bizkaia 40, Araba 21, Gipuzkoa 37):

    var x = 499983.69;            UTM huso 30, ETRS89
    var y = 4792619.83;
    var th = "Bizkaia";
    var popupTitle = "MAX CENTER";
    var popupValores = ["MAX CENTER","DONOSTIA / SAN SEBASTIÁN","BARAKALDO",
                        "Bizkaia","A-8","123.500","80 km/h"];

El bloque de TRABAKUA TRAMO 1/2 trae además una variante con un enlace a la
cámara (8 elementos); se usa la de 7. Si rediseñan la página, este lector
falla con ErrorLectura en lugar de devolver datos raros.
"""
import re
import unicodedata

from .dgt import ErrorLectura
from .utm import utm_a_latlon

URL = "https://apps.trafikoa.euskadi.eus/lfr/web/trafikoa/cabinas-de-radar-fijo"
FUENTE = "trafikoa"

_RE_Y = re.compile(r"var y\s*=\s*([\d.]+)")
_RE_TH = re.compile(r'var th\s*=\s*"([^"]*)"')
_RE_TITULO = re.compile(r'var popupTitle\s*=\s*"([^"]*)"')
_RE_VALORES = re.compile(r"var popupValores\s*=\s*\[([^\]]*)\]")
_RE_CADENA = re.compile(r'"([^"]*)"')
_RE_NUM = re.compile(r"\d+")


def _slug(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def _limite(texto: str):
    """Devuelve (limite_oficial, limite_texto).

    '80 km/h', '80 Km/h' y '80' dan 80. '60/80 km/h' (Kukularra) y '-' (fotorrojo)
    no dan un número único: limite_oficial None y se guarda el texto tal cual.
    """
    t = texto.strip()
    nums = _RE_NUM.findall(t)
    if len(nums) == 1:
        return int(nums[0]), t
    return None, t


def filas_desde_html(html: str) -> list:
    """Extrae del HTML una fila por radar: [x, y, territorio, nombre, destino,
    municipio, territorio2, carretera, km, velocidad], todo como texto."""
    trozos = re.split(r"var x\s*=\s*", html)[1:]
    if not trozos:
        raise ErrorLectura("Trafikoa: no hay ningún bloque 'var x' (¿cambió la página?)")
    filas = []
    for i, t in enumerate(trozos, 1):
        try:
            este = re.match(r"[\d.]+", t).group()
            norte = _RE_Y.search(t).group(1)
            territorio = _RE_TH.search(t).group(1)
            matrices = [_RE_CADENA.findall(m) for m in _RE_VALORES.findall(t)]
            vals = next((m for m in matrices if len(m) == 7), None)
            if vals is None:
                raise ValueError("no hay popupValores de 7 elementos")
        except (AttributeError, ValueError) as e:
            raise ErrorLectura(f"Trafikoa: bloque {i} ilegible ({e})") from e
        filas.append([este, norte, territorio, *vals])
    return filas


def filas_a_texto(filas: list, cabecera: str) -> str:
    """Formato de la copia guardada en datos/fuentes/: una fila por línea, separada por |."""
    return "# " + cabecera + "\n" + "\n".join("|".join(f) for f in filas) + "\n"


def filas_desde_texto(texto: str) -> list:
    filas = [l.split("|") for l in texto.splitlines() if l.strip() and not l.startswith("#")]
    if not filas or any(len(f) != 10 for f in filas):
        raise ErrorLectura("Trafikoa: la copia guardada no tiene el formato esperado")
    return filas


def leer(html: str) -> dict:
    """Lee el HTML de Trafikoa. Devuelve {'radares': [...]}."""
    return leer_filas(filas_desde_html(html))


def leer_filas(filas: list) -> dict:
    radares = []
    for i, f in enumerate(filas, 1):
        territorio = f[2]
        nombre, destino, municipio, terr2, carretera, km, vel = (v.strip() for v in f[3:])
        try:
            lat, lon = utm_a_latlon(float(f[0]), float(f[1]))
            pk_m = round(float(km) * 1000)
        except ValueError as e:
            raise ErrorLectura(f"Trafikoa: fila {i} ({nombre}) con números ilegibles ({e})") from e
        limite, limite_texto = _limite(vel)

        tipo = "fijo"
        extra = {}
        if re.search(r"foto[- ]?rojo", nombre, re.I):
            tipo = "fotorrojo"
        m = re.match(r"(.*?)\s+TRAMO\s+(\d)$", nombre)
        if m:
            tipo = "tramo_extremo"
            extra = {"tramo_grupo": m.group(1), "tramo_extremo": int(m.group(2))}

        radares.append({
            "id": f"eus-{_slug(carretera)}-{pk_m}-{_slug(nombre)}",
            "fuente": FUENTE,
            "tipo": tipo,
            "nombre": nombre,
            "lat": round(lat, 6), "lon": round(lon, 6),
            "lat_fin": None, "lon_fin": None,
            "municipio": municipio,
            "limite_oficial": limite,
            "limite_texto": limite_texto,
            "carretera": carretera,
            "pk_m": pk_m,
            # Euskadi da el sentido como destino, no como creciente/decreciente:
            # lo convierte el algoritmo de rutas (3.2).
            "sentido_oficial": None,
            "destino": destino or None,
            "provincia_ine": None,
            "provincia": territorio,
            "tipo_via": None,
            "enlace_oficial": URL,
            **extra,
        })
    return {"radares": radares}
