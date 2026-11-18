"""Pruebas de los radares de tramo (fase 5) con una autovía de juguete (coordenadas en metros).

Autovía este-oeste con dos calzadas de sentido único: la que va hacia el este al sur (y = -15) y la que
va hacia el oeste al norte (y = +15), como en España (se circula por la derecha).
"""
import math
import unittest

from datos.pipeline import geo, publicar, tramos

KY = geo.R_TIERRA * math.pi / 180
KX = KY * math.cos(math.radians(43.0))


def P(x, y=0.0):
    return [round(43.0 + y / KY, 7), round(-2.0 + x / KX, 7)]


def xy(p):
    return ((p[1] + 2.0) * KX, (p[0] - 43.0) * KY)


def via(id_, pts, **t):
    t.setdefault("highway", "motorway")
    t.setdefault("ref", "A-1")
    return {"id": id_, "t": t, "n": [P(*p) for p in pts]}


def autovia(cortes_este=None):
    """Calzada este (sur) partida en trozos [(x0, x1, maxspeed)] y calzada oeste (norte) a 120."""
    cortes_este = cortes_este or [(-3000, 9000, "120")]
    vias = [via(10 + i, [(a, -15), (b, -15)], oneway="yes", maxspeed=m) for i, (a, b, m) in enumerate(cortes_este)]
    vias.append(via(2, [(9000, 15), (-3000, 15)], oneway="yes", maxspeed="120"))
    return vias


class ServicioFalso:
    """Rutas de juguete: hacia el este se llega directo; hacia el oeste hay que dar la vuelta 1 km más allá."""

    def __init__(self, vias, rumbo_llegada_obligado=False):
        self.vias = vias
        self.rutas = []

    def vias_cerca(self, lat, lon):
        x, y = xy([lat, lon])
        return [v for v in self.vias if any(abs(xy(p)[1] - y) < 200 for p in v["n"])]

    def vias_aprox(self, lat, lon):
        return self.vias

    def ruta_geom(self, o, rumbo_o, d, rumbo_d=None):
        self.rutas.append((rumbo_o, rumbo_d))
        x0, _ = xy(o)
        x1, _ = xy(d)
        if abs(rumbo_o - 90) < 30 and x1 > x0:
            pts = [P(x0, -15), P(x1, -15)]
            return {"dist": round(x1 - x0), "puntos": pts}
        if abs(rumbo_o - 270) < 30:  # va hacia el oeste: vuelta en x0 - 1000
            pts = [P(x0, 15), P(x0 - 1000, 15), P(x0 - 1000, -15), P(x1, -15)]
            return {"dist": round(2000 + x1 - x0), "puntos": pts}
        return None


def tramo(id_="dgt-t", x0=0, x1=3000, ref_m=3000, **kw):
    a, b = P(x0, -20), P(x1, -20)
    r = {"id": id_, "fuente": "dgt", "tipo": "tramo", "nombre": None, "lat": a[0], "lon": a[1],
         "lat_fin": b[0], "lon_fin": b[1], "carretera": "A-1", "pk_m": 10000, "pk_fin_m": 10000 + ref_m,
         "sentido_oficial": "creciente", "limite_oficial": None, "limite_texto": None, "enlace_oficial": "x"}
    r.update(kw)
    return r


class PruebasTramos(unittest.TestCase):
    def test_elige_la_calzada_que_llega_sin_dar_la_vuelta(self):
        t = tramos.lista_tramos([tramo()])[0]
        res = tramos.calcular_uno(t, ServicioFalso(autovia()))
        self.assertEqual(res["fiab"], "alta", res)
        self.assertAlmostEqual(res["largo_m"], 3000, delta=5)
        self.assertAlmostEqual(xy(res["linea"][0])[1], -15, delta=1)  # calzada sur, hacia el este
        self.assertEqual(tramos.distancia_fin(res), 5)
        self.assertEqual(res["aprox"]["fiab"], "alta", res["aprox"])
        self.assertAlmostEqual(xy(res["aprox"]["puntos"][0])[0], -500, delta=3)  # 500 m antes del inicio
        self.assertEqual((res["limite"]["limite"], res["limite"]["origen"], res["limite"]["fiab"]), (120, "osm", "media"))

    def test_si_la_ruta_no_cuadra_con_el_kilometraje_es_dudosa(self):
        t = tramos.lista_tramos([tramo(ref_m=10000)])[0]
        serv = ServicioFalso(autovia())
        res = tramos.calcular_uno(t, serv)
        self.assertEqual(res["fiab"], "dudosa")
        self.assertIsNone(res["linea"])
        self.assertIn("kilometraje", res["motivos"][0])
        self.assertTrue(any(r[1] is not None for r in serv.rutas))  # probó también fijando la llegada

    def test_tolerancia_del_kilometraje(self):
        self.assertTrue(tramos._cuadra(3600, 3000))  # 20 % de 3000 = 600 m
        self.assertFalse(tramos._cuadra(3700, 3000))
        self.assertTrue(tramos._cuadra(1500, 1250))    # mínimo 300 m
        self.assertTrue(tramos._cuadra(12000, 10000))
        self.assertFalse(tramos._cuadra(12100, 10000))

    def test_limite_que_cambia_dentro_del_tramo_usa_el_mas_bajo(self):
        t = tramos.lista_tramos([tramo(x1=4000, ref_m=4000)])[0]
        res = tramos.calcular_uno(t, ServicioFalso(autovia([(-3000, 2000, "100"), (2000, 9000, "80")])))
        lim = res["limite"]
        self.assertEqual(lim["muestras"], 4)
        self.assertEqual(lim["valores"], {80: 2, 100: 2})
        self.assertEqual((lim["limite"], lim["origen"]), (80, "osm_tramo_minimo"))

    def test_limite_casi_uniforme_usa_el_mayoritario(self):
        t = tramos.lista_tramos([tramo(x1=5000, ref_m=5000)])[0]
        res = tramos.calcular_uno(t, ServicioFalso(autovia([(-3000, 4000, "100"), (4000, 9000, "80")])))
        self.assertEqual((res["limite"]["limite"], res["limite"]["origen"]), (100, "osm"))

    def test_sin_maxspeed_en_la_mayoria_es_baja(self):
        t = tramos.lista_tramos([tramo(x1=4000, ref_m=4000)])[0]
        res = tramos.calcular_uno(t, ServicioFalso(autovia([(-3000, 1000, "100"), (1000, 9000, "")])))
        self.assertEqual((res["limite"]["limite"], res["limite"]["fiab"]), (100, "baja"))

    def test_limite_oficial_prevalece(self):
        t = tramos.lista_tramos([tramo(limite_oficial=60)])[0]
        res = tramos.calcular_uno(t, ServicioFalso(autovia()))
        self.assertEqual((res["limite"]["limite"], res["limite"]["origen"]), (60, "oficial"))

    def test_trabakua_se_empareja_en_dos_tramos(self):
        a, b = P(0, -20), P(3000, -20)
        ex = [{"id": "e2", "fuente": "trafikoa", "tipo": "tramo_extremo", "lat": b[0], "lon": b[1], "pk_m": 39800,
               "tramo_grupo": "T", "tramo_extremo": 2, "carretera": "BI-633"},
              {"id": "e1", "fuente": "trafikoa", "tipo": "tramo_extremo", "lat": a[0], "lon": a[1], "pk_m": 36990,
               "tramo_grupo": "T", "tramo_extremo": 1, "carretera": "BI-633"}]
        ts = {t["id"]: t for t in tramos.lista_tramos(ex)}
        self.assertEqual(set(ts), {"e1", "e2"})
        self.assertEqual((ts["e1"]["lat_fin"], ts["e1"]["lon_fin"]), (b[0], b[1]))
        self.assertEqual((ts["e2"]["lat_fin"], ts["e2"]["lon_fin"]), (a[0], a[1]))
        self.assertEqual(ts["e1"]["largo_ref_m"], 2810)
        self.assertEqual(ts["e1"]["tipo"], "tramo")

    def test_muestras_repartidas(self):
        linea = [P(0), P(3000)]
        xs = [round(xy(p)[0]) for p in tramos.muestras(linea)]
        self.assertEqual(xs, [500, 1500, 2500])
        self.assertEqual(len(tramos.muestras([P(0), P(300)])), 1)


class ServicioFinEnLaOtraCalzada(ServicioFalso):
    """Sin calzada de llegada fijada, OSRM pone el fin en la calzada contraria: la ruta pasa junto al fin, da la
    vuelta 300 m más allá y vuelve por la otra calzada (como Trabakua sentido Markina el 18/11)."""

    def ruta_geom(self, o, rumbo_o, d, rumbo_d=None):
        self.rutas.append((rumbo_o, rumbo_d))
        x0, _ = xy(o)
        x1, _ = xy(d)
        if abs(rumbo_o - 90) >= 30:
            return None
        if rumbo_d is None:
            pts = [P(x0, -15), P(x1 + 300, -15), P(x1 + 300, 15), P(x1, 15)]
            return {"dist": round(x1 - x0 + 630), "puntos": pts}
        if abs(rumbo_d - 90) < 30:
            return {"dist": round(x1 - x0), "puntos": [P(x0, -15), P(x1, -15)]}
        return None


class PruebasVuelta(unittest.TestCase):
    def test_ruta_que_vuelve_se_repite_fijando_la_llegada(self):
        t = tramos.lista_tramos([tramo(ref_m=2800)])[0]
        serv = ServicioFinEnLaOtraCalzada(autovia())
        res = tramos.calcular_uno(t, serv)
        self.assertEqual(res["fiab"], "alta", res)
        self.assertAlmostEqual(res["largo_m"], 3000, delta=5)
        self.assertFalse(tramos.vuelve(res["linea"]))

    def test_vuelve(self):
        self.assertFalse(tramos.vuelve([P(0), P(1000), P(2000)]))
        self.assertTrue(tramos.vuelve([P(0, -15), P(1300, -15), P(1300, 15), P(1000, 15)]))
        # Una curva cerrada corta (menos de 200 m de recorrido entre los dos puntos cercanos) no cuenta.
        self.assertFalse(tramos.vuelve([P(0, -15), P(50, -15), P(50, 15), P(0, 15)]))

    def test_sin_nodos_cerca_usa_el_recuadro_grande(self):
        class SinNodosCerca(ServicioFalso):
            def vias_cerca(self, lat, lon):
                return []
        t = tramos.lista_tramos([tramo()])[0]
        res = tramos.calcular_uno(t, SinNodosCerca(autovia()))
        self.assertEqual(res["fiab"], "alta", res)
        self.assertEqual(res["limite"]["limite"], 120)


class PruebasPublicarTramos(unittest.TestCase):
    def test_tramo_publicado(self):
        t = tramo()
        res = tramos.calcular_uno(tramos.lista_tramos([t])[0], ServicioFalso(autovia()))
        r = publicar.construir([t], {}, {}, {"dgt-t": {"limite": None, "origen": None, "fiab": None}},
                               {"dgt-t": res})[0]
        self.assertEqual(r["tipo"], "tramo")
        self.assertEqual(r["tramo_fiab"], "alta")
        self.assertEqual(r["tramo"], res["linea"])
        self.assertEqual(r["tramo_largo"], res["largo_m"])
        self.assertEqual(r["aproximacion_fiab"], "alta")
        self.assertEqual(r["aproximacion"], res["aprox"]["puntos"])
        self.assertEqual((r["limite"], r["limite_origen"], r["limite_fiab"]), (120, "osm", "media"))
        self.assertEqual((r["pk"], r["pk_fin"], r["sentido_km"]), (10.0, 13.0, "creciente"))
        self.assertEqual(publicar.comprobar([r] * 1, {}), [f"solo 1 radares (mínimo {publicar.MIN_RADARES})"])

    def test_sentido_km_sale_del_orden_inicio_fin(self):
        # Indicador oficial creciente pero el kilometraje baja: manda el orden inicio -> fin.
        t = tramo(pk_m=2650, pk_fin_m=1400, sentido_oficial="creciente")
        r = publicar.construir([t], {}, {}, {}, {"dgt-t": {"fiab": "dudosa", "linea": None, "aprox": None,
                                                           "lat_fin": t["lat_fin"], "lon_fin": t["lon_fin"],
                                                           "pk_fin_m": 1400}})[0]
        self.assertEqual(r["sentido_km"], "decreciente")
        self.assertIsNone(r["tramo"])
        self.assertEqual(r["tramo_fiab"], "dudosa")

    def test_extremo_de_trabakua_pasa_a_tramo(self):
        e = {"id": "e1", "fuente": "trafikoa", "tipo": "tramo_extremo", "nombre": "TRABAKUA TRAMO 1", "lat": 43.19,
             "lon": -2.56, "lat_fin": None, "lon_fin": None, "pk_m": 36990, "carretera": "BI-633", "destino": "MARKINA"}
        r = publicar.construir([e], {}, {}, {"e1": {"limite": 60, "origen": "oficial", "fiab": "alta"}},
                               {"e1": {"fiab": "dudosa", "linea": None, "aprox": None, "lat_fin": 43.21,
                                       "lon_fin": -2.54, "pk_fin_m": 39800}})[0]
        self.assertEqual((r["tipo"], r["lat_fin"], r["pk_fin"], r["sentido_km"], r["limite"]),
                         ("tramo", 43.21, 39.8, "creciente", 60))

    def test_tramo_alta_sin_linea_no_se_publica(self):
        r = {"id": "x", "fuente": "dgt", "tipo": "tramo", "limite": None, "aproximacion_fiab": None,
             "aproximacion": None, "tramo_fiab": "alta", "tramo": None, "tramo_largo": None}
        self.assertTrue(any("tramo con fiabilidad alta" in x for x in publicar.comprobar([r], {})))


if __name__ == "__main__":
    unittest.main()
