package es.unai.avisosradares

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Estilo "racing" de las pantallas: fondo casi negro, acentos neón y letra estrecha en cursiva. */
object Estilo {
    val FONDO = Color.parseColor("#0A0A0F")
    val TARJETA = Color.parseColor("#15151D")
    val BORDE = Color.parseColor("#2A2A36")
    val TEXTO = Color.parseColor("#F4F4F8")
    val GRIS = Color.parseColor("#8B8B9C")
    val ROJO = Color.parseColor("#FF2E3A")
    val NARANJA = Color.parseColor("#FF8A00")
    val AMARILLO = Color.parseColor("#FFD60A")
    val VERDE = Color.parseColor("#2BFF88")
    val CIAN = Color.parseColor("#00E5FF")

    /** Titulares: estrecha, negrita y en cursiva, como los dorsales. */
    val RACING: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    val ESTRECHA: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val NORMAL: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun redondeado(color: Int, radioPx: Float, borde: Int? = null, grosorPx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radioPx
        if (borde != null) setStroke(grosorPx, borde)
    }

    fun texto(ctx: Context, t: String, sp: Float, color: Int = TEXTO, letra: Typeface = NORMAL): TextView = TextView(ctx).apply {
        text = t
        textSize = sp
        setTextColor(color)
        typeface = letra
        includeFontPadding = false
    }

    /** Etiqueta pequeña en mayúsculas y separada, para encabezar tarjetas. */
    fun etiqueta(ctx: Context, t: String, color: Int = GRIS): TextView = texto(ctx, t.uppercase(), 12f, color, ESTRECHA).apply {
        letterSpacing = 0.18f
    }

    fun vibra(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Tarjeta oscura con borde fino y esquinas redondeadas. */
    fun tarjeta(ctx: Context, borde: Int = BORDE): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = redondeado(TARJETA, dp(ctx, 18f).toFloat(), borde, dp(ctx, 1.5f))
        val p = dp(ctx, 18f)
        setPadding(p, p, p, p)
    }

    fun margenes(v: View, arriba: Int = 0, abajo: Int = 0) {
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = arriba
            bottomMargin = abajo
        }
    }

    /** Botón plano del modo pro: fondo de tarjeta, texto claro y una franja de color a la izquierda. */
    fun botonPro(ctx: Context, t: String, acento: Int = CIAN): TextView = texto(ctx, t, 15f, TEXTO, ESTRECHA).apply {
        val franja = redondeado(acento, dp(ctx, 3f).toFloat())
        val fondo = redondeado(TARJETA, dp(ctx, 12f).toFloat(), BORDE, dp(ctx, 1f))
        background = LayerDrawable(arrayOf(fondo, franja)).apply {
            setLayerInset(1, dp(ctx, 8f), dp(ctx, 12f), 0, dp(ctx, 12f))
            if (Build.VERSION.SDK_INT >= 23) setLayerWidth(1, dp(ctx, 4f))
        }
        setPadding(dp(ctx, 22f), dp(ctx, 14f), dp(ctx, 14f), dp(ctx, 14f))
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
    }
}

/** Tres franjas inclinadas (rojo, naranja, amarillo), el adorno de la cabecera. */
class FranjasRacing(ctx: Context) : View(ctx) {
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colores = intArrayOf(Estilo.ROJO, Estilo.NARANJA, Estilo.AMARILLO)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(Estilo.dp(context, 64f), Estilo.dp(context, 30f))
    }

    override fun onDraw(c: Canvas) {
        val ancho = width / 4.2f
        val inclinacion = height * 0.45f
        for (i in colores.indices) {
            val x = i * ancho * 1.35f
            val p = Path().apply {
                moveTo(x + inclinacion, 0f)
                lineTo(x + inclinacion + ancho, 0f)
                lineTo(x + ancho, height.toFloat())
                lineTo(x, height.toFloat())
                close()
            }
            pintura.color = colores[i]
            c.drawPath(p, pintura)
        }
    }
}

/** Fondo a cuadros blancos y negros que se desvanece hacia la derecha (la bandera de meta del botón pro). */
class BanderaCuadros(private val radioPx: Float, private val ladoPx: Float) : Drawable() {
    private val blanco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.TARJETA }
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.BORDE; style = Paint.Style.STROKE; strokeWidth = 3f }

    override fun draw(c: Canvas) {
        val r = RectF(bounds)
        val recorte = Path().apply { addRoundRect(r, radioPx, radioPx, Path.Direction.CW) }
        c.save()
        c.clipPath(recorte)
        c.drawRect(r, fondo)
        val columnas = (r.width() * 0.45f / ladoPx).toInt()
        val filas = (r.height() / ladoPx).toInt() + 1
        for (col in 0 until columnas) {
            blanco.color = Color.argb((150 * (1f - col.toFloat() / columnas)).toInt(), 255, 255, 255)
            for (fila in 0 until filas) {
                if ((col + fila) % 2 == 0) {
                    val x = r.left + col * ladoPx
                    val y = r.top + fila * ladoPx
                    c.drawRect(x, y, x + ladoPx, y + ladoPx, blanco)
                }
            }
        }
        c.restore()
        c.drawPath(recorte, borde)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Obsoleto en Android", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * El botón grande de la pantalla principal, como el START/STOP de un coche con su cuentarrevoluciones alrededor.
 * Parado: "START" y las marcas apagadas. En marcha: la velocidad en el centro, las marcas se encienden según la
 * velocidad (hasta 180 km/h) y el aro cambia de color: verde, naranja con un radar cerca, rojo (latiendo) con exceso.
 */
class BotonArranque(ctx: Context) : View(ctx) {
    var enMarcha = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var velocidadKmh: Double? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var colorAro = Estilo.ROJO
        set(v) { if (field != v) { field = v; invalidate() } }
    var latiendo = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Estilo.TEXTO }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Arrancar o parar los avisos"
    }

    override fun onMeasure(w: Int, h: Int) {
        val lado = min(MeasureSpec.getSize(w), Estilo.dp(context, 290f))
        setMeasuredDimension(MeasureSpec.getSize(w), lado)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.95f else 1f).scaleY(if (pressed) 0.95f else 1f).setDuration(90).start()
    }

    override fun onDraw(c: Canvas) {
        val lado = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radio = lado / 2f * 0.96f

        // Marcas del cuentarrevoluciones: 270°, de abajo a la izquierda a abajo a la derecha.
        val total = 36
        val encendidas = if (enMarcha) ((velocidadKmh ?: 0.0) / 180.0 * total).toInt().coerceIn(0, total) else 0
        marca.strokeWidth = lado * 0.022f
        for (i in 0 until total) {
            val ang = Math.toRadians(135.0 + 270.0 * i / (total - 1))
            val r1 = radio * 0.86f
            val r2 = radio * (if (i % 6 == 0) 0.98f else 0.94f)
            marca.color = when {
                i < encendidas -> if (i >= total * 0.8) Estilo.ROJO else if (i >= total * 0.6) Estilo.NARANJA else colorAro
                else -> Estilo.BORDE
            }
            c.drawLine(
                cx + (r1 * cos(ang)).toFloat(), cy + (r1 * sin(ang)).toFloat(),
                cx + (r2 * cos(ang)).toFloat(), cy + (r2 * sin(ang)).toFloat(), marca,
            )
        }

        // Disco del botón con un degradado, y aro de color con un halo.
        val rDisco = radio * 0.74f
        disco.shader = RadialGradient(cx, cy - rDisco * 0.3f, rDisco * 1.3f, Color.parseColor("#2A2A35"), Color.parseColor("#0C0C11"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rDisco, disco)
        val pulso = if (latiendo) (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 140.0).toFloat()) else 1f
        brillo.color = colorAro
        brillo.alpha = (60 * pulso).toInt()
        brillo.strokeWidth = lado * 0.06f
        c.drawCircle(cx, cy, rDisco + lado * 0.012f, brillo)
        aro.color = colorAro
        aro.alpha = (140 + 115 * pulso).toInt()
        aro.strokeWidth = lado * 0.018f
        c.drawCircle(cx, cy, rDisco, aro)

        if (!enMarcha) {
            letra.typeface = Estilo.RACING
            letra.textSize = lado * 0.17f
            letra.color = Estilo.TEXTO
            c.drawText("START", cx, cy + lado * 0.04f, letra)
            letra.typeface = Estilo.ESTRECHA
            letra.textSize = lado * 0.05f
            letra.color = Estilo.GRIS
            letra.letterSpacing = 0.25f
            c.drawText("PULSA PARA ARRANCAR", cx, cy + lado * 0.14f, letra)
            letra.letterSpacing = 0f
        } else {
            letra.typeface = Estilo.RACING
            letra.textSize = lado * 0.25f
            letra.color = Estilo.TEXTO
            c.drawText(velocidadKmh?.toInt()?.toString() ?: "--", cx, cy + lado * 0.06f, letra)
            letra.typeface = Estilo.ESTRECHA
            letra.textSize = lado * 0.055f
            letra.color = Estilo.GRIS
            letra.letterSpacing = 0.2f
            c.drawText("KM/H", cx, cy + lado * 0.14f, letra)
            letra.letterSpacing = 0f
            // "STOP" en una pastilla roja en la parte baja del disco.
            val w = lado * 0.22f
            val h = lado * 0.075f
            val y = cy + rDisco * 0.62f
            disco.shader = null
            disco.color = Estilo.ROJO
            c.drawRoundRect(RectF(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2), h / 2, h / 2, disco)
            letra.typeface = Estilo.RACING
            letra.textSize = lado * 0.05f
            letra.color = Color.WHITE
            c.drawText("STOP", cx, y + lado * 0.018f, letra)
        }
        if (latiendo) postInvalidateOnAnimation()
    }
}
