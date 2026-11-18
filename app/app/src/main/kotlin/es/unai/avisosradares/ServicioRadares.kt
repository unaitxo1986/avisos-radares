package es.unai.avisosradares

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Servicio de la app (fases 4.1 a 4.4): en primer plano y de tipo ubicación, lee el GPS cada segundo, pasa cada
 * posición por el [MotorAvisos], enseña la velocidad y el aviso en la ventana flotante, suena por el altavoz
 * del móvil y graba el viaje.
 *
 * Lo arranca el receptor Bluetooth al conectarse el coche (vía A, probada en la fase 2) o el botón de la
 * pantalla, y se para al desconectarse el coche, con el botón "Parar" de la notificación o desde la pantalla.
 * También sabe reproducir un recorrido (simulado o grabado) en lugar del GPS, para probar sin conducir.
 */
class ServicioRadares : Service() {

    companion object {
        private const val EXTRA_ORIGEN = "origen"
        private const val ACCION_PARAR = "es.unai.avisosradares.PARAR"
        private const val CANAL = "servicio"
        private const val ID_NOTIFICACION = 1

        @Volatile
        var enMarcha = false
            private set

        /** Texto de estado para la pantalla principal (velocidad, aviso, modo). */
        @Volatile
        var estadoTexto = ""
            private set

        /** Para el cuentarrevoluciones de la pantalla principal: velocidad, aviso en curso y si es una simulación. */
        @Volatile
        var velocidadKmh: Double? = null
            private set
        @Volatile
        var avisoActual: AvisoActivo? = null
            private set
        @Volatile
        var simulando = false
            private set

        /** Recorrido que se reproducirá en lugar del GPS en el próximo arranque, con su factor de velocidad. */
        private var recorridoPendiente: List<Posicion>? = null
        private var descripcionPendiente = ""
        private var factorPendiente = 1.0

        fun arrancar(contexto: Context, origen: String) {
            val ctx = contexto.applicationContext
            val intent = Intent(ctx, ServicioRadares::class.java).putExtra(EXTRA_ORIGEN, origen)
            try {
                ctx.startForegroundService(intent)
                Registro.anota(ctx, "SERVICIO", "arranque pedido (origen: $origen)")
            } catch (e: Throwable) {
                Registro.anota(ctx, "SERVICIO", "arranque RECHAZADO por Android (origen: $origen): ${e.javaClass.name}: ${e.message}")
            }
        }

        /** Reproduce [recorrido] en lugar del GPS. [factor] > 1 lo acelera (para viajes grabados largos). */
        fun reproducir(contexto: Context, recorrido: List<Posicion>, descripcion: String, factor: Double) {
            recorridoPendiente = recorrido
            descripcionPendiente = descripcion
            factorPendiente = factor
            arrancar(contexto, "reproducción: $descripcion")
        }

        fun parar(contexto: Context) {
            contexto.applicationContext.stopService(Intent(contexto.applicationContext, ServicioRadares::class.java))
        }
    }

    private val manejador = Handler(Looper.getMainLooper())
    private var gestorUbicacion: LocationManager? = null
    private var oyente: LocationListener? = null
    private var motor: MotorAvisos? = null
    private var grabador: Viajes.Grabador? = null
    private var inicioMs = 0L
    private var posiciones = 0
    private var avisos = 0
    private var recorrido: List<Posicion>? = null
    private var descripcionRecorrido = ""

    // Ventana flotante
    private var ventana: LinearLayout? = null
    private var textoVelocidad: TextView? = null
    private var columnaAviso: LinearLayout? = null
    private var senal: SenalLimite? = null
    private var vistaDistancia: TextView? = null
    private var textoEtiqueta: TextView? = null
    private var fondo: GradientDrawable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) {
            Registro.anota(this, "SERVICIO", "parado con el botón de la notificación")
            stopSelf()
            return START_NOT_STICKY
        }
        val origen = intent?.getStringExtra(EXTRA_ORIGEN) ?: "reinicio por el sistema"
        if (enMarcha) {
            Registro.anota(this, "SERVICIO", "ya estaba en marcha; arranque repetido desde $origen")
            recorridoPendiente = null
            return START_NOT_STICKY
        }
        Registro.anota(this, "SERVICIO", "inicio desde $origen | " + Diagnostico.estado(this))
        recorrido = recorridoPendiente
        simulando = recorrido != null
        descripcionRecorrido = descripcionPendiente
        val factor = factorPendiente
        recorridoPendiente = null
        if (!ponerEnPrimerPlano()) {
            stopSelf()
            return START_NOT_STICKY
        }
        enMarcha = true
        inicioMs = SystemClock.elapsedRealtime()
        estadoTexto = "arrancando…"

        val lista = try {
            GestorLista.lista(this)
        } catch (e: Throwable) {
            Registro.anota(this, "SERVICIO", "ERROR al leer la lista de radares: ${e.message}")
            null
        }
        if (lista != null) {
            motor = MotorAvisos(lista.radares)
            Registro.anota(this, "SERVICIO", "lista de ${lista.radares.size} radares, versión ${lista.version}")
        }
        arrancarVentana()

        val r = recorrido
        if (r != null) {
            Registro.anota(this, "SERVICIO", "REPRODUCCIÓN de $descripcionRecorrido: ${r.size} posiciones, ×$factor")
            reproducirRecorrido(r, factor)
        } else {
            val cabecera = "# viaje avisos-radares; inicio ${System.currentTimeMillis()}; lista ${lista?.version}; " +
                "app ${BuildConfigApp.version(this)}; origen $origen"
            grabador = Viajes.nuevo(this, cabecera)
            arrancarUbicacion()
            GestorLista.comprobar(this, forzar = false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val segundos = (SystemClock.elapsedRealtime() - inicioMs) / 1000
        Registro.anota(this, "SERVICIO", "parado tras ${segundos}s, $posiciones posiciones, $avisos avisos")
        manejador.removeCallbacksAndMessages(null)
        oyente?.let { o ->
            try {
                gestorUbicacion?.removeUpdates(o)
            } catch (e: Throwable) {
                // nada que hacer
            }
        }
        grabador?.cierra()
        quitarVentana()
        enMarcha = false
        estadoTexto = ""
        velocidadKmh = null
        avisoActual = null
        simulando = false
        super.onDestroy()
    }

    private fun ponerEnPrimerPlano(): Boolean {
        val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Avisos de radares en marcha", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val parar = PendingIntent.getService(
            this, 1, Intent(this, ServicioRadares::class.java).setAction(ACCION_PARAR), PendingIntent.FLAG_IMMUTABLE
        )
        val texto = if (recorrido != null) "Reproduciendo: $descripcionRecorrido" else "Vigilando radares con el GPS"
        val notificacion = Notification.Builder(this, CANAL)
            .setContentTitle("Avisos de radares")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Parar", parar).build())
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_NOTIFICACION, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID_NOTIFICACION, notificacion)
            }
            true
        } catch (e: Throwable) {
            Registro.anota(this, "SERVICIO", "startForeground() RECHAZADO: ${e.javaClass.name}: ${e.message}")
            false
        }
    }

    // ---------- Posiciones ----------

    @SuppressLint("MissingPermission")
    private fun arrancarUbicacion() {
        val gestor = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gestorUbicacion = gestor
        if (!Diagnostico.concedido(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            Registro.anota(this, "GPS", "sin permiso de ubicación precisa")
            estadoTexto = "SIN PERMISO DE UBICACIÓN"
            pintar(null)
            return
        }
        if (!gestor.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Registro.anota(this, "GPS", "el GPS está apagado en los ajustes del móvil")
            estadoTexto = "GPS APAGADO"
            pintar(null)
            return
        }
        val nuevo = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val p = Posicion(
                    tMs = location.time,
                    lat = location.latitude,
                    lon = location.longitude,
                    precisionM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    velocidadKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
                )
                grabador?.linea(FormatoViaje.posicion(p))
                procesa(p)
            }

            override fun onProviderEnabled(provider: String) {
                Registro.anota(this@ServicioRadares, "GPS", "proveedor activado: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Registro.anota(this@ServicioRadares, "GPS", "proveedor desactivado: $provider")
            }
        }
        try {
            gestor.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, nuevo, Looper.getMainLooper())
            oyente = nuevo
            Registro.anota(this, "GPS", "pidiendo posición al GPS cada segundo")
        } catch (e: Throwable) {
            Registro.anota(this, "GPS", "ERROR al pedir el GPS: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Pasa el recorrido por el motor respetando los tiempos (divididos por [factor]); al terminar, se para. */
    private fun reproducirRecorrido(r: List<Posicion>, factor: Double) {
        if (r.isEmpty()) {
            stopSelf()
            return
        }
        val desfase = System.currentTimeMillis() - r.first().tMs
        val inicio = SystemClock.uptimeMillis()
        for (p in r) {
            val cuando = inicio + ((p.tMs - r.first().tMs) / factor).toLong()
            val ajustada = Posicion(p.tMs + desfase, p.lat, p.lon, p.precisionM, p.velocidadKmh)
            manejador.postAtTime({ procesa(ajustada) }, cuando)
        }
        val fin = inicio + ((r.last().tMs - r.first().tMs) / factor).toLong() + 3000
        manejador.postAtTime({
            Registro.anota(this, "SERVICIO", "fin de la reproducción")
            stopSelf()
        }, fin)
    }

    private fun procesa(p: Posicion) {
        posiciones++
        if (posiciones == 1) {
            Registro.anota(this, "GPS", "primera posición tras ${(SystemClock.elapsedRealtime() - inicioMs) / 1000} s, precisión ${p.precisionM} m")
        }
        val m = motor
        if (m == null) {
            estadoTexto = "${p.velocidadKmh?.toInt() ?: "--"} km/h (sin lista de radares)"
            pintar(p.velocidadKmh, null)
            return
        }
        val resultado = m.procesa(p)
        for (e in resultado.eventos) {
            grabador?.linea(FormatoViaje.evento(p.tMs, e))
            val limite = e.radar.limite?.let { "límite $it" } ?: "sin límite"
            Registro.anota(
                this, "AVISO",
                "${e.tipo} ${e.radar.id} ${e.radar.descripcion()} a ${e.distanciaM.toInt()} m, $limite, " +
                    "${if (e.radar.porCarretera) "por carretera" else "por cercanía"}, " +
                    "velocidad ${resultado.velocidadKmh?.toInt()} km/h ${e.motivo}"
            )
            when (e.tipo) {
                TipoEvento.AVISO -> {
                    avisos++
                    // Volumen subido solo en viajes reales, no en simulaciones ni reproducciones (Unai, 18/11).
                    Sonido.radar(this, "AVISO", subirVolumen = recorrido == null)
                }
                // Decisión de Unai (18/11): el aviso de radar suena siempre la primera vez; después, cada 100 m,
                // 3 pitidos agudos si la velocidad supera el límite.
                TipoEvento.EXCESO -> Sonido.exceso(this, "AVISO", subirVolumen = recorrido == null)
                // Inicio y fin de la medida de un tramo (fase 5): sin sonido; la ventana enseña la media.
                else -> {}
            }
        }
        pintar(resultado.velocidadKmh, resultado.aviso)
    }

    // ---------- Ventana flotante ----------

    private fun arrancarVentana() {
        val ajustes = Ajustes(this)
        if (!ajustes.ventana) return
        if (!Settings.canDrawOverlays(this)) {
            Registro.anota(this, "VENTANA", "falta el permiso de superposición")
            return
        }
        try {
            val gestor = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dp = resources.displayMetrics.density
            // Izquierda: velocidad. Derecha (solo con un aviso): señal con el límite, distancia y qué es.
            val caja = LinearLayout(this)
            caja.orientation = LinearLayout.HORIZONTAL
            caja.gravity = Gravity.CENTER_VERTICAL
            caja.setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            val f = GradientDrawable()
            f.cornerRadius = 18 * dp
            f.setColor(COLOR_NORMAL)
            caja.background = f

            val columnaVelocidad = LinearLayout(this)
            columnaVelocidad.orientation = LinearLayout.VERTICAL
            columnaVelocidad.gravity = Gravity.CENTER_HORIZONTAL
            val v = TextView(this)
            v.textSize = 38f
            v.setTextColor(Color.WHITE)
            v.typeface = Typeface.DEFAULT_BOLD
            v.text = "--"
            val unidad = TextView(this)
            unidad.textSize = 13f
            unidad.setTextColor(Color.WHITE)
            unidad.text = "km/h"
            columnaVelocidad.addView(v)
            columnaVelocidad.addView(unidad)
            caja.addView(columnaVelocidad)

            val columna = LinearLayout(this)
            columna.orientation = LinearLayout.VERTICAL
            columna.gravity = Gravity.CENTER_HORIZONTAL
            columna.setPadding((14 * dp).toInt(), 0, 0, 0)
            val sl = SenalLimite(this, (64 * dp).toInt())
            val dist = TextView(this)
            dist.textSize = 20f
            dist.setTextColor(Color.WHITE)
            dist.typeface = Typeface.DEFAULT_BOLD
            dist.gravity = Gravity.CENTER_HORIZONTAL
            val etiqueta = TextView(this)
            etiqueta.textSize = 11f
            etiqueta.setTextColor(Color.WHITE)
            etiqueta.gravity = Gravity.CENTER_HORIZONTAL
            columna.addView(sl)
            columna.addView(dist)
            columna.addView(etiqueta)
            columna.visibility = View.GONE
            caja.addView(columna)

            val parametros = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            parametros.gravity = Gravity.TOP or Gravity.END
            parametros.x = ajustes.ventanaX
            parametros.y = ajustes.ventanaY
            hacerArrastrable(caja, parametros, gestor, ajustes)
            gestor.addView(caja, parametros)
            ventana = caja
            textoVelocidad = v
            columnaAviso = columna
            senal = sl
            vistaDistancia = dist
            textoEtiqueta = etiqueta
            fondo = f
            pintar(null, null)
        } catch (e: Throwable) {
            Registro.anota(this, "VENTANA", "ERROR al añadir la ventana: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** La ventana se puede mover con el dedo; la posición se guarda para la próxima vez. */
    @SuppressLint("ClickableViewAccessibility")
    private fun hacerArrastrable(vista: View, lp: WindowManager.LayoutParams, gestor: WindowManager, ajustes: Ajustes) {
        var x0 = 0
        var y0 = 0
        var tx = 0f
        var ty = 0f
        vista.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = lp.x; y0 = lp.y; tx = ev.rawX; ty = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gravedad arriba a la derecha: x crece hacia la izquierda.
                    lp.x = x0 - (ev.rawX - tx).toInt()
                    lp.y = y0 + (ev.rawY - ty).toInt()
                    try { gestor.updateViewLayout(vista, lp) } catch (e: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ajustes.ventanaX = lp.x
                    ajustes.ventanaY = lp.y
                    true
                }
                else -> false
            }
        }
    }

    private fun pintar(velocidadKmh: Double?, aviso: AvisoActivo? = null) {
        val textoVel = velocidadKmh?.let { "${it.toInt()}" } ?: "--"
        Companion.velocidadKmh = velocidadKmh
        avisoActual = aviso
        simulando = recorrido != null
        estadoTexto = "$textoVel km/h" +
            (aviso?.let {
                if (it.enTramo) " · TRAMO, ${textosAviso(it).first} km/h, ${textosAviso(it).second}, límite ${it.radar.limite ?: "desconocido"}"
                else " · ${etiquetaAviso(it.radar, it.porCarretera)} a ${textoDistancia(it.distanciaM)}, límite ${it.radar.limite ?: "desconocido"}"
            } ?: "") +
            (if (recorrido != null) " (simulación)" else "")
        val v = textoVelocidad ?: return
        v.text = textoVel
        val columna = columnaAviso
        if (columna != null) {
            if (aviso == null) {
                columna.visibility = View.GONE
            } else {
                senal?.limite = aviso.radar.limite
                val (arriba, abajo) = textosAviso(aviso)
                vistaDistancia?.text = arriba
                textoEtiqueta?.text = abajo
                columna.visibility = View.VISIBLE
            }
        }
        fondo?.setColor(
            when {
                aviso == null -> COLOR_NORMAL
                aviso.exceso -> COLOR_EXCESO
                else -> COLOR_AVISO
            }
        )
    }

    private fun quitarVentana() {
        val v = ventana ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Throwable) {
            // nada que hacer
        }
        ventana = null
        textoVelocidad = null
        columnaAviso = null
        senal = null
        vistaDistancia = null
        textoEtiqueta = null
    }
}

private val COLOR_NORMAL = Color.parseColor("#DD1B5E20")
private val COLOR_AVISO = Color.parseColor("#EEE65100")
private val COLOR_EXCESO = Color.parseColor("#EEB71C1C")

/** Versión instalada, para el registro de cada viaje. */
object BuildConfigApp {
    fun version(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        "${info.versionName} (${info.versionCode})"
    } catch (e: Throwable) {
        "?"
    }
}
