package es.unai.avisosradares

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Una posición del GPS. [velocidadKmh] nula si el GPS no la da (entonces se calcula con la anterior). */
class Posicion(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val precisionM: Double? = null,
    val velocidadKmh: Double? = null,
)

enum class TipoEvento { AVISO, EXCESO, PASADO, CANCELADO }

class Evento(val tipo: TipoEvento, val radar: Radar, val distanciaM: Double, val motivo: String = "")

/** El aviso que se enseña en la ventana: el radar avisado más cercano. */
class AvisoActivo(val radar: Radar, val distanciaM: Double, val porCarretera: Boolean, val exceso: Boolean)

class Resultado(val velocidadKmh: Double?, val aviso: AvisoActivo?, val eventos: List<Evento>)

/**
 * Parámetros del motor. Son criterios propios **[sin verificar]** hasta el uso real (fase 4.5), salvo la
 * longitud del tramo de aproximación (500 m, decisión de Unai) que viene ya en los datos.
 */
class ParametrosMotor(
    /** Distancia máxima a la línea de aproximación para considerar que el coche va por ella. */
    val margenLineaM: Double = 35.0,
    /** Hasta cuánto se ensancha el margen cuando el GPS da poca precisión. */
    val margenExtraMaxM: Double = 15.0,
    /** Metros que hay que avanzar por la línea, hacia el radar, antes de avisar. */
    val avanceParaAvisarM: Double = 15.0,
    /** Retroceso por la línea que indica que el coche va en sentido contrario. */
    val retrocesoParaCancelarM: Double = 40.0,
    /** Posiciones seguidas fuera de la línea para dar el aviso por cancelado (desvío). */
    val posicionesFueraParaCancelar: Int = 3,
    /** Metros pasado el radar para darlo por pasado. */
    val pasadoM: Double = 10.0,
    /** Aviso por cercanía (radares sin tramo de aproximación fiable): radio. */
    val radioCercaniaM: Double = 500.0,
    /** Tras pasar o cancelar un radar no se vuelve a avisar de él hasta alejarse esto. */
    val distanciaDesbloqueoM: Double = 1000.0,
    /** Se ignoran las posiciones con peor precisión que esta. */
    val precisionMaximaM: Double = 50.0,
    /** Sin posiciones durante este tiempo (túnel largo, GPS perdido) se olvida el seguimiento. */
    val huecoMaximoMs: Long = 120_000L,
    /** Mientras haya exceso, el aviso de exceso se repite cada tanto. */
    val repetirExcesoMs: Long = 4_000L,
    /** Se considera exceso si la velocidad supera el límite en más de esto (0 = en cuanto lo supere). */
    val toleranciaExcesoKmh: Double = 0.0,
)

/**
 * Lógica de avisos de la versión 1 (fase 4.3), en Kotlin puro y con pruebas automáticas.
 *
 * **El sentido se decide sobre la carretera, no con la brújula** (regla 2 del plan): para cada radar con
 * tramo de aproximación fiable, el coche tiene que estar sobre esa línea (a menos de [ParametrosMotor.margenLineaM])
 * y avanzar a lo largo de ella hacia el radar. La distancia al radar se mide siguiendo la línea. Si el coche se
 * sale de la línea (desvío) o retrocede por ella (va en sentido contrario), el aviso se cancela.
 *
 * Los radares sin línea fiable (tramo dudoso, sentido sin resolver, radares de tramo hasta la fase 5) se
 * avisan **por cercanía en cualquier sentido**: a menos de [ParametrosMotor.radioCercaniaM] en línea recta
 * y acercándose.
 *
 * El aviso suena siempre que el coche se acerca al radar, vaya a la velocidad que vaya (Unai, 18/11). Si
 * además se conoce el límite y la velocidad lo supera, hay aviso de exceso, que se repite mientras dure.
 */
class MotorAvisos(radares: List<Radar>, private val p: ParametrosMotor = ParametrosMotor()) {

    private val preparados = radares.map { Preparado(it) }
    private val seguimientos = HashMap<String, Seguimiento>()
    /** Radares ya pasados o cancelados: no se vuelven a avisar hasta alejarse. */
    private val bloqueados = HashMap<String, Preparado>()
    private var anterior: Posicion? = null

    val numeroRadares: Int get() = preparados.size

    /** Si se pone, recibe una línea por cada radar seguido y posición (para depurar; no se usa en la app). */
    var depuracion: ((String) -> Unit)? = null

    fun procesa(pos: Posicion): Resultado {
        val eventos = ArrayList<Evento>()
        val previa = anterior
        val velocidad = pos.velocidadKmh ?: previa?.let { velocidadEntre(it, pos) }

        if (previa != null && pos.tMs - previa.tMs > p.huecoMaximoMs) {
            seguimientos.clear()
        }
        if (pos.precisionM != null && pos.precisionM > p.precisionMaximaM) {
            // Posición mala: no mueve el seguimiento, pero sigue enseñando lo que hubiera.
            return Resultado(velocidad, avisoActual(), eventos)
        }
        anterior = pos

        // Desbloquea los radares de los que el coche ya se ha alejado.
        val desbloquear = bloqueados.values.filter { it.distanciaAlRadar(pos) > p.distanciaDesbloqueoM }
        for (r in desbloquear) bloqueados.remove(r.radar.id)

        val margen = p.margenLineaM + min(pos.precisionM ?: 0.0, p.margenExtraMaxM)
        for (r in preparados) {
            val id = r.radar.id
            val seg = seguimientos[id]
            if (seg == null && (bloqueados.containsKey(id) || !r.cerca(pos))) continue
            if (r.radar.porCarretera) {
                porCarretera(r, pos, margen, eventos)
            } else {
                porCercania(r, pos, eventos)
            }
        }

        // Exceso de velocidad en los radares avisados con límite conocido.
        for (seg in seguimientos.values) {
            if (!seg.avisado) continue
            val limite = seg.r.radar.limite
            val exceso = limite != null && velocidad != null && velocidad > limite + p.toleranciaExcesoKmh
            if (exceso && (!seg.exceso || pos.tMs - seg.ultimoExcesoMs >= p.repetirExcesoMs)) {
                eventos += Evento(TipoEvento.EXCESO, seg.r.radar, seg.distancia, "${velocidad!!.toInt()} km/h con límite $limite")
                seg.ultimoExcesoMs = pos.tMs
            }
            seg.exceso = exceso
        }
        return Resultado(velocidad, avisoActual(), eventos)
    }

    private fun avisoActual(): AvisoActivo? {
        val seg = seguimientos.values.filter { it.avisado }.minByOrNull { it.distancia } ?: return null
        return AvisoActivo(seg.r.radar, seg.distancia, seg.r.radar.porCarretera, seg.exceso)
    }

    private fun termina(seg: Seguimiento, tipo: TipoEvento?, motivo: String, eventos: MutableList<Evento>) {
        seguimientos.remove(seg.r.radar.id)
        bloqueados[seg.r.radar.id] = seg.r
        if (seg.avisado && tipo != null) eventos += Evento(tipo, seg.r.radar, seg.distancia, motivo)
    }

    private fun porCarretera(r: Preparado, pos: Posicion, margen: Double, eventos: MutableList<Evento>) {
        var seg = seguimientos[r.radar.id]
        val pr = r.proyecta(pos, margen, seg?.sUltimo)
        val sobreLinea = pr.distanciaM <= margen
        depuracion?.invoke("${r.radar.id} s=${pr.s.toInt()} lateral=${pr.distanciaM.toInt()} pasado=${pr.pasadoM.toInt()} sMax=${seg?.sMax?.toInt()}")
        if (seg == null) {
            if (!sobreLinea || pr.pasadoM > 0.0) return
            seg = Seguimiento(r, pr.s)
            seguimientos[r.radar.id] = seg
        }
        // Pasado el radar: el coche está más allá del final de la línea y venía de su parte final.
        if (pr.pasadoM > p.pasadoM && seg.sUltimo >= r.largo - 100.0) {
            termina(seg, TipoEvento.PASADO, "", eventos)
            return
        }
        if (!sobreLinea) {
            seg.fuera++
            if (seg.fuera >= p.posicionesFueraParaCancelar) termina(seg, TipoEvento.CANCELADO, "fuera de la carretera del radar", eventos)
            return
        }
        seg.fuera = 0
        if (pr.s > seg.sMax) seg.sMax = pr.s
        if (seg.sMax - pr.s > p.retrocesoParaCancelarM) {
            termina(seg, TipoEvento.CANCELADO, "avanza en sentido contrario", eventos)
            return
        }
        seg.distancia = max(0.0, r.largo - pr.s)
        if (!seg.avisado && seg.sMax - seg.sEntrada >= p.avanceParaAvisarM && pr.s >= seg.sUltimo - 5.0) {
            seg.avisado = true
            eventos += Evento(TipoEvento.AVISO, r.radar, seg.distancia)
        }
        seg.sUltimo = pr.s
    }

    private fun porCercania(r: Preparado, pos: Posicion, eventos: MutableList<Evento>) {
        val d = r.distanciaAlRadar(pos)
        var seg = seguimientos[r.radar.id]
        if (seg == null) {
            if (d > p.radioCercaniaM) return
            seg = Seguimiento(r, 0.0)
            seg.dMin = d
            seg.dUltima = d
            seg.distancia = d
            seguimientos[r.radar.id] = seg
            return
        }
        when {
            d < seg.dUltima - 2.0 -> seg.acercandose++
            d > seg.dUltima + 2.0 -> seg.acercandose = 0
        }
        seg.dUltima = d
        seg.distancia = d
        if (d < seg.dMin) seg.dMin = d
        if (seg.dMin < 60.0 && d > seg.dMin + 30.0) {
            termina(seg, TipoEvento.PASADO, "", eventos)
            return
        }
        if (d > seg.dMin + 150.0 || d > p.radioCercaniaM + 150.0) {
            termina(seg, TipoEvento.CANCELADO, "se aleja sin pasar por el radar", eventos)
            return
        }
        if (!seg.avisado && seg.acercandose >= 2 && d <= p.radioCercaniaM) {
            seg.avisado = true
            eventos += Evento(TipoEvento.AVISO, r.radar, d)
        }
    }

    private class Seguimiento(val r: Preparado, val sEntrada: Double) {
        var sMax = sEntrada
        var sUltimo = sEntrada
        var fuera = 0
        var avisado = false
        var exceso = false
        var ultimoExcesoMs = Long.MIN_VALUE / 2
        var distancia = 0.0
        var dMin = Double.MAX_VALUE
        var dUltima = Double.MAX_VALUE
        var acercandose = 0
    }

    private class Proyeccion(val distanciaM: Double, val s: Double, val pasadoM: Double)

    /** Radar con su línea pasada a metros en un plano local centrado en el radar. */
    private inner class Preparado(val radar: Radar) {
        private val cosLat = cos(radar.lat * PI / 180)
        private val mPorGrado = RADIO_TIERRA_M * PI / 180
        private val xs: DoubleArray
        private val ys: DoubleArray
        private val acumulado: DoubleArray
        val largo: Double
        private val alcanceM: Double

        init {
            val linea = radar.linea
            if (linea != null && linea.size >= 2) {
                xs = DoubleArray(linea.size) { (linea[it][1] - radar.lon) * mPorGrado * cosLat }
                ys = DoubleArray(linea.size) { (linea[it][0] - radar.lat) * mPorGrado }
                acumulado = DoubleArray(linea.size)
                for (i in 1 until linea.size) acumulado[i] = acumulado[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
                largo = acumulado.last()
                var lejos = 0.0
                for (i in xs.indices) lejos = max(lejos, hypot(xs[i], ys[i]))
                alcanceM = lejos + 100.0
            } else {
                xs = DoubleArray(0)
                ys = DoubleArray(0)
                acumulado = DoubleArray(0)
                largo = 0.0
                alcanceM = p.radioCercaniaM + 50.0
            }
        }

        private fun x(pos: Posicion) = (pos.lon - radar.lon) * mPorGrado * cosLat
        private fun y(pos: Posicion) = (pos.lat - radar.lat) * mPorGrado

        /** Comprobación rápida: ¿está el coche a menos del alcance del radar? */
        fun cerca(pos: Posicion): Boolean = abs(x(pos)) <= alcanceM && abs(y(pos)) <= alcanceM

        fun distanciaAlRadar(pos: Posicion): Double = hypot(x(pos), y(pos))

        /**
         * Punto de la línea que corresponde al coche: distancia lateral, metros recorridos y metros pasado el
         * radar. Sin [sAnterior] es el punto más cercano. Con él (el coche ya iba por la línea), entre los tramos
         * a menos de [margen] se elige el que sigue al anterior, para no saltar de un lado a otro donde la línea
         * pasa dos veces cerca del mismo sitio (rotondas, curvas cerradas).
         */
        fun proyecta(pos: Posicion, margen: Double, sAnterior: Double?): Proyeccion {
            val px = x(pos)
            val py = y(pos)
            var mejor = Double.MAX_VALUE
            var mejorS = 0.0
            var mejorPasado = 0.0
            var mejorCriterio = Double.MAX_VALUE
            val ultimo = xs.size - 2
            for (i in 0..ultimo) {
                val ax = xs[i]
                val ay = ys[i]
                val dx = xs[i + 1] - ax
                val dy = ys[i + 1] - ay
                val l2 = dx * dx + dy * dy
                val tLibre = if (l2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / l2
                val t = tLibre.coerceIn(0.0, 1.0)
                val d = hypot(px - (ax + t * dx), py - (ay + t * dy))
                val s = acumulado[i] + t * sqrtOr0(l2)
                // Criterio: fuera del margen, la distancia. Dentro del margen, al entrar en la línea gana el
                // tramo más cercano al principio (el coche llega desde atrás) y después, el que sigue al anterior.
                val criterio = when {
                    d > margen -> 1e9 + d
                    sAnterior == null -> s
                    else -> abs(s - sAnterior)
                }
                if (criterio < mejorCriterio) {
                    mejorCriterio = criterio
                    mejor = d
                    mejorS = s
                    mejorPasado = if (i == ultimo && tLibre > 1.0) (tLibre - 1.0) * sqrtOr0(l2) else 0.0
                }
            }
            // Pasado el radar el punto más cercano es el final de la línea: la distancia lateral que cuenta
            // es la perpendicular a la prolongación del último segmento, no la distancia al radar.
            if (mejorPasado > 0.0) {
                val i = ultimo
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val l = sqrtOr0(dx * dx + dy * dy)
                if (l > 0.0) mejor = abs((px - xs[i]) * dy - (py - ys[i]) * dx) / l
            }
            return Proyeccion(mejor, mejorS, mejorPasado)
        }
    }

    private fun sqrtOr0(v: Double) = if (v <= 0.0) 0.0 else kotlin.math.sqrt(v)

    private fun velocidadEntre(a: Posicion, b: Posicion): Double? {
        val dt = (b.tMs - a.tMs) / 1000.0
        if (dt <= 0.0 || dt > 10.0) return null
        return distanciaM(a.lat, a.lon, b.lat, b.lon) / dt * 3.6
    }
}
