package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CochesBluetoothTest {

    private val seat = DispositivoBt("AA:BB:CC:00:00:01", "Seat León")
    private val manos = DispositivoBt("AA:BB:CC:00:00:02", "Manos libres")

    @Test
    fun seGuardanYSeLeenVarios() {
        val texto = codificarDispositivos(listOf(seat, manos))
        assertEquals(listOf(seat, manos), decodificarDispositivos(texto))
        assertEquals(emptyList<DispositivoBt>(), decodificarDispositivos(""))
        // Sin nombre se usa la dirección; repetidos (aunque cambien las mayúsculas) una sola vez.
        assertEquals(listOf(DispositivoBt("11:22", "11:22")), decodificarDispositivos("11:22\n11:22\tOtro"))
    }

    @Test
    fun soloCuentanLosElegidos() {
        assertTrue(esElegido(listOf(seat, manos), "aa:bb:cc:00:00:02"))
        assertFalse(esElegido(listOf(seat), "AA:BB:CC:00:00:02"))
    }

    @Test
    fun arrancaConCualquieraYParaAlDesconectarseElUltimo() {
        var d = alConectarBt(emptySet(), seat.direccion, enMarcha = false)
        assertTrue(d.arrancar)
        d = alConectarBt(d.conectados, manos.direccion, enMarcha = true)
        assertFalse(d.arrancar)
        d = alDesconectarBt(d.conectados, seat.direccion)
        assertFalse("queda el manos libres", d.parar)
        d = alDesconectarBt(d.conectados, manos.direccion)
        assertTrue(d.parar)
    }

    @Test
    fun loApuntadoDeAntesSeOlvidaSiElServicioEstabaParado() {
        // Una desconexión que no llegó (por ejemplo, un reinicio) no impide parar después.
        val d = alConectarBt(setOf(manos.direccion), seat.direccion, enMarcha = false)
        assertEquals(setOf(seat.direccion), d.conectados)
        assertTrue(alDesconectarBt(d.conectados, seat.direccion).parar)
    }

    @Test
    fun textoDeLaTarjeta() {
        assertEquals("Elige su Bluetooth", textoDispositivos(emptyList()))
        assertEquals("Seat León · Manos libres", textoDispositivos(listOf(seat, manos)))
    }
}
