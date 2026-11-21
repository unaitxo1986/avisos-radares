"""Calcula el sentido de los radares (3.2) a partir de la salida de leer_fuentes.py.

Uso:
  python3 -I datos/pipeline/calcular_sentido.py --radares crudos.json \
      [--fuente trafikoa|dgt|todos] [--sin-red] [--cache datos/cache] [--salida datos/cache/sentido.json]

Con --sin-red solo se usa la caché (los datos que falten salen como "pendiente").
"""
import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from datos.pipeline import sentido  # noqa: E402
from datos.pipeline.servicios import Servicios  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--radares", required=True, help="JSON de leer_fuentes.py")
    ap.add_argument("--fuente", default="todos", choices=["trafikoa", "dgt", "todos"])
    ap.add_argument("--sin-red", action="store_true")
    ap.add_argument("--cache", default="datos/cache")
    ap.add_argument("--salida", default="datos/cache/sentido.json")
    ap.add_argument("--camaras", default="datos/fuentes/osm_camaras.json",
                    help="cámaras de OpenStreetMap con relación de control (pista para el sentido)")
    a = ap.parse_args(argv)

    radares = json.loads(Path(a.radares).read_text(encoding="utf-8"))["radares"]
    if a.fuente != "todos":
        radares = [r for r in radares if r["fuente"] == ("trafikoa" if a.fuente == "trafikoa" else "dgt")]
    serv = Servicios(a.cache, sin_red=a.sin_red)
    try:
        camaras = None
        if a.camaras and Path(a.camaras).exists():
            camaras = json.loads(Path(a.camaras).read_text(encoding="utf-8"))["camaras"]
        res = sentido.calcular(radares, serv, camaras)
    finally:
        serv.guardar()
    Path(a.salida).parent.mkdir(parents=True, exist_ok=True)
    Path(a.salida).write_text(json.dumps(res, ensure_ascii=False, indent=0, sort_keys=True) + "\n",
                              encoding="utf-8")
    por_res, por_regla = sentido.resumen(res)
    linea = f"Sentido de {len(res)} radares: {por_res}; reglas: {por_regla}; peticiones nuevas: {serv.peticiones}"
    print(linea)
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::notice title=Sentido::{linea}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
