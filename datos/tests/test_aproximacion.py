"""Pruebas del tramo de aproximación (3.3) con carreteras de juguete (coordenadas en metros)."""
import math
import unittest

from datos.pipeline import aproximacion, calcular_aproximacion, geo

KY = geo.R_TIERRA * math.pi / 180
KX = KY * math.cos(math.radians(43.0))


def P(x, y=0.0):
    return [43.0 + y / KY, -2.0 + x / KX]


def via(id_, pts, **t):
    t.setdefault("highway", "motorway")
    return {"id": id_, "t": t, "n": [P(*p) for p in pts]}


def res(via_id, x, rumbo, y=0.0):
    return {"resultado": "resuelto", "via": via_id, "rumbo": rumbo, "punto": P(x, y)}


RADAR = {"id": "r", "carretera": "A-1", "lat": 43.0, "lon": -2.0}


def x_de(p):
    return round((p[1] + 2.0) * KX)


class PruebasAproximacion(unittest.TestCase):
    def test_recta_de_sentido_unico_en_dos_vias(self):
        vias = [via(1, [(0, 0), (600, 0)], oneway="yes", ref="A-1"),
                via(2, [(600, 0), (2000, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(2, 1000, 90), vias)
        self.assertEqual(r["fiab"], "alta", r)
        self.assertEqual(r["largo_m"], 1000)  # 1000 m desde el 23/11 (antes 500)
        self.assertEqual(len(r["puntos"]), 2)  # recta: se simplifica a los extremos
        self.assertAlmostEqual(x_de(r["puntos"][0]), 0, delta=2)
        self.assertAlmostEqual(x_de(r["puntos"][-1]), 1000, delta=2)

    def test_doble_sentido_hacia_el_oeste(self):
        vias = [via(1, [(0, 0), (3000, 0)], highway="primary", oneway="no", ref="A-1")]
        r = aproximacion.construir(RADAR, res(1, 1500, 270), vias)
        self.assertEqual(r["fiab"], "alta", r)
        self.assertAlmostEqual(x_de(r["puntos"][0]), 2500, delta=2)  # se viene desde el este
        self.assertAlmostEqual(x_de(r["puntos"][-1]), 1500, delta=2)

    def test_sentido_unico_inverso_recorre_los_nodos_al_reves(self):
        vias = [via(1, [(0, 0), (3000, 0)], oneway="-1", ref="A-1")]
        r = aproximacion.construir(RADAR, res(1, 1500, 270), vias)
        self.assertAlmostEqual(x_de(r["puntos"][0]), 2500, delta=2)

    def test_curva_se_conserva(self):
        # Recta hasta x=700, curva hacia el norte; radar en el tramo norte.
        vias = [via(1, [(0, 0), (700, 0), (800, 40), (800, 400), (800, 900)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(1, 800, 0, y=300), vias)
        self.assertEqual(r["fiab"], "alta", r)
        self.assertGreater(len(r["puntos"]), 2)
        # el tramo empieza antes de la curva (x < 700) y acaba en el radar (x = 800)
        self.assertLess(x_de(r["puntos"][0]), 700)
        self.assertAlmostEqual(x_de(r["puntos"][-1]), 800, delta=2)

    def test_carretera_corta_es_dudosa(self):
        vias = [via(1, [(700, 0), (2000, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(1, 1000, 90), vias)
        self.assertEqual(r["fiab"], "dudosa")
        self.assertEqual(r["largo_m"], 300)
        self.assertIn("deja de estar", r["motivos"][0])

    def test_enlace_que_se_incorpora_no_confunde(self):
        vias = [via(1, [(0, 0), (1000, 0)], oneway="yes", ref="A-1"),
                via(2, [(1000, 0), (2000, 0)], oneway="yes", ref="A-1"),
                via(3, [(750, -250), (1000, 0)], highway="motorway_link", oneway="yes")]
        r = aproximacion.construir(RADAR, res(2, 1300, 90), vias)
        self.assertEqual(r["fiab"], "alta", r)
        self.assertEqual(r["cruces"], 1)
        self.assertAlmostEqual(x_de(r["puntos"][0]), 300, delta=2)

    def test_dos_entradas_equivalentes_son_ambiguas(self):
        # dos autovías con la misma referencia que confluyen con un ángulo de 10 grados
        vias = [via(1, [(0, 0), (1000, 0)], oneway="yes", ref="A-1"),
                via(2, [(0, 180), (1000, 0)], oneway="yes", ref="A-1"),
                via(3, [(1000, 0), (2000, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(3, 1300, 90), vias)
        self.assertEqual(r["fiab"], "dudosa")
        self.assertEqual(r["ambiguos"], 1)

    def test_la_prolongacion_acaba_en_el_primer_cruce_dudoso(self):
        # 23/11: el mismo cruce ambiguo a 700 m del radar (más allá de los 500 m) no hace dudosa la línea: la
        # prolongación se corta ahí.
        vias = [via(1, [(0, 0), (1000, 0)], oneway="yes", ref="A-1"),
                via(2, [(0, 180), (1000, 0)], oneway="yes", ref="A-1"),
                via(3, [(1000, 0), (2000, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(3, 1700, 90), vias)
        self.assertEqual((r["fiab"], r["largo_m"], r["ambiguos"]), ("alta", 700, 0), r)
        self.assertAlmostEqual(x_de(r["puntos"][0]), 1000, delta=2)

    def test_la_prolongacion_pide_otro_recuadro(self):
        # Los datos descargados acaban a 600 m del radar (borde del recuadro): se piden más alrededor de ese punto.
        vias = [via(2, [(900, 0), (2000, 0)], oneway="yes", ref="A-1")]
        pedidos = []

        def mas(lat, lon):
            pedidos.append(x_de([lat, lon]))
            return [via(1, [(0, 0), (900, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(2, 1500, 90), vias, mas_vias=mas)
        self.assertEqual((r["fiab"], r["largo_m"]), ("alta", 1000), r)
        self.assertEqual(pedidos, [900])
        # Sin más datos, la línea se queda en lo que hay (600 m), y sigue siendo buena.
        r = aproximacion.construir(RADAR, res(2, 1500, 90), vias, mas_vias=lambda lat, lon: None)
        self.assertEqual((r["fiab"], r["largo_m"]), ("alta", 600))

    def test_el_enlace_de_otro_tipo_no_cuenta_como_ambiguo_aunque_gire_poco(self):
        vias = [via(1, [(0, 0), (1000, 0)], oneway="yes", ref="A-1"),
                via(2, [(0, 180), (1000, 0)], highway="motorway_link", oneway="yes"),
                via(3, [(1000, 0), (2000, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(3, 1300, 90), vias)
        self.assertEqual(r["fiab"], "alta", r)

    def test_tunel_largo_es_dudoso(self):
        vias = [via(1, [(0, 0), (1000, 0)], oneway="yes", ref="A-1", tunnel="yes"),
                via(2, [(1000, 0), (2000, 0)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(2, 1310, 90), vias)
        self.assertEqual(r["tunel_m"], 190)
        self.assertEqual(r["fiab"], "alta")  # por debajo del umbral
        r = aproximacion.construir(RADAR, res(2, 1100, 90), vias)
        self.assertEqual(r["tunel_m"], 400)
        self.assertEqual(r["fiab"], "dudosa")
        self.assertIn("túnel", r["motivos"][0])

    def test_via_que_no_esta_en_los_datos(self):
        r = aproximacion.construir(RADAR, res(99, 1000, 90), [via(1, [(0, 0), (2000, 0)], oneway="yes")])
        self.assertEqual(r["fiab"], "dudosa")
        self.assertIsNone(r["puntos"])

    def test_bucle_no_se_cuelga(self):
        # un anillo de sentido único de 300 m de perímetro conectado al radar
        anillo = [(0, 0), (100, 0), (100, 100), (0, 100), (0, 0)]
        vias = [via(1, anillo, oneway="yes", junction="roundabout")]
        r = aproximacion.construir(RADAR, res(1, 50, 90), vias)
        self.assertEqual(r["fiab"], "dudosa")
        self.assertIn("sobre sí misma", r["motivos"][0])

    def test_calzadas_paralelas_no_se_mezclan(self):
        # dos calzadas separadas 20 m: el tramo sigue la elegida, no salta a la otra
        vias = [via(1, [(0, 0), (2000, 0)], oneway="yes", ref="A-1"),
                via(2, [(2000, 20), (0, 20)], oneway="yes", ref="A-1")]
        r = aproximacion.construir(RADAR, res(1, 1000, 90), vias)
        self.assertEqual(r["fiab"], "alta", r)
        for p in r["puntos"]:
            self.assertAlmostEqual((p[0] - 43.0) * KY, 0, delta=1)


class PruebasSimplificar(unittest.TestCase):
    def test_puntos_alineados(self):
        pts = [tuple(P(x)) for x in range(0, 500, 50)]
        self.assertEqual(len(aproximacion._simplificar(pts)), 2)

    def test_esquina_se_conserva(self):
        pts = [tuple(P(0, 0)), tuple(P(100, 0)), tuple(P(100, 100))]
        self.assertEqual(len(aproximacion._simplificar(pts)), 3)


class PruebasDestilar(unittest.TestCase):
    def test_segmento_largo_que_cruza_el_radio_se_conserva(self):
        from datos.pipeline.servicios import Servicios
        # nodos a -1500, 0 y 3000 m: solo el del medio está a menos de 700 m, pero los segmentos se conservan
        nodos = [{"type": "node", "id": i, "lat": P(x)[0], "lon": P(x)[1]} for i, x in enumerate((-1500, 0, 3000))]
        datos = {"elements": nodos + [{"type": "way", "id": 7, "nodes": [0, 1, 2],
                                      "tags": {"highway": "motorway", "tunnel": "yes", "name": "x"}}]}
        vias = Servicios._destilar_osm(datos, 43.0, -2.0, 700.0, ("highway", "tunnel"), partir=True)
        self.assertEqual(len(vias), 1)
        self.assertEqual(len(vias[0]["n"]), 3)
        self.assertEqual(vias[0]["t"], {"highway": "motorway", "tunnel": "yes"})

    def test_via_que_sale_y_vuelve_se_parte_en_dos_trozos(self):
        from datos.pipeline.servicios import Servicios
        xs = (0, 100, 5000, 6000, 200, 300)
        nodos = [{"type": "node", "id": i, "lat": P(x)[0], "lon": P(x)[1]} for i, x in enumerate(xs)]
        datos = {"elements": nodos + [{"type": "way", "id": 7, "nodes": list(range(6)),
                                      "tags": {"highway": "primary"}}]}
        vias = Servicios._destilar_osm(datos, 43.0, -2.0, 700.0, ("highway",), partir=True)
        self.assertEqual(len(vias), 2)


class ServicioFalso:
    def __init__(self, vias):
        self.vias = vias

    def vias_aprox(self, lat, lon):
        return self.vias


class PruebasCalcular(unittest.TestCase):
    def test_resueltos_ambos_y_no_aplica(self):
        vias = [via(1, [(0, 0), (2000, 0)], oneway="yes", ref="A-1")]
        radares = [dict(RADAR, id="a"), dict(RADAR, id="b"), dict(RADAR, id="c")]
        sentido = {"a": res(1, 1000, 90),
                   "b": {"resultado": "ambos", "motivo": "ninguna regla de confianza se cumple"},
                   "c": {"resultado": "no_aplica"}}
        out = calcular_aproximacion.calcular(radares, sentido, ServicioFalso(vias))
        self.assertEqual(out["a"]["fiab"], "alta")
        self.assertEqual(out["b"]["fiab"], "dudosa")
        self.assertIsNone(out["b"]["puntos"])
        self.assertNotIn("c", out)

    def test_solo_los_ids_pedidos(self):
        vias = [via(1, [(0, 0), (2000, 0)], oneway="yes", ref="A-1")]
        radares = [dict(RADAR, id="a"), dict(RADAR, id="b")]
        sentido = {"a": res(1, 1000, 90), "b": res(1, 1000, 90)}
        out = calcular_aproximacion.calcular(radares, sentido, ServicioFalso(vias), ids={"b"})
        self.assertEqual(list(out), ["b"])


if __name__ == "__main__":
    unittest.main()
