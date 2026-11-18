"""Geometría sobre latitud y longitud, sin dependencias externas."""
import math

R_TIERRA = 6371008.8


def haversine(lat1, lon1, lat2, lon2):
    """Distancia en metros entre dos puntos."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_TIERRA * math.asin(math.sqrt(a))


def rumbo(lat1, lon1, lat2, lon2):
    """Rumbo inicial en grados (0 = norte, 90 = este) de un punto a otro."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def dif_ang(a, b):
    """Diferencia entre dos rumbos, de 0 a 180 grados."""
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


def proyectar(lat, lon, a, b):
    """Proyecta el punto (lat, lon) sobre el segmento a-b (cada extremo (lat, lon)).

    Devuelve (distancia en metros al segmento, punto proyectado (lat, lon),
    rumbo del segmento de a hacia b). Aproximación plana válida para segmentos cortos.
    """
    ky = R_TIERRA * math.pi / 180
    kx = ky * math.cos(math.radians(lat))
    ax, ay = (a[1] - lon) * kx, (a[0] - lat) * ky
    bx, by = (b[1] - lon) * kx, (b[0] - lat) * ky
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
    px, py = ax + t * dx, ay + t * dy
    return math.hypot(px, py), (lat + py / ky, lon + px / kx), (math.degrees(math.atan2(dx, dy)) + 360) % 360


def simplificar(pts, tol):
    """Douglas-Peucker sobre coordenadas planas locales; conserva el primer y el último punto."""
    if len(pts) < 3:
        return list(pts)
    ky = R_TIERRA * math.pi / 180
    kx = ky * math.cos(math.radians(pts[0][0]))
    xy = [(p[1] * kx, p[0] * ky) for p in pts]

    def dist(i, a, b):
        (px, py), (ax, ay), (bx, by) = xy[i], xy[a], xy[b]
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        t = 0 if l2 == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / l2))
        return math.hypot(px - (ax + t * dx), py - (ay + t * dy))

    mantener = {0, len(pts) - 1}
    pila = [(0, len(pts) - 1)]
    while pila:
        a, b = pila.pop()
        mx, mi = 0.0, None
        for i in range(a + 1, b):
            d = dist(i, a, b)
            if d > mx:
                mx, mi = d, i
        if mi is not None and mx > tol:
            mantener.add(mi)
            pila += [(a, mi), (mi, b)]
    return [pts[i] for i in sorted(mantener)]


def largo(pts):
    """Longitud en metros de una línea [[lat, lon], ...]."""
    return sum(haversine(a[0], a[1], b[0], b[1]) for a, b in zip(pts, pts[1:]))


def puntos_a_lo_largo(pts, distancias):
    """Puntos (lat, lon) de la línea a esas distancias desde el principio (ordenadas, en metros)."""
    res, acum, i = [], 0.0, 0
    for d in distancias:
        while i < len(pts) - 1:
            seg = haversine(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
            if acum + seg >= d:
                f = 0.0 if seg == 0 else (d - acum) / seg
                res.append((pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f,
                            pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f))
                break
            acum += seg
            i += 1
        else:
            res.append(tuple(pts[-1]))
    return res
