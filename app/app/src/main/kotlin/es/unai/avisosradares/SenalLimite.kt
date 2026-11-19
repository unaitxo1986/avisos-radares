package es.unai.avisosradares

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Señal de velocidad máxima dibujada como la de verdad (R-301): círculo blanco con borde rojo y el número en
 * negro. Sin límite conocido enseña la señal sin número, con un "?" gris.
 */
class SenalLimite(contexto: Context, private val diametroPx: Int) : View(contexto) {

    var limite: Int? = null
        set(valor) {
            if (field != valor) {
                field = valor
                invalidate()
            }
        }

    /** Cámara de semáforo en rojo: en lugar de la señal de límite se dibuja un semáforo. */
    var semaforo = false
        set(valor) {
            if (field != valor) {
                field = valor
                invalidate()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(diametroPx, diametroPx)
    }

    override fun onDraw(canvas: Canvas) {
        dibujarSenal(canvas, diametroPx / 2f, diametroPx / 2f, diametroPx.toFloat(), limite, semaforo)
    }
}

private val rellenoSenal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
private val bordeSenal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0021B"); style = Paint.Style.STROKE }
private val numeroSenal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
}

private val cajaSemaforo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C22") }
private val luzSemaforo = Paint(Paint.ANTI_ALIAS_FLAG)

/**
 * Dibuja la señal de velocidad máxima de diámetro [d] centrada en ([cx], [cy]); la usan la ventana y Android Auto.
 * Con [semaforo], un semáforo con la luz roja encendida dentro del mismo círculo blanco con borde rojo.
 */
fun dibujarSenal(canvas: Canvas, cx: Float, cy: Float, d: Float, limite: Int?, semaforo: Boolean = false) {
    if (semaforo) {
        dibujarSemaforo(canvas, cx, cy, d)
        return
    }
    val grosor = d * 0.12f
    val r = d / 2
    canvas.drawCircle(cx, cy, r - 1, rellenoSenal)
    bordeSenal.strokeWidth = grosor
    canvas.drawCircle(cx, cy, r - grosor / 2 - 1, bordeSenal)
    val texto = limite?.toString() ?: "?"
    numeroSenal.color = if (limite == null) Color.GRAY else Color.BLACK
    // Con tres cifras el número se estrecha para que quepa dentro del borde.
    numeroSenal.textSize = if (texto.length >= 3) d * 0.36f else d * 0.46f
    val y = cy - (numeroSenal.descent() + numeroSenal.ascent()) / 2
    canvas.drawText(texto, cx, y, numeroSenal)
}

/** Semáforo vertical (rojo encendido, ámbar y verde apagados) dentro de un círculo blanco con borde rojo. */
private fun dibujarSemaforo(canvas: Canvas, cx: Float, cy: Float, d: Float) {
    val r = d / 2
    val grosor = d * 0.10f
    canvas.drawCircle(cx, cy, r - 1, rellenoSenal)
    bordeSenal.strokeWidth = grosor
    canvas.drawCircle(cx, cy, r - grosor / 2 - 1, bordeSenal)
    val alto = d * 0.58f
    val ancho = d * 0.25f
    val caja = android.graphics.RectF(cx - ancho / 2, cy - alto / 2, cx + ancho / 2, cy + alto / 2)
    canvas.drawRoundRect(caja, ancho * 0.3f, ancho * 0.3f, cajaSemaforo)
    val radioLuz = ancho * 0.32f
    val colores = intArrayOf(Color.parseColor("#FF2E3A"), Color.parseColor("#4D3A10"), Color.parseColor("#123D22"))
    for (i in 0..2) {
        luzSemaforo.color = colores[i]
        canvas.drawCircle(cx, caja.top + alto * (0.2f + 0.3f * i), radioLuz, luzSemaforo)
    }
}
