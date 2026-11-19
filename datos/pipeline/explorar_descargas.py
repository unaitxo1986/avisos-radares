"""Exploración puntual: descarga ficheros de fuentes nuevas a `datos/exploracion/descargas/` para estudiar su
formato antes de escribir el lector (el entorno de Claude no llega a esas webs; GitHub Actions sí).

19/11/2026: radares fijos del Servei Català de Trànsit (Cataluña no está en el fichero de la DGT) y radares fijos
municipales de Madrid (datos abiertos del Ayuntamiento).
"""
import sys
import urllib.request
from pathlib import Path

SALIDA = Path(sys.argv[1] if len(sys.argv) > 1 else "datos/exploracion/descargas")
UA = {"User-Agent": "avisos-radares/1.0 (https://github.com/unaitxo1986/avisos-radares)"}
DESCARGAS = {
    "sct_radars.txt": "http://transit.gencat.cat/web/.content/documents/seguretat_viaria/radars.txt",
    "sct_radars_https.txt": "https://transit.gencat.cat/web/.content/documents/seguretat_viaria/radars.txt",
    "madrid_radares.csv": "https://datos.madrid.es/dataset/300049-0-radares-fijos-moviles/resource/300049-1-radares-fijos-moviles-csv/download/300049-1-radares-fijos-moviles-csv.csv",
    "madrid_radares_ckan.json": "https://datos.madrid.es/api/3/action/package_show?id=300049-0-radares-fijos-moviles",
    "sct_ckan.json": "https://analisi.transparenciacatalunya.cat/api/views/metadata/v1?search=radars",
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
