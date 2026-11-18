package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Test

class PuestaAPuntoTest {

    private fun estado(
        ubicacion: Boolean = true, bluetooth: Boolean = true, notificaciones: Boolean = true, siempre: Boolean = true,
        ventana: Boolean = true, quiereVentana: Boolean = true, bateria: Boolean = true, coche: Boolean = true, arranque: Boolean = true,
    ) = EstadoMovil(ubicacion, bluetooth, notificaciones, siempre, ventana, quiereVentana, bateria, coche, arranque)

    @Test
    fun todoListoNoPideNada() {
        assertEquals(emptyList<PasoPendiente>(), pasosPendientes(estado()))
    }

    @Test
    fun movilRecienInstaladoPideTodoEnOrden() {
        val pasos = pasosPendientes(estado(false, false, false, false, false, true, false, false, true))
        assertEquals(
            listOf(PasoPendiente.PERMISOS_BASICOS, PasoPendiente.UBICACION_SIEMPRE, PasoPendiente.VENTANA_FLOTANTE, PasoPendiente.BATERIA, PasoPendiente.COCHE),
            pasos,
        )
    }

    @Test
    fun sinVentanaNiArranqueConElCocheNoLosPide() {
        assertEquals(emptyList<PasoPendiente>(), pasosPendientes(estado(ventana = false, quiereVentana = false, coche = false, arranque = false)))
        assertEquals(listOf(PasoPendiente.PERMISOS_BASICOS), pasosPendientes(estado(notificaciones = false)))
    }

    @Test
    fun estadoDeLaPista() {
        assertEquals(Pista.BOXES, pista(enMarcha = false, simulando = true))
        assertEquals(Pista.PISTA, pista(enMarcha = true, simulando = false))
        assertEquals(Pista.SIMULACION, pista(enMarcha = true, simulando = true))
    }
}
