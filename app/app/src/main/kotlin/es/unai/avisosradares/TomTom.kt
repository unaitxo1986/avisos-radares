package es.unai.avisosradares

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos

/**
 * TomTom (7.2, 27/11): lógica pura, con pruebas, de las dos consultas que hace la app.
 *
 * 1. **Comprobación antes de avisar** (Snap to Roads): con las últimas posiciones del GPS, TomTom dice por qué
 *    carretera va el coche. Si es otra que la del radar, el aviso se calla (Unai, 20/11: "arriesgaría un poquito
 *    más"); si coincide, si TomTom no lo sabe (ramal o calle sin número) o si no contesta, se avisa como siempre.
 * 2. **Límite de la vía** (Reverse Geocoding con `returnSpeedLimit`): el límite de la carretera por la que se va,
 *    preguntado como mucho cada [PlanLimiteVia.cadaMs] y cada [PlanLimiteVia.cadaM].
 *
 * Formato de las respuestas comprobado con la sonda del 27/11 (45 radares, `datos/exploracion/tomtom/sonda.json`):
 * en Snap to Roads, `projectedPoints[i].properties.{snapResult, routeIndex}` y
 * `route[j].properties.{address.{roadNumbers, roadName}, speedLimits.{value, unit, type}}` (`speedLimits` es un
 * objeto, no una lista); en Reverse Geocoding, `addresses[0].address.{routeNumbers, streetName, speedLimit}` con
 * el límite como texto ("80.00KPH").
 */
object TomTom {

    const val TIEMPO_MAXIMO_MS = 3000L

    // ---------- Comparar carreteras ----------

    private val ROMANOS = mapOf("I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5", "VI" to "6")

    /** "N-II" → "N2", "A-8" → "A8", "Ma-1" → "MA1", "AP-9V" → "AP9V": mayúsculas, sin guiones ni espacios. */
    fun normaliza(carretera: String): String {
        val partes = carretera.uppercase(Locale.ROOT).trim().split(Regex("[\\s\\-.]+")).filter { it.isNotEmpty() }
        if (partes.size >= 2 && partes[0] == "N" && ROMANOS.containsKey(partes[1])) {
            return "N" + ROMANOS[partes[1]] + partes.drop(2).joinToString("")
        }
        return partes.joinToString("")
    }

    private val FORMA = Regex("^([A-Z]+)(\\d+)([A-Z]*)$")

    /**
     * ¿Es la misma carretera? Iguales una vez normalizadas, o con el mismo número y prefijos equivalentes:
     * A, AP y N son la misma familia (la A-8 es AP-8 en Gipuzkoa; la N-II pasó a A-2), y se ignora la letra final
     * (AP-9V frente a AP-9). Así se prefiere avisar de más que callar un radar de verdad (sonda del 27/11).
     */
    fun mismaCarretera(a: String, b: String): Boolean {
        val na = normaliza(a)
        val nb = normaliza(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val ma = FORMA.matchEntire(na) ?: return false
        val mb = FORMA.matchEntire(nb) ?: return false
        if (ma.groupValues[2] != mb.groupValues[2]) return false
        return familia(ma.groupValues[1]) == familia(mb.groupValues[1])
    }

    private fun familia(prefijo: String) = when (prefijo) {
        "A", "AP", "N" -> "A"
        else -> prefijo
    }

    // ---------- Snap to Roads ----------

    /** Lo que dice TomTom del tramo de carretera donde cae la última posición. */
    class Carretera(val emparejada: Boolean, val numeros: List<String>, val nombre: String?, val limiteKmh: Int?)

    enum class Veredicto { CONFIRMADA, DESCONOCIDA, OTRA_CARRETERA }

    /** Lee la respuesta de Snap to Roads; nula si no tiene la forma esperada. */
    fun leerSnap(texto: String): Carretera? {
        val raiz = (try { leerJson(texto) } catch (e: Exception) { null }) as? Map<*, *> ?: return null
        val puntos = raiz["projectedPoints"] as? List<*> ?: return null
        val ultimo = (puntos.lastOrNull() as? Map<*, *>)?.get("properties") as? Map<*, *> ?: return null
        val emparejada = ultimo["snapResult"] == "Matched"
        val indice = (ultimo["routeIndex"] as? Double)?.toInt()
        val tramos = raiz["route"] as? List<*>
        if (!emparejada || indice == null || tramos == null || indice !in tramos.indices) return Carretera(false, emptyList(), null, null)
        val p = (tramos[indice] as? Map<*, *>)?.get("properties") as? Map<*, *> ?: return Carretera(false, emptyList(), null, null)
        val direccion = p["address"] as? Map<*, *>
        val numeros = (direccion?.get("roadNumbers") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val nombre = direccion?.get("roadName") as? String
        val limite = limiteDeSnap(p["speedLimits"])
        return Carretera(true, numeros, nombre, limite)
    }

    /** `speedLimits` llega como objeto; por si acaso, también se acepta una lista. Solo el máximo, en km/h. */
    private fun limiteDeSnap(valor: Any?): Int? {
        val lista = when (valor) {
            is Map<*, *> -> listOf(valor)
            is List<*> -> valor.filterIsInstance<Map<*, *>>()
            else -> return null
        }
        for (l in lista) {
            if (l["type"] != null && l["type"] != "Maximum") continue
            val v = (l["value"] as? Double) ?: continue
            if (l["unit"] != null && l["unit"] != "kmph") continue
            return v.toInt().takeIf { it in 5..150 }
        }
        return null
    }

    /**
     * CONFIRMADA si algún número o el nombre de TomTom es la carretera del radar; OTRA_CARRETERA si TomTom da
     * números y ninguno lo es; DESCONOCIDA si no hay respuesta, la posición no cae en una carretera o el tramo
     * no tiene número (ramales, calles), porque entonces no se sabe y se avisa.
     */
    fun veredicto(carreteraRadar: String, c: Carretera?): Veredicto {
        if (c == null || !c.emparejada) return Veredicto.DESCONOCIDA
        if (c.numeros.any { mismaCarretera(carreteraRadar, it) }) return Veredicto.CONFIRMADA
        val nombre = c.nombre?.let { normaliza(it) } ?: ""
        val radar = normaliza(carreteraRadar)
        if (radar.isNotEmpty() && nombre.contains(radar)) return Veredicto.CONFIRMADA
        return if (c.numeros.isEmpty()) Veredicto.DESCONOCIDA else Veredicto.OTRA_CARRETERA
    }

    /** Texto para el registro: "AP-8 / E-70 (Autopista del Cantábrico), límite 80". */
    fun describe(c: Carretera?): String {
        if (c == null) return "sin respuesta válida"
        if (!c.emparejada) return "la posición no cae en ninguna carretera"
        val numeros = c.numeros.joinToString(" / ").ifEmpty { "sin número" }
        return numeros + (c.nombre?.let { " ($it)" } ?: "") + (c.limiteKmh?.let { ", límite $it" } ?: "")
    }

    /**
     * Posiciones para preguntar: hasta [maximo] de los últimos [ventanaMs], separadas al menos [separacionM],
     * en orden y terminando en la última. Nula si no hay al menos dos que cubran [recorridoMinimoM].
     */
    fun puntosParaSnap(
        historial: List<Posicion>,
        maximo: Int = 6,
        ventanaMs: Long = 60_000L,
        separacionM: Double = 15.0,
        recorridoMinimoM: Double = 30.0,
    ): List<Posicion>? {
        val ultima = historial.lastOrNull() ?: return null
        val elegidas = ArrayList<Posicion>()
        elegidas += ultima
        for (p in historial.asReversed().drop(1)) {
            if (ultima.tMs - p.tMs > ventanaMs) break
            val previa = elegidas.last()
            if (distanciaM(p.lat, p.lon, previa.lat, previa.lon) >= separacionM) elegidas += p
            if (elegidas.size >= maximo) break
        }
        if (elegidas.size < 2) return null
        val primera = elegidas.last()
        if (distanciaM(primera.lat, primera.lon, ultima.lat, ultima.lon) < recorridoMinimoM) return null
        return elegidas.asReversed()
    }

    /** Rumbo de [a] a [b] en grados (0 = norte, 90 = este). */
    fun rumbo(a: Posicion, b: Posicion): Double {
        val dy = b.lat - a.lat
        val dx = (b.lon - a.lon) * cos(a.lat * PI / 180)
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private const val CAMPOS = "{projectedPoints{properties{routeIndex,snapResult}}," +
        "route{properties{speedLimits{value,unit,type},address{roadName,roadNumbers}}}}"

    fun urlSnap(clave: String, puntos: List<Posicion>): String {
        val coords = puntos.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lon, it.lat) }
        val rumbos = puntos.indices.joinToString(";") { i ->
            val a = puntos[if (i < puntos.size - 1) i else i - 1]
            val b = puntos[if (i < puntos.size - 1) i + 1 else i]
            String.format(Locale.ROOT, "%.0f", rumbo(a, b))
        }
        return "https://api.tomtom.com/snapToRoads/1?key=" + cod(clave) +
            "&points=" + cod(coords) + "&headings=" + cod(rumbos) + "&fields=" + cod(CAMPOS) +
            "&vehicleType=PassengerCar&measurementSystem=metric"
    }

    // ---------- Reverse Geocoding (límite de la vía) ----------

    class Via(val limiteKmh: Int?, val numeros: List<String>, val calle: String?)

    /** Lee la respuesta de Reverse Geocoding; nula si no tiene la forma esperada. */
    fun leerReverse(texto: String): Via? {
        val raiz = (try { leerJson(texto) } catch (e: Exception) { null }) as? Map<*, *> ?: return null
        val direcciones = raiz["addresses"] as? List<*> ?: return null
        val d = (direcciones.firstOrNull() as? Map<*, *>)?.get("address") as? Map<*, *> ?: return Via(null, emptyList(), null)
        val numeros = (d["routeNumbers"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return Via(leeLimite(d["speedLimit"] as? String), numeros, d["streetName"] as? String)
    }

    /** "80.00KPH" → 80; "30.00MPH" → 48; nulo si no se entiende o está fuera de 5..150 km/h. */
    fun leeLimite(texto: String?): Int? {
        if (texto == null) return null
        val m = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(KPH|MPH)\\s*$").matchEntire(texto.uppercase(Locale.ROOT)) ?: return null
        val v = m.groupValues[1].toDouble()
        val kmh = if (m.groupValues[2] == "MPH") v * 1.609344 else v
        return Math.round(kmh).toInt().takeIf { it in 5..150 }
    }

    fun urlReverse(clave: String, p: Posicion, rumbo: Double?): String =
        "https://api.tomtom.com/search/2/reverseGeocode/" +
            String.format(Locale.ROOT, "%.6f,%.6f", p.lat, p.lon) + ".json?key=" + cod(clave) +
            "&returnSpeedLimit=true&radius=50" + (rumbo?.let { String.format(Locale.ROOT, "&heading=%.0f", it) } ?: "")

    private fun cod(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * Cuándo preguntar a TomTom por el límite de la vía y cuánto vale la respuesta. Cuota gratuita de Reverse
 * Geocoding: 20.000 consultas al mes por clave, compartida por todos los que usan la app; con una cada 30 s y
 * cada 300 m, una hora de coche son unas 120 (criterio de Claude **[sin verificar]** con el uso real).
 */
class PlanLimiteVia(
    val cadaMs: Long = 30_000L,
    val cadaM: Double = 300.0,
    /** Tras un fallo (sin cobertura, error), se espera esto antes de volver a probar. */
    val trasFalloMs: Long = 60_000L,
    /** Velocidad mínima para preguntar (parado no cambia de carretera). */
    val velocidadMinimaKmh: Double = 10.0,
    /** La respuesta deja de enseñarse pasado este tiempo o esta distancia sin otra nueva. */
    val validezMs: Long = 120_000L,
    val validezM: Double = 1500.0,
) {
    private var ultimaPregunta: Posicion? = null
    private var ultimoFallo = false
    private var respuesta: Posicion? = null
    var limite: Int? = null
        private set

    fun tocaPreguntar(p: Posicion, velocidadKmh: Double?): Boolean {
        if ((velocidadKmh ?: 0.0) < velocidadMinimaKmh) return false
        val u = ultimaPregunta ?: return true
        val pasado = p.tMs - u.tMs
        if (ultimoFallo) return pasado >= trasFalloMs
        return pasado >= cadaMs && distanciaM(u.lat, u.lon, p.lat, p.lon) >= cadaM
    }

    fun pregunta(p: Posicion) {
        ultimaPregunta = p
    }

    /** [limiteKmh] nulo con [ok] = TomTom contestó pero no conoce el límite: deja de enseñarse el anterior. */
    fun respuesta(p: Posicion, ok: Boolean, limiteKmh: Int?) {
        ultimoFallo = !ok
        if (!ok) return
        respuesta = p
        limite = limiteKmh
    }

    /** El límite que se enseña ahora, o nulo si no hay o ya no vale. */
    fun vigente(p: Posicion): Int? {
        val r = respuesta ?: return null
        if (p.tMs - r.tMs > validezMs || distanciaM(r.lat, r.lon, p.lat, p.lon) > validezM) return null
        return limite
    }
}
