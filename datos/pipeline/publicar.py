"""Une la lectura de fuentes (3.1) con el sentido (3.2), el tramo de aproximación (3.3)
y el límite (3.4) y escribe los ficheros que lee la app (3.5).

Uso:
  python3 -I datos/pipeline/publicar.py --radares crudos.json [--cache datos/cache] \
      [--salida datos/salida] [--contraste contraste.json]

Escribe `radares.json` y `estado.json` en la carpeta de salida. Reglas:
- Si las cifras salen raras (ver `comprobar`), NO se toca `radares.json`: se conserva la última
  versión buena y `estado.json` lo dice con `ok: false` y el motivo en `avisos`.
- `generado` cambia en cada ejecución, así que cada ejecución deja un cambio en el repositorio.
- `radares.json` solo cambia de contenido cuando cambian los datos (su `version` es el momento
  de la última publicación con datos distintos).
Formato: ver datos/esquema-radares.md.
"""
import argparse
import datetime
import json
import sys
from collections import Counter
from pathlib import Path

# Mínimos para publicar (proporción de radares con resultado y variación máxima frente a lo publicado).
MIN_RADARES = 600
MAX_VARIACION = 0.10
MIN_CON_APROXIMACION = 0.60  # de los radares con sentido resuelto, o el cálculo 3.3 no ha terminado
SENTIDOS_KM = ("creciente", "decreciente")


def _km(pk_m):
    return None if pk_m is None else round(pk_m / 1000, 3)


def construir(crudos, sentido, aprox, limites):
    """Lista de radares publicables, en el orden de las fuentes."""
    out = []
    for r in crudos:
        i = r["id"]
        s = sentido.get(i) or {}
        a = aprox.get(i)
        lim = limites.get(i) or {}
        linea = a.get("puntos") if a else None
        if a is None:
            fiab = None
        elif linea:
            fiab = a["fiab"]
        else:
            fiab = "dudosa"
        sk = s.get("sentido_km")
        out.append({
            "id": i,
            "tipo": r["tipo"],
            "nombre": r.get("nombre"),
            "fuente": r["fuente"],
            "lat": r["lat"], "lon": r["lon"],
            "lat_fin": r.get("lat_fin"), "lon_fin": r.get("lon_fin"),
            "carretera": r.get("carretera"),
            "pk": _km(r.get("pk_m")),
            "sentido": r.get("destino") or r.get("sentido_oficial"),
            "sentido_km": sk if sk in SENTIDOS_KM else None,
            "aproximacion": linea if fiab == "alta" or (fiab == "manual" and linea) else None,
            "aproximacion_fiab": fiab,
            "limite": lim.get("limite"),
            "limite_origen": lim.get("origen"),
            "limite_fiab": lim.get("fiab"),
            "enlace": r.get("enlace_oficial"),
            "rotatoria": bool(r.get("rotatoria")),
        })
    return out


def comprobar(radares, fuentes, previo=None):
    """Problemas que impiden publicar (lista vacía si todo cuadra)."""
    p = []
    n = len(radares)
    if n < MIN_RADARES:
        p.append(f"solo {n} radares (mínimo {MIN_RADARES})")
    ids = [r["id"] for r in radares]
    if len(set(ids)) != len(ids):
        p.append("identificadores repetidos")
    if previo:
        antes = len(previo)
        if antes and abs(n - antes) / antes > MAX_VARIACION:
            p.append(f"{n} radares frente a {antes} publicados (variación mayor del {MAX_VARIACION:.0%})")
    resueltos = [r for r in radares if r["fuente"] in ("dgt", "trafikoa") and r["tipo"] != "tramo"]
    if resueltos:
        con = sum(1 for r in resueltos if r["aproximacion_fiab"] is not None)
        if con / len(resueltos) < MIN_CON_APROXIMACION:
            p.append(f"solo {con} de {len(resueltos)} radares con tramo de aproximación calculado")
    for r in radares:
        if r["limite"] is not None and not 20 <= r["limite"] <= 130:
            p.append(f"límite raro en {r['id']}: {r['limite']}")
            break
        if r["aproximacion_fiab"] == "alta" and not r["aproximacion"]:
            p.append(f"{r['id']}: fiabilidad alta sin línea")
            break
    return p


def _leer(ruta, defecto=None):
    try:
        return json.loads(Path(ruta).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return defecto


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--radares", required=True)
    ap.add_argument("--cache", default=str(Path(__file__).resolve().parents[1] / "cache"))
    ap.add_argument("--salida", default=str(Path(__file__).resolve().parents[1] / "salida"))
    ap.add_argument("--contraste", help="JSON del contraste con detector-radar.com (opcional)")
    ap.add_argument("--ahora", help="fecha y hora fijas (pruebas)")
    a = ap.parse_args(argv)

    cache, salida = Path(a.cache), Path(a.salida)
    ahora = a.ahora or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    crudos = json.loads(Path(a.radares).read_text(encoding="utf-8"))
    sentido = _leer(cache / "sentido.json", {})
    aprox = _leer(cache / "aproximacion.json", {})
    limites = _leer(cache / "limites.json", {})
    previo_doc = _leer(salida / "radares.json")
    previo = previo_doc["radares"] if previo_doc else None

    radares = construir(crudos["radares"], sentido, aprox, limites)
    problemas = comprobar(radares, crudos["fuentes"], previo)
    avisos = list(crudos.get("avisos") or [])

    contraste = _leer(a.contraste) if a.contraste else None
    if contraste and contraste.get("aviso"):
        avisos.append(contraste["aviso"])

    f = crudos["fuentes"]
    estado = {
        "generado": ahora,
        "ok": not problemas,
        "avisos": avisos + [f"NO PUBLICADO: {x}" for x in problemas],
        "fuentes": {
            "dgt": {"ok": True, "radares": f["dgt"]["radares"], "fecha_fuente": f["dgt"].get("publicado"),
                    "usada_copia": f["dgt"].get("usada_copia", False)},
            "trafikoa": {"ok": True, "radares": f["trafikoa"]["radares"], "fecha_fuente": None,
                         "usada_copia": f["trafikoa"].get("usada_copia", False)},
            "manual": {"ok": True, "radares": f["manual"]["radares"]},
            "municipal": {"ok": True, "radares": f.get("municipal", {}).get("radares", 0),
                          "copia_usada": f.get("municipal", {}).get("copia_usada", [])},
        },
        "radares_publicados": len(previo) if problemas and previo else len(radares),
    }
    if contraste:
        estado["contraste"] = {k: v for k, v in contraste.items() if k != "aviso"}

    salida.mkdir(parents=True, exist_ok=True)
    if not problemas:
        version = previo_doc["version"] if previo_doc and previo_doc["radares"] == radares else ahora
        doc = {"version": version, "radares": radares}
        (salida / "radares.json").write_text(
            json.dumps(doc, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (salida / "estado.json").write_text(
        json.dumps(estado, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    c = Counter((r["fuente"], r["tipo"]) for r in radares)
    print(f"{'OK' if not problemas else 'NO PUBLICADO'}: {len(radares)} radares {dict(c)}")
    print("Aproximación:", dict(Counter(r["aproximacion_fiab"] for r in radares)))
    print("Límite:", dict(Counter(r["limite_origen"] for r in radares)))
    for x in problemas:
        print(f"ERROR: {x}", file=sys.stderr)
    return 0 if not problemas else 3


if __name__ == "__main__":
    sys.exit(main())
