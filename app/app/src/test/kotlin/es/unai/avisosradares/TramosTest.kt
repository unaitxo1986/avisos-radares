package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de los radares de tramo (fase 5) con una carretera de juguete: 500 m de aproximación hacia el norte
 * hasta la cámara de inicio y 3 km de tramo, también hacia el norte, hasta la de fin.
 */
class TramosTest {

    private val latI = 43.20
    private val lonI = -2.55
    private val mLat = RADIO_TIERRA_M * Math.PI / 180

    private fun punto(norteM: Double, esteM: Double = 0.0) =
        doubleArrayOf(latI + norteM / mLat, lonI + esteM / (mLat * Math.cos(latI * Math.PI / 180)))

    private val aprox = (0..10).map { punto(-500.0 + it * 50.0) }
    private val tramo = (0..30).map { punto(it * 100.0) }

    private fun radar(limite: Int? = 100) = Radar(
        id = "t1", tipo = "tramo", nombre = "PRUEBA", carretera = "BI-633", pk = 36.99, lat = latI, lon = lonI,
        sentido = "MARKINA", limite = limite, limiteOrigen = "oficial", linea = aprox, tramo = tramo,
        tramoLargoM = 3000.0,
    )

    private fun pasa(motor: MotorAvisos, recorrido: List<Posicion>) = recorrido.flatMap { motor.procesa(it).eventos }
    private fun tipos(ev: List<Evento>) = ev.map { it.tipo }

    @Test
    fun avisaAlLlegarMideLaMediaYAcabaEnElFin() {
        val motor = MotorAvisos(listOf(radar()))
        val ev = pasa(motor, recorridoSimulado(lineaParaSimularTramo(radar()), 90.0))
        // PASADO (del aviso de llegada) e INICIO_TRAMO caen en la misma posición o en dos seguidas.
        assertEquals(TipoEvento.AVISO, ev.first().tipo)
        assertEquals(setOf(TipoEvento.PASADO, TipoEvento.INICIO_TRAMO), tipos(ev).subList(1, 3).toSet())
        assertEquals(4, ev.size)
        val fin = ev.last()
        assertEquals(90.0, fin.mediaKmh!!, 1.0)
        assertTrue(fin.motivo, fin.motivo.contains("media 9"))
    }

    @Test
    fun conErrorDeGpsLaMediaSigueSiendoBuena() {
        for (semilla in 1L..5L) {
            val motor = MotorAvisos(listOf(radar()))
            val ev = pasa(motor, recorridoSimulado(lineaParaSimularTramo(radar()), 100.0, ruidoM = 10.0, semilla = semilla))
            val fin = ev.firstOrNull { it.tipo == TipoEvento.FIN_TRAMO }
            assertNotNull("semilla $semilla: ${tipos(ev)}", fin)
            assertEquals(100.0, fin!!.mediaKmh!!, 2.0)
        }
    }

    @Test
    fun laVentanaEnsenaLaMediaYLoQueFalta() {
        val motor = MotorAvisos(listOf(radar()))
        val recorrido = recorridoSimulado(lineaParaSimularTramo(radar()), 90.0)
        var vistos = 0
        var anterior = Double.MAX_VALUE
        for (p in recorrido) {
            val a = motor.procesa(p).aviso ?: continue
            if (!a.enTramo) continue
            vistos++
            assertTrue("lo que falta debe bajar: $anterior -> ${a.distanciaM}", a.distanciaM <= anterior + 1.0)
            anterior = a.distanciaM
            if (a.distanciaM < 2800.0) assertEquals(90.0, a.mediaKmh!!, 2.0)
            assertTrue(!a.exceso)
        }
        assertTrue("debería verse el tramo en la ventana: $vistos", vistos > 100)
    }

    @Test
    fun pitaAlPasarLaMediaDelLimiteYDespuesCadaKilometro() {
        val motor = MotorAvisos(listOf(radar(limite = 100)))
        val ev = pasa(motor, recorridoSimulado(lineaParaSimularTramo(radar()), 110.0))
        val excesos = ev.filter { it.tipo == TipoEvento.EXCESO && it.mediaKmh != null }  // sin los del aviso de llegada
        // El primero en cuanto se puede calcular la media (100 m y 5 s: a 110 km/h, unos 150-180 m) y luego cada
        // 1000 m: a unos 180, 1180 y 2180 m del inicio.
        assertEquals(tipos(ev).toString(), 3, excesos.size)
        assertTrue(excesos.all { it.mediaKmh!! > 100.0 })
        assertTrue("primer pitido a ${excesos[0].distanciaM} m del fin", excesos[0].distanciaM in 2780.0..2900.0)
    }

    @Test
    fun sinLimiteNoHayExceso() {
        val motor = MotorAvisos(listOf(radar(limite = null)))
        val ev = pasa(motor, recorridoSimulado(lineaParaSimularTramo(radar()), 150.0))
        assertTrue(ev.none { it.tipo == TipoEvento.EXCESO })
        assertTrue(ev.any { it.tipo == TipoEvento.FIN_TRAMO })
    }

    @Test
    fun laMediaCuentaLoQueSeHaIdoMasDeprisaYMasDespacio() {
        // 1,5 km a 130 y 1,5 km a 70: tiempo 41,5 + 77,1 s, media 91 km/h (no 100).
        val motor = MotorAvisos(listOf(radar(limite = 100)))
        val primera = recorridoSimulado(aprox.dropLast(1) + tramo.take(16), 130.0, despuesM = 0.0)
        val segunda = recorridoSimulado(tramo.drop(15), 70.0, antesM = 0.0, t0Ms = primera.last().tMs + 1000)
        val ev = pasa(motor, primera + segunda)
        val fin = ev.first { it.tipo == TipoEvento.FIN_TRAMO }
        assertEquals(91.0, fin.mediaKmh!!, 2.0)
        // Al principio la media pasa del límite (pita) y al final ya no.
        assertTrue(ev.any { it.tipo == TipoEvento.EXCESO })
    }

    @Test
    fun enUnTunelSinGpsLaMediaSigueBien() {
        val motor = MotorAvisos(listOf(radar()))
        val recorrido = recorridoSimulado(lineaParaSimularTramo(radar()), 90.0)
        // Sin posiciones entre los km 1 y 2 del tramo (unos 40 s).
        val conHueco = recorrido.filter {
            val norte = (it.lat - latI) * mLat
            norte < 1000.0 || norte > 2000.0
        }
        val ev = pasa(motor, conHueco)
        val fin = ev.firstOrNull { it.tipo == TipoEvento.FIN_TRAMO }
        assertNotNull(tipos(ev).toString(), fin)
        assertEquals(90.0, fin!!.mediaKmh!!, 1.0)
    }

    @Test
    fun enSentidoContrarioNoMide() {
        val motor = MotorAvisos(listOf(radar()))
        val ev = pasa(motor, recorridoSimulado(lineaParaSimularTramo(radar()), 90.0, alReves = true))
        assertTrue(tipos(ev).toString(), ev.none { it.tipo == TipoEvento.INICIO_TRAMO })
    }

    @Test
    fun siEntraAMitadDelTramoNoMide() {
        val motor = MotorAvisos(listOf(radar()))
        val ev = pasa(motor, recorridoSimulado(tramo.drop(10), 90.0, antesM = 0.0))
        assertTrue(tipos(ev).toString(), ev.none { it.tipo == TipoEvento.INICIO_TRAMO })
    }

    @Test
    fun siSeSaleDelTramoSeCancela() {
        val motor = MotorAvisos(listOf(radar()))
        // Hasta el km 1,5 del tramo y luego un desvío hacia el este.
        val salida = aprox.dropLast(1) + tramo.take(16) + listOf(punto(1520.0, 100.0), punto(1540.0, 600.0))
        val ev = pasa(motor, recorridoSimulado(salida, 90.0, despuesM = 0.0))
        assertEquals(TipoEvento.CANCELADO, ev.last().tipo)
        assertEquals("se sale del tramo", ev.last().motivo)
        assertNull(motor.procesa(Posicion(10_000_000L, latI + 1540.0 / mLat, lonI + 0.01)).aviso)
    }

    @Test
    fun sinLineaDeAproximacionTambienMide() {
        // Tramo cuya aproximación es dudosa: aviso por cercanía al inicio y medida igual.
        val r = Radar("t2", "tramo", null, "CL-615", 1.7, latI, lonI, null, 90, "osm", null,
            tramo = tramo, tramoLargoM = 3000.0)
        val motor = MotorAvisos(listOf(r))
        val ev = pasa(motor, recorridoSimulado(tramo, 80.0, antesM = 600.0))
        assertTrue(tipos(ev).toString(), ev.map { it.tipo }.containsAll(listOf(TipoEvento.AVISO, TipoEvento.INICIO_TRAMO, TipoEvento.FIN_TRAMO)))
        assertEquals(80.0, ev.first { it.tipo == TipoEvento.FIN_TRAMO }.mediaKmh!!, 1.0)
    }

    @Test
    fun textosDeLaVentanaEnUnTramo() {
        val a = AvisoActivo(radar(), 2345.0, true, false, 97.6, enTramo = true)
        assertEquals("media 97" to "fin a 2,3 km", textosAviso(a))
        assertEquals("media --" to "fin a 2,9 km", textosAviso(AvisoActivo(radar(), 2900.0, true, false, null, enTramo = true)))
        assertEquals("460 m" to "TRAMO", textosAviso(AvisoActivo(radar(), 467.0, true, false)))
    }

    @Test
    fun leeLosCamposDelTramo() {
        val json = """{"version":"v","radares":[
            {"id":"a","tipo":"tramo","lat":43.2,"lon":-2.55,"tramo_fiab":"alta","tramo":[[43.2,-2.55],[43.21,-2.55]],"tramo_largo":1112},
            {"id":"b","tipo":"tramo","lat":43.2,"lon":-2.55,"tramo_fiab":"dudosa","tramo":null,"tramo_largo":null},
            {"id":"c","tipo":"fijo","lat":43.2,"lon":-2.55}]}"""
        val r = leerRadares(json).radares
        assertTrue(r[0].conTramo)
        assertEquals(1112.0, r[0].tramoLargoM!!, 0.1)
        assertTrue(!r[1].conTramo)
        assertTrue(!r[2].conTramo)
    }
}
