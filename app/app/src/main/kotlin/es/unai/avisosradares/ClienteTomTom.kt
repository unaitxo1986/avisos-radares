package es.unai.avisosradares

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Llamadas a TomTom (7.2) en un hilo aparte; las respuestas vuelven al hilo principal. La clave común va dentro
 * de la app (decisión de Unai, 27/11: "dejamos la clave de momento fija"); sale del secreto `TOMTOM_API_KEY` de
 * GitHub al compilar y nunca está en el repositorio. Sin clave (compilación local), no se pregunta nada.
 *
 * Topes por móvil y día, para no gastar la cuota gratuita común (Snap to Roads 2.500 al mes y Reverse
 * Geocoding 20.000, por clave): [MAX_SNAP_DIA] y [MAX_VIA_DIA]. Pasado el tope, la app avisa como antes.
 */
class ClienteTomTom(contexto: Context) {
    companion object {
        const val MAX_SNAP_DIA = 40
        const val MAX_VIA_DIA = 400
        val clave: String get() = BuildConfig.TOMTOM_CLAVE
        val hayClave: Boolean get() = clave.isNotBlank()
    }

    private val ctx = contexto.applicationContext
    private val ajustes = Ajustes(ctx)
    private val hilo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())

    fun puedeComprobar(): Boolean = hayClave && ajustes.comprobarTomTom && ajustes.consultasHoy("snap") < MAX_SNAP_DIA

    fun puedeLimiteVia(): Boolean = hayClave && ajustes.limiteViaTomTom && ajustes.consultasHoy("via") < MAX_VIA_DIA

    /** Pregunta por qué carretera va el coche; [alResponder] recibe nulo si no hay respuesta válida. */
    fun carretera(puntos: List<Posicion>, alResponder: (TomTom.Carretera?, String) -> Unit) {
        ajustes.sumaConsulta("snap")
        val url = TomTom.urlSnap(clave, puntos)
        hilo.execute {
            val (codigo, texto) = pedir(url)
            val c = texto?.let { TomTom.leerSnap(it) }
            val detalle = if (codigo == 200) TomTom.describe(c) else textoFallo(codigo)
            principal.post { alResponder(c, detalle) }
        }
    }

    /** Pregunta el límite de la vía; [alResponder] recibe ok = false si no hubo respuesta válida. */
    fun limiteVia(p: Posicion, rumbo: Double?, alResponder: (ok: Boolean, TomTom.Via?, String) -> Unit) {
        ajustes.sumaConsulta("via")
        val url = TomTom.urlReverse(clave, p, rumbo)
        hilo.execute {
            val (codigo, texto) = pedir(url)
            val v = if (codigo == 200) texto?.let { TomTom.leerReverse(it) } else null
            val detalle = if (codigo == 200) (v?.let { "límite ${it.limiteKmh ?: "desconocido"} en ${it.numeros.joinToString("/").ifEmpty { it.calle ?: "?" }}" } ?: "respuesta rara")
                else textoFallo(codigo)
            principal.post { alResponder(v != null, v, detalle) }
        }
    }

    private fun textoFallo(codigo: Int?) = when (codigo) {
        null -> "sin conexión o sin respuesta a tiempo"
        403 -> "HTTP 403 (clave no válida o cuota del mes gastada)"
        429 -> "HTTP 429 (demasiadas consultas)"
        else -> "HTTP $codigo"
    }

    fun cierra() {
        hilo.shutdownNow()
    }

    private fun pedir(url: String): Pair<Int?, String?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TomTom.TIEMPO_MAXIMO_MS.toInt()
        c.readTimeout = TomTom.TIEMPO_MAXIMO_MS.toInt()
        c.setRequestProperty("User-Agent", "avisos-radares-app")
        try {
            val codigo = c.responseCode
            Pair(codigo, if (codigo == 200) c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else null)
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Pair(null, null)
    }
}
