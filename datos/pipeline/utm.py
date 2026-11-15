"""Conversión de UTM (ETRS89, huso 30 norte) a latitud y longitud.

Series de Krüger. ETRS89 usa el elipsoide GRS80, prácticamente igual que WGS84
(diferencia inferior a 1 mm), así que el resultado sirve para WGS84.
Sin dependencias externas.
"""
import math

_A = 6378137.0
_F = 1 / 298.257222101
_K0 = 0.9996
_E0 = 500000.0


def utm_a_latlon(este: float, norte: float, huso: int = 30) -> tuple[float, float]:
    """Devuelve (latitud, longitud) en grados. Solo hemisferio norte."""
    n = _F / (2 - _F)
    a_ = _A / (1 + n) * (1 + n**2 / 4 + n**4 / 64)
    beta = (
        n / 2 - 2 * n**2 / 3 + 37 * n**3 / 96,
        n**2 / 48 + n**3 / 15,
        17 * n**3 / 480,
    )
    delta = (
        2 * n - 2 * n**2 / 3 - 2 * n**3,
        7 * n**2 / 3 - 8 * n**3 / 5,
        56 * n**3 / 15,
    )
    xi = norte / (_K0 * a_)
    eta = (este - _E0) / (_K0 * a_)
    xi_p = xi - sum(b * math.sin(2 * j * xi) * math.cosh(2 * j * eta)
                    for j, b in enumerate(beta, 1))
    eta_p = eta - sum(b * math.cos(2 * j * xi) * math.sinh(2 * j * eta)
                      for j, b in enumerate(beta, 1))
    chi = math.asin(math.sin(xi_p) / math.cosh(eta_p))
    lat = chi + sum(d * math.sin(2 * j * chi) for j, d in enumerate(delta, 1))
    lon0 = math.radians(6 * huso - 183)
    lon = lon0 + math.atan2(math.sinh(eta_p), math.cos(xi_p))
    return math.degrees(lat), math.degrees(lon)
