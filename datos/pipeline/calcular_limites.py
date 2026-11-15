"""Calcula el límite de cada radar (3.4). Ver `limites.py` para las reglas.

Uso:
  python3 -I datos/pipeline/calcular_limites.py --radares crudos.json [--cache datos/cache] \
      [--sentido datos/cache/sentido.json] [--salida datos/cache/limites.json]

Usa solo la caché de OpenStreetMap que dejó 3.2 (no pide nada a la red).
"""
import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from datos.pipeline import limites  # noqa: E402
from datos.pipeline.servicios import Servicios  # noqa: E402


def resumen(radares, res):
    """Líneas de resumen: recuentos por fuente y origen, y acuerdo con OpenStreetMap donde hay dato oficial."""
    fuente = {r["id"]: r["fuente"] for r in radares}
    cuenta = Counter((fuente[i], v["origen"] or "sin límite") for i, v in res.items())
    lineas = [f"Límites por fuente y origen: {dict(sorted(cuenta.items()))}"]
    comparables = [(i, v) for i, v in res.items() if v["origen"] == "oficial" and v["osm"]]
    if comparables:
        por_nivel = Counter()
        for i, v in comparables:
            por_nivel[(v["nivel_osm"], "coincide" if not v.get("discrepa") else "difiere")] += 1
        lineas.append(f"OpenStreetMap contra el límite oficial ({len(comparables)} comparables): {dict(sorted(por_nivel.items()))}")
        sin = sum(1 for v in res.values() if v["origen"] == "oficial" and not v["osm"])
        lineas.append(f"Radares con límite oficial y sin dato de OpenStreetMap: {sin}")
    return lineas


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--radares", required=True)
    ap.add_argument("--sentido", default="datos/cache/sentido.json")
    ap.add_argument("--cache", default="datos/cache")
    ap.add_argument("--salida", default="datos/cache/limites.json")
    a = ap.parse_args(argv)
    radares = json.loads(Path(a.radares).read_text(encoding="utf-8"))["radares"]
    sentido = json.loads(Path(a.sentido).read_text(encoding="utf-8"))
    res = limites.calcular(radares, sentido, Servicios(a.cache, sin_red=True))
    destino = Path(a.salida)
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(json.dumps(res, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
                       encoding="utf-8")
    lineas = resumen(radares, res)
    for linea in lineas:
        print(linea)
    if os.environ.get("GITHUB_ACTIONS"):
        for linea in lineas:
            print(f"::notice title=Límites::{linea}")
        dif = sorted(i for i, v in res.items() if v.get("discrepa"))
        print(f"::notice title=Límites, discrepancias con OpenStreetMap::{len(dif)}: "
              + "; ".join(f"{i[:34]} oficial={res[i]['limite']} osm={res[i]['osm']}" for i in dif[:10]))
        notas = [f"{i[:34]}: {v['nota']}" for i, v in sorted(res.items()) if v.get("nota")]
        if notas:
            print(f"::notice title=Límites, notas::{'; '.join(notas)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
