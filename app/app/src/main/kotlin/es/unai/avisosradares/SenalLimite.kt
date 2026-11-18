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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(diametroPx, diametroPx)
    }

    override fun onDraw(canvas: Canvas) {
        dibujarSenal(canvas, diametroPx / 2f, diametroPx / 2f, diametroPx.toFloat(), limite)
    }
}

private val rellenoSenal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
private val bordeSenal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0021B"); style = Paint.Style.STROKE }
private val numeroSenal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
}

/** Dibuja la señal de velocidad máxima de diámetro [d] centrada en ([cx], [cy]); la usan la ventana y Android Auto. */
fun dibujarSenal(canvas: Canvas, cx: Float, cy: Float, d: Float, limite: Int?) {
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
