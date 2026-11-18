package es.unai.avisosradares

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla principal, pensada para usarla sin pensar: el botón START/STOP (con la velocidad en marcha), el
 * Bluetooth del coche, la "puesta a punto" solo si falta algo y la entrada al modo pro, donde está todo lo demás.
 */
class MainActivity : Activity() {

    private lateinit var ajustes: Ajustes
    private lateinit var estadoPista: TextView
    private lateinit var boton: BotonArranque
    private lateinit var lineaAviso: TextView
    private lateinit var nombreCoche: TextView
    private lateinit var etiquetaCoche: TextView
    private lateinit var tarjetaCoche: LinearLayout
    private lateinit var tarjetaPuesta: LinearLayout
    private lateinit var listaPuesta: LinearLayout
    private lateinit var tituloPuesta: TextView
    private lateinit var resumenLista: TextView

    private val manejador = Handler(Looper.getMainLooper())
    private val refrescoPeriodico = object : Runnable {
        override fun run() {
            refrescarMarcha()
            manejador.postDelayed(this, 500)
        }
    }

    private fun dp(v: Float) = Estilo.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(20f), dp(18f), dp(20f), dp(28f))

        // Cabecera: nombre de la app en cursiva y las franjas.
        val cabecera = LinearLayout(this)
        cabecera.orientation = LinearLayout.HORIZONTAL
        cabecera.gravity = Gravity.CENTER_VERTICAL
        val nombre = LinearLayout(this)
        nombre.orientation = LinearLayout.VERTICAL
        nombre.addView(Estilo.texto(this, "RADAR", 34f, Estilo.TEXTO, Estilo.RACING))
        nombre.addView(Estilo.texto(this, "AVISOS", 34f, Estilo.ROJO, Estilo.RACING).apply { setPadding(0, dp(-6f), 0, 0) })
        cabecera.addView(nombre, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(FranjasRacing(this))
        raiz.addView(cabecera)

        // Estado (en boxes / en pista / simulación).
        estadoPista = Estilo.texto(this, "", 13f, Estilo.GRIS, Estilo.ESTRECHA)
        estadoPista.letterSpacing = 0.2f
        estadoPista.gravity = Gravity.CENTER
        estadoPista.setPadding(dp(16f), dp(7f), dp(16f), dp(7f))
        val filaEstado = LinearLayout(this)
        filaEstado.gravity = Gravity.CENTER
        filaEstado.addView(estadoPista)
        Estilo.margenes(filaEstado, arriba = dp(22f))
        raiz.addView(filaEstado)

        // Botón START/STOP con el cuentarrevoluciones.
        boton = BotonArranque(this)
        boton.setOnClickListener {
            Estilo.vibra(boton)
            if (ServicioRadares.enMarcha) ServicioRadares.parar(this) else ServicioRadares.arrancar(this, "botón START")
            manejador.postDelayed({ refrescar() }, 300)
        }
        Estilo.margenes(boton, arriba = dp(8f))
        raiz.addView(boton)

        lineaAviso = Estilo.texto(this, "", 16f, Estilo.TEXTO, Estilo.ESTRECHA)
        lineaAviso.gravity = Gravity.CENTER
        lineaAviso.minHeight = dp(24f)
        Estilo.margenes(lineaAviso, arriba = dp(4f))
        raiz.addView(lineaAviso)

        // Bluetooth del coche.
        tarjetaCoche = Estilo.tarjeta(this)
        tarjetaCoche.orientation = LinearLayout.HORIZONTAL
        tarjetaCoche.gravity = Gravity.CENTER_VERTICAL
        val textosCoche = LinearLayout(this)
        textosCoche.orientation = LinearLayout.VERTICAL
        etiquetaCoche = Estilo.etiqueta(this, "Tu coche")
        textosCoche.addView(etiquetaCoche)
        nombreCoche = Estilo.texto(this, "", 20f, Estilo.TEXTO, Estilo.RACING)
        nombreCoche.setPadding(0, dp(4f), 0, 0)
        textosCoche.addView(nombreCoche)
        tarjetaCoche.addView(textosCoche, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tarjetaCoche.addView(Estilo.texto(this, "›", 30f, Estilo.GRIS, Estilo.ESTRECHA))
        tarjetaCoche.isClickable = true
        tarjetaCoche.setOnClickListener {
            Estilo.vibra(tarjetaCoche)
            Acciones.elegirCoche(this, ajustes) { refrescar() }
        }
        Estilo.margenes(tarjetaCoche, arriba = dp(18f))
        raiz.addView(tarjetaCoche)

        // Puesta a punto: solo aparece si falta algo.
        tarjetaPuesta = Estilo.tarjeta(this, Estilo.AMARILLO)
        tituloPuesta = Estilo.texto(this, "", 20f, Estilo.AMARILLO, Estilo.RACING)
        tarjetaPuesta.addView(tituloPuesta)
        listaPuesta = LinearLayout(this)
        listaPuesta.orientation = LinearLayout.VERTICAL
        tarjetaPuesta.addView(listaPuesta)
        Estilo.margenes(tarjetaPuesta, arriba = dp(14f))
        raiz.addView(tarjetaPuesta)

        // Modo pro, sobre una bandera a cuadros.
        val pro = LinearLayout(this)
        pro.orientation = LinearLayout.HORIZONTAL
        pro.gravity = Gravity.CENTER_VERTICAL
        pro.background = BanderaCuadros(dp(18f).toFloat(), dp(9f).toFloat())
        pro.setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
        val textosPro = LinearLayout(this)
        textosPro.orientation = LinearLayout.VERTICAL
        textosPro.gravity = Gravity.END
        textosPro.addView(Estilo.texto(this, "MODO PRO", 22f, Estilo.TEXTO, Estilo.RACING).apply { gravity = Gravity.END })
        textosPro.addView(Estilo.etiqueta(this, "Simulación · viajes · ajustes").apply { gravity = Gravity.END; setPadding(0, dp(3f), 0, 0) })
        pro.addView(textosPro, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pro.addView(Estilo.texto(this, "  ›", 30f, Estilo.ROJO, Estilo.ESTRECHA))
        pro.isClickable = true
        pro.setOnClickListener {
            Estilo.vibra(pro)
            startActivity(Intent(this, ModoProActivity::class.java))
        }
        Estilo.margenes(pro, arriba = dp(14f))
        raiz.addView(pro)

        resumenLista = Estilo.etiqueta(this, "")
        resumenLista.gravity = Gravity.CENTER
        Estilo.margenes(resumenLista, arriba = dp(18f))
        raiz.addView(resumenLista)

        val desplazable = ScrollView(this)
        desplazable.fitsSystemWindows = true
        desplazable.isFillViewport = true
        desplazable.setBackgroundColor(Estilo.FONDO)
        desplazable.addView(raiz)
        window.decorView.setBackgroundColor(Estilo.FONDO)
        setContentView(desplazable)
    }

    override fun onResume() {
        super.onResume()
        GestorLista.alCambiar = { refrescar() }
        GestorLista.comprobar(this, forzar = false)
        refrescar()
        manejador.post(refrescoPeriodico)
    }

    override fun onPause() {
        super.onPause()
        GestorLista.alCambiar = null
        manejador.removeCallbacks(refrescoPeriodico)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val resultado = permissions.indices.joinToString(", ") { i ->
            permissions[i].substringAfterLast('.') + "=" + (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED)
        }
        Registro.anota(this, "PERMISOS", "respuesta: $resultado")
        refrescar()
    }

    /** Lo que cambia cada medio segundo: estado, velocidad y aviso en curso. */
    private fun refrescarMarcha() {
        val enMarcha = ServicioRadares.enMarcha
        val estado = pista(enMarcha, ServicioRadares.simulando)
        val colorEstado = when (estado) {
            Pista.BOXES -> Estilo.GRIS
            Pista.PISTA -> Estilo.VERDE
            Pista.SIMULACION -> Estilo.AMARILLO
        }
        estadoPista.text = "●  " + estado.texto
        estadoPista.setTextColor(colorEstado)
        estadoPista.background = Estilo.redondeado(Estilo.TARJETA, dp(20f).toFloat(), colorEstado, dp(1.5f))

        val aviso = ServicioRadares.avisoActual
        boton.enMarcha = enMarcha
        boton.velocidadKmh = ServicioRadares.velocidadKmh
        boton.colorAro = when {
            !enMarcha -> Estilo.ROJO
            aviso == null -> Estilo.VERDE
            aviso.exceso -> Estilo.ROJO
            else -> Estilo.NARANJA
        }
        boton.latiendo = enMarcha && aviso?.exceso == true

        if (aviso != null) {
            val (arriba, abajo) = textosAviso(aviso)
            val limite = aviso.radar.limite?.let { " · LÍMITE $it" } ?: ""
            lineaAviso.text = if (aviso.enTramo) "TRAMO · ${arriba.uppercase()} · ${abajo.uppercase()}$limite" else "${abajo.uppercase()} · $arriba$limite"
            lineaAviso.setTextColor(if (aviso.exceso) Estilo.ROJO else Estilo.NARANJA)
        } else {
            lineaAviso.text = if (enMarcha) "Sin radares cerca" else ""
            lineaAviso.setTextColor(Estilo.GRIS)
        }
    }

    private fun refrescar() {
        refrescarMarcha()

        // Coche.
        val sinCoche = ajustes.nombreCoche.isEmpty()
        nombreCoche.text = if (sinCoche) "Elige su Bluetooth" else ajustes.nombreCoche
        nombreCoche.setTextColor(if (sinCoche) Estilo.AMARILLO else Estilo.TEXTO)
        etiquetaCoche.text = (if (ajustes.arranqueBluetooth) "Tu coche · arranca y para sola" else "Tu coche · arranque automático apagado").uppercase()
        tarjetaCoche.background = Estilo.redondeado(Estilo.TARJETA, dp(18f).toFloat(), if (sinCoche) Estilo.AMARILLO else Estilo.BORDE, dp(1.5f))

        // Puesta a punto: pasos pendientes y problemas de la lista.
        val pasos = pasosPendientes(Acciones.estadoMovil(this, ajustes)).filter { it != PasoPendiente.COCHE }
        val avisosLista = GestorLista.avisos(this)
        listaPuesta.removeAllViews()
        for (paso in pasos) {
            listaPuesta.addView(filaPaso(paso.texto, paso.detalle) {
                Acciones.dar(this, paso, ajustes) { refrescar() }
            })
        }
        for (a in avisosLista) {
            listaPuesta.addView(filaPaso("Lista de radares", a) {
                GestorLista.comprobar(this, forzar = true)
                Acciones.aviso(this, "Buscando datos nuevos…")
            })
        }
        val total = pasos.size + avisosLista.size
        tarjetaPuesta.visibility = if (total == 0) View.GONE else View.VISIBLE
        tituloPuesta.text = if (total == 1) "PIT STOP · FALTA 1 AJUSTE" else "PIT STOP · FALTAN $total AJUSTES"

        // Resumen de la lista.
        val lista = try { GestorLista.lista(this) } catch (e: Throwable) { null }
        resumenLista.text = if (lista == null) "Sin lista de radares" else {
            val fecha = isoAMs(lista.version)?.let { SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(Date(it)) } ?: lista.version
            "${lista.radares.size} radares cargados · datos del $fecha"
        }
    }

    /** Una fila de la puesta a punto: qué falta, para qué sirve y un botón para arreglarlo. */
    private fun filaPaso(titulo: String, detalle: String, accion: () -> Unit): View {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(0, dp(12f), 0, 0)
        val textos = LinearLayout(this)
        textos.orientation = LinearLayout.VERTICAL
        textos.addView(Estilo.texto(this, titulo, 16f, Estilo.TEXTO, Estilo.ESTRECHA))
        textos.addView(Estilo.texto(this, detalle, 13f, Estilo.GRIS).apply { setPadding(0, dp(2f), 0, 0) })
        fila.addView(textos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val arreglar = Estilo.texto(this, "ARREGLAR", 13f, Estilo.FONDO, Estilo.RACING)
        arreglar.letterSpacing = 0.1f
        arreglar.background = Estilo.redondeado(Estilo.AMARILLO, dp(16f).toFloat())
        arreglar.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        arreglar.setOnClickListener {
            Estilo.vibra(arreglar)
            try {
                accion()
            } catch (e: Throwable) {
                Registro.anota(this, "PANTALLA", "ERROR: ${e.javaClass.name}: ${e.message}")
                Acciones.aviso(this, "Error: ${e.message}")
            }
        }
        fila.addView(arreglar)
        return fila
    }
}
