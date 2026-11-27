"""Sonda de TomTom (7.2, 27/11): llama a Snap to Roads y a Reverse Geocoding con la clave del secreto
TOMTOM_API_KEY sobre las líneas de aproximación de una muestra de radares, y guarda las respuestas en
datos/exploracion/tomtom/ para ver cómo nombra TomTom las carreteras frente a nuestros datos.
La clave no se escribe nunca en la salida (las respuestas no la incluyen y las URL no se guardan)."""
import json, math, os, random, re, sys, time, urllib.parse, urllib.request

CLAVE = os.environ.get("TOMTOM_API_KEY", "").strip()
if not CLAVE:
    print("::error::falta el secreto TOMTOM_API_KEY"); sys.exit(1)

radares = json.load(open("datos/salida/radares.json"))["radares"]
con_linea = [r for r in radares if r.get("aproximacion") and len(r["aproximacion"]) >= 2 and r.get("carretera")]

def prefijo(c):
    return re.sub(r"[0-9].*", "", c or "")

# Muestra variada: hasta 2 por prefijo de carretera y fuente, máximo 45.
random.seed(27)
random.shuffle(con_linea)
vistos, muestra = {}, []
for r in con_linea:
    k = (prefijo(r["carretera"]), r.get("fuente"))
    if vistos.get(k, 0) >= 2:
        continue
    vistos[k] = vistos.get(k, 0) + 1
    muestra.append(r)
    if len(muestra) >= 45:
        break

def rumbo(a, b):
    dy = b[0] - a[0]
    dx = (b[1] - a[1]) * math.cos(math.radians(a[0]))
    return (math.degrees(math.atan2(dx, dy)) + 360) % 360

def pedir(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "avisos-radares-sonda"}), timeout=20) as f:
            return f.status, json.loads(f.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", "replace")[:500]}
    except Exception as e:
        return None, {"error": f"{type(e).__name__}: {e}"}

CAMPOS = ("{projectedPoints{properties{routeIndex,snapResult}},route{properties{id,speedLimits{value,unit,type},"
          "address{roadName,roadNumbers,municipality},frc,formOfWay,traveledDistance{value,unit}}}}")

salida = []
for r in muestra:
    linea = r["aproximacion"]
    # Hasta 6 puntos repartidos por la línea, en el orden de la marcha (el último, el más cercano al radar).
    idx = sorted(set(round(i * (len(linea) - 1) / 5) for i in range(6)))
    pts = [linea[i] for i in idx]
    rumbos = [rumbo(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]
    rumbos.append(rumbos[-1])
    q = {
        "key": CLAVE,
        "points": ";".join(f"{p[1]:.6f},{p[0]:.6f}" for p in pts),
        "headings": ";".join(f"{h:.0f}" for h in rumbos),
        "fields": CAMPOS,
        "vehicleType": "PassengerCar",
        "measurementSystem": "metric",
    }
    cod_s, snap = pedir("https://api.tomtom.com/snapToRoads/1?" + urllib.parse.urlencode(q, safe="{},;"))
    time.sleep(0.3)
    ult = pts[-1]
    q2 = {"key": CLAVE, "returnSpeedLimit": "true", "returnMatchType": "true", "heading": f"{rumbos[-1]:.0f}", "radius": "50"}
    cod_r, rev = pedir(f"https://api.tomtom.com/search/2/reverseGeocode/{ult[0]:.6f},{ult[1]:.6f}.json?" + urllib.parse.urlencode(q2))
    time.sleep(0.3)
    salida.append({
        "id": r["id"], "fuente": r.get("fuente"), "carretera": r["carretera"], "limite": r.get("limite"),
        "limite_origen": r.get("limite_origen"), "puntos": pts,
        "snap_http": cod_s, "snap": snap, "rev_http": cod_r, "rev": rev,
    })
    # Resumen en una línea
    rn = rl = "?"
    try:
        pp = snap["projectedPoints"][-1]["properties"]
        el = snap["route"][pp["routeIndex"]]["properties"]
        rn = (el.get("address") or {}).get("roadNumbers")
        rl = [(s.get("value"), s.get("type")) for s in (el.get("speedLimits") or [])]
    except Exception:
        pass
    rv = "?"
    try:
        a = rev["addresses"][0]["address"]
        rv = (a.get("routeNumbers"), a.get("streetName"), a.get("speedLimit"))
    except Exception:
        pass
    print(f"{r['id']} {r['carretera']} lim={r.get('limite')} | snap {cod_s} {rn} {rl} | rev {cod_r} {rv}")

os.makedirs("datos/exploracion/tomtom", exist_ok=True)
json.dump(salida, open("datos/exploracion/tomtom/sonda.json", "w"), ensure_ascii=False, indent=1)
ok_s = sum(1 for s in salida if s["snap_http"] == 200)
ok_r = sum(1 for s in salida if s["rev_http"] == 200)
print(f"::notice title=Sonda TomTom::{len(salida)} radares; Snap to Roads 200 en {ok_s}; Reverse Geocoding 200 en {ok_r}")
