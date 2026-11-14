package es.unai.avisosradares

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistroTest {

    @Test
    fun formatoLineaUsaDiaHoraOrigenYTexto() {
        val linea = formatoLinea(0L, TimeZone.getTimeZone("UTC"), "BT", "hola")
        assertEquals("01 00:00:00.000 [BT] hola", linea)
    }

    @Test
    fun ultimasLineasDevuelveSoloLasUltimasSinVacias() {
        assertEquals("b\nc", ultimasLineas("a\nb\nc\n", 2))
    }

    @Test
    fun ultimasLineasConCeroEsVacio() {
        assertEquals("", ultimasLineas("a\nb\n", 0))
    }

    @Test
    fun recortarLineasConservaLasUltimas() {
        assertEquals(listOf("c", "d"), recortarLineas(listOf("a", "b", "c", "d"), 2))
        assertEquals(listOf("a", "b"), recortarLineas(listOf("a", "b"), 5))
    }
}
