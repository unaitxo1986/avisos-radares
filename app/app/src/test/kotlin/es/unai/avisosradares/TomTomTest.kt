package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TomTom (7.2): comparar carreteras, leer las respuestas (formato de la sonda del 27/11) y cuándo preguntar. */
class TomTomTest {

    @Test
    fun normalizaLasCarreteras() {
        assertEquals("A8", TomTom.normaliza("A-8"))
        assertEquals("N2", TomTom.normaliza("N-II"))
        assertEquals("N4", TomTom.normaliza("N-IV"))
        assertEquals("MA1", TomTom.normaliza("Ma-1"))
        assertEquals("AP9V", TomTom.normaliza("AP-9V"))
        assertEquals("A6BUSVAO", TomTom.normaliza("A-6 BUS-VAO"))
    }

    @Test
    fun mismaCarreteraConLasVariantesDeLaSonda() {
        // Casos reales de la sonda del 27/11: deben contar como la misma carretera.
        assertTrue(TomTom.mismaCarretera("A-8", "AP-8"))      // Gipuzkoa
        assertTrue(TomTom.mismaCarretera("AP-9V", "AP-9"))
        assertTrue(TomTom.mismaCarretera("Ma-1", "MA-1"))
        assertTrue(TomTom.mismaCarretera("N-II", "A-2"))
        assertTrue(TomTom.mismaCarretera("SE-30", "SE-30"))
        // Distintas.
        assertFalse(TomTom.mismaCarretera("N-637", "BI-30"))
        assertFalse(TomTom.mismaCarretera("GI-632", "GI-11"))
        assertFalse(TomTom.mismaCarretera("A-8", "A-68"))
        assertFalse(TomTom.mismaCarretera("BI-633", "BI-631"))
        assertFalse(TomTom.mismaCarretera("", "A-8"))
    }

    private fun snap(numeros: String, nombre: String? = null, resultado: String = "Matched", limite: Int = 80) = """
        {"route":[{"properties":{"speedLimits":{"value":120,"unit":"kmph","type":"Maximum"},"address":{"roadNumbers":["X-1"]}}},
                  {"properties":{"speedLimits":{"value":$limite,"unit":"kmph","type":"Maximum"},
                   "address":{${nombre?.let { "\"roadName\":\"$it\"," } ?: ""}"roadNumbers":[$numeros],"municipality":"Bilbao"}}}],
         "projectedPoints":[{"properties":{"routeIndex":0,"snapResult":"Matched"}},
                            {"properties":{"routeIndex":1,"snapResult":"$resultado"}}]}
    """.trimIndent()

    @Test
    fun leeSnapToRoadsYDecide() {
        val c = TomTom.leerSnap(snap("\"AP-8\",\"E-70\"", "Autopista del Cantábrico"))
        assertNotNull(c)
        assertEquals(listOf("AP-8", "E-70"), c!!.numeros)
        assertEquals(80, c.limiteKmh)
        assertEquals(TomTom.Veredicto.CONFIRMADA, TomTom.veredicto("A-8", c))
        // Otra carretera con número: se calla (Unai, 20/11).
        assertEquals(TomTom.Veredicto.OTRA_CARRETERA, TomTom.veredicto("BI-637", c))
        // Ramal sin número: no se sabe, se avisa.
        assertEquals(TomTom.Veredicto.DESCONOCIDA, TomTom.veredicto("A-8", TomTom.leerSnap(snap(""))))
        // El nombre también vale: "Autovía GI-632".
        assertEquals(TomTom.Veredicto.CONFIRMADA, TomTom.veredicto("GI-632", TomTom.leerSnap(snap("\"A-636\"", "Autovía GI-632"))))
        // La última posición no cae en una carretera, o la respuesta es rara: se avisa.
        assertEquals(TomTom.Veredicto.DESCONOCIDA, TomTom.veredicto("A-8", TomTom.leerSnap(snap("\"N-1\"", resultado = "OffRoad"))))
        assertNull(TomTom.leerSnap("{no es json"))
        assertEquals(TomTom.Veredicto.DESCONOCIDA, TomTom.veredicto("A-8", null))
    }

    @Test
    fun leeElLimiteDeLaVia() {
        assertEquals(80, TomTom.leeLimite("80.00KPH"))
        assertEquals(48, TomTom.leeLimite("30.00MPH"))
        assertNull(TomTom.leeLimite("rápido"))
        assertNull(TomTom.leeLimite(null))
        val v = TomTom.leerReverse("""{"summary":{"numResults":1},"addresses":[{"address":{"routeNumbers":["N-240"],
            "streetName":"Carretera Bilbao-Vitoria","speedLimit":"60.00KPH"},"position":"43.1,-2.8","matchType":"Street"}]}""")
        assertNotNull(v)
        assertEquals(60, v!!.limiteKmh)
        assertEquals(listOf("N-240"), v.numeros)
        assertNull(TomTom.leerReverse("{no"))
        assertNull(TomTom.leerReverse("""{"addresses":[{"address":{"streetName":"Calle"}}]}""")!!.limiteKmh)
    }

    private fun alNorte(segundos: Int, metrosPorSegundo: Double): List<Posicion> {
        val mLat = RADIO_TIERRA_M * Math.PI / 180
        return (0..segundos).map { s -> Posicion(1_000_000L + s * 1000L, 43.0 + s * metrosPorSegundo / mLat, -2.9) }
    }

    @Test
    fun eligeLosPuntosParaSnapToRoads() {
        val puntos = TomTom.puntosParaSnap(alNorte(40, 25.0))
        assertNotNull(puntos)
        assertEquals(6, puntos!!.size)
        assertTrue(puntos.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
        assertEquals(1_040_000L, puntos.last().tMs)
        // Parado o casi: no hay recorrido para saber la carretera.
        assertNull(TomTom.puntosParaSnap(alNorte(40, 0.3)))
        assertNull(TomTom.puntosParaSnap(alNorte(0, 25.0)))
        // El rumbo de un coche hacia el norte.
        assertEquals(0.0, TomTom.rumbo(puntos[0], puntos[1]), 1.0)
    }

    @Test
    fun lasUrlLlevanLaClaveYLongitudLatitud() {
        val u = TomTom.urlSnap("CLAVE", alNorte(2, 20.0))
        assertTrue(u, u.startsWith("https://api.tomtom.com/snapToRoads/1?key=CLAVE&points=-2.900000%2C43.000000%3B"))
        assertTrue(u, u.contains("&fields=%7BprojectedPoints"))
        val r = TomTom.urlReverse("CLAVE", Posicion(0L, 43.25, -2.9), 91.4)
        assertEquals("https://api.tomtom.com/search/2/reverseGeocode/43.250000,-2.900000.json?key=CLAVE&returnSpeedLimit=true&radius=50&heading=91", r)
    }

    @Test
    fun planDelLimiteDeLaVia() {
        val plan = PlanLimiteVia()
        val ruta = alNorte(200, 25.0)
        assertFalse("parado no pregunta", plan.tocaPreguntar(ruta[0], 3.0))
        assertTrue(plan.tocaPreguntar(ruta[0], 90.0))
        plan.pregunta(ruta[0])
        plan.respuesta(ruta[0], ok = true, limiteKmh = 100)
        assertEquals(100, plan.vigente(ruta[5]))
        assertFalse("antes de 30 s no", plan.tocaPreguntar(ruta[20], 90.0))
        assertTrue("a los 30 s y 750 m sí", plan.tocaPreguntar(ruta[30], 90.0))
        // Sin respuesta nueva, deja de valer a los 2 minutos (o 1,5 km).
        assertNull(plan.vigente(ruta[130]))
        // Tras un fallo espera un minuto.
        plan.pregunta(ruta[30])
        plan.respuesta(ruta[30], ok = false, limiteKmh = null)
        assertFalse(plan.tocaPreguntar(ruta[60], 90.0))
        assertTrue(plan.tocaPreguntar(ruta[91], 90.0))
    }

    @Test
    fun elMotorRetieneLiberaYDescartaElAviso() {
        val mLat = RADIO_TIERRA_M * Math.PI / 180
        val linea = (0..20).map { i -> doubleArrayOf(43.25 - (1000.0 - i * 50.0) / mLat, -2.90) }
        val radar = Radar(id = "r1", tipo = "fijo", nombre = null, carretera = "A-8", pk = null, lat = 43.25, lon = -2.90,
            sentido = null, limite = 80, limiteOrigen = "oficial", linea = null)
        // Retenido mientras se pregunta (no se enseña) y liberado después: se enseña, pita por exceso y se da por pasado.
        var motor = MotorAvisos(listOf(radar))
        var eventos = ArrayList<TipoEvento>()
        var porLiberar = false
        var retenidoComprobado = false
        var vistoTrasLiberar = false
        for (p in recorridoSimulado(linea, 100.0)) {
            val r = motor.procesa(p)
            if (porLiberar) {
                assertNull("retenido: no se enseña", r.aviso)
                retenidoComprobado = true
                assertTrue(motor.libera("r1"))
                porLiberar = false
            } else if (retenidoComprobado && r.aviso != null) {
                vistoTrasLiberar = true
            }
            for (e in r.eventos) {
                eventos.add(e.tipo)
                if (e.tipo == TipoEvento.AVISO) { assertTrue(motor.retiene("r1")); porLiberar = true }
            }
        }
        assertTrue(retenidoComprobado && vistoTrasLiberar)
        assertTrue(eventos.toString(), eventos.first() == TipoEvento.AVISO && eventos.contains(TipoEvento.EXCESO) && eventos.last() == TipoEvento.PASADO)

        // Descartado: ni se enseña, ni pita, ni se da por pasado.
        motor = MotorAvisos(listOf(radar))
        eventos = ArrayList()
        var descartado = false
        for (p in recorridoSimulado(linea, 100.0)) {
            val r = motor.procesa(p)
            if (descartado) assertNull(r.aviso)
            for (e in r.eventos) {
                eventos.add(e.tipo)
                if (e.tipo == TipoEvento.AVISO) { assertTrue(motor.descarta("r1")); descartado = true }
            }
        }
        assertEquals(listOf(TipoEvento.AVISO), eventos)
        // Radar que ya no se sigue: no hay nada que hacer.
        assertFalse(motor.libera("r1"))
    }
}
