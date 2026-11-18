package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NombresTest {

    @Test
    fun nombresDeCubosMotivosEImportancia() {
        assertEquals("RESTRICTED", nombreBucket(45))
        assertEquals("USER_REQUESTED", nombreMotivoCierre(10))
        assertEquals("servicio en primer plano", nombreImportancia(125))
        assertEquals("desconocido(99)", nombreBucket(99))
    }

    @Test
    fun nombresDeSalidasDeAudio() {
        assertEquals("Bluetooth A2DP", nombreSalidaAudio(8))
        assertEquals("altavoz del móvil", nombreSalidaAudio(2))
        assertEquals("tipo 99", nombreSalidaAudio(99))
    }
}
