import json
import tempfile
import unittest
from pathlib import Path

from datos.pipeline import municipales
from datos.pipeline.dgt import ErrorLectura

COPIA = Path(__file__).resolve().parents[1] / "fuentes" / "municipales.json"


def _identify(n):
    return {"results": [{"layerId": 36, "attributes": {"OBJECTID": str(i), "Tipo_de_elemento": "Cabina de radar de velocidad",
                                                        "Calle": "ZARAMAGA ", "Descripción": "Nº 2", "Control": "Un sentido"},
                         "geometry": {"x": 527064.11 + i, "y": 4745445.61}} for i in range(n)]}


def _query(n):
    return {"features": [{"attributes": {"FID": i, "IzenKalea": "Sibilia", "Abiadura": 50},
                          "geometry": {"x": -1.96, "y": 43.31}} for i in range(n)]}


class PruebasMunicipales(unittest.TestCase):
    def _copia(self, d):
        p = Path(d) / "m.json"
        p.write_text(COPIA.read_text(encoding="utf-8"), encoding="utf-8")
        return p

    def test_copia_real(self):
        r = municipales.leer(None)
        ids = [x["id"] for x in r["radares"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len(ids), 34)
        don = [x for x in r["radares"] if x["id"].startswith("mun-donostia")]
        self.assertTrue(all(x["limite_oficial"] in (30, 50) for x in don))
        vit = [x for x in r["radares"] if x["id"].startswith("mun-vitoria-cabina")]
        self.assertTrue(all(x["rotatoria"] for x in vit))
        # Vitoria-Gasteiz, en grados
        self.assertTrue(all(42.8 < x["lat"] < 42.9 and -2.75 < x["lon"] < -2.6 for x in vit))

    def test_descarga_buena_renueva_copia(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._copia(d)
            def bajar(url):
                return json.dumps(_identify(12) if "identify" in url else _query(10)).encode()
            r = municipales.leer(bajar, ruta_copia=p, hoy="2026-12-01")
            c = json.loads(p.read_text(encoding="utf-8"))
            self.assertEqual(c["vitoria_cabinas"]["leido"], "2026-12-01")
            self.assertEqual(len(c["donostia_radares"]["registros"]), 10)
            self.assertEqual(r["copia_usada"], ["vitoria_fotorrojo"])

    def test_descarga_fallida_o_rara_usa_copia(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._copia(d)
            antes = p.read_text(encoding="utf-8")
            avisos = []
            def bajar(url):
                if "identify" in url:
                    raise OSError("sin respuesta")
                return json.dumps(_query(2)).encode()  # demasiado pocos
            r = municipales.leer(bajar, ruta_copia=p, avisos=avisos)
            self.assertEqual(p.read_text(encoding="utf-8"), antes)
            self.assertEqual(len(avisos), 2)
            self.assertEqual(len(r["radares"]), 34)

    def test_copia_rota(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "m.json"
            p.write_text("{", encoding="utf-8")
            with self.assertRaises(ErrorLectura):
                municipales.leer(None, ruta_copia=p)


if __name__ == "__main__":
    unittest.main()
