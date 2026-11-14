package es.unai.avisosradares

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Línea de registro: "14 19:41:03.123 [origen] texto". Código puro, con prueba automática. */
fun formatoLinea(ahoraMs: Long, zona: TimeZone, origen: String, texto: String): String {
    val formato = SimpleDateFormat("dd HH:mm:ss.SSS", Locale.ROOT)
    formato.timeZone = zona
    return formato.format(Date(ahoraMs)) + " [" + origen + "] " + texto
}

/** Las últimas [cuantas] líneas no vacías de un texto. */
fun ultimasLineas(texto: String, cuantas: Int): String {
    if (cuantas <= 0) return ""
    return texto.lines().filter { it.isNotEmpty() }.takeLast(cuantas).joinToString("\n")
}

/** Conserva solo las últimas [maximo] líneas. */
fun recortarLineas(lineas: List<String>, maximo: Int): List<String> =
    if (lineas.size <= maximo) lineas else lineas.takeLast(maximo)

/**
 * Registro de diagnóstico de la fase 2: un fichero de texto en el almacenamiento privado de la app.
 * Lo escriben a la vez el receptor Bluetooth, el servicio y la pantalla (mismo proceso).
 */
object Registro {
    private const val FICHERO = "registro.txt"
    private const val MAX_LINEAS = 3000
    private const val MAX_BYTES = 400_000L
    private val candado = Any()

    private fun fichero(contexto: Context) = File(contexto.applicationContext.filesDir, FICHERO)

    fun anota(contexto: Context, origen: String, texto: String) {
        val linea = formatoLinea(System.currentTimeMillis(), TimeZone.getDefault(), origen, texto)
        synchronized(candado) {
            try {
                val f = fichero(contexto)
                f.appendText(linea + "\n")
                if (f.length() > MAX_BYTES) {
                    val recortadas = recortarLineas(f.readLines(), MAX_LINEAS / 2)
                    f.writeText(recortadas.joinToString("\n") + "\n")
                }
            } catch (e: Throwable) {
                // Si no se puede escribir el registro no hay nada más que hacer.
            }
        }
    }

    fun lee(contexto: Context, cuantas: Int): String = synchronized(candado) {
        val f = fichero(contexto)
        if (f.exists()) ultimasLineas(f.readText(), cuantas) else ""
    }

    fun borra(contexto: Context) {
        synchronized(candado) { fichero(contexto).delete() }
    }
}
