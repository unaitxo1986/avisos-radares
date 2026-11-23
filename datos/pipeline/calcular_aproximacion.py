"""Calcula el tramo de aproximación de los radares (3.3) a partir de `sentido.json`.

Uso:
  python3 -I datos/pipeline/calcular_aproximacion.py --radares crudos.json \
      [--ids id1,id2,... | --prueba] [--sin-red] [--cache datos/cache] [--sentido datos/cache/sentido.json] \
      [--salida datos/cache/aproximacion.json]

Con --ids o --prueba solo se calculan esos radares y se mezclan con lo ya guardado en la salida;
sin ellos se calculan todos los resueltos y la salida se reescribe entera. Las respuestas de
OpenStreetMap se guardan en `datos/cache/osm_aprox.json`; con --sin-red solo se usa esa caché.
"""
import argparse
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from datos.pipeline import aproximacion  # noqa: E402
from datos.pipeline.servicios import Pendiente, Servicios  # noqa: E402

# Radares vascos de la prueba 0.8: autovía, calzada única, doble sentido, enlaces, urbano y túneles.
IDS_PRUEBA = [
    "eus-a-8-123500-max-center",
    "eus-a-8-124400-recta-max-center",
    "eus-a-8-119430-monolito",
    "eus-a-8-112300-tunel-malmasin-ss",
    "eus-a-15-148775-tunel-belabieta",
    "eus-ap-1-145950-tunel-maltzaga-salida",
    "eus-a-124-68600-laguardia-vi",
    "eus-n-240-38430-alto-barazar",
    "eus-n-1-451219-lasarte",
    "eus-bi-637-14600-artea",
    "eus-gi-3440-18045-hondarribia-santaengrazi",
]


def calcular(radares, sentido, serv, ids=None):
    """{id: resultado} para los radares resueltos (o los de `ids`)."""
    salida = {}
    for r in radares:
        if ids is not None and r["id"] not in ids:
            continue
        s = sentido.get(r["id"])
        if s is None:
            continue
        if s["resultado"] == "no_aplica":
            continue
        if s["resultado"] == "pendiente":
            salida[r["id"]] = {"fiab": "dudosa", "puntos": None, "motivos": ["el sentido está pendiente"]}
            continue
        if s["resultado"] != "resuelto":
            salida[r["id"]] = {"fiab": "dudosa", "puntos": None,
                               "motivos": [f"sin sentido fiable: {s.get('motivo', 'ambos sentidos')}"]}
            continue
        try:
            vias = serv.vias_aprox(r["lat"], r["lon"])
        except Pendiente as e:
            salida[r["id"]] = {"fiab": "dudosa", "puntos": None, "motivos": [f"falta en la caché: {e}"]}
            continue
        except OSError as e:  # sin respuesta de OpenStreetMap: no se guarda en caché, se reintenta en la siguiente
            salida[r["id"]] = {"fiab": "dudosa", "puntos": None,
                               "motivos": [f"no se pudo descargar OpenStreetMap: {str(e)[:80]}"]}
            continue
        salida[r["id"]] = aproximacion.construir(r, s, vias, mas_vias=mas_vias(serv))
    return salida


def mas_vias(serv):
    """Otro recuadro de OpenStreetMap alrededor de un punto, para prolongar la línea hasta 1000 m (23/11)."""
    def pedir(lat, lon):
        try:
            return serv.vias_aprox(round(lat, 5), round(lon, 5))
        except (Pendiente, OSError):
            return None
    return pedir


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--radares", required=True, help="JSON de leer_fuentes.py")
    ap.add_argument("--sentido", default="datos/cache/sentido.json")
    ap.add_argument("--ids", default=None, help="ids separados por comas")
    ap.add_argument("--prueba", action="store_true", help="solo los radares de la prueba 0.8")
    ap.add_argument("--sin-red", action="store_true")
    ap.add_argument("--cache", default="datos/cache")
    ap.add_argument("--salida", default="datos/cache/aproximacion.json")
    a = ap.parse_args(argv)

    radares = json.loads(Path(a.radares).read_text(encoding="utf-8"))["radares"]
    sentido = json.loads(Path(a.sentido).read_text(encoding="utf-8"))
    ids = set(IDS_PRUEBA) if a.prueba else (set(a.ids.split(",")) if a.ids else None)
    serv = Servicios(a.cache, sin_red=a.sin_red)
    try:
        res = calcular(radares, sentido, serv, ids)
    finally:
        serv.guardar()
    destino = Path(a.salida)
    if ids is not None and destino.exists():
        previo = json.loads(destino.read_text(encoding="utf-8"))
        previo.update(res)
        res = previo
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(json.dumps(res, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
                       encoding="utf-8")
    cuenta = Counter((i.split("-")[0], v["fiab"]) for i, v in res.items())
    linea = f"Aproximación de {len(res)} radares: {dict(cuenta)}; peticiones nuevas: {serv.peticiones}"
    print(linea)
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::notice title=Aproximación::{linea}")
        motivos = Counter(re.sub(r"\d+", "N", m) for v in res.values() for m in (v.get("motivos") or []))
        print(f"::notice title=Aproximación, motivos de dudosa::{dict(motivos)}")
        print(f"::notice title=Aproximación, tamaño::{destino.stat().st_size} bytes; "
              f"puntos totales: {sum(len(v['puntos']) for v in res.values() if v.get('puntos'))}")
        for id_, v in sorted(res.items()):
            if ids is not None and id_ not in ids:
                continue
            n = len(v["puntos"]) if v.get("puntos") else 0
            print(f"::notice title=Aprox {id_[:40]}::{v['fiab']} largo={v.get('largo_m')} puntos={n} "
                  f"tunel={v.get('tunel_m')} cruces={v.get('cruces')} amb={v.get('ambiguos')} "
                  f"{'; '.join(v.get('motivos') or [])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
