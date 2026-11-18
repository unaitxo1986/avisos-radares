package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListaYViajeTest {

    @Test
    fun leeJson() {
        @Suppress("UNCHECKED_CAST")
        val m = leerJson("""{"a": [1, -2.5e1, true, null], "b": "x\"ñ\n", "c": {}}""") as Map<String, Any?>
        assertEquals(listOf(1.0, -25.0, true, null), m["a"])
        assertEquals("x\"ñ\n", m["b"])
        assertEquals(emptyMap<String, Any?>(), m["c"])
        var fallo = false
        try { leerJson("{\"a\": 1,}") } catch (e: IllegalArgumentException) { fallo = true }
        assertTrue(fallo)
    }

    @Test
    fun leeRadaresConYSinLinea() {
        val texto = """{"version":"v1","radares":[
            {"id":"a","tipo":"fijo","lat":43.0,"lon":-2.0,"aproximacion":[[42.99,-2.0],[43.0,-2.0]],"aproximacion_fiab":"alta","limite":80,"limite_fiab":"media"},
            {"id":"b","tipo":"fijo","lat":43.1,"lon":-2.1,"aproximacion":null,"aproximacion_fiab":"dudosa","limite":90,"limite_fiab":"baja"},
            {"id":"c","tipo":"tramo","lat":43.2,"lon":-2.2},
            {"id":"mun-x","tipo":"fijo","fuente":"municipal","rotatoria":true,"lat":42.85,"lon":-2.67,"limite":50,"limite_fiab":"alta"}]}"""
        val l = leerRadares(texto)
        assertEquals("v1", l.version)
        assertEquals(4, l.radares.size)
        assertTrue(l.radares[3].municipal && l.radares[3].rotatoria)
        assertTrue(l.radares[0].porCarretera)
        assertEquals(80, l.radares[0].limite)
        assertTrue(!l.radares[1].porCarretera)
        assertNull("límite de fiabilidad baja: se avisa sin límite", l.radares[1].limite)
        assertEquals("Inicio de tramo", l.radares[2].descripcion())
    }

    @Test
    fun rechazaUnaListaVaciaOMalFormada() {
        for (malo in listOf("{}", "{\"radares\":[]}", "[1,2]", "<html>")) {
            var fallo = false
            try { leerRadares(malo) } catch (e: IllegalArgumentException) { fallo = true }
            assertTrue(malo, fallo)
        }
    }

    @Test
    fun avisosDeListaAnticuada() {
        val ahora = isoAMs("2026-12-31T00:00:00Z")!!
        val bueno = EstadoDatos("2026-12-29T04:17:00Z", true, emptyList())
        assertTrue(avisosDeLista(bueno, ahora - UN_DIA_MS, ahora).isEmpty())
        // La app no ha podido comprobar en más de un mes.
        assertEquals(1, avisosDeLista(bueno, ahora - 40 * UN_DIA_MS, ahora).size)
        // El proceso de datos no publicó.
        val malo = EstadoDatos("2026-12-29T04:17:00Z", false, listOf("NO PUBLICADO: total 300"))
        assertTrue(avisosDeLista(malo, ahora, ahora).single().contains("NO PUBLICADO: total 300"))
        // El proceso de datos lleva más de un mes sin ejecutarse.
        val viejo = EstadoDatos("2026-11-01T04:17:00Z", true, emptyList())
        assertEquals(1, avisosDeLista(viejo, ahora, ahora).size)
        // Nunca se ha comprobado.
        assertEquals(1, avisosDeLista(null, null, ahora).size)
    }

    @Test
    fun guardaYReproduceUnViaje() {
        val radar = Radar("r", "fijo", null, null, null, 43.0, -2.0, null, 80, null, null)
        val posiciones = listOf(Posicion(1000, 43.123456, -2.654321, 4.5, 87.3), Posicion(2000, 43.1235, -2.6543, null, null))
        val texto = "# viaje\n" +
            posiciones.joinToString("\n") { FormatoViaje.posicion(it) } + "\n" +
            FormatoViaje.evento(1500, Evento(TipoEvento.AVISO, radar, 432.4, "a, b")) + "\n"
        val leidas = FormatoViaje.posiciones(texto)
        assertEquals(2, leidas.size)
        assertEquals(43.123456, leidas[0].lat, 1e-9)
        assertEquals(87.3, leidas[0].velocidadKmh!!, 1e-9)
        assertNull(leidas[1].precisionM)
        assertEquals(listOf("# viaje", "E,1500,AVISO,r,432,a; b"), FormatoViaje.resumenEventos(texto))
    }

    @Test
    fun distanciaConocida() {
        // Un grado de latitud son unos 111,2 km.
        assertEquals(111_195.0, distanciaM(43.0, -2.0, 44.0, -2.0), 50.0)
    }
}
