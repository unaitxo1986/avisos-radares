package es.unai.avisosradares

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitidoTest {

    @Test
    fun pitidoTieneLaLongitudPedida() {
        assertEquals(4410, generarPitido(880, 200, 22050).size)
    }

    @Test
    fun pitidoEmpiezaYAcabaEnSilencioYNoSeSatura() {
        val muestras = generarPitido(880, 200, 22050)
        assertEquals(0, muestras.first().toInt())
        assertEquals(0, muestras.last().toInt())
        assertTrue(muestras.all { abs(it.toInt()) <= 20000 })
    }

    @Test
    fun pitidoTieneSonidoEnElMedio() {
        val muestras = generarPitido(880, 200, 22050)
        assertTrue(muestras.any { abs(it.toInt()) > 10000 })
    }

    @Test
    fun avisoSonDosPitidosYUnSilencio() {
        assertEquals(4410 + 2646 + 4410, generarAviso(22050).size)
    }
}
