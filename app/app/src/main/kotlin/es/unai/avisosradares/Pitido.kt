package es.unai.avisosradares

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** Un tono senoidal con subida y bajada de 10 ms para que no suene un chasquido. Código puro, con prueba. */
fun generarPitido(frecuenciaHz: Int, duracionMs: Int, frecuenciaMuestreo: Int): ShortArray {
    val n = frecuenciaMuestreo * duracionMs / 1000
    val rampa = minOf(frecuenciaMuestreo / 100, n / 2)
    val salida = ShortArray(n)
    for (i in 0 until n) {
        val fase = 2.0 * Math.PI * frecuenciaHz * i / frecuenciaMuestreo
        var amplitud = 0.6 * Math.sin(fase)
        if (rampa > 0) {
            if (i < rampa) {
                amplitud *= i.toDouble() / rampa
            } else if (i >= n - rampa) {
                amplitud *= (n - 1 - i).toDouble() / rampa
            }
        }
        salida[i] = (amplitud * Short.MAX_VALUE).toInt().toShort()
    }
    return salida
}

/** Aviso de prueba: dos pitidos (880 Hz y 1320 Hz) separados por 120 ms de silencio. */
fun generarAviso(frecuenciaMuestreo: Int): ShortArray {
    val primero = generarPitido(880, 200, frecuenciaMuestreo)
    val silencio = ShortArray(frecuenciaMuestreo * 120 / 1000)
    val segundo = generarPitido(1320, 200, frecuenciaMuestreo)
    return primero + silencio + segundo
}

/** Reproduce el aviso como lo haría un aviso de navegación y deja en el registro todo lo que pasa. */
object Sonido {
    private const val MUESTREO = 22050

    fun avisoDePrueba(contexto: Context, origen: String) {
        val ctx = contexto.applicationContext
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val peticion = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .build()
        val foco = audio.requestAudioFocus(peticion)
        val textoFoco = if (foco == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "concedido" else "denegado($foco)"
        val volumen = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumenMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Registro.anota(
            ctx, origen,
            "sonido: foco=$textoFoco volumen música=$volumen/$volumenMax salidas disponibles=" + salidas(audio)
        )
        val muestras = generarAviso(MUESTREO)
        try {
            val formato = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MUESTREO)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val pista = AudioTrack.Builder()
                .setAudioAttributes(atributos)
                .setAudioFormat(formato)
                .setBufferSizeInBytes(muestras.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            pista.write(muestras, 0, muestras.size)
            pista.play()
            val duracionMs = muestras.size * 1000L / MUESTREO
            Handler(Looper.getMainLooper()).postDelayed({
                val posicion = pista.playbackHeadPosition
                val aviso = if (posicion == 0) " (¿silenciado por el sistema?)" else ""
                val salida = pista.routedDevice
                val destino = if (salida == null) "desconocida" else nombreSalidaAudio(salida.type) + " (" + salida.productName + ")"
                Registro.anota(ctx, origen, "sonido: reproducidas $posicion de ${muestras.size} muestras, SALIDA USADA=$destino$aviso")
                pista.release()
                audio.abandonAudioFocusRequest(peticion)
            }, duracionMs + 300)
        } catch (e: Throwable) {
            Registro.anota(ctx, origen, "sonido: ERROR ${e.javaClass.name}: ${e.message}")
            audio.abandonAudioFocusRequest(peticion)
        }
    }

    private fun salidas(audio: AudioManager): String {
        val nombres = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { nombreSalidaAudio(it.type) }
            .distinct()
        return nombres.joinToString(", ")
    }
}
