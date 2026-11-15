"""Contraste con detector-radar.com (no oficial): solo sirve para detectar que la lectura de
Trafikoa se ha roto. No se publican sus datos. Nunca falla: si no responde o el formato es
otro, lo anota en `aviso` y sigue.

Uso: python3 -I datos/pipeline/contraste.py --radares crudos.json --salida contraste.json

[sin verificar] el formato de la respuesta: se cuentan los elementos de la lista que encuentre
(directa, o en la primera clave que sea una lista) y se compara con el número de radares de Trafikoa.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

URL = "https://detector-radar.com/wp-json/dgt/v1/capa/euskadi"
UMBRAL = 0.15  # diferencia relativa a partir de la cual se avisa


def contar(doc):
    if isinstance(doc, list):
        return len(doc)
    if isinstance(doc, dict):
        for v in doc.values():
            if isinstance(v, list):
                return len(v)
            if isinstance(v, dict) and isinstance(v.get("features"), list):
                return len(v["features"])
    return None


def comparar(n_trafikoa, n_externo):
    """Devuelve el diccionario de contraste (con `aviso` si difieren mucho)."""
    out = {"trafikoa": n_trafikoa, "detector_radar": n_externo}
    if n_externo is None:
        out["aviso"] = "Contraste con detector-radar.com: formato de respuesta no reconocido"
    elif n_trafikoa and abs(n_externo - n_trafikoa) / n_trafikoa > UMBRAL:
        out["aviso"] = (f"Contraste con detector-radar.com: {n_externo} radares frente a {n_trafikoa} de Trafikoa; "
                        "revisar si la lectura de Trafikoa se ha roto")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--radares", required=True)
    ap.add_argument("--salida", required=True)
    a = ap.parse_args(argv)
    crudos = json.loads(Path(a.radares).read_text(encoding="utf-8"))
    n_t = sum(1 for r in crudos["radares"] if r["fuente"] == "trafikoa")
    try:
        req = urllib.request.Request(URL, headers={"User-Agent": "avisos-radares/0.1"})
        with urllib.request.urlopen(req, timeout=60) as r:
            n_e = contar(json.loads(r.read().decode("utf-8")))
        out = comparar(n_t, n_e)
    except (OSError, ValueError) as e:
        out = {"trafikoa": n_t, "detector_radar": None, "aviso": f"Contraste con detector-radar.com no disponible: {e}"}
    Path(a.salida).write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
