package es.unai.avisosradares

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Radio medio de la Tierra en metros. */
const val RADIO_TIERRA_M = 6_371_008.8

/** Distancia en metros entre dos puntos (fórmula del semiverseno). */
fun distanciaM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val f1 = lat1 * PI / 180
    val f2 = lat2 * PI / 180
    val df = f2 - f1
    val dl = (lon2 - lon1) * PI / 180
    val a = sin(df / 2) * sin(df / 2) + cos(f1) * cos(f2) * sin(dl / 2) * sin(dl / 2)
    return 2 * RADIO_TIERRA_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * Un radar tal como lo usa la app, leído de `radares.json` (formato en `datos/esquema-radares.md`).
 *
 * - [linea]: tramo de aproximación (puntos `[lat, lon]` en el orden de la marcha, termina en el radar). Solo
 *   viene si su fiabilidad es `alta`; entonces el aviso se da **por carretera y sentido**. Si es nula, el aviso
 *   se da **por cercanía en cualquier sentido** (tramo dudoso, sentido sin resolver, radares de tramo).
 * - [limite]: el que se muestra y con el que se compara la velocidad. Es nulo si no se conoce **o si su
 *   fiabilidad es `baja`** (decisión de Unai del 18/11: en los `osm_cercana` se avisa sin límite).
 * - [fuente] `municipal`: radares de listas oficiales de ayuntamientos (3.7); avisan por cercanía con un radio
 *   menor (150 m, decisión de Unai del 18/11). [rotatoria]: cabina que puede estar vacía (Vitoria-Gasteiz); se
 *   avisa igual que las demás y la ventana lo indica (decisión de Unai del 18/11).
 */
class Radar(
    val id: String,
    val tipo: String,
    val nombre: String?,
    val carretera: String?,
    val pk: Double?,
    val lat: Double,
    val lon: Double,
    val sentido: String?,
    val limite: Int?,
    val limiteOrigen: String?,
    val linea: List<DoubleArray>?,
    val fuente: String? = null,
    val rotatoria: Boolean = false,
) {
    val municipal: Boolean get() = fuente == "municipal"

    val porCarretera: Boolean get() = linea != null && linea.size >= 2

    /** Texto corto para la pantalla y el registro: "A-8 km 120,5 (MAX CENTER)". */
    fun descripcion(): String {
        val partes = mutableListOf<String>()
        when (tipo) {
            "tramo" -> partes += "Inicio de tramo"
            "tramo_extremo" -> partes += "Extremo de tramo"
            "fotorrojo" -> partes += "Semáforo"
        }
        if (carretera != null) partes += carretera
        if (pk != null) partes += "km " + String.format(java.util.Locale.ROOT, "%.1f", pk).replace('.', ',')
        if (nombre != null) partes += if (partes.isEmpty()) nombre else "($nombre)"
        if (rotatoria) partes += "[cabina rotatoria]"
        return partes.joinToString(" ")
    }
}

class ListaRadares(val version: String?, val radares: List<Radar>)

/**
 * Lee `radares.json`. Falla con una excepción si el fichero no tiene la forma esperada, para no
 * sustituir una lista buena por una mala.
 */
fun leerRadares(texto: String): ListaRadares {
    @Suppress("UNCHECKED_CAST")
    val raiz = leerJson(texto) as? Map<String, Any?> ?: throw IllegalArgumentException("radares.json: no es un objeto")
    val lista = raiz["radares"] as? List<*> ?: throw IllegalArgumentException("radares.json: falta la lista de radares")
    val radares = ArrayList<Radar>(lista.size)
    for (elemento in lista) {
        @Suppress("UNCHECKED_CAST")
        val r = elemento as? Map<String, Any?> ?: continue
        val id = r.texto("id") ?: continue
        val lat = r.numero("lat") ?: continue
        val lon = r.numero("lon") ?: continue
        val linea = if (r.texto("aproximacion_fiab") == "alta") {
            (r["aproximacion"] as? List<*>)?.mapNotNull { p ->
                val par = p as? List<*>
                val la = par?.getOrNull(0) as? Double
                val lo = par?.getOrNull(1) as? Double
                if (la != null && lo != null) doubleArrayOf(la, lo) else null
            }?.takeIf { it.size >= 2 }
        } else {
            null
        }
        val limiteFiab = r.texto("limite_fiab")
        val limite = if (limiteFiab == "baja") null else r.entero("limite")
        radares += Radar(
            id = id,
            tipo = r.texto("tipo") ?: "fijo",
            nombre = r.texto("nombre"),
            carretera = r.texto("carretera"),
            pk = r.numero("pk"),
            lat = lat,
            lon = lon,
            sentido = r.texto("sentido"),
            limite = limite,
            limiteOrigen = r.texto("limite_origen"),
            linea = linea,
            fuente = r.texto("fuente"),
            rotatoria = r.logico("rotatoria") ?: false,
        )
    }
    if (radares.isEmpty()) throw IllegalArgumentException("radares.json: lista vacía")
    return ListaRadares(raiz.texto("version"), radares)
}

/** Lo que importa de `estado.json` para avisar al usuario. */
class EstadoDatos(val generado: String?, val ok: Boolean, val avisos: List<String>)

fun leerEstado(texto: String): EstadoDatos {
    @Suppress("UNCHECKED_CAST")
    val raiz = leerJson(texto) as? Map<String, Any?> ?: throw IllegalArgumentException("estado.json: no es un objeto")
    val avisos = (raiz["avisos"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    return EstadoDatos(raiz.texto("generado"), raiz.logico("ok") ?: false, avisos)
}

/** Fecha ISO ("2026-11-18T08:31:55Z") a milisegundos; nulo si no se entiende. */
fun isoAMs(iso: String?): Long? = try {
    if (iso == null) null else Instant.parse(iso).toEpochMilli()
} catch (e: Exception) {
    null
}

const val UN_DIA_MS = 24L * 3600 * 1000
const val LISTA_ANTICUADA_MS = 30L * UN_DIA_MS

/**
 * Avisos para el usuario sobre la lista de radares (fase 4.2): si el proceso de datos dice que no publicó
 * (`ok: false`), si la última ejecución del proceso de datos tiene más de un mes, o si la app lleva más de un
 * mes sin poder comprobar si hay datos nuevos. Lista vacía = todo bien. Código puro, con prueba.
 */
fun avisosDeLista(estado: EstadoDatos?, ultimaComprobacionOkMs: Long?, ahoraMs: Long): List<String> {
    val avisos = mutableListOf<String>()
    if (ultimaComprobacionOkMs == null) {
        avisos += "La app aún no ha podido comprobar si hay datos nuevos: usa la lista que trae instalada."
    } else if (ahoraMs - ultimaComprobacionOkMs > LISTA_ANTICUADA_MS) {
        avisos += "Hace más de un mes que la app no puede comprobar si hay datos nuevos."
    }
    if (estado != null) {
        if (!estado.ok) {
            val motivo = estado.avisos.firstOrNull { it.startsWith("NO PUBLICADO") } ?: "sin motivo"
            avisos += "La última ejecución del proceso de datos no publicó la lista ($motivo). Se usa la última buena."
        }
        val generado = isoAMs(estado.generado)
        if (generado != null && ahoraMs - generado > LISTA_ANTICUADA_MS) {
            avisos += "El proceso de datos no se ha ejecutado desde hace más de un mes: la lista puede estar anticuada."
        }
    }
    return avisos
}
