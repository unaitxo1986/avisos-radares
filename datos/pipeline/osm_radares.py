"""Exploración (18/11/2026, a petición de Unai): cámaras de control de OpenStreetMap en Euskadi
que no estén ya en `datos/salida/radares.json`.

Uso: python3 -I datos/pipeline/osm_radares.py --osm filtrado.osm --radares datos/salida/radares.json \
         --salida datos/exploracion/osm_radares_euskadi.json

`filtrado.osm` es el XML que deja `osmium tags-filter` sobre el extracto de Geofabrik (nodos
`highway=speed_camera` y relaciones `type=enforcement` con sus miembros). No publica nada: solo
escribe una lista para revisarla.
"""
import argparse
import json
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CERCA_M = 150  # a menos de esto se considera que ya lo tenemos


def dist(a, b, c, d):
    return math.hypot((a - c) * 111_000, (b - d) * 111_000 * math.cos(math.radians(a)))


def leer_osm(ruta):
    raiz = ET.parse(ruta).getroot()
    nodos, rels = {}, []
    for e in raiz:
        tags = {t.get("k"): t.get("v") for t in e.findall("tag")}
        if e.tag == "node":
            nodos[e.get("id")] = {"id": int(e.get("id")), "lat": float(e.get("lat")), "lon": float(e.get("lon")),
                                  "tags": tags, "version": e.get("version"), "fecha": (e.get("timestamp") or "")[:10]}
        elif e.tag == "relation" and tags.get("type") == "enforcement":
            rels.append({"id": int(e.get("id")), "tags": tags, "fecha": (e.get("timestamp") or "")[:10],
                         "miembros": [(m.get("role"), m.get("type"), m.get("ref")) for m in e.findall("member")]})
    return nodos, rels


def candidatos(nodos, rels):
    """Un candidato por nodo cámara (o por dispositivo de una relación de control)."""
    out = {}
    for n in nodos.values():
        if n["tags"].get("highway") == "speed_camera":
            out[n["id"]] = {**n, "control": None, "desde": None, "hacia": None, "relacion": None}
    for r in rels:
        disp = [ref for rol, t, ref in r["miembros"] if rol == "device" and t == "node" and ref in nodos]
        desde = next((ref for rol, t, ref in r["miembros"] if rol == "from" and t == "node" and ref in nodos), None)
        hacia = next((ref for rol, t, ref in r["miembros"] if rol == "to" and t == "node" and ref in nodos), None)
        for ref in disp:
            n = nodos[ref]
            c = out.setdefault(n["id"], {**n, "control": None, "desde": None, "hacia": None, "relacion": None})
            c["control"] = r["tags"].get("enforcement")
            c["relacion"] = r["id"]
            c["limite_rel"] = r["tags"].get("maxspeed")
            if desde:
                c["desde"] = [nodos[desde]["lat"], nodos[desde]["lon"]]
            if hacia:
                c["hacia"] = [nodos[hacia]["lat"], nodos[hacia]["lon"]]
    return list(out.values())


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--osm", required=True)
    ap.add_argument("--radares", required=True)
    ap.add_argument("--salida", required=True)
    a = ap.parse_args(argv)
    nodos, rels = leer_osm(a.osm)
    rad = json.loads(Path(a.radares).read_text(encoding="utf-8"))["radares"]
    lista = []
    for c in candidatos(nodos, rels):
        mejor = min(rad, key=lambda r: dist(c["lat"], c["lon"], r["lat"], r["lon"]))
        d = dist(c["lat"], c["lon"], mejor["lat"], mejor["lon"])
        c["mas_cercano"] = {"id": mejor["id"], "nombre": mejor.get("nombre"), "m": round(d)}
        c["ya_esta"] = d <= CERCA_M
        lista.append(c)
    lista.sort(key=lambda c: (c["ya_esta"], -c["lat"]))
    nuevos = [c for c in lista if not c["ya_esta"]]
    Path(a.salida).parent.mkdir(parents=True, exist_ok=True)
    Path(a.salida).write_text(json.dumps({"total": len(lista), "nuevos": len(nuevos), "camaras": lista},
                                         ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"::notice title=OSM Euskadi::{len(lista)} cámaras; {len(nuevos)} a más de {CERCA_M} m de un radar nuestro")
    return 0


if __name__ == "__main__":
    sys.exit(main())
