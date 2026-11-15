"""Pruebas del límite de cada radar (3.4)."""
import math
import unittest

from datos.pipeline import geo, limites

KY = geo.R_TIERRA * math.pi / 180
KX = KY * math.cos(math.radians(43.0))


def P(x, y=0.0):
    return [43.0 + y / KY, -2.0 + x / KX]


def via(id_, pts, **t):
    t.setdefault("highway", "primary")
    return {"id": id_, "t": t, "n": [P(*p) for p in pts]}


def radar(**kw):
    r = {"id": "r", "tipo": "fijo", "lat": 43.0, "lon": -2.0, "carretera": "A-1",
         "limite_oficial": None, "limite_texto": None, "limite_manual": None}
    r.update(kw)
    return r


def res(via_id):
    return {"resultado": "resuelto", "via": via_id}


class PruebasMaxspeed(unittest.TestCase):
    def test_valores(self):
        self.assertEqual(limites.num_maxspeed("80"), 80)
        self.assertEqual(limites.num_maxspeed("100 km/h"), 100)
        for malo in (None, "ES:urban", "none", "walk", "50 mph", "50;30", "10", "150", "", "signals"):
            self.assertIsNone(limites.num_maxspeed(malo), malo)


class PruebasOficial(unittest.TestCase):
    def test_numero_directo(self):
        self.assertEqual(limites.limite_oficial(radar(limite_oficial=80, limite_texto="80 km/h")), (80, None))

    def test_dos_limites_usa_el_mas_bajo_y_lo_anota(self):
        n, nota = limites.limite_oficial(radar(limite_texto="60/80 km/h"))
        self.assertEqual(n, 60)
        self.assertIn("60/80", nota)

    def test_sin_numero(self):
        self.assertEqual(limites.limite_oficial(radar(limite_texto="-")), (None, None))


class PruebasPrioridad(unittest.TestCase):
    VIAS = [via(1, [(-100, 0), (100, 0)], maxspeed="90", ref="A-1")]

    def test_oficial_manda_aunque_osm_difiera(self):
        r = limites.limite_de(radar(limite_oficial=60, limite_manual=70), res(1), self.VIAS)
        self.assertEqual((r["limite"], r["origen"], r["fiab"]), (60, "oficial", "alta"))
        self.assertEqual(r["osm"], 90)
        self.assertTrue(r["discrepa"])

    def test_manual_antes_que_osm(self):
        r = limites.limite_de(radar(limite_manual=70), res(1), self.VIAS)
        self.assertEqual((r["limite"], r["origen"]), (70, "manual"))

    def test_osm_de_la_calzada_del_radar(self):
        r = limites.limite_de(radar(), res(1), self.VIAS)
        self.assertEqual((r["limite"], r["origen"], r["fiab"]), (90, "osm", "media"))

    def test_sin_nada(self):
        r = limites.limite_de(radar(), res(1), [via(1, [(-100, 0), (100, 0)], ref="A-1")])
        self.assertEqual((r["limite"], r["origen"], r["fiab"]), (None, None, None))

    def test_sin_datos_de_osm_en_la_cache(self):
        r = limites.limite_de(radar(limite_oficial=80), res(1), None)
        self.assertEqual(r["limite"], 80)
        r = limites.limite_de(radar(), res(1), None)
        self.assertIsNone(r["limite"])


class PruebasCercanas(unittest.TestCase):
    def test_via_sin_maxspeed_usa_la_calzada_contraria_de_la_misma_carretera(self):
        vias = [via(1, [(-100, 0), (100, 0)], ref="A-1"),
                via(2, [(100, 15), (-100, 15)], maxspeed="100", ref="A-1")]
        r = limites.limite_de(radar(), res(1), vias)
        self.assertEqual((r["limite"], r["origen"], r["fiab"]), (100, "osm_cercana", "baja"))

    def test_dos_vias_que_discrepan_no_dan_limite(self):
        vias = [via(1, [(-100, 0), (100, 0)], ref="A-1"),
                via(2, [(100, 15), (-100, 15)], maxspeed="100", ref="A-1"),
                via(3, [(-100, 25), (100, 25)], maxspeed="50", ref="A-1")]
        self.assertIsNone(limites.limite_de(radar(), res(1), vias)["limite"])

    def test_una_calle_de_otra_carretera_no_cuenta(self):
        vias = [via(1, [(-100, 0), (100, 0)], ref="A-1"),
                via(2, [(-100, 20), (100, 20)], maxspeed="30", ref="N-240")]
        self.assertIsNone(limites.limite_de(radar(), res(1), vias)["limite"])

    def test_vias_lejanas_no_cuentan(self):
        vias = [via(1, [(-100, 0), (100, 0)], ref="A-1"),
                via(2, [(-100, 80), (100, 80)], maxspeed="100", ref="A-1")]
        self.assertIsNone(limites.limite_de(radar(), res(1), vias)["limite"])

    def test_radar_sin_via_elegida(self):
        vias = [via(1, [(-100, 0), (100, 0)], maxspeed="80", ref="A-1")]
        r = limites.limite_de(radar(), {"resultado": "ambos"}, vias)
        self.assertEqual((r["limite"], r["origen"]), (80, "osm_cercana"))


class PruebasTramos(unittest.TestCase):
    def test_los_tramos_no_usan_osm(self):
        vias = [via(1, [(-100, 0), (100, 0)], maxspeed="90", ref="A-1")]
        r = limites.limite_de(radar(tipo="tramo"), {"resultado": "no_aplica"}, vias)
        self.assertIsNone(r["limite"])

    def test_extremo_con_limite_oficial(self):
        r = limites.limite_de(radar(tipo="tramo_extremo", limite_oficial=60), {"resultado": "no_aplica"}, [])
        self.assertEqual((r["limite"], r["origen"]), (60, "oficial"))


if __name__ == "__main__":
    unittest.main()
