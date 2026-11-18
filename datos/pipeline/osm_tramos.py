"""Exploración (fase 5, 18/11/2026): radares de tramo de OpenStreetMap en España, para contrastar las
coordenadas y el kilometraje de los tramos de la DGT y de Trafikoa.

Uso: python3 -I datos/pipeline/osm_tramos.py --osm filtrado.osm --radares datos/salida/radares.json \
         --salida datos/exploracion/osm_tramos_espana.json

`filtrado.osm` es el XML que deja `osmium tags-filter` sobre el extracto de España de Geofabrik:
relaciones `type=enforcement` (con sus miembros) y nodos `highway=speed_camera`. Para cada tramo nuestro
se anotan las relaciones de velocidad media (`enforcement=average_speed`) con algún miembro a menos de
1 km de su inicio o su fin, y las cámaras a menos de 500 m de cada extremo. No publica nada.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from datos.pipeline.osm_radares import dist, leer_osm  # noqa: E402

CERCA_REL_M = 1000
CERCA_CAM_M = 500


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--osm", required=True)
    ap.add_argument("--radares", required=True)
    ap.add_argument("--salida", required=True)
    a = ap.parse_args(argv)
    nodos, rels = leer_osm(a.osm)
    rad = json.loads(Path(a.radares).read_text(encoding="utf-8"))["radares"]
    tramos = [r for r in rad if r["tipo"] in ("tramo", "tramo_extremo")]
    medias = []
    for r in rels:
        if r["tags"].get("enforcement") != "average_speed":
            continue
        miembros = [{"rol": rol, "id": int(ref), "lat": nodos[ref]["lat"], "lon": nodos[ref]["lon"],
                     "tags": nodos[ref]["tags"]}
                    for rol, t, ref in r["miembros"] if t == "node" and ref in nodos]
        medias.append({"id": r["id"], "tags": r["tags"], "fecha": r["fecha"], "miembros": miembros})
    camaras = [n for n in nodos.values() if n["tags"].get("highway") == "speed_camera"]
    salida = []
    for t in tramos:
        ext = [("inicio", t["lat"], t["lon"])]
        if t.get("lat_fin") is not None:
            ext.append(("fin", t["lat_fin"], t["lon_fin"]))
        rels_cerca = []
        for m in medias:
            dmin = min((dist(la, lo, x["lat"], x["lon"]), nombre) for nombre, la, lo in ext for x in m["miembros"]) \
                if m["miembros"] else (1e9, None)
            if dmin[0] <= CERCA_REL_M:
                rels_cerca.append({**m, "a_m": round(dmin[0])})
        cams = {}
        for nombre, la, lo in ext:
            cams[nombre] = sorted(({"id": c["id"], "lat": c["lat"], "lon": c["lon"], "tags": c["tags"],
                                    "a_m": round(dist(la, lo, c["lat"], c["lon"]))}
                                   for c in camaras if dist(la, lo, c["lat"], c["lon"]) <= CERCA_CAM_M),
                                  key=lambda c: c["a_m"])
        salida.append({"id": t["id"], "carretera": t.get("carretera"), "pk": t.get("pk"), "pk_fin": t.get("pk_fin"),
                       "inicio": [t["lat"], t["lon"]], "fin": [t.get("lat_fin"), t.get("lon_fin")],
                       "tramo_fiab": t.get("tramo_fiab"), "relaciones": rels_cerca, "camaras": cams})
    Path(a.salida).parent.mkdir(parents=True, exist_ok=True)
    Path(a.salida).write_text(json.dumps({"relaciones_velocidad_media": len(medias), "camaras": len(camaras),
                                          "tramos": salida}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    con = sum(1 for s in salida if s["relaciones"])
    print(f"::notice title=OSM tramos::{len(medias)} relaciones de velocidad media y {len(camaras)} cámaras en España; "
          f"{con} de {len(salida)} tramos nuestros con alguna relación cerca")
    return 0


if __name__ == "__main__":
    sys.exit(main())
