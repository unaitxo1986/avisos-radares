package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Test

class EtiquetaVersionTest {

    @Test
    fun incluyeNombreYNumeroDeCompilacion() {
        assertEquals("versión 0.1.7 (compilación 7)", etiquetaVersion("0.1.7", 7))
    }
}
