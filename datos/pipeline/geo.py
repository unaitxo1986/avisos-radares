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
