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

/**
 * AVISO, EXCESO, PASADO y CANCELADO son de los radares (y del aviso al llegar al inicio de un tramo).
 * INICIO_TRAMO y FIN_TRAMO marcan cuándo empieza y acaba la medida de la velocidad media (fase 5); también hay
 * CANCELADO si el coche se sale del tramo antes del fin.
 */
enum class TipoEvento { AVISO, EXCESO, PASADO, CANCELADO, INICIO_TRAMO, FIN_TRAMO }

/** [mediaKmh]: velocidad media del tramo en ese momento (solo en los eventos de tramo). */
class Evento(val tipo: TipoEvento, val radar: Radar, val distanciaM: Double, val motivo: String = "", val mediaKmh: Double? = null)

/**
 * El aviso que se enseña en la ventana: el radar avisado más cercano o, si no hay ninguno, el tramo en curso.
 * En un tramo ([enTramo]), [distanciaM] es lo que falta hasta el fin, [mediaKmh] la velocidad media desde el
 * inicio (nula los primeros metros) y [exceso] indica que la media supera el límite.
 */
class AvisoActivo(
    val radar: Radar,
    val distanciaM: Double,
    val porCarretera: Boolean,
    val exceso: Boolean,
    val mediaKmh: Double? = null,
    val enTramo: Boolean = false,
)

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
    /** Radio de cercanía de los radares municipales, en ciudad (decisión de Unai, 18/11). */
    val radioMunicipalM: Double = 150.0,
    /**
     * Cámaras de OpenStreetMap (sin confirmar o confirmadas por Unai): se avisa unos segundos antes de llegar yendo a la velocidad del
     * radar (decisión de Unai, 18/11: "según velocidad del radar para que avise con 10 segundos"). Sin límite
     * conocido se supone la velocidad urbana; nunca menos del radio mínimo.
     */
    val segundosAvisoSinConfirmar: Double = 10.0,
    val velocidadSupuestaSinLimiteKmh: Double = 50.0,
    val radioMinimoSinConfirmarM: Double = 100.0,
    /** Tras pasar o cancelar un radar no se vuelve a avisar de él hasta alejarse esto. */
    val distanciaDesbloqueoM: Double = 1000.0,
    /** Se ignoran las posiciones con peor precisión que esta. */
    val precisionMaximaM: Double = 50.0,
    /** Sin posiciones durante este tiempo (túnel largo, GPS perdido) se olvida el seguimiento. */
    val huecoMaximoMs: Long = 120_000L,
    /**
     * Tras el aviso, cada vez que el coche avanza esto hacia el radar se vuelve a pitar si va por encima del
     * límite (decisión de Unai, 18/11: "seguir pitando cada 100 m si la velocidad es excesiva").
     */
    val pasoPitidoExcesoM: Double = 100.0,
    /** Se considera exceso si la velocidad supera el límite en más de esto (0 = en cuanto lo supere). */
    val toleranciaExcesoKmh: Double = 0.0,
    // ---- Radares de tramo (fase 5) ----
    /** La medida empieza si el coche cruza el inicio por la línea del tramo o aparece en ella antes de esto. */
    val inicioTramoMaxM: Double = 250.0,
    /** Posiciones seguidas fuera de la línea del tramo para darlo por abandonado (más que en los radares: túneles). */
    val posicionesFueraTramo: Int = 5,
    /** Sin posiciones durante este tiempo dentro de un tramo, se deja de medir (en túneles la medida sigue). */
    val huecoMaximoTramoMs: Long = 20L * 60_000L,
    /** La media no se enseña hasta recorrer esto (al principio sale muy variable). */
    val mediaMinimaM: Double = 100.0,
    /**
     * Pitidos de exceso en un tramo: al pasar la media por encima del límite y, mientras siga por encima, cada
     * esta distancia. Criterio propio **[sin verificar]**: en un tramo largo, cada 100 m sería demasiado.
     */
    val pasoPitidoExcesoTramoM: Double = 1000.0,
    /** Tras pitar por pasar la media del límite, no se vuelve a pitar "al pasarlo" hasta bajar esto por debajo. */
    val histeresisExcesoTramoKmh: Double = 2.0,
    /** Velocidad máxima creíble, para buscar al coche en la línea del tramo tras un hueco del GPS (túnel). */
    val velocidadMaximaMs: Double = 70.0,
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
 * además se conoce el límite y la velocidad lo supera, el aviso activo se marca con exceso (la ventana se pone
 * roja) y, cada 100 m de avance hacia el radar, si en ese momento hay exceso, se emite un evento EXCESO, que
 * suena (Unai, 18/11: "pitar siempre la primera vez que te acercas al radar, y seguir pitando cada 100 m si la
 * velocidad es excesiva").
 *
 * **Radares de tramo (fase 5).** El aviso al llegar al inicio es el de cualquier radar (por su tramo de
 * aproximación o por cercanía). Si además el radar trae la línea del tramo ([Radar.tramo]), el motor sigue al
 * coche por ella con el mismo criterio (sobre la línea y avanzando): la medida empieza cuando cruza la cámara de
 * inicio (evento INICIO_TRAMO; el momento del cruce se interpola entre dos posiciones) y la velocidad media es
 * lo recorrido por la línea entre el tiempo transcurrido, así que no le afectan los huecos del GPS en los túneles.
 * Acaba al pasar la cámara de fin (FIN_TRAMO, con la media final) o se cancela si el coche se sale de la línea
 * o retrocede. Si la media supera el límite, la ventana se pone roja y hay un evento EXCESO al pasarlo y luego
 * cada [ParametrosMotor.pasoPitidoExcesoTramoM] mientras siga por encima. Un coche que entra en la carretera a
 * mitad del tramo no se mide (la cámara de inicio no lo ha visto).
 */
class MotorAvisos(radares: List<Radar>, private val p: ParametrosMotor = ParametrosMotor()) {

    private val preparados = radares.map { Preparado(it) }
    private val seguimientos = HashMap<String, Seguimiento>()
    private val tramos = radares.filter { it.conTramo }.map { PreparadoTramo(it) }
    private val enTramo = HashMap<String, SeguimientoTramo>()
    /** Última posición sobre el principio de la línea de cada tramo, antes de empezar a medir. */
    private val candidatosTramo = HashMap<String, Pair<Double, Long>>()
    private val tramosBloqueados = HashSet<String>()
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

        for (t in tramos) siguiendoTramo(t, pos, margen, eventos)

        // Exceso de velocidad en los radares avisados con límite conocido: la ventana se pone roja enseguida y,
        // cada 100 m de avance hacia el radar desde el aviso, si en ese momento hay exceso, evento EXCESO (pitido).
        for (seg in seguimientos.values) {
            if (!seg.avisado) continue
            val limite = seg.r.radar.limite
            seg.exceso = limite != null && velocidad != null && velocidad > limite + p.toleranciaExcesoKmh
            if (seg.distancia <= seg.distanciaUltimoPaso - p.pasoPitidoExcesoM) {
                while (seg.distancia <= seg.distanciaUltimoPaso - p.pasoPitidoExcesoM) seg.distanciaUltimoPaso -= p.pasoPitidoExcesoM
                if (seg.exceso) {
                    eventos += Evento(TipoEvento.EXCESO, seg.r.radar, seg.distancia, "${velocidad!!.toInt()} km/h con límite $limite")
                }
            }
        }
        return Resultado(velocidad, avisoActual(), eventos)
    }

    private fun avisoActual(): AvisoActivo? {
        val seg = seguimientos.values.filter { it.avisado }.minByOrNull { it.distancia }
        if (seg != null) return AvisoActivo(seg.r.radar, seg.distancia, seg.r.radar.porCarretera, seg.exceso)
        val t = enTramo.values.minByOrNull { it.t.largo - it.sUltimo } ?: return null
        return AvisoActivo(t.t.radar, max(0.0, t.t.largo - t.sUltimo), true, t.exceso, t.media, enTramo = true)
    }

    // ---------- Radares de tramo (fase 5) ----------

    private fun siguiendoTramo(t: PreparadoTramo, pos: Posicion, margen: Double, eventos: MutableList<Evento>) {
        val id = t.radar.id
        val seg = enTramo[id]
        if (seg != null) {
            midiendo(t, seg, pos, margen, eventos)
            return
        }
        if (id in tramosBloqueados) {
            if (t.distanciaAlInicio(pos) > p.distanciaDesbloqueoM) tramosBloqueados.remove(id)
            return
        }
        if (!t.cercaDelInicio(pos)) {
            candidatosTramo.remove(id)
            return
        }
        val pr = t.proyecta(pos, -p.inicioTramoMaxM, p.inicioTramoMaxM)
        if (pr.distanciaM > margen) {
            candidatosTramo.remove(id)
            return
        }
        val c = candidatosTramo[id]
        // Antes del inicio, o retrocediendo, o la primera vez sobre la línea: se apunta y se espera.
        if (c == null || pr.s <= 0.0 || pr.s < c.first - 5.0) {
            candidatosTramo[id] = pr.s to pos.tMs
            return
        }
        val avance = pr.s - c.first
        if (avance < p.avanceParaAvisarM && c.first > 0.0) return
        val dt = (pos.tMs - c.second).toDouble()
        if (avance <= 0.0 || dt <= 0.0) return
        // Momento del cruce de la cámara de inicio (s = 0), interpolado entre las dos posiciones.
        val t0 = c.second + ((0.0 - c.first) / avance * dt).toLong()
        val nuevo = SeguimientoTramo(t, t0, pr.s, pos.tMs)
        enTramo[id] = nuevo
        candidatosTramo.remove(id)
        eventos += Evento(TipoEvento.INICIO_TRAMO, t.radar, t.largo - pr.s, "tramo de ${t.largo.toInt()} m")
    }

    private fun midiendo(t: PreparadoTramo, seg: SeguimientoTramo, pos: Posicion, margen: Double, eventos: MutableList<Evento>) {
        val dt = pos.tMs - seg.tUltimo
        if (dt > p.huecoMaximoTramoMs) {
            terminaTramo(seg, TipoEvento.CANCELADO, "sin GPS durante ${dt / 60_000} min", eventos)
            return
        }
        val alcance = max(300.0, dt / 1000.0 * p.velocidadMaximaMs + 100.0)
        val pr = t.proyecta(pos, seg.sUltimo - 100.0, seg.sUltimo + alcance)
        val sTotal = pr.s + pr.pasadoM
        if (pr.distanciaM <= margen && sTotal >= t.largo + p.pasadoM) {
            // Pasada la cámara de fin: media hasta el momento del cruce (interpolado).
            val v = (sTotal - seg.sUltimo) / max(1.0, dt.toDouble())
            val tFin = if (v > 0.0) pos.tMs - ((sTotal - t.largo) / v).toLong() else pos.tMs
            seg.sUltimo = t.largo
            seg.tUltimo = tFin
            seg.actualizaMedia(p.mediaMinimaM)
            terminaTramo(seg, TipoEvento.FIN_TRAMO, "media ${seg.media?.toInt()} km/h", eventos)
            return
        }
        if (pr.distanciaM > margen) {
            seg.fuera++
            if (seg.fuera >= p.posicionesFueraTramo) {
                // Si se sale ya junto al fin (la carretera gira tras la cámara), cuenta como terminado.
                if (seg.sUltimo >= t.largo - 200.0) {
                    terminaTramo(seg, TipoEvento.FIN_TRAMO, "media ${seg.media?.toInt()} km/h (sale de la línea junto al fin)", eventos)
                } else {
                    terminaTramo(seg, TipoEvento.CANCELADO, "se sale del tramo", eventos)
                }
            }
            return
        }
        seg.fuera = 0
        if (pr.s > seg.sMax) seg.sMax = pr.s
        if (seg.sMax - pr.s > p.retrocesoParaCancelarM) {
            terminaTramo(seg, TipoEvento.CANCELADO, "avanza en sentido contrario", eventos)
            return
        }
        if (pr.s >= seg.sUltimo) {
            seg.sUltimo = pr.s
            seg.tUltimo = pos.tMs
        }
        seg.actualizaMedia(p.mediaMinimaM)
        val limite = t.radar.limite
        val media = seg.media
        seg.exceso = limite != null && media != null && media > limite + p.toleranciaExcesoKmh
        if (limite != null && media != null && media <= limite - p.histeresisExcesoTramoKmh) seg.armado = true
        if (seg.exceso && (seg.armado || seg.sUltimo - seg.sUltimoPitido >= p.pasoPitidoExcesoTramoM)) {
            seg.armado = false
            seg.sUltimoPitido = seg.sUltimo
            eventos += Evento(TipoEvento.EXCESO, t.radar, t.largo - seg.sUltimo, "media ${media!!.toInt()} km/h con límite $limite", media)
        }
    }

    private fun terminaTramo(seg: SeguimientoTramo, tipo: TipoEvento, motivo: String, eventos: MutableList<Evento>) {
        enTramo.remove(seg.t.radar.id)
        tramosBloqueados += seg.t.radar.id
        eventos += Evento(tipo, seg.t.radar, max(0.0, seg.t.largo - seg.sUltimo), motivo, seg.media)
    }

    private class SeguimientoTramo(val t: PreparadoTramo, val t0: Long, s: Double, tMs: Long) {
        var sUltimo = s
        var sMax = s
        var tUltimo = tMs
        var fuera = 0
        var media: Double? = null
        var exceso = false
        var armado = true
        var sUltimoPitido = Double.NEGATIVE_INFINITY

        fun actualizaMedia(minimoM: Double) {
            val segundos = (tUltimo - t0) / 1000.0
            media = if (sUltimo >= minimoM && segundos >= 5.0) sUltimo / segundos * 3.6 else null
        }
    }

    /**
     * Línea de un tramo en metros, en un plano local centrado en la cámara de inicio. Las distancias a lo largo
     * de la línea se miden con el semiverseno (los tramos llegan a 33 km).
     */
    private inner class PreparadoTramo(val radar: Radar) {
        private val linea = radar.tramo!!
        private val cosLat = cos(radar.lat * PI / 180)
        private val mPorGrado = RADIO_TIERRA_M * PI / 180
        private val xs = DoubleArray(linea.size) { (linea[it][1] - radar.lon) * mPorGrado * cosLat }
        private val ys = DoubleArray(linea.size) { (linea[it][0] - radar.lat) * mPorGrado }
        private val largos = DoubleArray(linea.size - 1) { distanciaM(linea[it][0], linea[it][1], linea[it + 1][0], linea[it + 1][1]) }
        private val acumulado = DoubleArray(linea.size).also { a -> for (i in 1 until a.size) a[i] = a[i - 1] + largos[i - 1] }
        val largo = acumulado.last()
        private val alcanceInicioM = p.inicioTramoMaxM + 300.0

        private fun x(pos: Posicion) = (pos.lon - radar.lon) * mPorGrado * cosLat
        private fun y(pos: Posicion) = (pos.lat - radar.lat) * mPorGrado

        fun cercaDelInicio(pos: Posicion): Boolean = abs(x(pos)) <= alcanceInicioM && abs(y(pos)) <= alcanceInicioM

        fun distanciaAlInicio(pos: Posicion): Double = hypot(x(pos), y(pos))

        /**
         * El punto más cercano de la línea entre [sDesde] y [sHasta] metros. Antes del inicio (s negativo) se
         * prolonga el primer segmento hacia atrás y pasado el fin el último hacia delante ([Proyeccion.pasadoM]).
         */
        fun proyecta(pos: Posicion, sDesde: Double, sHasta: Double): Proyeccion {
            val px = x(pos)
            val py = y(pos)
            val ultimo = xs.size - 2
            var mejor = Proyeccion(Double.MAX_VALUE, 0.0, 0.0)
            for (i in 0..ultimo) {
                if (acumulado[i + 1] < sDesde && i != 0) continue
                if (acumulado[i] > sHasta) break
                val ax = xs[i]
                val ay = ys[i]
                val dx = xs[i + 1] - ax
                val dy = ys[i + 1] - ay
                val l2 = dx * dx + dy * dy
                val lPlano = sqrtOr0(l2)
                val tLibre = if (l2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / l2
                val tMin = if (i == 0) Double.NEGATIVE_INFINITY else 0.0
                val tMax = if (i == ultimo) Double.POSITIVE_INFINITY else 1.0
                val tc = tLibre.coerceIn(tMin, tMax)
                val d = hypot(px - (ax + tc * dx), py - (ay + tc * dy))
                if (d < mejor.distanciaM) {
                    val escala = if (lPlano > 0.0) largos[i] / lPlano else 1.0
                    val s = acumulado[i] + min(tc, 1.0) * lPlano * escala
                    val pasado = if (tc > 1.0) (tc - 1.0) * lPlano * escala else 0.0
                    mejor = Proyeccion(d, s, pasado)
                }
            }
            return mejor
        }
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
            seg.distanciaUltimoPaso = seg.distancia
            eventos += Evento(TipoEvento.AVISO, r.radar, seg.distancia)
        }
        seg.sUltimo = pr.s
    }

    private fun porCercania(r: Preparado, pos: Posicion, eventos: MutableList<Evento>) {
        val radio = r.radioCercania
        val d = r.distanciaAlRadar(pos)
        var seg = seguimientos[r.radar.id]
        if (seg == null) {
            if (d > radio) return
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
        if (d > seg.dMin + 150.0 || d > radio + 150.0) {
            termina(seg, TipoEvento.CANCELADO, "se aleja sin pasar por el radar", eventos)
            return
        }
        if (!seg.avisado && seg.acercandose >= 2 && d <= radio) {
            seg.avisado = true
            seg.distanciaUltimoPaso = d
            eventos += Evento(TipoEvento.AVISO, r.radar, d)
        }
    }

    private class Seguimiento(val r: Preparado, val sEntrada: Double) {
        var sMax = sEntrada
        var sUltimo = sEntrada
        var fuera = 0
        var avisado = false
        var exceso = false
        /** Distancia al radar en el último punto de control de 100 m (empieza en la del aviso). */
        var distanciaUltimoPaso = Double.MAX_VALUE
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
        val radioCercania = when {
            radar.sinConfirmar || radar.fuente == "osm" -> maxOf(
                p.radioMinimoSinConfirmarM,
                (radar.limite?.toDouble() ?: p.velocidadSupuestaSinLimiteKmh) / 3.6 * p.segundosAvisoSinConfirmar,
            )
            radar.municipal -> p.radioMunicipalM
            else -> p.radioCercaniaM
        }

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
                alcanceM = radioCercania + 50.0
            }
        }

        private fun x(pos: Posicion) = (pos.lon - radar.lon) * mPorGrado * cosLat
        private fun y(pos: Posicion) = (pos.lat - radar.lat) * mPorGrado

        /** Comprobación rápida: ¿está el coche a menos del alcance del radar? */
        fun cerca(pos: Posicion): Boolean = abs(x(pos)) <= alcanceM && abs(y(pos)) <= alcanceM

        fun distanciaAlRadar(pos: Posicion): Double = hypot(x(pos), y(pos))

        /**
         * Punto de la línea que corresponde al coche: distancia lateral, metros recorridos y metros pasado el
         * radar. [sAnterior] es el punto de la línea donde iba el coche (nulo al entrar). Ver el criterio abajo.
         */
        fun proyecta(pos: Posicion, margen: Double, sAnterior: Double?): Proyeccion {
            val px = x(pos)
            val py = y(pos)
            val ultimo = xs.size - 2
            val ds = DoubleArray(ultimo + 1)
            val ss = DoubleArray(ultimo + 1)
            val pasados = DoubleArray(ultimo + 1)
            var dMin = Double.MAX_VALUE
            for (i in 0..ultimo) {
                val ax = xs[i]
                val ay = ys[i]
                val dx = xs[i + 1] - ax
                val dy = ys[i + 1] - ay
                val l2 = dx * dx + dy * dy
                val tLibre = if (l2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / l2
                val t = tLibre.coerceIn(0.0, 1.0)
                ds[i] = hypot(px - (ax + t * dx), py - (ay + t * dy))
                ss[i] = acumulado[i] + t * sqrtOr0(l2)
                pasados[i] = if (i == ultimo && tLibre > 1.0) (tLibre - 1.0) * sqrtOr0(l2) else 0.0
                if (ds[i] < dMin) dMin = ds[i]
            }
            // Criterio de elección del tramo (errores corregidos con la simulación del 18/11):
            // - fuera del margen, el más cercano;
            // - al entrar en la línea, el más cercano; si hay varios casi igual de cerca (a menos de 10 m más),
            //   el más próximo al principio de la línea (rotonda al principio: la entrada y la salida se tocan);
            // - ya en la línea, el más cercano entre los que caen a menos de 90 m del punto anterior, para no
            //   saltar a otra pasada de la línea por el mismo sitio.
            var elegido = 0
            var mejorCriterio = Double.MAX_VALUE
            for (i in 0..ultimo) {
                val criterio = when {
                    ds[i] > margen -> 1e9 + ds[i]
                    sAnterior == null -> if (ds[i] <= dMin + 10.0) ss[i] else 1e6 + ds[i]
                    ss[i] in (sAnterior - 90.0)..(sAnterior + 90.0) -> ds[i]
                    else -> 1e6 + ds[i]
                }
                if (criterio < mejorCriterio) {
                    mejorCriterio = criterio
                    elegido = i
                }
            }
            var mejor = ds[elegido]
            val mejorS = ss[elegido]
            val mejorPasado = pasados[elegido]
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
