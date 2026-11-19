import tempfile
import unittest
from pathlib import Path

from datos.pipeline import madrid, sct, tramos
from datos.pipeline.dgt import ErrorLectura

SCT = """(ETRS89)
Via         PK          Velocitat   X           Y           
A-2         445,35      120         288075,4643 4601625,063 
C-32 nord   85          120         441144,0836 4592656,0677
B-10        18,5        80          42552972    457695385   
C-17        37,79-41,0  80          462228682   462463775   
"""


class PruebasSct(unittest.TestCase):
    def test_lee_huso_31_coma_que_falta_y_sin_tramos(self):
        rs = sct.leer_texto(SCT)
        self.assertEqual([r["id"] for r in rs], ["cat-a-2-445-35", "cat-c-32-nord-85", "cat-b-10-18-5"])
        a2 = rs[0]
        self.assertTrue(41.4 < a2["lat"] < 41.6 and 0.4 < a2["lon"] < 0.5, (a2["lat"], a2["lon"]))
        self.assertEqual((a2["carretera"], a2["pk_m"], a2["limite_oficial"], a2["fuente"]), ("A-2", 445350, 120, "sct"))
        self.assertEqual((rs[1]["carretera"], rs[1]["calzada"]), ("C-32", "nord"))
        self.assertAlmostEqual(rs[2]["lat"], 41.3406, places=3)
        self.assertEqual(sct.leer_texto.descartes, ["C-17 pk 37,79-41,0 (tramo)"])

    def test_copia_real(self):
        avisos = []
        r = sct.leer(None, avisos=avisos)
        self.assertGreaterEqual(len(r["radares"]), 180)
        self.assertTrue(any("tramo" in a for a in avisos))

    def test_si_falla_la_descarga_usa_la_copia(self):
        avisos = []

        def falla(url):
            raise OSError("sin red")
        r = sct.leer(falla, avisos=avisos)
        self.assertTrue(r["usada_copia"])
        self.assertTrue(any("no se pudo leer" in a for a in avisos))

    def test_cifras_raras(self):
        with self.assertRaises(ErrorLectura):
            sct._bien(sct.leer_texto(SCT))


class PruebasMadrid(unittest.TestCase):
    def test_copia_real(self):
        r = madrid.leer(None)["radares"]
        fijos = [x for x in r if x["tipo"] == "fijo"]
        tramos_m = [x for x in r if x["tipo"] == "tramo"]
        self.assertEqual((len(fijos), len(tramos_m)), (27, 9))
        m30 = next(x for x in r if x["id"] == "mun-madrid-4")
        self.assertEqual((m30["carretera"], m30["pk_m"], m30["limite_oficial"]), ("M-30", 6700, 90))
        castellana = next(x for x in r if x["id"] == "mun-madrid-27")
        self.assertIsNone(castellana["carretera"])  # calle: aviso urbano
        a5 = next(x for x in r if x["id"] == "mun-madrid-29")
        self.assertEqual((a5["carretera"], a5["pk_m"], a5["pk_fin_m"]), ("A-5", 4000, 5750))
        self.assertAlmostEqual(a5["lat_fin"], 40.400124)

    def test_los_tramos_entran_en_el_calculo_de_tramos(self):
        lista = tramos.lista_tramos(madrid.leer(None)["radares"])
        self.assertEqual(len(lista), 9)
        a5 = next(t for t in lista if t["id"] == "mun-madrid-29")
        self.assertEqual(a5["largo_ref_m"], 1750)

    def test_sin_moviles(self):
        texto = "Nº RADAR;Ubicacion;Carretara o vial;UBICACIÓN Calle 30;PK;Sentido;Tipo;Carril;Longitud inicio tramo;Latitud inicio tramo;X (WGS84);Y (WGS84);Longitud;Latitud;Velocidad límite;Responsable\n" \
                "99;X;M-30;;1+000;;Móvil;;;;;;-3.7;40.4;90;DGP\n"
        self.assertEqual(madrid.leer_texto(texto), [])


if __name__ == "__main__":
    unittest.main()
