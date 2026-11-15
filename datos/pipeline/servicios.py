"""Clientes de los servicios externos del algoritmo del sentido, con caché.

- OpenStreetMap (API normal, `map.json`): carreteras alrededor de un punto. Overpass
  no se usa: se satura (error 502, comprobado el 15/11/2026).
- OSRM (servidor público de demostración): distancia por carretera entre dos puntos,
  con rumbo de salida y, opcionalmente, de llegada para fijar la calzada.
- Nominatim: coordenadas de un nombre de destino.

Los tres admiten uso moderado: una petición por segundo como máximo, agente de usuario
identificado y todo guardado en caché. Con `sin_red=True` solo se usa la caché y, si
falta algo, se lanza `Pendiente`.
"""
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from .cache import Cache
from .geo import haversine

AGENTE = "avisos-radares/0.1 (+https://github.com/unaitxo1986/avisos-radares)"
URL_OSM = "https://api.openstreetmap.org/api/0.6/map.json"
URL_OSRM = "https://router.project-osrm.org/route/v1/driving/"
URL_NOMINATIM = "https://nominatim.openstreetmap.org/search"
URL_CARTO = "https://www.cartociudad.es/geocoder/api/geocoder/candidatesJsonp"

# Tipos de vía que cuentan como calzada candidata (sin pistas, sendas ni aceras).
VIAS = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
}
ETIQUETAS = ("highway", "oneway", "junction", "ref", "maxspeed", "name")
RADIO_CAJA_M = 90.0    # mitad del lado del recuadro que se pide a OpenStreetMap
RADIO_NODOS_M = 250.0  # solo se guardan los nodos de la vía a menos de esto del punto


class Pendiente(Exception):
    """Falta un dato en la caché y no se puede pedir (modo sin red)."""


def _clave_punto(lat, lon):
    return f"{lat:.5f},{lon:.5f}"


class Servicios:
    def __init__(self, dir_cache, sin_red=False, pausa=1.1):
        d = Path(dir_cache)
        self.osm = Cache(d / "osm.json")
        self.osrm = Cache(d / "osrm.json")
        self.nominatim = Cache(d / "nominatim.json")
        self.carto = Cache(d / "cartociudad.json")
        self.sin_red = sin_red
        self.pausa = pausa
        self._ultimo = 0.0
        self.peticiones = 0

    def guardar(self):
        for c in (self.osm, self.osrm, self.nominatim, self.carto):
            c.guardar()

    # ---- red ----
    def _get(self, url):
        espera = self.pausa - (time.time() - self._ultimo)
        if espera > 0:
            time.sleep(espera)
        ultimo_error = None
        for intento in range(3):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": AGENTE})
                with urllib.request.urlopen(req, timeout=60) as r:
                    self._ultimo = time.time()
                    self.peticiones += 1
                    if self.peticiones % 50 == 0:
                        self.guardar()  # así una ejecución cortada no pierde lo ya pedido
                    texto = r.read().decode("utf-8")
                    if texto.startswith("callback("):  # JSONP de CartoCiudad
                        texto = texto[len("callback("):].rstrip().rstrip(";").rstrip(")")
                    return json.loads(texto)
            except urllib.error.HTTPError as e:
                self._ultimo = time.time()
                if e.code == 400:
                    raise
                ultimo_error = e
            except OSError as e:
                ultimo_error = e
            time.sleep(5 * (intento + 1))
        raise OSError(f"sin respuesta de {url}: {ultimo_error}")

    # ---- carreteras alrededor de un punto ----
    def vias_cerca(self, lat, lon):
        """Lista de vías cercanas: [{'id', 't': etiquetas, 'n': [[lat, lon], ...]}]."""
        clave = _clave_punto(lat, lon)
        if clave in self.osm:
            return self.osm.get(clave)
        if self.sin_red:
            raise Pendiente(f"osm {clave}")
        dlat = math.degrees(RADIO_CAJA_M / 6371008.8)
        dlon = dlat / math.cos(math.radians(lat))
        bbox = f"{lon - dlon:.6f},{lat - dlat:.6f},{lon + dlon:.6f},{lat + dlat:.6f}"
        datos = self._get(f"{URL_OSM}?bbox={bbox}")
        vias = self._destilar_osm(datos, lat, lon)
        self.osm.set(clave, vias)
        return vias

    @staticmethod
    def _destilar_osm(datos, lat, lon):
        nodos = {e["id"]: (e["lat"], e["lon"]) for e in datos.get("elements", []) if e.get("type") == "node"}
        vias = []
        for e in datos.get("elements", []):
            if e.get("type") != "way":
                continue
            tags = e.get("tags", {})
            if tags.get("highway") not in VIAS:
                continue
            pts = [nodos[i] for i in e.get("nodes", []) if i in nodos]
            pts = [p for p in pts if haversine(lat, lon, p[0], p[1]) <= RADIO_NODOS_M]
            if len(pts) < 2:
                continue
            vias.append({
                "id": e["id"],
                "t": {k: tags[k] for k in ETIQUETAS if k in tags},
                "n": [[round(p[0], 6), round(p[1], 6)] for p in pts],
            })
        return vias

    # ---- rutas ----
    def ruta(self, o, rumbo_o, d, rumbo_d=None):
        """Distancia en metros por carretera de `o` a `d` (cada uno (lat, lon)).

        `rumbo_o` fija la calzada de salida; `rumbo_d`, si se da, la de llegada.
        Devuelve None si no hay ruta.
        """
        rd = "" if rumbo_d is None else str(round(rumbo_d))
        clave = f"{_clave_punto(*o)},{round(rumbo_o)}>{_clave_punto(*d)},{rd}"
        if clave in self.osrm:
            return self.osrm.get(clave)
        if self.sin_red:
            raise Pendiente(f"osrm {clave}")
        coord = f"{o[1]:.6f},{o[0]:.6f};{d[1]:.6f},{d[0]:.6f}"
        bearings = f"{round(rumbo_o)},25;" + ("" if rumbo_d is None else f"{round(rumbo_d)},25")
        radios = "40;" + ("unlimited" if rumbo_d is None else "40")
        url = (f"{URL_OSRM}{coord}?overview=false&bearings={urllib.parse.quote(bearings, safe=',;')}"
               f"&radiuses={radios}")
        try:
            datos = self._get(url)
            rutas = datos.get("routes") or []
            dist = round(rutas[0]["distance"]) if datos.get("code") == "Ok" and rutas else None
        except urllib.error.HTTPError:  # 400: no hay segmento cercano con ese rumbo
            dist = None
        self.osrm.set(clave, dist)
        return dist

    # ---- destinos ----
    def geocodificar(self, texto):
        """Hasta 5 lugares con ese nombre: [{'lat', 'lon', 'imp', 'cat', 'tipo', 'nombre'}]."""
        clave = " ".join(texto.lower().split())
        if clave in self.nominatim:
            return self.nominatim.get(clave)
        if self.sin_red:
            raise Pendiente(f"nominatim {clave}")
        q = urllib.parse.urlencode({"q": texto, "format": "jsonv2", "limit": 5,
                                    "countrycodes": "es,fr,pt,ad", "accept-language": "es"})
        datos = self._get(f"{URL_NOMINATIM}?{q}")
        res = [{
            "lat": float(x["lat"]), "lon": float(x["lon"]),
            "imp": round(float(x.get("importance", 0)), 4),
            "cat": x.get("category"), "tipo": x.get("type"),
            "nombre": (x.get("display_name") or "")[:80],
        } for x in datos]
        self.nominatim.set(clave, res)
        return res

    # ---- puntos kilométricos (CartoCiudad, IGN) ----
    def punto_km(self, carretera, km):
        """Puntos de CartoCiudad para 'carretera km N' (km entero): [{'lat','lon','dir','muni'}]."""
        clave = f"{' '.join(carretera.upper().split())} km {int(km)}"
        if clave in self.carto:
            return self.carto.get(clave)
        if self.sin_red:
            raise Pendiente(f"cartociudad {clave}")
        q = urllib.parse.urlencode({"q": clave, "limit": 10, "no_process": "municipio"})
        try:
            datos = self._get(f"{URL_CARTO}?{q}")
        except OSError:
            datos = []
        res = [{"lat": round(x["lat"], 6), "lon": round(x["lng"], 6),
                "dir": x.get("address") or "", "muni": x.get("muni")}
               for x in datos if x.get("type") == "portal" and x.get("lat") is not None]
        self.carto.set(clave, res)
        return res
