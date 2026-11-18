package es.unai.avisosradares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pruebas con la lista real publicada (`datos/salida/radares.json`): la lectura y una simulación de paso por
 * **cada** radar con tramo de aproximación fiable, en su sentido y en el contrario, con error de GPS.
 * Es la reproducción de recorridos de la fase 4.4 sin salir de casa.
 */
class DatosRealesTest {

    private val fichero = File("../../datos/salida/radares.json")
    private val lista: ListaRadares by lazy { leerRadares(fichero.readText()) }

    @Test
    fun leeLaListaPublicada() {
        assertTrue("no se encuentra ${fichero.absolutePath}", fichero.exists())
        val r = lista.radares
        assertTrue("radares: ${r.size}", r.size >= 600)
        assertEquals(r.size, r.map { it.id }.toSet().size)
        val conLinea = r.count { it.porCarretera }
        assertTrue("con línea: $conLinea", conLinea >= r.size / 2)
        // Ninguna línea demasiado corta ni demasiado larga (500 m, decisión de Unai).
        for (radar in r.filter { it.porCarretera }) {
            val l = radar.linea!!
            var largo = 0.0
            for (i in 1 until l.size) largo += distanciaM(l[i - 1][0], l[i - 1][1], l[i][0], l[i][1])
            assertTrue("${radar.id}: línea de $largo m", largo in 400.0..560.0)
            assertTrue("${radar.id}: la línea no acaba junto al radar",
                distanciaM(l.last()[0], l.last()[1], radar.lat, radar.lon) < 150.0)
        }
        // Límites dentro de lo razonable.
        assertTrue(r.all { it.limite == null || it.limite in 20..130 })
        println("Lista ${lista.version}: ${r.size} radares, $conLinea por carretera, ${r.count { it.limite != null }} con límite mostrado")
    }

    @Test
    fun noMuestraLosLimitesDeFiabilidadBaja() {
        // Decisión de Unai (18/11): en los `osm_cercana` (fiabilidad baja) se avisa sin límite.
        val texto = fichero.readText()
        val bajas = Regex("\"id\":\"([^\"]+)\"[^{}]*\"limite_fiab\":\"baja\"").findAll(texto).map { it.groupValues[1] }.toSet()
        assertTrue("debería haber radares con límite de fiabilidad baja", bajas.isNotEmpty())
        for (radar in lista.radares.filter { it.id in bajas }) assertEquals(null, radar.limite)
    }

    @Test
    fun simulaElPasoPorCadaRadarConLinea() {
        val motor = { MotorAvisos(lista.radares) }
        val conLinea = lista.radares.filter { it.porCarretera }
        val sinAviso = mutableListOf<String>()
        val sinPasado = mutableListOf<String>()
        val avisoAlReves = mutableListOf<String>()
        val distancias = mutableListOf<Double>()
        val conBucle = mutableListOf<String>()
        var pasosDistancia = 0
        var pasosAtascados = 0
        for ((i, radar) in conLinea.withIndex()) {
            // En su sentido, a 100 km/h y con 10 m de error de GPS. La simulación prolonga la línea 300 m hacia
            // atrás en línea recta; si la línea da una vuelta (rotonda al principio), esa prolongación caería
            // encima de la propia línea en sentido contrario, así que en esos casos se empieza en la línea.
            val bucle = vuelveSobreSiMisma(radar.linea!!)
            if (bucle) conBucle += radar.id
            val m = motor()
            val ev = mutableListOf<Evento>()
            var anterior: Double? = null
            for (p in recorridoSimulado(radar.linea, 100.0, antesM = if (bucle) 0.0 else 300.0, ruidoM = 10.0, semilla = i.toLong())) {
                val res = m.procesa(p)
                ev += res.eventos
                // La distancia que ve el conductor baja de forma continua: a 100 km/h son unos 28 m por segundo.
                val d = res.aviso?.takeIf { it.radar.id == radar.id }?.distanciaM
                if (d != null && anterior != null) {
                    pasosDistancia++
                    if (anterior - d < 8.0) pasosAtascados++
                }
                anterior = d
            }
            // Un radar repetido en el mismo punto que otro no suena por separado: cuenta el aviso del otro.
            val propio = ev.filter { mismoPunto(it.radar, radar) }
            val aviso = propio.firstOrNull { it.tipo == TipoEvento.AVISO }
            if (aviso == null) sinAviso += radar.id else distancias += aviso.distanciaM
            if (propio.none { it.tipo == TipoEvento.PASADO }) sinPasado += radar.id
            // En sentido contrario por la misma línea: no debe avisar de este radar.
            val m2 = motor()
            val ev2 = recorridoSimulado(radar.linea, 100.0, alReves = true, ruidoM = 10.0, semilla = i.toLong())
                .flatMap { m2.procesa(it).eventos }
            if (ev2.any { it.radar.id == radar.id && it.tipo == TipoEvento.AVISO }) avisoAlReves += radar.id
        }
        distancias.sort()
        val mediana = if (distancias.isEmpty()) 0.0 else distancias[distancias.size / 2]
        println(
            "Simulación sobre ${conLinea.size} radares con línea: sin aviso ${sinAviso.size} ${sinAviso.take(10)}, " +
                "sin 'pasado' ${sinPasado.size} ${sinPasado.take(10)}, aviso en sentido contrario ${avisoAlReves.size} " +
                "${avisoAlReves.take(10)}, distancia mediana del aviso ${mediana.toInt()} m; líneas con bucle ${conBucle.size} $conBucle"
        )
        println("Distancia mostrada: $pasosAtascados de $pasosDistancia segundos sin bajar al menos 8 m")
        assertTrue("la distancia se queda quieta demasiadas veces: $pasosAtascados de $pasosDistancia", pasosAtascados * 20 <= pasosDistancia)
        assertTrue("radares sin aviso: $sinAviso", sinAviso.isEmpty())
        assertTrue("radares que avisan en sentido contrario: $avisoAlReves", avisoAlReves.isEmpty())
        assertTrue("radares que no se dan por pasados: $sinPasado", sinPasado.size <= conLinea.size / 50)
        assertTrue("el aviso debe llegar a unos 450 m o más; mediana $mediana", mediana >= 420.0)
    }

    private fun mismoPunto(a: Radar, b: Radar) = a.id == b.id || distanciaM(a.lat, a.lon, b.lat, b.lon) <= 30.0

    /**
     * Radares repetidos en el mismo punto (la DGT lista a veces varias cabinas con las mismas coordenadas, y la
     * cámara de inicio de un tramo también como radar fijo): al pasar por ellos suena un solo aviso.
     */
    @Test
    fun losRadaresRepetidosSuenanUnaVez() {
        val grupos = lista.radares.filter { it.porCarretera }.groupBy { Pair(Math.round(it.lat * 1e5), Math.round(it.lon * 1e5)) }
            .values.filter { it.size > 1 }
        assertTrue("debería haber radares repetidos en la lista", grupos.isNotEmpty())
        val repetidos = mutableListOf<String>()
        for (g in grupos) {
            val m = MotorAvisos(lista.radares)
            val avisos = recorridoSimulado(g[0].linea!!, 100.0).flatMap { m.procesa(it).eventos }
                .count { it.tipo == TipoEvento.AVISO && g.any { r -> mismoPunto(r, it.radar) } }
            if (avisos != 1) repetidos += "${g.map { it.id }}: $avisos avisos"
        }
        println("Radares repetidos en el mismo punto: ${grupos.size} grupos; con más de un aviso: $repetidos")
        assertTrue("suenan varias veces: $repetidos", repetidos.isEmpty())
    }

    /** ¿Pasa la línea a menos de 40 m de su primer punto después de haber recorrido 60 m? */
    private fun vuelveSobreSiMisma(l: List<DoubleArray>): Boolean {
        var recorrido = 0.0
        for (i in 1 until l.size) {
            recorrido += distanciaM(l[i - 1][0], l[i - 1][1], l[i][0], l[i][1])
            if (recorrido > 60.0 && distanciaM(l[0][0], l[0][1], l[i][0], l[i][1]) < 40.0) return true
        }
        return false
    }

    @Test
    fun simulaElPasoPorCadaRadarSinLinea() {
        val sinLinea = lista.radares.filter { !it.porCarretera }
        val sinAviso = mutableListOf<String>()
        for (radar in sinLinea) {
            val m = MotorAvisos(lista.radares)
            val ev = recorridoRectoPor(radar.lat, radar.lon, 45.0, 100.0).flatMap { m.procesa(it).eventos }
            if (ev.none { mismoPunto(it.radar, radar) && it.tipo == TipoEvento.AVISO }) sinAviso += radar.id
        }
        println("Simulación sobre ${sinLinea.size} radares sin línea (cercanía): sin aviso ${sinAviso.size} ${sinAviso.take(10)}")
        assertTrue("radares sin aviso: $sinAviso", sinAviso.isEmpty())
    }

    /**
     * Fase 5: paso por cada radar de tramo con línea, a 100 km/h y con 10 m de error de GPS. Tiene que empezar a
     * medir, acabar en la cámara de fin con una media de unos 100 km/h y no medir en sentido contrario.
     */
    @Test
    fun simulaElPasoPorCadaTramo() {
        val conTramo = lista.radares.filter { it.conTramo }
        val tramos = lista.radares.filter { it.tipo == "tramo" }
        assertTrue("tramos con línea: ${conTramo.size} de ${tramos.size}", conTramo.size >= tramos.size / 2)
        val sinInicio = mutableListOf<String>()
        val sinFin = mutableListOf<String>()
        val mediaMal = mutableListOf<String>()
        val alReves = mutableListOf<String>()
        for ((i, radar) in conTramo.withIndex()) {
            // La línea del tramo tiene que medir lo que dice el dato y empezar junto a la cámara de inicio.
            val l = radar.tramo!!
            var largo = 0.0
            for (k in 1 until l.size) largo += distanciaM(l[k - 1][0], l[k - 1][1], l[k][0], l[k][1])
            assertEquals(radar.id, radar.tramoLargoM!!, largo, 5.0)
            assertTrue("${radar.id}: la línea no empieza junto a la cámara", distanciaM(l[0][0], l[0][1], radar.lat, radar.lon) < 60.0)

            val m = MotorAvisos(lista.radares)
            val ev = recorridoSimulado(lineaParaSimularTramo(radar), 100.0, ruidoM = 10.0, semilla = i.toLong())
                .flatMap { m.procesa(it).eventos }.filter { it.radar.id == radar.id }
            if (ev.none { it.tipo == TipoEvento.INICIO_TRAMO }) sinInicio += radar.id
            val fin = ev.firstOrNull { it.tipo == TipoEvento.FIN_TRAMO }
            if (fin == null) sinFin += "${radar.id} ${ev.lastOrNull()?.tipo} ${ev.lastOrNull()?.motivo}"
            else if (kotlin.math.abs(fin.mediaKmh!! - 100.0) > 3.0) mediaMal += "${radar.id} ${fin.mediaKmh}"

            val m2 = MotorAvisos(lista.radares)
            val ev2 = recorridoSimulado(lineaParaSimularTramo(radar), 100.0, alReves = true, ruidoM = 10.0, semilla = i.toLong())
                .flatMap { m2.procesa(it).eventos }
            if (ev2.any { it.radar.id == radar.id && it.tipo == TipoEvento.INICIO_TRAMO }) alReves += radar.id
        }
        println(
            "Simulación sobre ${conTramo.size} tramos con línea (de ${tramos.size}): sin inicio ${sinInicio.size} $sinInicio, " +
                "sin fin ${sinFin.size} $sinFin, media mal ${mediaMal.size} $mediaMal, mide en sentido contrario ${alReves.size} $alReves"
        )
        assertTrue("tramos que no empiezan: $sinInicio", sinInicio.isEmpty())
        assertTrue("tramos que no acaban: $sinFin", sinFin.isEmpty())
        assertTrue("media equivocada: $mediaMal", mediaMal.isEmpty())
        assertTrue("miden en sentido contrario: $alReves", alReves.isEmpty())
    }

    @Test
    fun rendimientoConTodaLaLista() {
        // Una posición por segundo durante una hora de viaje debe costar muy poco.
        val motor = MotorAvisos(lista.radares)
        val r = lista.radares.first { it.porCarretera }
        val recorrido = recorridoSimulado(r.linea!!, 100.0, antesM = 2000.0, despuesM = 2000.0)
        val inicio = System.nanoTime()
        var n = 0
        while (n < 3600) {
            for (p in recorrido) {
                motor.procesa(p)
                n++
            }
        }
        val ms = (System.nanoTime() - inicio) / 1_000_000
        println("$n posiciones procesadas en $ms ms")
        assertTrue("demasiado lento: $ms ms", ms < 20_000)
    }
}
