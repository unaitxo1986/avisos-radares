package es.unai.avisosradares

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lista de radares en el móvil (fase 4.2).
 *
 * - El APK lleva dentro una copia de `radares.json` y `estado.json` (la que había al compilar), así que la app
 *   funciona desde el primer momento y sin cobertura.
 * - Cada vez que se comprueba, baja `estado.json` y, si `radares.json` ha cambiado (etiqueta HTTP `ETag`), la
 *   lista nueva. Solo sustituye la guardada si la nueva se lee bien.
 * - Se comprueba al abrir la app y al arrancar el servicio, como mucho cada [CADA_MS]; también con un botón.
 */
object GestorLista {
    private const val BASE = "https://raw.githubusercontent.com/unaitxo1986/avisos-radares/main/datos/salida/"
    private const val CADA_MS = 6L * 3600 * 1000
    private const val RADARES = "radares.json"
    private const val ESTADO = "estado.json"

    @Volatile
    private var cache: ListaRadares? = null
    @Volatile
    var comprobando = false
        private set

    /** Se avisa (en el hilo principal) cuando cambia la lista o termina una comprobación. */
    var alCambiar: (() -> Unit)? = null

    private fun fichero(ctx: Context, nombre: String) = File(ctx.applicationContext.filesDir, nombre)

    /** La lista guardada en el móvil o, si no la hay o está dañada, la que trae el APK. Tarda unas décimas. */
    fun lista(ctx: Context): ListaRadares {
        cache?.let { return it }
        val f = fichero(ctx, RADARES)
        val leida = try {
            if (f.exists()) leerRadares(f.readText()) else null
        } catch (e: Exception) {
            Registro.anota(ctx, "LISTA", "la lista guardada no se puede leer (${e.message}); se usa la del APK")
            null
        } ?: leerRadares(ctx.assets.open(RADARES).bufferedReader().use { it.readText() })
        cache = leida
        return leida
    }

    fun estado(ctx: Context): EstadoDatos? = try {
        val f = fichero(ctx, ESTADO)
        val texto = if (f.exists()) f.readText() else ctx.assets.open(ESTADO).bufferedReader().use { it.readText() }
        leerEstado(texto)
    } catch (e: Exception) {
        null
    }

    fun avisos(ctx: Context): List<String> {
        val a = Ajustes(ctx)
        return avisosDeLista(estado(ctx), a.ultimaComprobacionOkMs.takeIf { it > 0 }, System.currentTimeMillis())
    }

    /** Comprueba si hay datos nuevos si hace más de 6 horas de la última vez (o siempre, con [forzar]). */
    fun comprobar(ctx: Context, forzar: Boolean) {
        val app = ctx.applicationContext
        val ajustes = Ajustes(app)
        if (comprobando) return
        if (!forzar && System.currentTimeMillis() - ajustes.ultimoIntentoMs < CADA_MS) return
        comprobando = true
        ajustes.ultimoIntentoMs = System.currentTimeMillis()
        Thread {
            val resultado = try {
                descargar(app, ajustes)
            } catch (e: Exception) {
                "sin conexión o error (${e.javaClass.simpleName}: ${e.message}); se sigue con la lista guardada"
            }
            ajustes.ultimoResultado = resultado
            Registro.anota(app, "LISTA", resultado)
            comprobando = false
            Handler(Looper.getMainLooper()).post { alCambiar?.invoke() }
        }.start()
    }

    private fun descargar(ctx: Context, ajustes: Ajustes): String {
        // 1. Estado del proceso de datos (pequeño).
        val (codigoEstado, textoEstado, _) = pedir(BASE + ESTADO, null)
        if (codigoEstado != 200 || textoEstado == null) return "no se pudo leer estado.json (HTTP $codigoEstado)"
        leerEstado(textoEstado)
        escribirSeguro(fichero(ctx, ESTADO), textoEstado)

        // 2. Lista de radares, solo si ha cambiado.
        val (codigo, texto, etiqueta) = pedir(BASE + RADARES, ajustes.etiquetaLista.ifEmpty { null })
        val resultado = when {
            codigo == 304 -> "lista sin cambios (${lista(ctx).radares.size} radares, versión ${lista(ctx).version})"
            codigo == 200 && texto != null -> {
                val nueva = leerRadares(texto) // si no se lee bien, lanza y no se toca la guardada
                escribirSeguro(fichero(ctx, RADARES), texto)
                ajustes.etiquetaLista = etiqueta ?: ""
                val antes = cache?.version
                cache = nueva
                if (antes == nueva.version) "lista sin cambios (${nueva.radares.size} radares, versión ${nueva.version})"
                else "LISTA NUEVA: ${nueva.radares.size} radares, versión ${nueva.version} (antes $antes)"
            }
            else -> return "no se pudo bajar radares.json (HTTP $codigo)"
        }
        ajustes.ultimaComprobacionOkMs = System.currentTimeMillis()
        return resultado
    }

    private fun pedir(url: String, etiqueta: String?): Triple<Int, String?, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "avisos-radares-app")
        if (etiqueta != null) c.setRequestProperty("If-None-Match", etiqueta)
        try {
            val codigo = c.responseCode
            if (codigo != 200) return Triple(codigo, null, null)
            val texto = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Triple(codigo, texto, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    /** Escribe en un fichero temporal y lo renombra, para no dejar nunca un fichero a medias. */
    private fun escribirSeguro(f: File, texto: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
