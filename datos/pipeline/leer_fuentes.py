"""Lee las tres fuentes (DGT, Trafikoa y fichero manual) y escribe un único JSON
de radares "crudos": sin sentido resuelto ni límite de OpenStreetMap (eso es 3.2 y 3.4).

Uso (solo librería estándar):
    python3 -I datos/pipeline/leer_fuentes.py --salida datos/salida/radares_crudos.json
    python3 -I datos/pipeline/leer_fuentes.py --dgt copia.xml --trafikoa copia.html --salida x.json

Sin --dgt / --trafikoa descarga de las URL oficiales. Si una fuente falla, sale
con error y NO escribe la salida: conservar la última copia buena es de 3.5.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from datos.pipeline import dgt, manual, trafikoa  # noqa: E402
from datos.pipeline.dgt import ErrorLectura  # noqa: E402

# Cajas de sanidad (lat_min, lat_max, lon_min, lon_max) para detectar coordenadas rotas.
CAJA_ESPANA = (27.0, 44.5, -19.0, 5.0)  # incluye Canarias y Baleares
CAJA_EUSKADI = (42.4, 43.5, -3.5, -1.7)
# Rango de recuentos esperados: fuera de él se considera que la lectura se ha roto.
RANGO_DGT_FIJOS = (500, 900)
RANGO_DGT_TRAMOS = (30, 70)
RANGO_TRAFIKOA = (80, 130)

AGENTE = "avisos-radares/0.1 (+https://github.com/unaitxo1986/avisos-radares)"


def descargar(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": AGENTE})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def _en_caja(r, caja):
    la0, la1, lo0, lo1 = caja
    return la0 <= r["lat"] <= la1 and lo0 <= r["lon"] <= lo1


def comprobar(fuente: str, radares: list, caja, rangos: dict) -> list:
    """Devuelve la lista de problemas (vacía si todo cuadra)."""
    problemas = []
    por_tipo = {}
    for r in radares:
        por_tipo[r["tipo"]] = por_tipo.get(r["tipo"], 0) + 1
    for tipo, (lo, hi) in rangos.items():
        n = por_tipo.get(tipo, 0)
        if not lo <= n <= hi:
            problemas.append(f"{fuente}: {n} radares de tipo '{tipo}', esperado entre {lo} y {hi}")
    fuera = [r["id"] for r in radares if not _en_caja(r, caja)]
    if fuera:
        problemas.append(f"{fuente}: {len(fuera)} radares fuera de la zona esperada, p. ej. {fuera[:3]}")
    ids = [r["id"] for r in radares]
    if len(set(ids)) != len(ids):
        problemas.append(f"{fuente}: identificadores repetidos")
    return problemas


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dgt", help="fichero XML local en vez de descargar")
    ap.add_argument("--trafikoa", help="fichero HTML local en vez de descargar")
    ap.add_argument("--manual", default=str(Path(__file__).resolve().parents[1] / "manual" / "anadidos.json"))
    ap.add_argument("--salida", required=True)
    a = ap.parse_args(argv)

    problemas = []
    try:
        xml = Path(a.dgt).read_bytes() if a.dgt else descargar(dgt.URL)
        d = dgt.leer(xml)
        problemas += comprobar("DGT", d["radares"], CAJA_ESPANA,
                               {"fijo": RANGO_DGT_FIJOS, "tramo": RANGO_DGT_TRAMOS})

        html = (Path(a.trafikoa).read_text(encoding="utf-8") if a.trafikoa
                else descargar(trafikoa.URL).decode("utf-8"))
        t = trafikoa.leer(html)
        problemas += comprobar("Trafikoa", t["radares"], CAJA_EUSKADI,
                               {"fijo": (RANGO_TRAFIKOA[0] - 3, RANGO_TRAFIKOA[1])})

        m = manual.leer(a.manual)
        problemas += comprobar("Manual", m["radares"], CAJA_ESPANA, {})
    except (ErrorLectura, OSError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    if problemas:
        for p in problemas:
            print(f"ERROR: {p}", file=sys.stderr)
        return 3

    radares = d["radares"] + t["radares"] + m["radares"]
    out = {
        "generado_con": "datos/leer_fuentes.py",
        "fuentes": {
            "dgt": {"publicado": d["publicado"], "radares": len(d["radares"]), "url": dgt.URL},
            "trafikoa": {"radares": len(t["radares"]), "url": trafikoa.URL},
            "manual": {"radares": len(m["radares"])},
        },
        "radares": radares,
    }
    Path(a.salida).parent.mkdir(parents=True, exist_ok=True)
    Path(a.salida).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"OK: {len(radares)} radares (DGT {len(d['radares'])}, Trafikoa {len(t['radares'])}, manual {len(m['radares'])})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
