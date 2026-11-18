"""Lee las tres fuentes (DGT, Trafikoa y fichero manual) y escribe un único JSON
de radares "crudos": sin sentido resuelto ni límite de OpenStreetMap (eso es 3.2 y 3.4).

Uso (solo librería estándar):
    python3 -I datos/pipeline/leer_fuentes.py --salida datos/salida/radares_crudos.json
    python3 -I datos/pipeline/leer_fuentes.py --dgt copia.xml --trafikoa copia.html --salida x.json

Sin --dgt / --trafikoa descarga de las URL oficiales. Si falla la DGT o da cifras
raras, sale con error y NO escribe la salida. Si falla Trafikoa (desde GitHub
Actions su servidor no responde, comprobado el 15/11/2026), sigue con la última
copia buena de datos/fuentes/trafikoa.txt y lo anota en "avisos".
"""
import argparse
import datetime
import gzip
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from datos.pipeline import dgt, manual, municipales, trafikoa  # noqa: E402
from datos.pipeline.dgt import ErrorLectura  # noqa: E402

# Cajas de sanidad (lat_min, lat_max, lon_min, lon_max) para detectar coordenadas rotas.
CAJA_ESPANA = (27.0, 44.5, -19.0, 5.0)  # incluye Canarias y Baleares
CAJA_EUSKADI = (42.4, 43.5, -3.5, -1.7)
# Rango de recuentos esperados: fuera de él se considera que la lectura se ha roto.
RANGO_DGT_FIJOS = (500, 900)
RANGO_DGT_TRAMOS = (30, 70)
RANGO_TRAFIKOA = (80, 130)

RUTA_COPIA_TRAFIKOA = Path(__file__).resolve().parents[1] / "fuentes" / "trafikoa.txt"
RUTA_COPIA_DGT = Path(__file__).resolve().parents[1] / "fuentes" / "dgt.xml.gz"

AGENTE = "avisos-radares/0.1 (+https://github.com/unaitxo1986/avisos-radares)"


def descargar(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": AGENTE})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.read()
    except OSError as e:
        raise OSError(f"no se pudo descargar {url}: {e}") from e


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


def _filas_trafikoa(ruta_local, avisos):
    """Filas de Trafikoa. Si la descarga o la lectura fallan, usa la última copia
    buena (datos/fuentes/trafikoa.txt) y lo anota en `avisos`. Si la lectura sale
    bien, renueva la copia. Devuelve (filas, usada_copia)."""
    try:
        html = (Path(ruta_local).read_text(encoding="utf-8") if ruta_local
                else descargar(trafikoa.URL).decode("utf-8"))
        filas = trafikoa.filas_desde_html(html)
    except (ErrorLectura, OSError) as e:
        if not RUTA_COPIA_TRAFIKOA.exists():
            raise
        filas = trafikoa.filas_desde_texto(RUTA_COPIA_TRAFIKOA.read_text(encoding="utf-8"))
        avisos.append(f"Trafikoa no se pudo leer ({e}); se usa la copia guardada: "
                      + RUTA_COPIA_TRAFIKOA.read_text(encoding="utf-8").splitlines()[0].lstrip("# "))
        return filas, True
    hoy = datetime.date.today().isoformat()
    RUTA_COPIA_TRAFIKOA.parent.mkdir(parents=True, exist_ok=True)
    RUTA_COPIA_TRAFIKOA.write_text(
        trafikoa.filas_a_texto(filas, f"Copia de la página oficial de Trafikoa leída el {hoy} ({len(filas)} radares)."),
        encoding="utf-8")
    return filas, False


def _datos_dgt(ruta_local, avisos):
    """Lee la DGT. Si la descarga o la lectura fallan, usa la última copia buena
    (datos/fuentes/dgt.xml.gz) y lo anota en `avisos`; si sale bien, renueva la copia.
    La copia se renueva en `main`, solo si además pasan las comprobaciones de cifras.
    Devuelve (datos, usada_copia, xml descargado o None)."""
    try:
        xml = Path(ruta_local).read_bytes() if ruta_local else descargar(dgt.URL)
        d = dgt.leer(xml)
    except (ErrorLectura, OSError) as e:
        if ruta_local or not RUTA_COPIA_DGT.exists():
            raise
        d = dgt.leer(gzip.decompress(RUTA_COPIA_DGT.read_bytes()))
        avisos.append(f"La DGT no se pudo leer ({e}); se usa la copia guardada (publicada {d['publicado']})")
        return d, True, None
    return d, False, xml


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dgt", help="fichero XML local en vez de descargar")
    ap.add_argument("--trafikoa", help="fichero HTML local en vez de descargar")
    ap.add_argument("--manual", default=str(Path(__file__).resolve().parents[1] / "manual" / "anadidos.json"))
    ap.add_argument("--salida", required=True)
    ap.add_argument("--sin-municipales-red", action="store_true",
                    help="no descargar los municipales (usar solo su copia)")
    a = ap.parse_args(argv)

    problemas = []
    avisos = []
    try:
        d, dgt_copia, xml_dgt = _datos_dgt(a.dgt, avisos)
        problemas_dgt = comprobar("DGT", d["radares"], CAJA_ESPANA,
                                  {"fijo": RANGO_DGT_FIJOS, "tramo": RANGO_DGT_TRAMOS})
        problemas += problemas_dgt

        filas, usada_copia = _filas_trafikoa(a.trafikoa, avisos)
        t = trafikoa.leer_filas(filas)
        problemas += comprobar("Trafikoa", t["radares"], CAJA_EUSKADI,
                               {"fijo": (RANGO_TRAFIKOA[0] - 3, RANGO_TRAFIKOA[1])})

        m = manual.leer(a.manual)
        problemas += comprobar("Manual", m["radares"], CAJA_ESPANA, {})

        mu = municipales.leer(None if (a.sin_municipales_red or a.dgt) else descargar,
                              hoy=datetime.date.today().isoformat(), avisos=avisos)
        problemas += comprobar("Municipales", mu["radares"], CAJA_EUSKADI, {})
    except (ErrorLectura, OSError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    if problemas:
        for p in problemas:
            print(f"ERROR: {p}", file=sys.stderr)
        return 3

    if xml_dgt is not None and not a.dgt:
        RUTA_COPIA_DGT.parent.mkdir(parents=True, exist_ok=True)
        RUTA_COPIA_DGT.write_bytes(gzip.compress(xml_dgt, 9, mtime=0))

    radares = d["radares"] + t["radares"] + m["radares"] + mu["radares"]
    ids = [r["id"] for r in radares]
    if len(set(ids)) != len(ids):
        print("ERROR: identificadores repetidos entre fuentes", file=sys.stderr)
        return 3
    out = {
        "generado_con": "datos/leer_fuentes.py",
        "fuentes": {
            "dgt": {"publicado": d["publicado"], "radares": len(d["radares"]), "url": dgt.URL,
                    "usada_copia": dgt_copia},
            "trafikoa": {"radares": len(t["radares"]), "url": trafikoa.URL, "usada_copia": usada_copia},
            "manual": {"radares": len(m["radares"])},
            "municipal": {"radares": len(mu["radares"]), "copia_usada": mu["copia_usada"],
                          "url": "datos/pipeline/municipales.py"},
        },
        "avisos": avisos,
        "radares": radares,
    }
    Path(a.salida).parent.mkdir(parents=True, exist_ok=True)
    Path(a.salida).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    for av in avisos:
        print(f"AVISO: {av}", file=sys.stderr)
    print(f"OK: {len(radares)} radares (DGT {len(d['radares'])}, Trafikoa {len(t['radares'])}, manual {len(m['radares'])}, municipales {len(mu['radares'])})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
