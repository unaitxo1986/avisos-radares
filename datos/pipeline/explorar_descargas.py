"""Exploración puntual: descarga ficheros de fuentes nuevas a `datos/exploracion/descargas/` para estudiar su
formato antes de escribir el lector (el entorno de Claude no llega a esas webs; GitHub Actions sí).

21/11/2026: calles Iparraguirre y Gran Vía de Bilbao. 20/11/2026: geometría alrededor de la cabina Derio Loiu. 19/11/2026: radares fijos del Servei Català de Trànsit (Cataluña no está en el fichero de la DGT) y radares fijos
municipales de Madrid (datos abiertos del Ayuntamiento).
"""
import sys
import urllib.request
from pathlib import Path

SALIDA = Path(sys.argv[1] if len(sys.argv) > 1 else "datos/exploracion/descargas")
UA = {"User-Agent": "avisos-radares/1.0 (https://github.com/unaitxo1986/avisos-radares)"}
DESCARGAS = {
    # 21/11/2026: semáforo en rojo que Unai ha visto bajando por Iparraguirre justo al llegar a Gran Vía (Bilbao):
    # geometría de las dos calles en OpenStreetMap (Overpass) para situar la cámara y su línea de aproximación.
    "iparraguirre_osm.json": "https://overpass-api.de/api/interpreter?data=%5Bout%3Ajson%5D%5Btimeout%3A90%5D%3Barea%5B%22name%22%3D%22Bilbao%22%5D%5B%22admin_level%22%3D%228%22%5D-%3E.a%3B%28way%28area.a%29%5B%22highway%22%5D%5B%22name%22~%22Iparra%28g%7Cgu%29irre%22%2Ci%5D%3Bway%28area.a%29%5B%22highway%22%5D%5B%22name%22~%22Gran%20V%5Bi%C3%AD%5Da%7CKale%20Nagusia%22%2Ci%5D%3B%29%3Bout%20geom%20tags%3B",
    # 20/11/2026: cabina "Derio Loiu" (N-637 ramal R-02) que avisó a Unai yendo hacia Bilbao: carreteras de
    # OpenStreetMap en unos 600 x 600 m alrededor de la cabina (43.286086, -2.892918).
    "loiu_osm.json": "https://api.openstreetmap.org/api/0.6/map.json?bbox=-2.8965,43.2834,-2.8890,43.2888",
}


def main():
    SALIDA.mkdir(parents=True, exist_ok=True)
    for nombre, url in DESCARGAS.items():
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                datos = r.read(3_000_000)
                tipo = r.headers.get("Content-Type", "")
            (SALIDA / nombre).write_bytes(datos)
            muestra = datos[:400].decode("utf-8", "replace").replace("\n", " ")
            print(f"::notice title=Descargado {nombre}::{len(datos)} bytes, {tipo} || {muestra}")
        except Exception as e:
            print(f"::warning title=Fallo {nombre}::{url}: {e}")


if __name__ == "__main__":
    main()
