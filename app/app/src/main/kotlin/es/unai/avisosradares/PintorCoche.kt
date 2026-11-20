package es.unai.avisosradares

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja la pantalla de la app en Android Auto (fase 7): la misma información que la ventana flotante, en grande.
 * La velocidad en un aro (verde, naranja con un radar cerca, rojo con exceso) y, con un aviso, una tarjeta naranja
 * o roja (latiendo con exceso) con la señal, la distancia y qué es. El reparto del espacio lo decide
 * [disponerCoche] según el hueco que dé Android Auto (ancho, o la columna estrecha de la derecha); los textos se
 * encogen o pasan a dos líneas si no caben.
 */
object PintorCoche {
    private val COLOR_AVISO = Color.parseColor("#E65100")
    private val COLOR_EXCESO = Color.parseColor("#C62828")

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun dibujar(c: Canvas, ancho: Int, alto: Int, area: Rect?, enMarcha: Boolean, simulando: Boolean, velocidadKmh: Double?, aviso: AvisoActivo?) {
        c.drawColor(Estilo.FONDO)
        val visible = area?.let { Caja(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        val d = disponerCoche(ancho.toFloat(), alto.toFloat(), enMarcha && aviso != null, visible)
        marca(c, d.marca, d.letraMarca)
        val v = d.velocidad

        if (!enMarcha) {
            texto(c, "EN BOXES", v.cx, v.cy, v.alto * 0.18f, v.ancho * 0.9f, Estilo.GRIS, Estilo.RACING)
            texto(c, "PULSA ARRANCAR", v.cx, v.cy + v.alto * 0.13f, v.alto * 0.06f, v.ancho * 0.9f, Estilo.GRIS, Estilo.ESTRECHA, 0.2f)
            return
        }

        // Velocidad en su aro.
        val colorAro = when {
            aviso == null -> Estilo.VERDE
            aviso.exceso -> Estilo.ROJO
            else -> Estilo.NARANJA
        }
        val r = min(v.ancho, v.alto) * (if (aviso == null) 0.36f else 0.42f)
        val cy = if (aviso == null) v.cy - r * 0.12f else v.cy
        trazo.color = colorAro
        trazo.strokeWidth = r * 0.07f
        c.drawCircle(v.cx, cy, r, trazo)
        texto(c, velocidadKmh?.toInt()?.toString() ?: "--", v.cx, cy + r * 0.22f, r * 0.75f, r * 1.6f, Estilo.TEXTO, Estilo.RACING)
        texto(c, "KM/H", v.cx, cy + r * 0.55f, r * 0.17f, r * 1.2f, Estilo.GRIS, Estilo.ESTRECHA, 0.2f)
        if (simulando) texto(c, "SIMULACIÓN", v.cx, cy - r - r * 0.12f, r * 0.15f, v.ancho * 0.9f, Estilo.AMARILLO, Estilo.ESTRECHA, 0.2f)
        if (aviso == null) {
            texto(c, "SIN RADARES CERCA", v.cx, min(cy + r + r * 0.34f, v.aba - 4f), r * 0.15f, v.ancho * 0.95f, Estilo.GRIS, Estilo.ESTRECHA, 0.2f)
            return
        }
        tarjeta(c, d.tarjeta!!, d.tarjetaEnFila, aviso)
    }

    /** Tarjeta del aviso: fondo de color, señal (o semáforo), distancia y qué es. */
    private fun tarjeta(c: Canvas, t: Caja, enFila: Boolean, aviso: AvisoActivo) {
        val pulso = if (aviso.exceso) 0.75f + 0.25f * sin(SystemClock.uptimeMillis() / 160.0).toFloat() else 1f
        relleno.color = if (aviso.exceso) COLOR_EXCESO else COLOR_AVISO
        relleno.alpha = (255 * pulso).toInt()
        val radio = min(t.ancho, t.alto) * 0.08f
        c.drawRoundRect(RectF(t.izq, t.arr, t.der, t.aba), radio, radio, relleno)
        relleno.alpha = 255

        val (arriba, abajo) = textosAviso(aviso)
        val pad = min(t.ancho, t.alto) * 0.08f
        val textos: Caja
        if (enFila) {
            // Señal a la izquierda, textos a la derecha.
            val d = min(t.alto * 0.62f, t.ancho * 0.32f)
            dibujarSenal(c, t.izq + pad + d / 2, t.cy, d, aviso.radar.limite, aviso.radar.semaforo)
            textos = Caja(t.izq + pad * 2 + d, t.arr + pad, t.der - pad, t.aba - pad)
        } else {
            // Señal arriba, textos debajo.
            val d = min(t.alto * 0.40f, t.ancho * 0.55f)
            val cySenal = t.arr + pad + d / 2
            dibujarSenal(c, t.cx, cySenal, d, aviso.radar.limite, aviso.radar.semaforo)
            textos = Caja(t.izq + pad, cySenal + d / 2 + pad * 0.5f, t.der - pad, t.aba - pad)
        }
        bloque(c, textos, arriba.uppercase(), abajo.uppercase())
    }

    /** Distancia (grande) y qué es (pequeño, en una o dos líneas), centrados en [caja]. */
    private fun bloque(c: Canvas, caja: Caja, grande: String, pequeno: String) {
        val t1 = ajustar(grande, min(caja.alto * 0.42f, caja.ancho * 0.30f), caja.ancho, Estilo.RACING, 0f)
        val deseado = t1 * 0.40f
        var lineas = listOf(pequeno)
        var t2 = ajustar(pequeno, deseado, caja.ancho, Estilo.ESTRECHA, 0.1f)
        if (t2 < deseado * 0.8f) {
            val partes = partirEnDos(pequeno)
            if (partes.size == 2) {
                lineas = partes
                t2 = partes.minOf { ajustar(it, deseado, caja.ancho, Estilo.ESTRECHA, 0.1f) }
            }
        }
        val altoTotal = t1 + t2 * 0.6f + lineas.size * t2 * 1.2f
        var y = caja.cy - altoTotal / 2 + t1 * 0.85f
        texto(c, grande, caja.cx, y, t1, caja.ancho, Color.WHITE, Estilo.RACING)
        y += t1 * 0.15f + t2 * 0.6f
        for (l in lineas) {
            y += t2 * 1.05f
            texto(c, l, caja.cx, y, t2, caja.ancho, Color.WHITE, Estilo.ESTRECHA, 0.1f)
            y += t2 * 0.15f
        }
    }

    /** "RADAR AVISOS" y las franjas, en su caja de arriba a la izquierda. */
    private fun marca(c: Canvas, caja: Caja, t: Float) {
        letra.textAlign = Paint.Align.LEFT
        letra.typeface = Estilo.RACING
        letra.textSize = t
        letra.letterSpacing = 0.05f
        letra.color = Estilo.TEXTO
        val base = caja.arr + t
        c.drawText("RADAR", caja.izq, base, letra)
        val x = caja.izq + letra.measureText("RADAR ")
        letra.color = Estilo.ROJO
        c.drawText("AVISOS", x, base, letra)
        val x2 = x + letra.measureText("AVISOS ") + t * 0.2f
        val colores = intArrayOf(Estilo.ROJO, Estilo.NARANJA, Estilo.AMARILLO)
        for (i in colores.indices) {
            val bx = x2 + i * t * 0.5f
            relleno.color = colores[i]
            c.drawPath(Path().apply {
                moveTo(bx + t * 0.3f, caja.arr + t * 0.1f); lineTo(bx + t * 0.6f, caja.arr + t * 0.1f)
                lineTo(bx + t * 0.3f, base); lineTo(bx, base); close()
            }, relleno)
        }
        letra.textAlign = Paint.Align.CENTER
        letra.letterSpacing = 0f
    }

    /** Tamaño de letra para que [t] quepa en [maxAncho], sin pasar de [tam]. */
    private fun ajustar(t: String, tam: Float, maxAncho: Float, tipo: Typeface, espacio: Float): Float {
        letra.typeface = tipo
        letra.letterSpacing = espacio
        letra.textSize = tam
        val w = letra.measureText(t)
        letra.letterSpacing = 0f
        return if (w <= maxAncho || w <= 0f) tam else tam * maxAncho / w
    }

    /** Texto centrado en ([x], [y]) de tamaño [tam], encogido si no cabe en [maxAncho]. */
    private fun texto(c: Canvas, t: String, x: Float, y: Float, tam: Float, maxAncho: Float, color: Int, tipo: Typeface, espacio: Float = 0f) {
        letra.textAlign = Paint.Align.CENTER
        letra.textSize = ajustar(t, tam, maxAncho, tipo, espacio)
        letra.color = color
        letra.typeface = tipo
        letra.letterSpacing = espacio
        c.drawText(t, x, y, letra)
        letra.letterSpacing = 0f
    }
}
