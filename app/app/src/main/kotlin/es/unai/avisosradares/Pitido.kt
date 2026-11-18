package es.unai.avisosradares

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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

/** [cuantos] pitidos iguales de [duracionMs] separados por [silencioMs]. Código puro, con prueba. */
fun generarSecuencia(cuantos: Int, frecuenciaHz: Int, duracionMs: Int, silencioMs: Int, frecuenciaMuestreo: Int): ShortArray {
    val pitido = generarPitido(frecuenciaHz, duracionMs, frecuenciaMuestreo)
    val silencio = ShortArray(frecuenciaMuestreo * silencioMs / 1000)
    var salida = ShortArray(0)
    for (i in 0 until cuantos) {
        if (i > 0) salida += silencio
        salida += pitido
    }
    return salida
}

/**
 * Sonidos de la app: arranque (1 pitido suave), parada (2), radar (5 agudos, el mismo que se probó en la
 * fase 2) y exceso de velocidad (3 muy agudos y rápidos, distinto del de radar para reconocerlo sin mirar).
 */
fun sonidoConexion(m: Int): ShortArray = generarSecuencia(1, 660, 150, 0, m)
fun sonidoDesconexion(m: Int): ShortArray = generarSecuencia(2, 660, 150, 120, m)
fun sonidoRadar(m: Int): ShortArray = generarSecuencia(5, 1320, 150, 100, m)
fun sonidoExceso(m: Int): ShortArray = generarSecuencia(3, 1760, 90, 60, m)

/**
 * Reproduce los sonidos como un aviso de navegación, **siempre por el altavoz del móvil** (decisión de Unai,
 * 15/11), y deja en el registro por dónde ha salido.
 */
object Sonido {
    const val MUESTREO = 22050

    fun avisoDePrueba(contexto: Context, origen: String) =
        reproducir(contexto, origen, "aviso de prueba", generarAviso(MUESTREO))

    fun conexion(contexto: Context, origen: String) =
        reproducir(contexto, origen, "conexión (1 pitido)", sonidoConexion(MUESTREO))

    fun desconexion(contexto: Context, origen: String) =
        reproducir(contexto, origen, "desconexión (2 pitidos)", sonidoDesconexion(MUESTREO))

    fun radar(contexto: Context, origen: String) =
        reproducir(contexto, origen, "radar (5 pitidos)", sonidoRadar(MUESTREO))

    fun exceso(contexto: Context, origen: String) =
        reproducir(contexto, origen, "exceso (3 pitidos)", sonidoExceso(MUESTREO))

    private fun reproducir(contexto: Context, origen: String, nombre: String, muestras: ShortArray) {
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
            "sonido [$nombre]: foco=$textoFoco volumen música=$volumen/$volumenMax salidas disponibles=" + salidas(audio)
        )
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
            // Decisión de Unai (15/11): el aviso suena siempre en el altavoz del móvil, nunca por Bluetooth.
            val altavoz = altavozDelMovil(audio)
            if (altavoz == null) {
                Registro.anota(ctx, origen, "sonido: este móvil no declara altavoz propio; sale por la salida que elija Android")
            } else {
                val aceptado = pista.setPreferredDevice(altavoz)
                Registro.anota(ctx, origen, "sonido: salida forzada al altavoz del móvil, aceptado por Android=$aceptado")
            }
            pista.write(muestras, 0, muestras.size)
            pista.play()
            val duracionMs = muestras.size * 1000L / MUESTREO
            Handler(Looper.getMainLooper()).postDelayed({
                val posicion = pista.playbackHeadPosition
                val aviso = if (posicion == 0) " (¿silenciado por el sistema?)" else ""
                val salida = pista.routedDevice
                val destino = if (salida == null) "desconocida" else nombreSalidaAudio(salida.type) + " (" + salida.productName + ")"
                Registro.anota(ctx, origen, "sonido [$nombre]: reproducidas $posicion de ${muestras.size} muestras, SALIDA USADA=$destino$aviso")
                pista.release()
                audio.abandonAudioFocusRequest(peticion)
            }, duracionMs + 300)
        } catch (e: Throwable) {
            Registro.anota(ctx, origen, "sonido: ERROR ${e.javaClass.name}: ${e.message}")
            audio.abandonAudioFocusRequest(peticion)
        }
    }

    private fun altavozDelMovil(audio: AudioManager): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun salidas(audio: AudioManager): String {
        val nombres = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { nombreSalidaAudio(it.type) }
            .distinct()
        return nombres.joinToString(", ")
    }
}
