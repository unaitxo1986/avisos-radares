"""Pruebas del algoritmo del sentido con una carretera de juguete y servicios simulados."""
import unittest

from datos.pipeline import geo, sentido
from datos.pipeline.servicios import Pendiente

M_POR_GRADO_LON = 111195 * 0.7314  # a latitud 43


def _lon(m):
    return -2.0 + m / M_POR_GRADO_LON


class ServicioFalso:
    """Carretera recta este-oeste a latitud 43 (calzada doble). Rutas: en el sentido de la marcha."""

    def __init__(self, vias, destinos=None):
        self.vias = vias
        self.destinos = destinos or {}

    def vias_cerca(self, lat, lon):
        return self.vias

    def ruta(self, o, rumbo_o, d, rumbo_d=None):
        dx = (d[1] - o[1]) * M_POR_GRADO_LON
        este = geo.dif_ang(rumbo_o, 90) < 90
        delante = dx >= 0 if este else dx <= 0
        return abs(dx) if delante else abs(dx) + 60000

    def geocodificar(self, texto):
        return self.destinos.get(texto, [])


def via_doble():
    return [{"id": 1, "t": {"highway": "primary", "ref": "N-1", "oneway": "no"},
             "n": [[43.0, _lon(-2000)], [43.0, _lon(12000)]]}]


def radar(id_, pk, x_m, **kw):
    r = {"id": id_, "fuente": "dgt", "tipo": "fijo", "nombre": id_, "lat": 43.0, "lon": _lon(x_m),
         "carretera": "N-1", "provincia": "X", "pk_m": pk, "sentido_oficial": None, "destino": None}
    r.update(kw)
    return r


class PruebasGeo(unittest.TestCase):
    def test_distancia_y_rumbo(self):
        self.assertAlmostEqual(geo.haversine(43, -2, 43, -2 + 1 / 0.7314 / 111.195 * 1.0) / 1000, 1.0, delta=0.01)
        self.assertAlmostEqual(geo.rumbo(43, -2, 43.1, -2), 0, delta=0.5)
        self.assertEqual(geo.dif_ang(350, 10), 20)

    def test_proyectar(self):
        d, _, rb = geo.proyectar(43.0005, -2.0, (43.0, -2.01), (43.0, -1.99))
        self.assertAlmostEqual(d, 55.6, delta=1)
        self.assertAlmostEqual(rb, 90, delta=0.5)


class PruebasSentido(unittest.TestCase):
    def test_oficial_creciente_con_eje(self):
        rs = [radar("a", 1000, 1000, sentido_oficial="creciente"), radar("b", 5000, 5000)]
        res = sentido.calcular(rs, ServicioFalso(via_doble()))
        a = res["a"]
        self.assertEqual(a["resultado"], "resuelto")
        self.assertEqual(a["regla"], "a")
        self.assertEqual(a["sentido_km"], "creciente")
        self.assertAlmostEqual(a["rumbo"], 90, delta=2)

    def test_oficial_decreciente(self):
        rs = [radar("a", 1000, 1000, sentido_oficial="decreciente"), radar("b", 5000, 5000)]
        a = sentido.calcular(rs, ServicioFalso(via_doble()))["a"]
        self.assertEqual(a["sentido_km"], "decreciente")
        self.assertAlmostEqual(a["rumbo"], 270, delta=2)

    def test_destino_lejano_al_este(self):
        destinos = {"Este": [{"lat": 43.0, "lon": _lon(40000), "imp": 0.5, "cat": "place", "tipo": "city", "nombre": "Este"}]}
        rs = [radar("a", 1000, 1000, destino="Este"), radar("b", 5000, 5000, destino="Este")]
        res = sentido.calcular(rs, ServicioFalso(via_doble(), destinos))
        for k in "ab":
            self.assertEqual(res[k]["resultado"], "resuelto", res[k])
            self.assertEqual(res[k]["sentido_km"], "creciente")
            self.assertEqual(res[k]["regla"], "b")  # grupo coherente de 2

    def test_destino_no_localizado_da_ambos(self):
        rs = [radar("a", 1000, 1000, destino="Nada"), radar("b", 5000, 5000)]
        a = sentido.calcular(rs, ServicioFalso(via_doble()))["a"]
        self.assertEqual(a["resultado"], "ambos")

    def test_sin_calzada_cercana(self):
        rs = [radar("a", 1000, 1000)]
        a = sentido.calcular(rs, ServicioFalso([]))["a"]
        self.assertEqual(a["resultado"], "ambos")

    def test_sentido_unico_una_candidata(self):
        via = [{"id": 2, "t": {"highway": "primary", "ref": "N-1", "oneway": "yes"},
                "n": [[43.0, _lon(-2000)], [43.0, _lon(12000)]]}]
        a = sentido.calcular([radar("a", 1000, 1000)], ServicioFalso(via))["a"]
        self.assertEqual(a["regla"], "d")
        self.assertAlmostEqual(a["rumbo"], 90, delta=2)

    def test_tipo_tramo_no_aplica(self):
        r = radar("t", 0, 0, tipo="tramo")
        self.assertEqual(sentido.calcular([r], ServicioFalso(via_doble()))["t"]["resultado"], "no_aplica")

    def test_pendiente_sin_red(self):
        class Sin(ServicioFalso):
            def vias_cerca(self, lat, lon):
                raise Pendiente("osm")
        a = sentido.calcular([radar("a", 1000, 1000)], Sin([]))["a"]
        self.assertEqual(a["resultado"], "pendiente")


if __name__ == "__main__":
    unittest.main()
