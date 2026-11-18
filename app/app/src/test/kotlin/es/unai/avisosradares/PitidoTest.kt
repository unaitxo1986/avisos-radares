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

    @Test
    fun sonidoDeConexionEsUnPitidoSuave() {
        assertEquals(3307, sonidoConexion(22050).size)
    }

    @Test
    fun sonidoDeDesconexionSonDosPitidos() {
        assertEquals(3307 + 2646 + 3307, sonidoDesconexion(22050).size)
    }

    @Test
    fun sonidoDeRadarSonCincoPitidos() {
        assertEquals(5 * 3307 + 4 * 2205, sonidoRadar(22050).size)
    }

    @Test
    fun secuenciaSinPitidosEstaVacia() {
        assertEquals(0, generarSecuencia(0, 660, 150, 100, 22050).size)
    }

    @Test
    fun sonidoDeExcesoSonTresPitidosCortos() {
        assertEquals(3 * 1984 + 2 * 1323, sonidoExceso(22050).size)
    }

    @Test
    fun volumenDeLosAvisosAlMenosDosTercios() {
        assertEquals(10, volumenParaAviso(2, 15))
        assertEquals(10, volumenParaAviso(10, 15))
        assertEquals(14, volumenParaAviso(14, 15))
        assertEquals(17, volumenParaAviso(3, 25))
    }
}
