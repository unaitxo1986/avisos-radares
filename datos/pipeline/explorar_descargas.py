"""Exploración puntual: descarga ficheros de fuentes nuevas a `datos/exploracion/descargas/` para estudiar su
formato antes de escribir el lector (el entorno de Claude no llega a esas webs; GitHub Actions sí).

20/11/2026: geometría alrededor de la cabina Derio Loiu. 19/11/2026: radares fijos del Servei Català de Trànsit (Cataluña no está en el fichero de la DGT) y radares fijos
municipales de Madrid (datos abiertos del Ayuntamiento).
"""
import sys
import urllib.request
from pathlib import Path

SALIDA = Path(sys.argv[1] if len(sys.argv) > 1 else "datos/exploracion/descargas")
UA = {"User-Agent": "avisos-radares/1.0 (https://github.com/unaitxo1986/avisos-radares)"}
DESCARGAS = {
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
