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

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0021B"); style = Paint.Style.STROKE }
    private val numero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(diametroPx, diametroPx)
    }

    override fun onDraw(canvas: Canvas) {
        val d = diametroPx.toFloat()
        val grosor = d * 0.12f
        val c = d / 2
        canvas.drawCircle(c, c, c - 1, relleno)
        borde.strokeWidth = grosor
        canvas.drawCircle(c, c, c - grosor / 2 - 1, borde)
        val texto = limite?.toString() ?: "?"
        numero.color = if (limite == null) Color.GRAY else Color.BLACK
        // Con tres cifras el número se estrecha para que quepa dentro del borde.
        numero.textSize = if (texto.length >= 3) d * 0.36f else d * 0.46f
        val y = c - (numero.descent() + numero.ascent()) / 2
        canvas.drawText(texto, c, y, numero)
    }
}
