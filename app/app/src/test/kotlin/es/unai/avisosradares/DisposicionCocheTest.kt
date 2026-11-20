package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisposicionCocheTest {

    @Test
    fun columnaEstrechaVelocidadArribaYTarjetaDebajo() {
        // Como en la foto de Unai (20/11): la columna de la derecha, más alta que ancha.
        val d = disponerCoche(560f, 1000f, conAviso = true)
        assertTrue(d.vertical)
        val t = d.tarjeta!!
        assertTrue("la velocidad va encima de la tarjeta", d.velocidad.aba <= t.arr)
        assertFalse("la tarjeta no tapa el logo", t.corta(d.marca))
        assertFalse(d.velocidad.corta(d.marca))
        assertTrue(d.zona.contiene(t) && d.zona.contiene(d.velocidad) && d.zona.contiene(d.marca))
    }

    @Test
    fun huecoAnchoVelocidadALaIzquierda() {
        // Los 2/3 de la pantalla, con la música al lado.
        val d = disponerCoche(1400f, 700f, conAviso = true)
        assertFalse(d.vertical)
        val t = d.tarjeta!!
        assertTrue(d.velocidad.der <= t.izq)
        assertFalse(t.corta(d.marca))
        assertTrue(d.zona.contiene(t))
    }

    @Test
    fun tarjetaEnFilaSiEsMuyAncha() {
        // Pantalla entera muy apaisada y baja: la tarjeta sale ancha y baja -> señal a la izquierda.
        assertTrue(disponerCoche(1920f, 720f, conAviso = true).tarjetaEnFila)
        // Los 2/3 de la pantalla: tarjeta casi cuadrada -> señal arriba y textos debajo.
        assertFalse(disponerCoche(1400f, 700f, conAviso = true).tarjetaEnFila)
        // Hueco casi cuadrado: velocidad arriba y tarjeta debajo, ancha -> señal a la izquierda.
        val d = disponerCoche(900f, 950f, conAviso = true)
        assertTrue(d.vertical)
        assertTrue(d.tarjetaEnFila)
    }

    @Test
    fun sinAvisoSinTarjetaYRespetaLaZonaVisible() {
        val zona = Caja(100f, 50f, 700f, 650f)
        val d = disponerCoche(800f, 700f, conAviso = false, zonaVisible = zona)
        assertNull(d.tarjeta)
        assertTrue(zona.contiene(d.velocidad) && zona.contiene(d.marca))
        assertTrue(d.velocidad.arr >= d.marca.aba)
    }

    @Test
    fun partirTextos() {
        assertEquals(listOf("SEMÁFORO?", "SIN CONFIRMAR"), partirEnDos("SEMÁFORO? SIN CONFIRMAR"))
        assertEquals(listOf("RADAR"), partirEnDos("RADAR"))
    }
}
