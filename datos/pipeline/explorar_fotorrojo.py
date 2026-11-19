"""Exploración puntual (19/11/2026): cámaras de semáforo en rojo (foto rojo) publicadas oficialmente en España.

Unai pidió (19/11) añadir las cámaras de semáforo en rojo de fuentes oficiales de toda España. Este programa no
publica nada: busca en el catálogo nacional de datos abiertos (datos.gob.es) los conjuntos que hablan de foto
rojo o de radares, descarga los ficheros pequeños de los que tienen pinta de servir (Madrid, Málaga y los que
salgan) y deja todo en `datos/exploracion/fotorrojo/` para estudiarlo antes de escribir el lector.
Se ejecuta en GitHub Actions (el entorno de Claude no llega a esas webs).
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

SALIDA = Path(sys.argv[1] if len(sys.argv) > 1 else "datos/exploracion/fotorrojo")
UA = {"User-Agent": "avisos-radares/1.0 (https://github.com/unaitxo1986/avisos-radares)"}
API = "https://datos.gob.es/apidata/catalog/dataset"
TERMINOS_TITULO = ["foto-rojo", "foto rojo", "fotorrojo", "fotorojo", "foto-roig", "fotovermell", "semaforo rojo",
                   "semáforos con control", "radares", "radar", "cinemometro", "cámaras de tráfico", "camaras trafico"]
TERMINOS_CLAVE = ["foto-rojo", "fotorrojo", "radares", "radar", "semáforos", "semaforos"]
CONOCIDOS = {
    "madrid": f"{API}/l01280796-trafico-semaforos-con-control-foto-rojo1.json",
    "malaga": f"{API}/l01290672-foto-rojo.json",
}
DIRECTOS = {
    "malaga_4326.csv": "https://datosabiertos.malaga.eu/recursos/transporte/trafico/da_transporteFotoRojo-4326.csv",
    "madrid_ckan.json": "https://datos.madrid.es/api/3/action/package_search?q=foto-rojo",
}
MAX_BYTES = 3_000_000


def bajar(url, limite=MAX_BYTES):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        datos = r.read(limite + 1)
        return datos[:limite], r.headers.get("Content-Type", ""), len(datos) > limite


def aviso(tipo, titulo, texto):
    texto = texto.replace("\n", " ")[:900]
    print(f"::{tipo} title={titulo}::{texto}", flush=True)


def items(doc):
    res = doc.get("result", doc)
    return res.get("items", []) if isinstance(res, dict) else []


def texto(v):
    if isinstance(v, list):
        for x in v:
            if isinstance(x, dict) and x.get("_lang") in ("es", None):
                return x.get("_value", "")
        return texto(v[0]) if v else ""
    if isinstance(v, dict):
        return v.get("_value", "") or v.get("_about", "")
    return str(v or "")


def distribuciones(it):
    ds = it.get("distribution", [])
    if isinstance(ds, dict):
        ds = [ds]
    out = []
    for d in ds:
        if not isinstance(d, dict):
            continue
        url = d.get("accessURL") or d.get("downloadURL") or ""
        if isinstance(url, list):
            url = url[0] if url else ""
        fmt = d.get("format", {})
        fmt = fmt.get("value", "") if isinstance(fmt, dict) else str(fmt)
        out.append({"url": url, "formato": fmt, "titulo": texto(d.get("title", ""))})
    return out


def main():
    SALIDA.mkdir(parents=True, exist_ok=True)
    encontrados = {}
    consultas = [f"{API}/title/{urllib.parse.quote(t)}.json?_pageSize=50" for t in TERMINOS_TITULO]
    consultas += [f"{API}/keyword/{urllib.parse.quote(k)}.json?_pageSize=50" for k in TERMINOS_CLAVE]
    consultas += list(CONOCIDOS.values())
    for q in consultas:
        try:
            datos, _, _ = bajar(q)
            doc = json.loads(datos.decode("utf-8"))
        except Exception as e:
            aviso("warning", "Consulta fallida", f"{q}: {e}")
            continue
        lista = items(doc)
        if not lista and isinstance(doc.get("result", {}).get("primaryTopic"), dict):
            lista = [doc["result"]["primaryTopic"]]
        for it in lista:
            ident = it.get("_about", "") or texto(it.get("identifier", ""))
            titulo = texto(it.get("title", ""))
            if ident in encontrados:
                continue
            encontrados[ident] = {"titulo": titulo, "editor": texto(it.get("publisher", "")),
                                  "descripcion": texto(it.get("description", ""))[:400],
                                  "distribuciones": distribuciones(it), "consulta": q}
    # Solo interesan los que hablan de semáforo / foto rojo o de radares de tráfico.
    patron = re.compile(r"foto.?r[oj]|fotorr|semáfor|semafor|radar|cinem", re.I)
    utiles = {k: v for k, v in encontrados.items() if patron.search(v["titulo"] + " " + v["descripcion"])}
    (SALIDA / "catalogo.json").write_text(json.dumps(utiles, ensure_ascii=False, indent=1), encoding="utf-8")
    aviso("notice", "Catálogo", f"{len(encontrados)} conjuntos consultados, {len(utiles)} hablan de semáforos o radares")
    lineas = [f"{v['titulo']} | {v['editor'].rsplit('/', 1)[-1]} | {len(v['distribuciones'])} ficheros" for v in utiles.values()]
    for i in range(0, len(lineas), 8):
        aviso("notice", f"Conjuntos {i + 1}-{min(i + 8, len(lineas))}", " ;; ".join(lineas[i:i + 8]))

    # Descarga de los ficheros de los conjuntos de foto rojo (y de los directos conocidos).
    descargas = dict(DIRECTOS)
    for k, v in utiles.items():
        if not re.search(r"foto.?r[oj]|fotorr|semáforos con control|semaforos con control", v["titulo"], re.I):
            continue
        nombre = re.sub(r"[^a-z0-9]+", "_", (k.rsplit("/", 1)[-1] or v["titulo"]).lower())[:50]
        for i, d in enumerate(v["distribuciones"]):
            if d["url"] and re.search(r"csv|json|kml|xml|geo", d["formato"] + d["url"], re.I):
                ext = re.search(r"\.(csv|json|geojson|kml|xml)(\?|$)", d["url"].lower())
                descargas[f"{nombre}_{i}.{ext.group(1) if ext else 'dat'}"] = d["url"]
    for fichero, url in descargas.items():
        try:
            datos, tipo, cortado = bajar(url)
            (SALIDA / fichero).write_bytes(datos)
            muestra = datos[:300].decode("utf-8", "replace")
            aviso("notice", f"Descargado {fichero}", f"{len(datos)} bytes{' (cortado)' if cortado else ''}, {tipo}, {url} || {muestra}")
        except Exception as e:
            aviso("warning", f"Fallo {fichero}", f"{url}: {e}")


if __name__ == "__main__":
    main()
