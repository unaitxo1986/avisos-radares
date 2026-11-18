package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Test

class TextosVentanaTest {

    private fun radar(tipo: String = "fijo", rotatoria: Boolean = false) =
        Radar("r", tipo, null, null, null, 43.0, -2.0, null, 80, null, null, rotatoria = rotatoria)

    @Test
    fun distanciaRedondeadaYEnKilometros() {
        assertEquals("460 m", textoDistancia(467.9))
        assertEquals("0 m", textoDistancia(4.0))
        assertEquals("990 m", textoDistancia(999.0))
        assertEquals("1,2 km", textoDistancia(1234.0))
    }

    @Test
    fun etiquetaSegunElTipoYElSentido() {
        assertEquals("RADAR", etiquetaAviso(radar(), porCarretera = true))
        assertEquals("RADAR?", etiquetaAviso(radar(), porCarretera = false))
        assertEquals("TRAMO?", etiquetaAviso(radar("tramo"), porCarretera = false))
        assertEquals("SEMÁFORO?", etiquetaAviso(radar("fotorrojo"), porCarretera = false))
        assertEquals("RADAR? rotatorio", etiquetaAviso(radar(rotatoria = true), porCarretera = false))
    }
}
