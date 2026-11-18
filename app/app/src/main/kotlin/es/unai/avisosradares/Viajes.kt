package es.unai.avisosradares

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de viajes (fase 4.4): un CSV por cada vez que arranca el servicio, con todas las posiciones del
 * GPS y todos los avisos (formato en [FormatoViaje]). Se guardan los últimos [MAXIMO] en el almacenamiento
 * privado de la app; se pueden exportar a la carpeta Descargas para enviarlos y para reproducirlos.
 */
object Viajes {
    private const val MAXIMO = 40

    fun carpeta(ctx: Context) = File(ctx.applicationContext.filesDir, "viajes").also { it.mkdirs() }

    /** Viajes guardados, del más reciente al más antiguo. */
    fun lista(ctx: Context): List<File> =
        (carpeta(ctx).listFiles { f -> f.name.endsWith(".csv") } ?: emptyArray()).sortedByDescending { it.name }

    fun nuevo(ctx: Context, cabecera: String): Grabador {
        val viejos = lista(ctx).drop(MAXIMO - 1)
        for (f in viejos) f.delete()
        val nombre = "viaje-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".csv"
        return Grabador(File(carpeta(ctx), nombre), cabecera)
    }

    /**
     * Copia un viaje a Descargas/AvisosRadares (visible con la app Mis archivos y desde el ordenador).
     * Devuelve la ruta donde ha quedado, para enseñarla.
     */
    fun exportar(ctx: Context, viaje: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val valores = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, viaje.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AvisosRadares")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw IllegalStateException("Android no deja crear el fichero en Descargas")
            resolver.openOutputStream(uri)?.use { salida -> viaje.inputStream().use { it.copyTo(salida) } }
            return "Descargas/AvisosRadares/" + viaje.name
        }
        // Android 8 y 9: carpeta de la app en el almacenamiento externo (no necesita permisos).
        val destino = File(ctx.getExternalFilesDir(null), viaje.name)
        viaje.copyTo(destino, overwrite = true)
        return destino.absolutePath
    }

    class Grabador(val fichero: File, cabecera: String) {
        private var salida: BufferedWriter? = try {
            fichero.bufferedWriter().also { it.write(cabecera + "\n") }
        } catch (e: Exception) {
            null
        }
        private var pendientes = 0

        fun linea(texto: String) {
            val s = salida ?: return
            try {
                s.write(texto)
                s.write("\n")
                // Se vuelca a disco a menudo: si Android mata el proceso, se pierde como mucho medio minuto.
                if (++pendientes >= 30) {
                    s.flush()
                    pendientes = 0
                }
            } catch (e: Exception) {
                salida = null
            }
        }

        fun cierra() {
            try {
                salida?.close()
            } catch (e: Exception) {
                // nada que hacer
            }
            salida = null
        }
    }
}
