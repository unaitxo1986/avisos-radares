"""Pruebas de los lectores de fuentes. Sin red: usan datos con la misma forma
que las fuentes reales (comprobadas el 15/11/2026).

    python3 -I -m unittest discover -s datos/tests -t .
"""
import json
import tempfile
import unittest
from pathlib import Path

from datos.pipeline import dgt, manual, trafikoa
from datos.pipeline.dgt import ErrorLectura
from datos.pipeline.utm import utm_a_latlon

REF = Path(__file__).parent / "referencia" / "trafikoa_2026-11-15.txt"


def _filas_ref():
    return [l.split("|") for l in REF.read_text(encoding="utf-8").splitlines() if l.strip()]


def _html_trafikoa(filas):
    """Imita el bloque de JavaScript de la página oficial (incluida la variante
    de 8 elementos con enlace a la cámara de Trabakua)."""
    partes = ["<script>var imgXOffset = \"1px\";\n"]
    for x, y, th, nombre, dest, mun, th2, carr, km, vel in filas:
        vals7 = '["%s","%s","%s","%s","%s","%s","%s"]' % (nombre, dest, mun, th2, carr, km, vel)
        vals8 = vals7[:-1] + ',"https://example.invalid/cam"]'
        partes.append(
            f'var x = {x};\n var y = {y};\n var th = "{th}";\n var popupTitle = "{nombre}";\n'
            f' if (popupTitle == "TRABAKUA TRAMO 1"){{ var popupValores = {vals8}; }}'
            f' else {{ var popupValores = {vals7}; }}\n'
            f' var popupValores = {vals7};\n var popupValores = {vals7};\n'
        )
    partes.append("</script>")
    return "".join(partes)


class PruebasUTM(unittest.TestCase):
    def test_max_center(self):
        # Referencia comprobada en la fase 0: cae sobre la A-8.
        lat, lon = utm_a_latlon(499983.69, 4792619.83)
        self.assertAlmostEqual(lat, 43.2864, places=3)
        self.assertAlmostEqual(lon, -3.0002, places=3)

    def test_trabakua_coincide_con_la_pagina_oficial(self):
        # Los dos extremos que dio la página oficial en lat/lon (referencia técnica, 0.6).
        lat1, lon1 = utm_a_latlon(535605, 4782670)
        lat2, lon2 = utm_a_latlon(536973, 4784393)
        self.assertAlmostEqual(lat1, 43.19597, places=3)
        self.assertAlmostEqual(lon1, -2.56178, places=3)
        self.assertAlmostEqual(lat2, 43.21141, places=3)
        self.assertAlmostEqual(lon2, -2.54483, places=3)


class PruebasTrafikoa(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.filas = _filas_ref()
        cls.res = trafikoa.leer(_html_trafikoa(cls.filas))["radares"]

    def test_recuentos(self):
        self.assertEqual(len(self.filas), 98)
        self.assertEqual(len(self.res), 98)
        por_terr = {}
        for r in self.res:
            por_terr[r["provincia"]] = por_terr.get(r["provincia"], 0) + 1
        self.assertEqual(por_terr, {"Bizkaia": 40, "Araba": 21, "Gipuzkoa": 37})

    def test_ids_unicos(self):
        ids = [r["id"] for r in self.res]
        self.assertEqual(len(set(ids)), 98)

    def test_todos_dentro_de_euskadi(self):
        for r in self.res:
            self.assertTrue(42.4 <= r["lat"] <= 43.5 and -3.5 <= r["lon"] <= -1.7, r["id"])

    def test_tipos(self):
        tipos = {}
        for r in self.res:
            tipos[r["tipo"]] = tipos.get(r["tipo"], 0) + 1
        self.assertEqual(tipos, {"fijo": 95, "fotorrojo": 1, "tramo_extremo": 2})

    def test_trabakua_usa_la_variante_de_7_elementos(self):
        tr = [r for r in self.res if r.get("tramo_grupo") == "TRABAKUA"]
        self.assertEqual({r["tramo_extremo"] for r in tr}, {1, 2})
        self.assertEqual({r["pk_m"] for r in tr}, {36990, 39800})
        self.assertEqual({r["limite_oficial"] for r in tr}, {60})

    def test_limites(self):
        por_nombre = {r["nombre"]: r for r in self.res}
        self.assertEqual(por_nombre["MAX CENTER"]["limite_oficial"], 80)
        self.assertEqual(por_nombre["TUNEL ITZIAR"]["limite_oficial"], 80)       # "80" sin unidad
        self.assertEqual(por_nombre["ARMIÑON"]["limite_oficial"], 80)            # "80 Km/h"
        self.assertIsNone(por_nombre["KUKULARRA"]["limite_oficial"])             # "60/80 km/h"
        self.assertEqual(por_nombre["KUKULARRA"]["limite_texto"], "60/80 km/h")
        self.assertIsNone(por_nombre["FOTO-ROJO GETARIA"]["limite_oficial"])     # "-"

    def test_kilometraje_en_metros(self):
        por_nombre = {r["nombre"]: r for r in self.res}
        self.assertEqual(por_nombre["MAX CENTER"]["pk_m"], 123500)
        self.assertEqual(por_nombre["DERIO LOIU"]["pk_m"], 90)

    def test_destino_sin_espacios_sobrantes(self):
        for r in self.res:
            self.assertEqual(r["destino"], r["destino"].strip())

    def test_sin_sentido_creciente(self):
        # Trafikoa da destino; el creciente/decreciente lo calcula 3.2.
        self.assertTrue(all(r["sentido_oficial"] is None for r in self.res))

    def test_pagina_rota_da_error(self):
        with self.assertRaises(ErrorLectura):
            trafikoa.leer("<html>otra cosa</html>")
        with self.assertRaises(ErrorLectura):
            trafikoa.leer("var x = 1000;\n var y = 2000;")


# Mini fichero DATEX II con la misma estructura que el de la DGT (ver dgt.py).
_PUNTO = """<_0:predefinedLocation id="GUID_CABINACINEMOMETRO_{n}">
 <_0:predefinedLocationName><_0:value lang="es">CABINACINEMOMETRO_{n}</_0:value></_0:predefinedLocationName>
 <_0:predefinedLocation xsi:type="_0:Point"><_0:tpegpointLocation xsi:type="_0:TPEGSimplePoint">
  <_0:tpegDirection>unknown</_0:tpegDirection><_0:tpegLocationType>nonLinkedPoint</_0:tpegLocationType>
  <_0:point><_0:pointCoordinates><_0:latitude>{lat}</_0:latitude><_0:longitude>{lon}</_0:longitude></_0:pointCoordinates>
   <_0:name><_0:descriptor><_0:value lang="es">{via}</_0:value></_0:descriptor><_0:tpegDescriptorType>linkName</_0:tpegDescriptorType></_0:name>
   <_0:name><_0:descriptor><_0:value lang="es">{mun}</_0:value></_0:descriptor><_0:tpegDescriptorType>townName</_0:tpegDescriptorType></_0:name>
  </_0:point></_0:tpegpointLocation></_0:predefinedLocation>
 <_0:referencePoint><_0:referencePointIdentifier>X</_0:referencePointIdentifier>
  <_0:administrativeArea><_0:value lang="es">{prov}</_0:value></_0:administrativeArea>
  <_0:roadName><_0:value lang="es">{via}</_0:value></_0:roadName><_0:roadNumber>{via}</_0:roadNumber>
  <_0:directionRelative>{rel}</_0:directionRelative><_0:referencePointDistance>{pk}</_0:referencePointDistance>
  <_0:referencePointExtension><_0:ExtendedReferencePoint>{dest}
   <_0:provinceINEIdentifier>50</_0:provinceINEIdentifier><_0:singularity>AUTOPISTA / AUTOVÍA</_0:singularity>
  </_0:ExtendedReferencePoint></_0:referencePointExtension></_0:referencePoint>
</_0:predefinedLocation>"""

_TRAMO = """<_0:predefinedLocation id="GUID_CVM_161274">
 <_0:predefinedLocationName><_0:value lang="es">CVM_161274</_0:value></_0:predefinedLocationName>
 <_0:predefinedLocation xsi:type="_0:Linear"><_0:tpeglinearLocation>
  <_0:tpegDirection>unknown</_0:tpegDirection><_0:tpegLocationType>segment</_0:tpegLocationType>
  <_0:to xsi:type="_0:TPEGNonJunctionPoint"><_0:pointCoordinates><_0:latitude>41.6192</_0:latitude><_0:longitude>-0.9496</_0:longitude></_0:pointCoordinates>
   <_0:name><_0:descriptor><_0:value lang="es">Z-40</_0:value></_0:descriptor><_0:tpegDescriptorType>linkName</_0:tpegDescriptorType></_0:name></_0:to>
  <_0:from xsi:type="_0:TPEGNonJunctionPoint"><_0:pointCoordinates><_0:latitude>41.6088</_0:latitude><_0:longitude>-0.915697</_0:longitude></_0:pointCoordinates>
   <_0:name><_0:descriptor><_0:value lang="es">Z-40</_0:value></_0:descriptor><_0:tpegDescriptorType>linkName</_0:tpegDescriptorType></_0:name>
   <_0:name><_0:descriptor><_0:value lang="es">ZARAGOZA</_0:value></_0:descriptor><_0:tpegDescriptorType>townName</_0:tpegDescriptorType></_0:name></_0:from>
 </_0:tpeglinearLocation></_0:predefinedLocation>
 <_0:referencePointLinear>
  <_0:referencePointPrimaryLocation><_0:referencePoint><_0:referencePointIdentifier>CVM_161274_ini</_0:referencePointIdentifier>
   <_0:administrativeArea><_0:value lang="es">ZARAGOZA</_0:value></_0:administrativeArea><_0:roadNumber>Z-40</_0:roadNumber>
   <_0:directionRelative>positive</_0:directionRelative><_0:referencePointDistance>26600.0</_0:referencePointDistance>
   <_0:referencePointExtension><_0:ExtendedReferencePoint><_0:directionNamed>MADRID</_0:directionNamed><_0:provinceINEIdentifier>50</_0:provinceINEIdentifier><_0:singularity>AUTOPISTA / AUTOVÍA</_0:singularity></_0:ExtendedReferencePoint></_0:referencePointExtension>
  </_0:referencePoint></_0:referencePointPrimaryLocation>
  <_0:referencePointSecondaryLocation><_0:referencePoint><_0:referencePointIdentifier>CVM_161274_fin</_0:referencePointIdentifier>
   <_0:administrativeArea><_0:value lang="es">ZARAGOZA</_0:value></_0:administrativeArea><_0:roadNumber>Z-40</_0:roadNumber>
   <_0:directionRelative>positive</_0:directionRelative><_0:referencePointDistance>29700.0</_0:referencePointDistance>
   <_0:referencePointExtension><_0:ExtendedReferencePoint><_0:directionNamed>MADRID</_0:directionNamed><_0:provinceINEIdentifier>50</_0:provinceINEIdentifier><_0:singularity>AUTOPISTA / AUTOVÍA</_0:singularity></_0:ExtendedReferencePoint></_0:referencePointExtension>
  </_0:referencePoint></_0:referencePointSecondaryLocation>
 </_0:referencePointLinear>
</_0:predefinedLocation>"""


def _xml_dgt(fijos, con_tramo=True):
    cuerpo = "".join(_PUNTO.format(n=120001 + i, **f) for i, f in enumerate(fijos))
    tramos = ('<_0:predefinedLocationSet id="T"><_0:predefinedLocationSetName><_0:value lang="es">'
              'CinemometrosVelocidadMedia</_0:value></_0:predefinedLocationSetName>'
              + _TRAMO + "</_0:predefinedLocationSet>") if con_tramo else ""
    return ('<?xml version="1.0" encoding="UTF-8"?>'
            '<d2LogicalModel xmlns="http://datex2.eu/schema/1_0/1_0" '
            'xmlns:_0="http://datex2.eu/schema/1_0/1_0" '
            'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
            '<_0:payloadPublication><_0:publicationTime>2025-12-18T09:56:52.406+01:00</_0:publicationTime>'
            + tramos +
            '<_0:predefinedLocationSet id="F"><_0:predefinedLocationSetName><_0:value lang="es">'
            'CabinasCinemometro</_0:value></_0:predefinedLocationSetName>' + cuerpo +
            '</_0:predefinedLocationSet></_0:payloadPublication></d2LogicalModel>').encode()


FIJOS = [
    dict(lat="41.30326", lon="-1.94488", via="A-2", mun="ZARAGOZA", prov="ZARAGOZA",
         rel="positive", pk="202330.0", dest="<_0:directionNamed>ZARAGOZA</_0:directionNamed>"),
    dict(lat="40.1", lon="-3.5", via="M-30", mun="MADRID", prov="MADRID",
         rel="negative", pk="12500.0", dest=""),
]


class PruebasDGT(unittest.TestCase):
    def test_fijos_y_tramo(self):
        r = dgt.leer(_xml_dgt(FIJOS))
        self.assertEqual(r["publicado"], "2025-12-18T09:56:52.406+01:00")
        self.assertEqual([x["tipo"] for x in r["radares"]], ["tramo", "fijo", "fijo"])

    def test_fijo_creciente_con_destino(self):
        a = [x for x in dgt.leer(_xml_dgt(FIJOS))["radares"] if x["tipo"] == "fijo"][0]
        self.assertEqual(a["id"], "dgt-CABINACINEMOMETRO_120001")
        self.assertEqual((a["lat"], a["lon"]), (41.30326, -1.94488))
        self.assertEqual(a["carretera"], "A-2")
        self.assertEqual(a["pk_m"], 202330)
        self.assertEqual(a["sentido_oficial"], "creciente")
        self.assertEqual(a["destino"], "ZARAGOZA")
        self.assertEqual(a["municipio"], "ZARAGOZA")
        self.assertIsNone(a["limite_oficial"])  # la DGT no publica el límite

    def test_fijo_decreciente_sin_destino(self):
        b = [x for x in dgt.leer(_xml_dgt(FIJOS))["radares"] if x["tipo"] == "fijo"][1]
        self.assertEqual(b["sentido_oficial"], "decreciente")
        self.assertIsNone(b["destino"])

    def test_tramo(self):
        t = dgt.leer(_xml_dgt(FIJOS))["radares"][0]
        self.assertEqual(t["id"], "dgt-CVM_161274")
        self.assertEqual((t["lat"], t["lon"]), (41.6088, -0.915697))      # from
        self.assertEqual((t["lat_fin"], t["lon_fin"]), (41.6192, -0.9496))  # to
        self.assertEqual((t["pk_m"], t["pk_fin_m"]), (26600, 29700))
        self.assertEqual(t["sentido_oficial"], "creciente")

    def test_xml_roto(self):
        with self.assertRaises(ErrorLectura):
            dgt.leer(b"<esto no es xml")
        with self.assertRaises(ErrorLectura):
            dgt.leer(b"<a><b/></a>")


class PruebasManual(unittest.TestCase):
    def _leer(self, datos):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "m.json"
            p.write_text(json.dumps(datos), encoding="utf-8")
            return manual.leer(p)["radares"]

    def test_no_existe_o_vacio(self):
        self.assertEqual(manual.leer("/no/existe.json")["radares"], [])
        self.assertEqual(self._leer({"radares": []}), [])

    def test_radar_nuevo_valido(self):
        r = self._leer({"radares": [{"id": "man-salamanca-1", "tipo": "fijo", "lat": 40.97,
                                     "lon": -5.66, "pk": 1.5, "sentido": "creciente", "limite": 50,
                                     "enlace_oficial": "https://ejemplo.invalid/lista"}]})
        self.assertEqual(r[0]["id"], "man-salamanca-1")
        self.assertEqual(r[0]["fuente"], "manual")
        self.assertEqual(r[0]["pk_m"], 1500)
        self.assertEqual(r[0]["sentido_oficial"], "creciente")
        self.assertEqual(r[0]["limite_manual"], 50)
        self.assertIsNone(r[0]["limite_oficial"])

    def test_sentido_destino(self):
        r = self._leer({"radares": [{"id": "man-x", "tipo": "fijo", "lat": 1, "lon": 2,
                                     "sentido": "CANTABRIA", "enlace_oficial": "https://e.invalid"}]})
        self.assertIsNone(r[0]["sentido_oficial"])
        self.assertEqual(r[0]["destino"], "CANTABRIA")

    def test_revision_no_pide_enlace(self):
        r = self._leer({"radares": [{"id": "man-rev-1", "tipo": "fijo", "lat": 1, "lon": 2,
                                     "completa": "dgt-CABINACINEMOMETRO_120001", "limite": 90}]})
        self.assertEqual(r[0]["completa"], "dgt-CABINACINEMOMETRO_120001")

    def test_errores(self):
        malos = [
            {"radares": [{"id": "man-a", "tipo": "fijo", "lat": 1, "lon": 2}]},                      # sin enlace
            {"radares": [{"id": "man-a", "tipo": "fijo", "lat": 1, "lon": 2, "enlace_oficial": "x"}]},  # no URL
            {"radares": [{"id": "a", "tipo": "fijo", "lat": 1, "lon": 2, "enlace_oficial": "https://e.invalid"}]},  # id sin man-
            {"radares": [{"id": "man-a", "tipo": "movil", "lat": 1, "lon": 2, "enlace_oficial": "https://e.invalid"}]},
            {"radares": [{"id": "man-a", "tipo": "fijo", "lat": "uno", "lon": 2, "enlace_oficial": "https://e.invalid"}]},
            {"otra": 1},
        ]
        for m in malos:
            with self.assertRaises(ErrorLectura, msg=str(m)):
                self._leer(m)


if __name__ == "__main__":
    unittest.main()
