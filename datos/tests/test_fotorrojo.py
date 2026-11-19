import json
import tempfile
import unittest
from pathlib import Path

from datos.pipeline import fotorrojo

COPIA = Path(__file__).resolve().parents[1] / "fuentes" / "fotorrojo.json"

MADRID = ('id;"nombre";distrito;id_cruce;activo;"fecha_denuncia";"utm_x";"utm_y";longitud;latitud\n'
          '724;"ALBERTO AGUILERA - BALTASAR GRACIÁN";1;701;1;"2016/09/26";"1";"2";-3.7109220647;40.4300319733\n'
          '725;"RETIRADO";1;701;0;"2016/09/26";"1";"2";-3.7;40.43\n').encode("cp1252")
MALAGA = ('FID,ID,NOMBRE,DESCRIPCION,DIRECCION,SDOGEOMETRIA\n'
          '1,79133,Foto-Rojo Jiménez Fraud dirección Hospital Clínico,x,y,POINT (-4.475607482816217 36.71642190674621)\n'
          '2,80734,AFECTACION_00000451_277314,Corte de tráfico,y,POINT (-4.45 36.71)\n').encode("utf-8")
GEOJSON = json.dumps({"type": "FeatureCollection", "features": [
    {"type": "Feature", "properties": {"OBJECTID": 7, "Ubicacion": "Av. Tres de Mayo"},
     "geometry": {"type": "Point", "coordinates": [-16.25, 28.46]}}]}).encode("utf-8")


class PruebasFotoRojo(unittest.TestCase):
    def test_madrid_solo_activos_y_acentos(self):
        r = fotorrojo.registros_madrid(MADRID)
        self.assertEqual(r, [{"id": "724", "nombre": "ALBERTO AGUILERA - BALTASAR GRACIÁN", "lat": 40.430032, "lon": -3.710922}])

    def test_malaga_solo_camaras(self):
        r = fotorrojo.registros_malaga(MALAGA)
        self.assertEqual([x["nombre"] for x in r], ["Jiménez Fraud dirección Hospital Clínico"])
        self.assertAlmostEqual(r[0]["lat"], 36.716422)

    def test_geojson(self):
        r = fotorrojo.registros_geojson(GEOJSON)
        self.assertEqual(r, [{"id": "7", "nombre": "Av. Tres de Mayo", "lat": 28.46, "lon": -16.25}])

    def test_copia_real(self):
        r = fotorrojo.leer(None)
        ids = [x["id"] for x in r["radares"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(r["por_fuente"]["madrid"], 30)
        self.assertTrue(all(x["tipo"] == "fotorrojo" and x["carretera"] is None for x in r["radares"]))

    def test_si_falla_la_descarga_usa_la_copia(self):
        avisos = []
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "f.json"
            p.write_text(COPIA.read_text(encoding="utf-8"), encoding="utf-8")

            def falla(url):
                raise OSError("sin red")
            r = fotorrojo.leer(falla, ruta_copia=p, hoy="2026-11-19", avisos=avisos)
        self.assertEqual(len(r["radares"]), len(fotorrojo.leer(None)["radares"]))
        self.assertEqual(len(avisos), 3)

    def test_cifras_raras_no_renuevan_la_copia(self):
        avisos = []
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "f.json"
            p.write_text(COPIA.read_text(encoding="utf-8"), encoding="utf-8")
            antes = p.read_text(encoding="utf-8")

            def baja(url):
                if "madrid" in url:
                    return MADRID  # una sola cámara: fuera de rango
                if "malaga" in url:
                    return MALAGA
                raise OSError("sin red")
            fotorrojo.leer(baja, ruta_copia=p, hoy="2026-11-19", avisos=avisos)
            self.assertTrue(any("madrid" in a for a in avisos))
            self.assertEqual(json.loads(p.read_text(encoding="utf-8"))["madrid"], json.loads(antes)["madrid"])


if __name__ == "__main__":
    unittest.main()
