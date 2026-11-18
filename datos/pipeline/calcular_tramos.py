"""Calcula la línea, la aproximación y el límite de los radares de tramo (fase 5).

Uso:
  python3 -I datos/pipeline/calcular_tramos.py --radares crudos.json [--sin-red] [--cache datos/cache] \
      [--salida datos/cache/tramos.json]

Las rutas de OSRM se guardan en `datos/cache/osrm_tramos.json` (se versiona); las carreteras de
OpenStreetMap, en las cachés de 3.2 y 3.3. Algoritmo en `tramos.py`.
"""
import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from datos.pipeline import tramos  # noqa: E402
from datos.pipeline.servicios import Servicios  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--radares", required=True, help="JSON de leer_fuentes.py")
    ap.add_argument("--sin-red", action="store_true")
    ap.add_argument("--cache", default="datos/cache")
    ap.add_argument("--salida", default="datos/cache/tramos.json")
    ap.add_argument("--relaciones", default="datos/fuentes/osm_tramos.json",
                    help="relación de OpenStreetMap de cada tramo (copia fija; si falta, no se usa)")
    a = ap.parse_args(argv)

    crudos = json.loads(Path(a.radares).read_text(encoding="utf-8"))["radares"]
    serv = Servicios(a.cache, sin_red=a.sin_red)
    try:
        rel_path = Path(a.relaciones)
        relaciones = json.loads(rel_path.read_text(encoding="utf-8"))["tramos"] if rel_path.exists() else {}
        res = tramos.calcular(crudos, serv, relaciones=relaciones)
    finally:
        serv.guardar()
    destino = Path(a.salida)
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(json.dumps(res, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
                       encoding="utf-8")

    fiab = Counter(v["fiab"] for v in res.values())
    aprox = Counter((v.get("aprox") or {}).get("fiab") for v in res.values())
    lim = Counter(((v.get("limite") or {}).get("origen"), (v.get("limite") or {}).get("fiab")) for v in res.values())
    linea = (f"Tramos: {len(res)}; línea {dict(fiab)}; aproximación {dict(aprox)}; límite {dict(lim)}; "
             f"peticiones nuevas: {serv.peticiones}; fichero {destino.stat().st_size} bytes")
    print(linea)
    gh = bool(os.environ.get("GITHUB_ACTIONS"))
    if gh:
        print(f"::notice title=Tramos::{linea}")
    # Una línea por tramo, agrupadas en anotaciones (GitHub solo enseña unas 10 por tipo y paso).
    filas = []
    for id_, v in sorted(res.items()):
        lim_ = v.get("limite") or {}
        filas.append(f"{id_.replace('dgt-CVM_', '')} {v['fiab']} ruta={v.get('largo_m')} km={v.get('largo_ref_m')} "
                     f"fin={tramos.distancia_fin(v)} aprox={(v.get('aprox') or {}).get('fiab')} "
                     f"lim={lim_.get('limite')}/{lim_.get('origen')}/{lim_.get('fiab')} {lim_.get('valores', '')} "
                     f"{'; '.join(v.get('motivos') or [])}")
    for i in range(0, len(filas), 7):
        texto = " | ".join(filas[i:i + 7])
        print(f"::notice title=Tramos {i + 1}-{min(i + 7, len(filas))}::{texto}" if gh else texto)
    return 0


if __name__ == "__main__":
    sys.exit(main())
