import json
import tempfile
import unittest
from pathlib import Path

from datos.pipeline import contraste, publicar


def radar(i, fuente="dgt", tipo="fijo", **kw):
    r = {"id": i, "fuente": fuente, "tipo": tipo, "nombre": i, "lat": 43.0, "lon": -2.0,
         "lat_fin": None, "lon_fin": None, "carretera": "A-8", "pk_m": 12500,
         "sentido_oficial": "creciente", "enlace_oficial": "x"}
    r.update(kw)
    return r


LINEA = [[43.0, -2.01], [43.0, -2.0]]


class PruebasConstruir(unittest.TestCase):
    def test_campos_y_pk_en_km(self):
        r = publicar.construir([radar("a")], {"a": {"sentido_km": "decreciente"}},
                               {"a": {"fiab": "alta", "puntos": LINEA}},
                               {"a": {"limite": 80, "origen": "oficial", "fiab": "alta"}})[0]
        self.assertEqual(r["pk"], 12.5)
        self.assertEqual(r["sentido"], "creciente")
        self.assertEqual(r["sentido_km"], "decreciente")
        self.assertEqual(r["aproximacion"], LINEA)
        self.assertEqual((r["limite"], r["limite_origen"]), (80, "oficial"))

    def test_destino_oficial_prevalece_como_sentido(self):
        r = publicar.construir([radar("a", destino="CANTABRIA", sentido_oficial=None)], {}, {}, {})[0]
        self.assertEqual(r["sentido"], "CANTABRIA")

    def test_dudosa_tambien_publica_linea(self):
        # 21/11: la app avisa por la línea (carretera y sentido) también si es dudosa, salvo bucles y giros bruscos.
        r = publicar.construir([radar("a")], {}, {"a": {"fiab": "dudosa", "puntos": LINEA, "largo_m": 500,
                                                       "motivos": ["400 m de túnel"]}}, {})[0]
        self.assertEqual(r["aproximacion"], LINEA)
        bucle = publicar.construir([radar("a")], {}, {"a": {"fiab": "dudosa", "puntos": LINEA, "largo_m": 500,
                                                           "motivos": ["la carretera vuelve sobre sí misma (rotonda o bucle)"]}}, {})[0]
        self.assertIsNone(bucle["aproximacion"])
        corta = publicar.construir([radar("a")], {}, {"a": {"fiab": "dudosa", "puntos": LINEA, "largo_m": 300}}, {})[0]
        self.assertIsNone(corta["aproximacion"])
        media_vuelta = [[43.0, -2.0], [43.002, -2.0], [43.0021, -2.0002], [43.0001, -2.0002]]
        self.assertFalse(publicar.dudosa_util({"puntos": media_vuelta, "largo_m": 500}))
        self.assertEqual(r["aproximacion_fiab"], "dudosa")

    def test_ambos_sin_puntos_es_dudosa(self):
        r = publicar.construir([radar("a")], {}, {"a": {"fiab": "dudosa", "puntos": None}}, {})[0]
        self.assertEqual(r["aproximacion_fiab"], "dudosa")

    def test_sin_calculo_no_tiene_fiabilidad(self):
        r = publicar.construir([radar("a")], {}, {}, {})[0]
        self.assertIsNone(r["aproximacion_fiab"])
        self.assertIsNone(r["limite"])


class PruebasComprobar(unittest.TestCase):
    def _rs(self, n, pre="dgt", **kw):
        return [dict(publicar.construir([radar(f"{pre}-{i}")], {}, {f"{pre}-{i}": {"fiab": "alta", "puntos": LINEA}}, {})[0], **kw)
                for i in range(n)]

    def test_bien(self):
        self.assertEqual(publicar.comprobar(self._rs(700), {}), [])

    def test_pocos(self):
        self.assertTrue(publicar.comprobar(self._rs(100), {}))

    def test_variacion_frente_a_lo_publicado(self):
        self.assertTrue(publicar.comprobar(self._rs(700), {}, self._rs(900)))

    def test_una_fuente_nueva_no_cuenta_como_variacion(self):
        # 19/11: entran Cataluña (cat-) y Madrid; lo que ya estaba no cambia.
        self.assertEqual(publicar.comprobar(self._rs(700) + self._rs(300, pre="cat"), {}, self._rs(700)), [])
        # Pero si una fuente que ya estaba cae, sí.
        self.assertTrue(publicar.comprobar(self._rs(700) + self._rs(10, pre="cat"), {}, self._rs(700) + self._rs(300, pre="cat")))

    def test_grupos(self):
        self.assertEqual([publicar.grupo(i) for i in ("dgt-CABINA_1", "mun-vitoria-cabina-3", "fr-madrid-109", "osm-123", "cat-c-32-85000")],
                         ["dgt", "mun-vitoria", "fr-madrid", "osm", "cat"])

    def test_limite_raro(self):
        rs = self._rs(700)
        rs[3]["limite"] = 500
        self.assertTrue(publicar.comprobar(rs, {}))

    def test_sin_aproximacion_calculada(self):
        rs = self._rs(700)
        for r in rs:
            r["aproximacion_fiab"] = None
        self.assertTrue(publicar.comprobar(rs, {}))


class PruebasMain(unittest.TestCase):
    def _preparar(self, d, n):
        crudos = {"fuentes": {"dgt": {"radares": n, "publicado": "p"}, "trafikoa": {"radares": 0},
                              "manual": {"radares": 0}},
                  "avisos": [], "radares": [radar(f"r{i}") for i in range(n)]}
        (d / "crudos.json").write_text(json.dumps(crudos), encoding="utf-8")
        (d / "cache").mkdir(exist_ok=True)
        (d / "cache" / "aproximacion.json").write_text(
            json.dumps({f"r{i}": {"fiab": "alta", "puntos": LINEA} for i in range(n)}), encoding="utf-8")

    def _ejecutar(self, d, ahora):
        return publicar.main(["--radares", str(d / "crudos.json"), "--cache", str(d / "cache"),
                              "--salida", str(d / "salida"), "--ahora", ahora])

    def test_publica_y_conserva_version_si_no_cambian_los_datos(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            self._preparar(d, 700)
            self.assertEqual(self._ejecutar(d, "2026-11-15T06:00:00Z"), 0)
            self.assertEqual(self._ejecutar(d, "2026-11-22T06:00:00Z"), 0)
            doc = json.loads((d / "salida" / "radares.json").read_text(encoding="utf-8"))
            est = json.loads((d / "salida" / "estado.json").read_text(encoding="utf-8"))
            self.assertEqual(doc["version"], "2026-11-15T06:00:00Z")
            self.assertEqual(est["generado"], "2026-11-22T06:00:00Z")
            self.assertTrue(est["ok"])

    def test_cifras_raras_no_tocan_lo_publicado(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            self._preparar(d, 700)
            self._ejecutar(d, "2026-11-15T06:00:00Z")
            antes = (d / "salida" / "radares.json").read_text(encoding="utf-8")
            self._preparar(d, 300)
            self.assertEqual(self._ejecutar(d, "2026-11-22T06:00:00Z"), 3)
            self.assertEqual((d / "salida" / "radares.json").read_text(encoding="utf-8"), antes)
            est = json.loads((d / "salida" / "estado.json").read_text(encoding="utf-8"))
            self.assertFalse(est["ok"])
            self.assertEqual(est["radares_publicados"], 700)
            self.assertTrue(any("NO PUBLICADO" in x for x in est["avisos"]))


class PruebasContraste(unittest.TestCase):
    def test_contar(self):
        self.assertEqual(contraste.contar([1, 2, 3]), 3)
        self.assertEqual(contraste.contar({"x": 1, "datos": [1, 2]}), 2)
        self.assertIsNone(contraste.contar({"x": 1}))

    def test_comparar(self):
        self.assertNotIn("aviso", contraste.comparar(98, 100))
        self.assertIn("aviso", contraste.comparar(98, 40))
        self.assertIn("aviso", contraste.comparar(98, None))


if __name__ == "__main__":
    unittest.main()
