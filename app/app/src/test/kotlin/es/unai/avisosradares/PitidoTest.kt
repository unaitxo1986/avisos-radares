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
        // Selector de volumen de los avisos (23/11): 70 % es casi lo de antes (10 de 15).
        assertEquals(11, volumenParaAviso(70, 15))
        assertEquals(15, volumenParaAviso(100, 15))
        assertEquals(15, volumenParaAviso(200, 15))
        assertEquals(2, volumenParaAviso(10, 15))
        assertEquals(1, volumenParaAviso(0, 5))
        assertEquals(0, refuerzoAviso(100))
        assertEquals(750, refuerzoAviso(150))
        assertEquals(1500, refuerzoAviso(200))
        assertEquals(1500, refuerzoAviso(500))
        assertEquals(70, volumenAvisosValido(73))
        assertEquals(10, volumenAvisosValido(-5))
        assertEquals("150 % · más alto que el máximo del móvil", textoVolumenAvisos(150))
        assertEquals("70 %", textoVolumenAvisos(70))
        // Los avisos suenan más fuerte que antes (0,9 del máximo; los de arranque y parada, 0,6).
        assertTrue(sonidoRadar(22050).maxOf { kotlin.math.abs(it.toInt()) } > 28000)
        assertTrue(sonidoConexion(22050).maxOf { kotlin.math.abs(it.toInt()) } < 20000)
    }
}
