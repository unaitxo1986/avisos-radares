package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvisoCocheTest {

    private val radar = Radar("r1", "fijo", null, "A-8", null, 43.0, -3.0, null, 80, null, null)
    private val otro = Radar("r2", "fijo", null, "A-8", null, 43.1, -3.0, null, 80, null, null)
    private fun aviso(r: Radar = radar, m: Double = 460.0, exceso: Boolean = false) = AvisoActivo(r, m, porCarretera = true, exceso = exceso)

    @Test
    fun distanciaPorCentenas() {
        assertEquals("500 m", distanciaAproximada(460.0))
        assertEquals("400 m", distanciaAproximada(449.0))
        assertEquals("100 m", distanciaAproximada(12.0))
        assertEquals("2,3 km", distanciaAproximada(2310.0))
    }

    @Test
    fun textos() {
        assertEquals("RADAR · 500 m" to "Límite 80 · vas a 92 km/h", textosAvisoCoche(aviso(), 92.4))
        assertEquals("¡POR ENCIMA! · Límite 80 · vas a 92 km/h", textosAvisoCoche(aviso(exceso = true), 92.0).second)
        val tramo = AvisoActivo(radar, 2310.0, porCarretera = true, exceso = false, mediaKmh = 97.6, enTramo = true)
        assertEquals("TRAMO · media 97 · fin a 2,3 km", textosAvisoCoche(tramo, null).first)
    }

    @Test
    fun muestraActualizaYQuita() {
        var (accion, estado) = decidirAvisoCoche(null, aviso(), "RADAR · 500 m", 0)
        assertEquals(AccionAvisoCoche.MOSTRAR, accion)
        // Mismo título: nada. Título nuevo antes de 5 s: nada; después: actualizar en silencio.
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(estado, aviso(), "RADAR · 500 m", 1000).first)
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(estado, aviso(m = 380.0), "RADAR · 400 m", 3000).first)
        decidirAvisoCoche(estado, aviso(m = 380.0), "RADAR · 400 m", 6000).let { accion = it.first; estado = it.second }
        assertEquals(AccionAvisoCoche.ACTUALIZAR, accion)
        // Pasa a exceso: vuelve a saltar el aviso.
        assertEquals(AccionAvisoCoche.MOSTRAR, decidirAvisoCoche(estado, aviso(exceso = true), "RADAR · 400 m", 6500).first)
        // Otro radar: aviso nuevo. Sin aviso: se quita.
        assertEquals(AccionAvisoCoche.MOSTRAR, decidirAvisoCoche(estado, aviso(otro), "RADAR · 500 m", 7000).first)
        val (quitar, nada) = decidirAvisoCoche(estado, null, "", 8000)
        assertEquals(AccionAvisoCoche.QUITAR, quitar)
        assertNull(nada)
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(null, null, "", 9000).first)
    }

    @Test
    fun elExcesoSoloSaltaUnaVezMientrasDura() {
        val (_, e1) = decidirAvisoCoche(null, aviso(exceso = true), "RADAR · 500 m", 0)
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(e1, aviso(exceso = true), "RADAR · 500 m", 1000).first)
        // Baja del límite y vuelve a pasarlo: salta otra vez.
        val (_, e2) = decidirAvisoCoche(e1, aviso(exceso = false), "RADAR · 500 m", 2000)
        assertEquals(AccionAvisoCoche.MOSTRAR, decidirAvisoCoche(e2, aviso(exceso = true), "RADAR · 500 m", 3000).first)
    }
}
