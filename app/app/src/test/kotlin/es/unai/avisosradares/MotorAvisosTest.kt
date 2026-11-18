package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la lógica de avisos (fase 4.3) con carreteras de juguete: una recta de 500 m hacia el norte
 * que termina en el radar, como un tramo de aproximación real.
 */
class MotorAvisosTest {

    private val latR = 43.25
    private val lonR = -2.90
    private val mLat = RADIO_TIERRA_M * Math.PI / 180

    /** Línea de 500 m que llega al radar desde el sur (el coche va hacia el norte). */
    private val linea = (0..10).map { i -> doubleArrayOf(latR - (500.0 - i * 50.0) / mLat, lonR) }

    private fun radar(limite: Int? = 100, conLinea: Boolean = true, id: String = "r1") = Radar(
        id = id, tipo = "fijo", nombre = "PRUEBA", carretera = "A-8", pk = 120.5, lat = latR, lon = lonR,
        sentido = "creciente", limite = limite, limiteOrigen = "oficial", linea = if (conLinea) linea else null,
    )

    private fun pasa(motor: MotorAvisos, recorrido: List<Posicion>): List<Evento> =
        recorrido.flatMap { motor.procesa(it).eventos }

    private fun tipos(eventos: List<Evento>) = eventos.map { it.tipo }

    @Test
    fun avisaAlRecorrerLaLineaYLoDaPorPasado() {
        val motor = MotorAvisos(listOf(radar()))
        val ev = pasa(motor, recorridoSimulado(linea, 90.0))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(ev))
        // Avisa al entrar en la línea, casi 500 m antes del radar.
        assertTrue("distancia del aviso ${ev[0].distanciaM}", ev[0].distanciaM in 440.0..500.0)
    }

    @Test
    fun avisaAunqueVayaPorDebajoDelLimite() {
        // Unai (18/11): el aviso suena siempre al acercarse, vaya a la velocidad que vaya.
        val motor = MotorAvisos(listOf(radar(limite = 120)))
        val ev = pasa(motor, recorridoSimulado(linea, 60.0))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(ev))
    }

    @Test
    fun noAvisaEnSentidoContrario() {
        val motor = MotorAvisos(listOf(radar()))
        val ev = pasa(motor, recorridoSimulado(linea, 90.0, alReves = true))
        assertTrue("no debe avisar: ${tipos(ev)}", ev.isEmpty())
    }

    @Test
    fun noAvisaEnLaOtraCalzadaNiEnUnaParalela() {
        // Otra calzada a 25 m en sentido contrario: está "sobre la línea" pero retrocede por ella.
        val motor = MotorAvisos(listOf(radar()))
        assertTrue(pasa(motor, recorridoSimulado(linea, 100.0, alReves = true, desplazamientoLateralM = 25.0)).isEmpty())
        // Carretera paralela a 80 m en el mismo sentido: fuera del margen.
        val motor2 = MotorAvisos(listOf(radar()))
        assertTrue(pasa(motor2, recorridoSimulado(linea, 100.0, desplazamientoLateralM = 80.0)).isEmpty())
    }

    @Test
    fun avisaConElErrorNormalDelGps() {
        val motor = MotorAvisos(listOf(radar()))
        val ev = pasa(motor, recorridoSimulado(linea, 120.0, ruidoM = 15.0, semilla = 7))
        assertEquals(TipoEvento.AVISO, ev.first().tipo)
        assertEquals(1, ev.count { it.tipo == TipoEvento.AVISO })
        assertEquals(TipoEvento.PASADO, ev.last().tipo)
    }

    @Test
    fun cancelaSiSeDesvia() {
        val motor = MotorAvisos(listOf(radar()))
        val ida = recorridoSimulado(linea, 90.0).filter { it.lat < latR - 250.0 / mLat }
        // Desvío hacia el este desde la mitad de la línea.
        val ultimo = ida.last()
        val desvio = (1..6).map { k ->
            Posicion(ultimo.tMs + k * 1000, ultimo.lat + k * 10 / mLat, ultimo.lon + k * 25 / (mLat * Math.cos(latR * Math.PI / 180)), 5.0, 90.0)
        }
        val ev = pasa(motor, ida + desvio)
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.CANCELADO), tipos(ev))
        assertNull(motor.procesa(desvio.last()).aviso)
    }

    @Test
    fun paradoEnUnAtascoNoCancela() {
        val motor = MotorAvisos(listOf(radar()))
        val ida = recorridoSimulado(linea, 50.0).filter { it.lat < latR - 300.0 / mLat }
        val parado = ida.last()
        var ev = pasa(motor, ida)
        assertEquals(listOf(TipoEvento.AVISO), tipos(ev))
        // Dos minutos parado con el GPS bailando 8 m.
        val r = java.util.Random(3)
        val atasco = (1..110).map { k ->
            Posicion(parado.tMs + k * 1000, parado.lat + (r.nextDouble() - 0.5) * 16 / mLat, parado.lon, 8.0, 0.0)
        }
        ev = pasa(motor, atasco)
        assertTrue("en el atasco no debe cambiar nada: ${tipos(ev)}", ev.isEmpty())
        assertTrue(motor.procesa(atasco.last()).aviso != null)
    }

    @Test
    fun excesoPitaCada100mSoloSiSuperaElLimite() {
        val motor = MotorAvisos(listOf(radar(limite = 80)))
        val ev = pasa(motor, recorridoSimulado(linea, 100.0))
        val excesos = ev.filter { it.tipo == TipoEvento.EXCESO }
        // Aviso a unos 470 m; después un pitido de exceso a los 370, 270, 170 y 70 m.
        assertEquals(TipoEvento.AVISO, ev.first().tipo)
        assertEquals(4, excesos.size)
        val aviso = ev.first().distanciaM
        for ((i, e) in excesos.withIndex()) {
            val esperado = aviso - 100.0 * (i + 1)
            assertTrue("pitido ${i + 1} a ${e.distanciaM} m, esperado hacia $esperado", e.distanciaM in (esperado - 30.0)..esperado)
        }
        // Por debajo del límite: solo el aviso.
        val motor2 = MotorAvisos(listOf(radar(limite = 80)))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(pasa(motor2, recorridoSimulado(linea, 79.0))))
    }

    @Test
    fun excesoSoloEnLosTramosDe100mEnQueSeSupera() {
        // Llega a 70 (límite 80) y acelera a 100 a mitad de la línea: pita solo a partir de ahí.
        val motor = MotorAvisos(listOf(radar(limite = 80)))
        val lento = recorridoSimulado(linea, 70.0)
        val ev = mutableListOf<Evento>()
        for (p in lento) {
            val rapido = p.lat > latR - 250 / mLat
            ev += motor.procesa(Posicion(p.tMs, p.lat, p.lon, p.precisionM, if (rapido) 100.0 else 70.0)).eventos
        }
        val excesos = ev.filter { it.tipo == TipoEvento.EXCESO }
        assertTrue("excesos ${excesos.map { it.distanciaM.toInt() }}", excesos.size in 2..3 && excesos.all { it.distanciaM < 250.0 })
    }

    @Test
    fun sinLimiteAvisaSinExceso() {
        val motor = MotorAvisos(listOf(radar(limite = null)))
        val ev = pasa(motor, recorridoSimulado(linea, 150.0))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(ev))
    }

    @Test
    fun laVentanaMuestraLaDistanciaPorLaCarretera() {
        val motor = MotorAvisos(listOf(radar()))
        val recorrido = recorridoSimulado(linea, 90.0)
        var ultimaDistancia = Double.MAX_VALUE
        var vistos = 0
        for (pos in recorrido) {
            val aviso = motor.procesa(pos).aviso ?: continue
            assertTrue(aviso.porCarretera)
            assertTrue("la distancia debe bajar", aviso.distanciaM <= ultimaDistancia + 1.0)
            ultimaDistancia = aviso.distanciaM
            vistos++
        }
        assertTrue(vistos >= 15)
    }

    @Test
    fun noRepiteElAvisoAlVolverAPasarEnseguida() {
        val motor = MotorAvisos(listOf(radar()))
        val ida = recorridoSimulado(linea, 90.0)
        assertEquals(1, pasa(motor, ida).count { it.tipo == TipoEvento.AVISO })
        // Media vuelta y vuelta a pasar 30 s después, sin haberse alejado 1 km: no repite.
        val otra = recorridoSimulado(linea, 90.0, t0Ms = ida.last().tMs + 30_000, antesM = 0.0)
        assertEquals(0, pasa(motor, otra).count { it.tipo == TipoEvento.AVISO })
        // Tras alejarse más de 1 km, sí vuelve a avisar.
        val lejos = Posicion(otra.last().tMs + 60_000, latR + 1500 / mLat, lonR, 5.0, 90.0)
        motor.procesa(lejos)
        val otraVez = recorridoSimulado(linea, 90.0, t0Ms = lejos.tMs + 90_000)
        assertEquals(1, pasa(motor, otraVez).count { it.tipo == TipoEvento.AVISO })
    }

    @Test
    fun porCercaniaAvisaEnLosDosSentidosYSoloAlAcercarse() {
        val sinLinea = radar(conLinea = false)
        for (rumbo in listOf(0.0, 180.0, 90.0)) {
            val motor = MotorAvisos(listOf(sinLinea))
            val ev = pasa(motor, recorridoRectoPor(latR, lonR, rumbo, 90.0))
            assertEquals("rumbo $rumbo", listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(ev))
            assertTrue(ev[0].distanciaM <= 500.0)
        }
        // Alejándose desde el radar: nada.
        val motor = MotorAvisos(listOf(sinLinea))
        val saliendo = recorridoRectoPor(latR, lonR, 0.0, 90.0).filter { it.lat > latR }
        assertTrue(pasa(motor, saliendo).isEmpty())
    }

    @Test
    fun porCercaniaCancelaSiPasaLejos() {
        // Carretera que pasa a 300 m del radar: avisa al acercarse y cancela al alejarse.
        val motor = MotorAvisos(listOf(radar(conLinea = false)))
        val lonParalela = lonR + 300 / (mLat * Math.cos(latR * Math.PI / 180))
        val ev = pasa(motor, recorridoRectoPor(latR, lonParalela, 0.0, 90.0))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.CANCELADO), tipos(ev))
    }

    @Test
    fun ignoraPosicionesImprecisas() {
        val motor = MotorAvisos(listOf(radar()))
        val malas = recorridoSimulado(linea, 90.0).map { Posicion(it.tMs, it.lat, it.lon, 80.0, it.velocidadKmh) }
        assertTrue(pasa(motor, malas).isEmpty())
    }

    @Test
    fun calculaLaVelocidadSiElGpsNoLaDa() {
        val motor = MotorAvisos(listOf(radar()))
        val sinVelocidad = recorridoSimulado(linea, 72.0).map { Posicion(it.tMs, it.lat, it.lon, 5.0, null) }
        var ultima: Double? = null
        for (p in sinVelocidad) ultima = motor.procesa(p).velocidadKmh
        assertTrue("velocidad calculada $ultima", ultima != null && kotlin.math.abs(ultima - 72.0) < 2.0)
    }

    @Test
    fun conDosRadaresSeguidosMuestraElMasCercano() {
        // Segundo radar 250 m antes del primero, en la misma carretera.
        val lat2 = latR - 250 / mLat
        val linea2 = (0..10).map { i -> doubleArrayOf(lat2 - (500.0 - i * 50.0) / mLat, lonR) }
        val r2 = Radar("r2", "fijo", "ANTES", "A-8", 120.25, lat2, lonR, null, 100, "oficial", linea2)
        val motor = MotorAvisos(listOf(radar(), r2))
        val ev = pasa(motor, recorridoSimulado(linea2 + linea.filter { it[0] > lat2 }, 90.0, antesM = 100.0))
        assertEquals(2, ev.count { it.tipo == TipoEvento.AVISO })
        assertEquals(2, ev.count { it.tipo == TipoEvento.PASADO })
        assertEquals("r2", ev.first { it.tipo == TipoEvento.PASADO }.radar.id)
    }

    @Test
    fun losMunicipalesAvisanA150m() {
        val municipal = Radar("m", "fijo", "CALLE", null, null, latR, lonR, null, 50, "oficial", null, fuente = "municipal", rotatoria = true)
        val motor = MotorAvisos(listOf(municipal))
        val ev = pasa(motor, recorridoRectoPor(latR, lonR, 0.0, 50.0))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(ev))
        assertTrue("distancia ${ev[0].distanciaM}", ev[0].distanciaM <= 150.0)
        // Calle paralela a 200 m: nada.
        val motor2 = MotorAvisos(listOf(municipal))
        val lonParalela = lonR + 200 / (mLat * Math.cos(latR * Math.PI / 180))
        assertTrue(pasa(motor2, recorridoRectoPor(latR, lonParalela, 0.0, 50.0)).isEmpty())
        assertTrue(municipal.descripcion().contains("rotatoria"))
    }

    @Test
    fun lasSinConfirmarAvisanUnos10SegundosAntesSegunElLimite() {
        fun camara(limite: Int?) = Radar("osm-1", "fijo", "Gorliz", null, null, latR, lonR, null, limite, null, null, fuente = "osm", sinConfirmar = true)
        // 50 km/h: 10 s son unos 139 m. 30 km/h: 83 m, pero nunca menos de 100. Sin límite: como 50.
        // El aviso llega dos posiciones después de entrar en el radio (hay que confirmar que se acerca).
        for ((limite, radio) in listOf(50 to 139.0, 30 to 100.0, null to 139.0, 90 to 250.0)) {
            val motor = MotorAvisos(listOf(camara(limite)))
            val ev = pasa(motor, recorridoRectoPor(latR, lonR, 0.0, 40.0, mitadM = 600.0))
            assertEquals("límite $limite", listOf(TipoEvento.AVISO, TipoEvento.PASADO), tipos(ev))
            assertTrue("límite $limite: aviso a ${ev[0].distanciaM} m, radio $radio", ev[0].distanciaM in (radio - 35.0)..radio)
        }
        assertEquals("RADAR? sin confirmar", etiquetaAviso(camara(null), porCarretera = false))
    }

    @Test
    fun dosRadaresEnElMismoPuntoSuenanUnaVez() {
        // La DGT lista a veces dos cabinas con las mismas coordenadas (A-2 km 15,04 tiene tres).
        val motor = MotorAvisos(listOf(radar(id = "r1"), radar(id = "r2")))
        val ev = pasa(motor, recorridoSimulado(linea, 110.0))
        assertEquals(listOf(TipoEvento.AVISO, TipoEvento.EXCESO, TipoEvento.EXCESO, TipoEvento.EXCESO, TipoEvento.EXCESO, TipoEvento.PASADO), tipos(ev))
        assertTrue(ev.all { it.radar.id == ev[0].radar.id })
    }
}
