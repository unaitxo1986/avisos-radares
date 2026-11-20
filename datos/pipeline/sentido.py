"""Algoritmo del sentido de cada radar, sin revisión manual (3.2).

Describe `prueba-sentido-radares.md` del proyecto: convierte el sentido oficial
(destino o creciente/decreciente) en una calzada concreta del mapa. Toda la red
(OpenStreetMap, rutas, geocodificador) entra por el objeto `serv` (ver servicios.py),
así que se prueba sin red con un servicio simulado.

Pasos por radar:
 1. Candidatas: calzadas a menos de 50 m de la cabina, una por dirección (como máximo 2).
 2. Eje kilométrico: ruta desde cada candidata hasta el radar más próximo en
    kilometraje de la misma carretera y territorio; la que llega antes va hacia él.
 3. Sentido oficial -> candidata: creciente/decreciente con el eje (DGT, manda el dato
    oficial) o, si no, ruta al destino oficial (gana la más corta).
 4. Coherencia: en una misma carretera, territorio y destino, el sentido tiene que ser
    el mismo; se vota ponderando por el margen y se corrige al que discrepe.
 5. Confianza: reglas a-e; lo que no cumple ninguna sale como "ambos".
Los radares de tramo no se calculan aquí (fase 5).
"""
import re
import unicodedata
from collections import defaultdict

from .geo import dif_ang, haversine, proyectar, rumbo as rumbo_entre
from .servicios import Pendiente

RADIO_CAND_M = 50.0      # calzadas candidatas a menos de esto de la cabina
TOL_EJE_M = 150.0        # diferencia mínima entre las dos rutas para fiarse del eje
CAP_PESO_M = 5000.0      # tope del margen al ponderar votos
MARGEN_C_M = 1000.0      # regla c: margen mínimo
DEST_LEJOS_C_M = 0.0  # regla c: sin exigir destino lejano (ver plan, 0.13: con margen >= 1 km basta)
DEST_LEJOS_E_M = 2000.0   # regla e: destino a más de esto (carretera de doble sentido)
RADIO_DEST_CERCA_M = 30000.0
DIST_MIN_CARTO_M = 400.0  # el punto de CartoCiudad debe estar al menos a esto del radar

TIPOS_CALCULABLES = ("fijo", "fotorrojo")
# Fase 5 (18/11): la ruta hasta el vecino que fija el eje tiene que cuadrar con la diferencia de kilometraje
# (como en los tramos): si no, el vecino o sus datos no sirven y se prueba otro.
TOL_EJE_ABS_M = 300.0
TOL_EJE_REL = 0.2
MAX_VECINOS = 4
# Veto por destino (fase 5, 18/11): si la calzada elegida se aleja del destino oficial en línea recta, el radar
# pasa a "ambos sentidos". Solo veta, nunca elige calzada, y solo con destinos fiables (en España, 15-300 km).
VETO_ANGULO = 150.0
VETO_DEST_MIN_M = 15000.0
VETO_DEST_MAX_M = 300000.0


# Cabina pegada a otra vía (20/11): la cabina está junto a la calzada que controla. Si la elegida está lejos y
# otra candidata pegada a la cabina, algo falla. Caso real: "Derio Loiu" (N-637 ramal R-02), con la cabina a 3 m
# de un ramal y la calzada elegida (la principal, por la regla del destino) a 49 m: avisaba a Unai yendo por la
# principal hacia Bilbao. Ver `conflicto_cabina`.
CERCA_CABINA_M = 10.0
LEJOS_CABINA_M = 25.0
RAZON_CABINA = 3.0
# Reglas débiles, que ceden ante la cercanía de la cabina: c (margen de las rutas al destino).
REGLAS_CEDEN_A_CABINA = ("c",)


def conflicto_cabina(cands, elegida):
    """Índice de la candidata pegada a la cabina (a 10 m o menos) cuando la elegida está a 25 m o más y al menos
    a tres veces esa distancia; None si no hay conflicto."""
    if elegida is None or len(cands) < 2:
        return None
    e = cands[elegida]
    k = min((k for k in range(len(cands)) if k != elegida), key=lambda k: cands[k]["d"])
    o = cands[k]
    if o["d"] <= CERCA_CABINA_M and e["d"] >= LEJOS_CABINA_M and e["d"] >= RAZON_CABINA * max(o["d"], 1.0):
        return k
    return None


def norm_ref(s):
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())


def norm_destino(s):
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Z0-9]", "", s.upper())


def sentido_unico(t):
    """+1 si la vía es de sentido único en el orden de sus nodos, -1 si va al revés, 0 si es doble."""
    ow = (t.get("oneway") or "").lower()
    if ow in ("yes", "true", "1"):
        return 1
    if ow == "-1":
        return -1
    if ow == "no":
        return 0
    if t.get("junction") in ("roundabout", "circular"):
        return 1
    if t.get("highway") in ("motorway", "motorway_link"):
        return 1
    return 0


def candidatas(r, vias):
    """Hasta dos calzadas candidatas (una por dirección) junto a la cabina del radar."""
    carretera = r.get("carretera") or ""
    oficiales = {norm_ref(carretera), norm_ref(carretera.split()[0] if carretera.split() else "")} - {""}
    cands = []
    for v in vias:
        mejor = None
        for a, b in zip(v["n"], v["n"][1:]):
            d, p, rb = proyectar(r["lat"], r["lon"], a, b)
            if mejor is None or d < mejor[0]:
                mejor = (d, p, rb)
        if mejor is None or mejor[0] > RADIO_CAND_M:
            continue
        d, p, rb = mejor
        u = sentido_unico(v["t"])
        rumbos = {1: [rb], -1: [(rb + 180) % 360], 0: [rb, (rb + 180) % 360]}[u]
        refs = {norm_ref(x) for x in (v["t"].get("ref") or "").split(";")}
        for rumbo_ in rumbos:
            cands.append({"via": v["id"], "d": d, "p": p, "rumbo": rumbo_, "unico": u != 0,
                          "coincide": bool(oficiales & refs), "hw": v["t"].get("highway")})
    if not cands:
        return []
    if any(c["coincide"] for c in cands):
        cands = [c for c in cands if c["coincide"]]
    eje = min(cands, key=lambda c: c["d"])["rumbo"]
    res = []
    for contrario in (0, 180):
        grupo = [c for c in cands if dif_ang(c["rumbo"], (eje + contrario) % 360) <= 45]
        if grupo:
            res.append(min(grupo, key=lambda c: c["d"]))
    return res


def _vecinos(r, infos):
    """Radares de la misma carretera y territorio, ordenados por cercanía en kilometraje."""
    if r.get("pk_m") is None:
        return []
    mismos = [i for i in infos.values()
              if i["r"]["id"] != r["id"] and i["cands"]
              and i["r"].get("carretera") == r.get("carretera")
              and i["r"].get("provincia") == r.get("provincia")
              and i["r"].get("pk_m") is not None and abs(i["r"]["pk_m"] - r["pk_m"]) >= 100]
    return sorted(mismos, key=lambda i: abs(i["r"]["pk_m"] - r["pk_m"]))


def _puntos_carto(r, serv):
    """Puntos de CartoCiudad en los kilómetros enteros que rodean al radar, ya validados.

    Cada uno: (km, lat, lon). Se acepta si la dirección empieza por la misma carretera y
    la distancia en línea recta al radar es coherente con la diferencia de kilometraje.
    """
    pk = r["pk_m"]
    ref = norm_ref(r.get("carretera") or "")
    res = []
    for km in (int(pk // 1000), int(pk // 1000) + 1):
        d_km = abs(km * 1000 - pk)
        if d_km < DIST_MIN_CARTO_M or km < 0:
            continue
        buenos = []
        for x in serv.punto_km(r["carretera"], km):
            if norm_ref(x["dir"].split(" km ")[0]) != ref:
                continue
            d = haversine(r["lat"], r["lon"], x["lat"], x["lon"])
            if abs(d - d_km) <= 0.4 * d_km + 150:
                buenos.append((abs(d - d_km), x))
        if buenos:
            x = min(buenos, key=lambda t: t[0])[1]
            res.append((km, x["lat"], x["lon"]))
    return res


def cuadra_km(dist, dpk):
    """¿Cuadra una distancia por carretera con una diferencia de kilometraje? (300 m o 20 %).

    Se usa para no fijar el eje kilométrico con una ruta que da un rodeo o con datos rotos (fase 5, 18/11:
    en la N-240 de Huesca, con el vecino a 600 m, las rutas medían 2,7 y 3,5 km y la calzada salió al revés;
    en la N-632 dos cabinas a 14 m tienen 1.650 m de diferencia de kilometraje)."""
    return abs(dist - dpk) <= max(TOL_EJE_ABS_M, TOL_EJE_REL * dpk)


def eje_carto(r, cands, serv):
    """Eje kilométrico con un punto de CartoCiudad cuando el radar no tiene vecino."""
    if r.get("pk_m") is None or not hasattr(serv, "punto_km"):
        return None
    for km, lat, lon in _puntos_carto(r, serv):
        d = [serv.ruta(c["p"], c["rumbo"], (lat, lon), None) for c in cands]
        if d[0] is None or d[1] is None or abs(d[0] - d[1]) < TOL_EJE_M:
            continue
        if not cuadra_km(min(d), abs(km * 1000 - r["pk_m"])):
            continue
        hacia = 0 if d[0] < d[1] else 1
        creciente = hacia if km * 1000 > r["pk_m"] else 1 - hacia
        return {"creciente": creciente, "margen": abs(d[0] - d[1]), "vecino": f"cartociudad-km{km}"}
    return None


def eje_km(r, cands, vecinos, serv):
    """Qué candidata es la creciente. Devuelve {'creciente': indice, 'margen': m, 'vecino': id} o None."""
    if len(cands) != 2:
        return None
    e = _eje_vecinos(r, cands, vecinos, serv)
    return e if e else eje_carto(r, cands, serv)


def _eje_vecinos(r, cands, vecinos, serv):
    for v in vecinos[:MAX_VECINOS]:
        d = []
        for c in cands:
            ds = [serv.ruta(c["p"], c["rumbo"], n["p"], n["rumbo"]) for n in v["cands"]]
            ds = [x for x in ds if x is not None]
            d.append(min(ds) if ds else None)
        if d[0] is None or d[1] is None or abs(d[0] - d[1]) < TOL_EJE_M:
            continue
        if not cuadra_km(min(d), abs(v["r"]["pk_m"] - r["pk_m"])):
            continue
        hacia_vecino = 0 if d[0] < d[1] else 1
        creciente = hacia_vecino if v["r"]["pk_m"] > r["pk_m"] else 1 - hacia_vecino
        return {"creciente": creciente, "margen": abs(d[0] - d[1]), "vecino": v["r"]["id"]}
    return None


def punto_destino(r, serv):
    """Coordenadas del destino oficial, o None si no se localiza.

    Entre los resultados, el más cercano al radar si alguno está a menos de 30 km;
    si no, el más importante. Solo cuentan poblaciones y regiones.
    """
    texto = (r.get("destino") or "").strip()
    if not texto:
        return None
    pruebas = [texto] + [p.strip() for p in texto.split("/")] + [p.strip() for p in texto.split(" - ")]
    vistos = []
    for q in pruebas:
        if q and q not in vistos:
            vistos.append(q)
    for q in vistos:
        res = [x for x in serv.geocodificar(q) if x.get("cat") in ("place", "boundary")]
        if res:
            cerca = [x for x in res if haversine(r["lat"], r["lon"], x["lat"], x["lon"]) <= RADIO_DEST_CERCA_M]
            if cerca:
                return min(cerca, key=lambda x: haversine(r["lat"], r["lon"], x["lat"], x["lon"]))
            return max(res, key=lambda x: x["imp"])
    return None


def _sentido_km(info, idx):
    if info["eje"] is None or len(info["cands"]) != 2:
        return None
    return "creciente" if idx == info["eje"]["creciente"] else "decreciente"


def _decidir(info, serv):
    """Elige candidata para un radar. Rellena info['elegida'], 'origen' y datos del cálculo."""
    r, cands, eje = info["r"], info["cands"], info["eje"]
    if not cands:
        info["motivo"] = "ninguna calzada de OpenStreetMap a menos de 50 m"
        return
    oficial = r.get("sentido_oficial")
    if len(cands) == 1:
        info.update(elegida=0, origen="unica", regla="d")
    elif oficial in ("creciente", "decreciente") and eje:
        idx = eje["creciente"] if oficial == "creciente" else 1 - eje["creciente"]
        info.update(elegida=idx, origen="oficial", regla="a", margen=eje["margen"])
    elif r.get("destino"):
        g = punto_destino(r, serv)
        if g is None:
            info["motivo"] = "destino no localizable"
            return
        dist = [serv.ruta(c["p"], c["rumbo"], (g["lat"], g["lon"]), None) for c in cands]
        if dist[0] is None or dist[1] is None:
            info["motivo"] = "sin ruta al destino desde alguna calzada"
            return
        info.update(elegida=0 if dist[0] < dist[1] else 1, origen="destino",
                    margen=abs(dist[0] - dist[1]),
                    lejos=haversine(r["lat"], r["lon"], g["lat"], g["lon"]))
    else:
        info["motivo"] = "sin destino ni sentido creciente/decreciente utilizable"


def _coherencia(infos):
    """Paso 4: mismo destino en la misma carretera y territorio, mismo sentido."""
    grupos = defaultdict(list)
    for i in infos.values():
        if i.get("origen") == "destino" and i["eje"] is not None and (i["r"].get("destino") or "").strip():
            clave = (i["r"].get("carretera"), i["r"].get("provincia"), norm_destino(i["r"]["destino"]))
            grupos[clave].append(i)
    for clave, miembros in grupos.items():
        if len(miembros) < 2:
            continue
        votos = {"creciente": 0.0, "decreciente": 0.0}
        for m in miembros:
            votos[_sentido_km(m, m["elegida"])] += min(m["margen"], CAP_PESO_M) + 1
        if votos["creciente"] == votos["decreciente"]:
            continue
        ganador = max(votos, key=votos.get)
        for m in miembros:
            if _sentido_km(m, m["elegida"]) != ganador:
                m["elegida"] = 1 - m["elegida"]
                m["corregido"] = True
            m["grupo"] = {"carretera": clave[0], "territorio": clave[1], "destino": clave[2],
                          "n": len(miembros), "sentido": ganador}


def _confianza(info):
    """Paso 5: reglas a-e. Devuelve la regla cumplida o None."""
    if info.get("regla") in ("a", "d"):
        return info["regla"]
    if info.get("origen") != "destino":
        return None
    if info.get("grupo"):
        return "b"
    if info["margen"] >= MARGEN_C_M and info["lejos"] > DEST_LEJOS_C_M:
        return "c"
    if not info["cands"][info["elegida"]]["unico"] and info["lejos"] > DEST_LEJOS_E_M:
        return "e"
    return None


def veto_destino(r, res, serv):
    """Motivo para pasar a "ambos sentidos" un radar resuelto cuya calzada se aleja de su destino oficial.

    El rumbo de la calzada y la dirección en línea recta al destino solo se comparan para vetar, con un umbral
    muy amplio (más de 150°) y destinos lejanos y localizados en España, porque una ruta puede fallar en sitios
    raros: en la N-240 de Huesca, OSRM daba rodeos desde las dos calzadas y la elegida iba hacia Huesca con
    destino Barbastro. Encontró 7 de 199 el 18/11 (entre ellos, N-1 Andoain hacia Vitoria y A-2 km 202 hacia
    Zaragoza, con una sola calzada a menos de 50 m que era la del otro sentido). Devuelve None si no hay veto.
    """
    if res.get("resultado") != "resuelto" or not r.get("destino"):
        return None
    try:
        g = punto_destino(r, serv)
    except Pendiente:
        return None
    if not g or "España" not in (g.get("nombre") or ""):
        return None
    d = haversine(r["lat"], r["lon"], g["lat"], g["lon"])
    if not VETO_DEST_MIN_M <= d <= VETO_DEST_MAX_M:
        return None
    ang = dif_ang(res["rumbo"], rumbo_entre(r["lat"], r["lon"], g["lat"], g["lon"]))
    if ang > VETO_ANGULO:
        return f"la calzada elegida se aleja del destino oficial ({round(ang)}° de diferencia, a {round(d / 1000)} km)"
    return None


def calcular(radares, serv):
    """Calcula el sentido de cada radar. Devuelve {id: resultado}."""
    infos = {}
    for r in radares:
        if r["tipo"] in TIPOS_CALCULABLES and r.get("carretera"):
            infos[r["id"]] = {"r": r, "cands": [], "eje": None}
    resultados = {r["id"]: {"resultado": "no_aplica", "motivo": f"tipo {r['tipo']}"}
                  for r in radares if r["id"] not in infos}

    # 1. candidatas
    for i in infos.values():
        try:
            i["cands"] = candidatas(i["r"], serv.vias_cerca(i["r"]["lat"], i["r"]["lon"]))
        except Pendiente as e:
            i["pendiente"] = str(e)
    # 2. eje kilométrico y 3. decisión
    for i in infos.values():
        if i.get("pendiente"):
            continue
        try:
            i["eje"] = eje_km(i["r"], i["cands"], _vecinos(i["r"], infos), serv)
            _decidir(i, serv)
        except Pendiente as e:
            i["pendiente"] = str(e)
    # 4. coherencia y 5. confianza
    _coherencia(infos)
    for id_, i in infos.items():
        if i.get("pendiente"):
            resultados[id_] = {"resultado": "pendiente", "motivo": f"falta en la caché: {i['pendiente']}"}
            continue
        if "elegida" not in i:
            resultados[id_] = {"resultado": "ambos", "regla": None, "motivo": i.get("motivo", "sin datos"),
                               "candidatas": len(i["cands"])}
            continue
        regla = _confianza(i)
        # Cabina pegada a otra calzada (20/11): con una regla débil manda la cercanía; con una fuerte (indicador
        # oficial, grupo coherente), dos indicios fuertes se contradicen y el radar pasa a "ambos sentidos".
        pegada = conflicto_cabina(i["cands"], i["elegida"]) if regla else None
        conflicto = None
        if pegada is not None:
            lejos, cerca = i["cands"][i["elegida"]]["d"], i["cands"][pegada]["d"]
            if regla in REGLAS_CEDEN_A_CABINA:
                i["elegida"], regla = pegada, "p"
            else:
                conflicto = (f"la cabina está a {round(cerca)} m de otra calzada y la elegida (regla {regla}) "
                             f"a {round(lejos)} m")
        c = i["cands"][i["elegida"]]
        res = {
            "resultado": "resuelto" if regla else "ambos",
            "regla": regla, "origen": i["origen"],
            "via": c["via"], "rumbo": round(c["rumbo"]), "punto": [round(c["p"][0], 6), round(c["p"][1], 6)],
            "sentido_km": _sentido_km(i, i["elegida"]),
            "candidatas": len(i["cands"]),
        }
        if "margen" in i:
            res["margen_m"] = round(i["margen"])
        if "lejos" in i:
            res["destino_a_m"] = round(i["lejos"])
        if i.get("corregido"):
            res["corregido"] = True
        if i.get("grupo"):
            res["grupo"] = i["grupo"]
        if not regla:
            res["motivo"] = "ninguna regla de confianza se cumple"
        veto = conflicto or veto_destino(i["r"], res, serv)
        if veto:
            res.update(resultado="ambos", motivo=veto, regla_vetada=res["regla"], regla=None)
        resultados[id_] = res
    return resultados


def resumen(resultados):
    """Recuentos por resultado y por regla."""
    por_res, por_regla = defaultdict(int), defaultdict(int)
    for v in resultados.values():
        por_res[v["resultado"]] += 1
        if v["resultado"] == "resuelto":
            por_regla[v["regla"]] += 1
    return dict(por_res), dict(por_regla)
