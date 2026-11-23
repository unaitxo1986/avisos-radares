package es.unai.avisosradares

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Un tono senoidal con subida y bajada de 10 ms para que no suene un chasquido. Código puro, con prueba.
 * [nivel]: fracción del máximo; los avisos de radar y de exceso van a 0,9 desde el 23/11 (antes 0,6, unos 3,5 dB
 * menos; Iker: "el sonido se pueda poner más alto").
 */
fun generarPitido(frecuenciaHz: Int, duracionMs: Int, frecuenciaMuestreo: Int, nivel: Double = 0.6): ShortArray {
    val n = frecuenciaMuestreo * duracionMs / 1000
    val rampa = minOf(frecuenciaMuestreo / 100, n / 2)
    val salida = ShortArray(n)
    for (i in 0 until n) {
        val fase = 2.0 * Math.PI * frecuenciaHz * i / frecuenciaMuestreo
        var amplitud = nivel * Math.sin(fase)
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
fun generarSecuencia(cuantos: Int, frecuenciaHz: Int, duracionMs: Int, silencioMs: Int, frecuenciaMuestreo: Int,
                     nivel: Double = 0.6): ShortArray {
    val pitido = generarPitido(frecuenciaHz, duracionMs, frecuenciaMuestreo, nivel)
    val silencio = ShortArray(frecuenciaMuestreo * silencioMs / 1000)
    var salida = ShortArray(0)
    for (i in 0 until cuantos) {
        if (i > 0) salida += silencio
        salida += pitido
    }
    return salida
}

/**
 * Volumen de los avisos, el selector de la pantalla principal (23/11, a petición de Iker), en %: de 10 a 200, de 10
 * en 10. Hasta 100 % es el volumen multimedia del móvil mientras suena el aviso (70 % es casi lo de antes, 10 de
 * 15 en el S23); por encima de 100 %, el volumen al máximo y además un refuerzo propio del sonido de la app (hasta
 * +15 dB a 200 %), que también vale cuando el volumen del móvil no se puede tocar (Bluetooth del coche conectado).
 */
const val VOLUMEN_AVISOS_MIN = 10
const val VOLUMEN_AVISOS_MAX = 200
const val VOLUMEN_AVISOS_DEFECTO = 70

fun volumenAvisosValido(porcentaje: Int): Int =
    (Math.round(porcentaje / 10.0).toInt() * 10).coerceIn(VOLUMEN_AVISOS_MIN, VOLUMEN_AVISOS_MAX)

/** Volumen multimedia del sistema para un aviso con el selector en [porcentaje] (mínimo 1). */
fun volumenParaAviso(porcentaje: Int, maximo: Int): Int =
    Math.round(maximo * minOf(volumenAvisosValido(porcentaje), 100) / 100.0).toInt().coerceIn(1, maximo)

/** Refuerzo del sonido de la app por encima del 100 %, en milibelios (0 hasta 100 %; 1500 = +15 dB a 200 %). */
fun refuerzoAviso(porcentaje: Int): Int = maxOf(0, volumenAvisosValido(porcentaje) - 100) * 15

fun textoVolumenAvisos(porcentaje: Int): String {
    val v = volumenAvisosValido(porcentaje)
    return if (v > 100) "$v % · más alto que el máximo del móvil" else "$v %"
}

/**
 * Sonidos de la app: arranque (1 pitido suave), parada (2), radar (5 agudos, el mismo que se probó en la
 * fase 2) y exceso de velocidad (3 muy agudos y rápidos, distinto del de radar para reconocerlo sin mirar).
 */
fun sonidoConexion(m: Int): ShortArray = generarSecuencia(1, 660, 150, 0, m)
fun sonidoDesconexion(m: Int): ShortArray = generarSecuencia(2, 660, 150, 120, m)
fun sonidoRadar(m: Int): ShortArray = generarSecuencia(5, 1320, 150, 100, m, NIVEL_AVISO)
fun sonidoExceso(m: Int): ShortArray = generarSecuencia(3, 1760, 90, 60, m, NIVEL_AVISO)
const val NIVEL_AVISO = 0.9

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

    /**
     * Avisos: siempre con el refuerzo del selector de volumen; con [subirVolumen] (viajes reales y el botón PROBAR,
     * no las simulaciones) además pone el volumen multimedia del selector mientras suena.
     */
    fun radar(contexto: Context, origen: String, subirVolumen: Boolean = false) =
        reproducir(contexto, origen, "radar (5 pitidos)", sonidoRadar(MUESTREO), subirVolumen, aviso = true)

    fun exceso(contexto: Context, origen: String, subirVolumen: Boolean = false) =
        reproducir(contexto, origen, "exceso (3 pitidos)", sonidoExceso(MUESTREO), subirVolumen, aviso = true)

    // Volumen subido para los avisos: el original se guarda al subir el primero y se devuelve al terminar el
    // último (los avisos pueden solaparse).
    private var avisosSonando = 0
    private var volumenOriginal = -1

    /**
     * Decisión de Unai (18/11): para los avisos, subir el volumen y luego bajarlo, pero no en las simulaciones.
     * Solo se toca si el sonido de navegación va al altavoz del móvil: si Android lo enviaría a un Bluetooth (el
     * coche), el volumen multimedia que se cambia es el del coche y no el del altavoz, así que no se toca.
     */
    private fun subir(ctx: Context, origen: String, audio: AudioManager, atributos: AudioAttributes, porcentaje: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Desde el 23/11 manda el selector (antes: al menos 2/3 del máximo). Si sonaban dos avisos a la vez y el
        // primero ya lo puso, no se vuelve a tocar.
        val objetivo = volumenParaAviso(porcentaje, max)
        if (objetivo == actual && avisosSonando == 0) return
        if (Build.VERSION.SDK_INT >= 33) {
            val destinos = audio.getAudioDevicesForAttributes(atributos)
            if (destinos.any { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
                Registro.anota(ctx, origen, "volumen: no se sube (el volumen multimedia es ahora el de " +
                    destinos.joinToString { nombreSalidaAudio(it.type) } + ", no el del altavoz)")
                return
            }
        }
        try {
            if (avisosSonando == 0) volumenOriginal = actual
            avisosSonando++
            if (objetivo != actual) audio.setStreamVolume(AudioManager.STREAM_MUSIC, objetivo, 0)
            Registro.anota(ctx, origen, "volumen: de $actual a $objetivo de $max para el aviso (selector $porcentaje %)")
        } catch (e: Throwable) {
            avisosSonando = maxOf(0, avisosSonando - 1)
            Registro.anota(ctx, origen, "volumen: no se pudo subir (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun bajar(ctx: Context, origen: String, audio: AudioManager) {
        if (avisosSonando == 0) return
        avisosSonando--
        if (avisosSonando > 0 || volumenOriginal < 0) return
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumenOriginal, 0)
            Registro.anota(ctx, origen, "volumen: devuelto a $volumenOriginal")
        } catch (e: Throwable) {
            Registro.anota(ctx, origen, "volumen: no se pudo devolver (${e.javaClass.simpleName}: ${e.message})")
        }
        volumenOriginal = -1
    }

    private fun reproducir(contexto: Context, origen: String, nombre: String, muestras: ShortArray, subirVolumen: Boolean = false,
                           aviso: Boolean = false) {
        val ctx = contexto.applicationContext
        val porcentaje = if (aviso) Ajustes(ctx).volumenAvisos else 100
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val peticion = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .build()
        val foco = audio.requestAudioFocus(peticion)
        var subido = false
        if (subirVolumen) {
            val antes = avisosSonando
            subir(ctx, origen, audio, atributos, porcentaje)
            subido = avisosSonando > antes
        }
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
            // Refuerzo por encima del 100 % del selector (23/11): solo afecta al sonido de la app.
            var realce: LoudnessEnhancer? = null
            val refuerzo = refuerzoAviso(porcentaje)
            if (aviso && refuerzo > 0) {
                try {
                    realce = LoudnessEnhancer(pista.audioSessionId).apply {
                        setTargetGain(refuerzo)
                        enabled = true
                    }
                    Registro.anota(ctx, origen, "sonido: refuerzo de ${refuerzo / 100.0} dB (selector $porcentaje %)")
                } catch (e: Throwable) {
                    Registro.anota(ctx, origen, "sonido: refuerzo no disponible (${e.javaClass.simpleName}: ${e.message})")
                }
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
                try { realce?.release() } catch (_: Throwable) {}
                pista.release()
                if (subido) bajar(ctx, origen, audio)
                audio.abandonAudioFocusRequest(peticion)
            }, duracionMs + 300)
        } catch (e: Throwable) {
            Registro.anota(ctx, origen, "sonido: ERROR ${e.javaClass.name}: ${e.message}")
            if (subido) bajar(ctx, origen, audio)
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
