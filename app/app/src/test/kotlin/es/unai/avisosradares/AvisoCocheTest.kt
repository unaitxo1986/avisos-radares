package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvisoCocheTest {

    private val radar = Radar("r1", "fijo", null, "A-8", null, 43.0, -3.0, null, 80, null, null)
    private val otro = Radar("r2", "fijo", null, "A-8", null, 43.1, -3.0, null, 80, null, null)
    private fun aviso(r: Radar = radar, m: Double = 460.0, exceso: Boolean = false) = AvisoActivo(r, m, porCarretera = true, exceso = exceso)
    private fun tramo(m: Double, media: Double?, exceso: Boolean = false) =
        AvisoActivo(radar, m, porCarretera = true, exceso = exceso, mediaKmh = media, enTramo = true)

    @Test
    fun textosConLosMetrosDeLaVentana() {
        assertEquals("RADAR · 460 m" to "Límite 80 · vas a 92 km/h", textosAvisoCoche(aviso(), 92.4))
        assertEquals("¡POR ENCIMA! · Límite 80 · vas a 92 km/h", textosAvisoCoche(aviso(exceso = true), 92.0).second)
        assertEquals("TRAMO · media 97 · fin a 2,3 km", textosAvisoCoche(tramo(2310.0, 97.6), null).first)
        assertEquals("TRAMO · media -- · fin a 3,0 km", textosAvisoCoche(tramo(2990.0, null), null).first)
        val semaforo = Radar("s", "fotorrojo", null, null, null, 43.0, -3.0, null, null, null, null)
        assertEquals(
            "SEMÁFORO? · 120 m" to "Cámara de semáforo en rojo · vas a 40 km/h",
            textosAvisoCoche(AvisoActivo(semaforo, 120.0, porCarretera = false, exceso = false), 40.0),
        )
    }

    @Test
    fun cambiaCadaSegundoConLosMetros() {
        var (accion, estado) = decidirAvisoCoche(null, aviso(), "RADAR · 460 m", 0)
        assertEquals(AccionAvisoCoche.MOSTRAR, accion)
        // Mismos textos: nada. Textos nuevos: se actualizan en silencio, como mucho una vez por segundo.
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(estado, aviso(), "RADAR · 460 m", 1000).first)
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(estado, aviso(m = 440.0), "RADAR · 440 m", 500).first)
        decidirAvisoCoche(estado, aviso(m = 430.0), "RADAR · 430 m", 1000).let { accion = it.first; estado = it.second }
        assertEquals(AccionAvisoCoche.ACTUALIZAR, accion)
        assertEquals("RADAR · 430 m", estado!!.textos)
        // A los 3 s de saltar se renueva, para que Android Auto no lo esconda.
        assertEquals(AccionAvisoCoche.RENOVAR, decidirAvisoCoche(estado, aviso(m = 380.0), "RADAR · 380 m", 3_000).first)
    }

    @Test
    fun saltaConRadarNuevoYConExcesoYSeQuita() {
        val (_, estado) = decidirAvisoCoche(null, aviso(), "RADAR · 460 m", 0)
        assertEquals(AccionAvisoCoche.MOSTRAR, decidirAvisoCoche(estado, aviso(exceso = true), "x", 2000).first)
        assertEquals(AccionAvisoCoche.MOSTRAR, decidirAvisoCoche(estado, aviso(otro), "x", 2000).first)
        val (quitar, nada) = decidirAvisoCoche(estado, null, "", 3000)
        assertEquals(AccionAvisoCoche.QUITAR, quitar)
        assertNull(nada)
        assertEquals(AccionAvisoCoche.NADA, decidirAvisoCoche(null, null, "", 4000).first)
    }

    @Test
    fun enUnTramoLaMediaSeActualizaYSeRenuevaCadaTresSegundos() {
        val (_, e1) = decidirAvisoCoche(null, tramo(3000.0, null), "a", 0)
        val (a2, e2) = decidirAvisoCoche(e1, tramo(2970.0, 98.0), "b", 1_000)
        assertEquals(AccionAvisoCoche.ACTUALIZAR, a2)
        val (a3, e3) = decidirAvisoCoche(e2, tramo(2900.0, 98.0), "c", 3_000)
        assertEquals(AccionAvisoCoche.RENOVAR, a3)
        // Al pasarse del límite, aviso nuevo (en rojo); después, sigue renovándose.
        val (a4, e4) = decidirAvisoCoche(e3, tramo(2870.0, 101.0, exceso = true), "d", 4_000)
        assertEquals(AccionAvisoCoche.MOSTRAR, a4)
        assertEquals(AccionAvisoCoche.ACTUALIZAR, decidirAvisoCoche(e4, tramo(2840.0, 102.0, exceso = true), "e", 5_000).first)
        assertEquals(AccionAvisoCoche.RENOVAR, decidirAvisoCoche(e4, tramo(2780.0, 102.0, exceso = true), "f", 7_000).first)
    }
}
