package es.unai.avisosradares

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja la pantalla de la app en Android Auto (fase 7): la misma información que la ventana flotante, en grande.
 * A la izquierda, la velocidad; con un aviso, a la derecha la señal con el límite, la distancia y qué es. Fondo de
 * la zona del aviso naranja (radar cerca) o rojo (exceso, latiendo). Se dibuja solo dentro de [area], la parte de
 * la pantalla que Android Auto deja libre.
 */
object PintorCoche {
    private val COLOR_AVISO = Color.parseColor("#E65100")
    private val COLOR_EXCESO = Color.parseColor("#C62828")

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun dibujar(c: Canvas, ancho: Int, alto: Int, area: Rect?, enMarcha: Boolean, simulando: Boolean, velocidadKmh: Double?, aviso: AvisoActivo?) {
        c.drawColor(Estilo.FONDO)
        val a = if (area != null && area.width() > 0 && area.height() > 0) RectF(area) else RectF(0f, 0f, ancho.toFloat(), alto.toFloat())
        val m = min(a.width(), a.height()) * 0.05f
        a.inset(m, m)
        marca(c, a)

        if (!enMarcha) {
            texto(c, "EN BOXES", a.centerX(), a.centerY(), a.height() * 0.18f, Estilo.GRIS, Estilo.RACING)
            texto(c, "PULSA ARRANCAR", a.centerX(), a.centerY() + a.height() * 0.13f, a.height() * 0.06f, Estilo.GRIS, Estilo.ESTRECHA, 0.2f)
            return
        }

        // Zona de la velocidad (izquierda) y del aviso (derecha).
        val corte = a.left + a.width() * (if (aviso != null) 0.42f else 1f)
        val zonaVel = RectF(a.left, a.top, corte, a.bottom)
        val colorAro = when {
            aviso == null -> Estilo.VERDE
            aviso.exceso -> Estilo.ROJO
            else -> Estilo.NARANJA
        }
        val r = min(zonaVel.width(), zonaVel.height()) * 0.40f
        trazo.color = colorAro
        trazo.strokeWidth = r * 0.07f
        c.drawCircle(zonaVel.centerX(), zonaVel.centerY(), r, trazo)
        texto(c, velocidadKmh?.toInt()?.toString() ?: "--", zonaVel.centerX(), zonaVel.centerY() + r * 0.22f, r * 0.75f, Estilo.TEXTO, Estilo.RACING)
        texto(c, "KM/H", zonaVel.centerX(), zonaVel.centerY() + r * 0.55f, r * 0.17f, Estilo.GRIS, Estilo.ESTRECHA, 0.2f)
        if (simulando) texto(c, "SIMULACIÓN", zonaVel.centerX(), zonaVel.bottom - m * 0.2f, r * 0.15f, Estilo.AMARILLO, Estilo.ESTRECHA, 0.2f)

        if (aviso == null) {
            texto(c, "SIN RADARES CERCA", zonaVel.centerX(), zonaVel.centerY() + r + r * 0.32f, r * 0.14f, Estilo.GRIS, Estilo.ESTRECHA, 0.2f)
            return
        }

        // Zona del aviso: tarjeta de color con la señal, la distancia y qué es.
        val zona = RectF(corte + m * 0.5f, a.top, a.right, a.bottom)
        val pulso = if (aviso.exceso) 0.75f + 0.25f * sin(SystemClock.uptimeMillis() / 160.0).toFloat() else 1f
        relleno.color = if (aviso.exceso) COLOR_EXCESO else COLOR_AVISO
        relleno.alpha = (255 * pulso).toInt()
        val radio = zona.height() * 0.08f
        c.drawRoundRect(zona, radio, radio, relleno)
        relleno.alpha = 255

        val (arriba, abajo) = textosAviso(aviso)
        val d = min(zona.height() * 0.42f, zona.width() * 0.42f)
        val cxSenal = zona.centerX()
        val cySenal = zona.top + zona.height() * 0.08f + d / 2
        dibujarSenal(c, cxSenal, cySenal, d, aviso.radar.limite, aviso.radar.semaforo)
        val yDist = cySenal + d / 2 + zona.height() * 0.22f
        texto(c, arriba.uppercase(), zona.centerX(), yDist, min(zona.height() * 0.19f, zona.width() * 0.16f), Color.WHITE, Estilo.RACING)
        texto(c, abajo.uppercase(), zona.centerX(), yDist + zona.height() * 0.14f, min(zona.height() * 0.08f, zona.width() * 0.07f), Color.WHITE, Estilo.ESTRECHA, 0.1f)
    }

    /** "RADAR AVISOS" y las franjas, pequeño, arriba a la izquierda. */
    private fun marca(c: Canvas, a: RectF) {
        val t = min(a.height() * 0.05f, 28f)
        letra.textAlign = Paint.Align.LEFT
        letra.typeface = Estilo.RACING
        letra.textSize = t
        letra.letterSpacing = 0.05f
        letra.color = Estilo.TEXTO
        c.drawText("RADAR", a.left, a.top + t, letra)
        val x = a.left + letra.measureText("RADAR ")
        letra.color = Estilo.ROJO
        c.drawText("AVISOS", x, a.top + t, letra)
        val x2 = x + letra.measureText("AVISOS ") + t * 0.2f
        val colores = intArrayOf(Estilo.ROJO, Estilo.NARANJA, Estilo.AMARILLO)
        for (i in colores.indices) {
            val bx = x2 + i * t * 0.5f
            relleno.color = colores[i]
            c.drawPath(Path().apply {
                moveTo(bx + t * 0.3f, a.top + t * 0.1f); lineTo(bx + t * 0.6f, a.top + t * 0.1f)
                lineTo(bx + t * 0.3f, a.top + t); lineTo(bx, a.top + t); close()
            }, relleno)
        }
        letra.textAlign = Paint.Align.CENTER
        letra.letterSpacing = 0f
    }

    private fun texto(c: Canvas, t: String, x: Float, y: Float, tam: Float, color: Int, letraTipo: android.graphics.Typeface, espacio: Float = 0f) {
        letra.textAlign = Paint.Align.CENTER
        letra.textSize = tam
        letra.color = color
        letra.typeface = letraTipo
        letra.letterSpacing = espacio
        c.drawText(t, x, y, letra)
        letra.letterSpacing = 0f
    }
}
