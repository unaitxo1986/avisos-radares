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
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Servicio en primer plano de pruebas (fase 2): lee el GPS, anota un latido cada 30 s,
 * enseña la velocidad en una ventana flotante y, si se pide, emite un pitido cada minuto.
 * Todo lo que ocurre queda en el registro.
 */
class ServicioPrueba : Service() {

    companion object {
        const val EXTRA_TIPO = "tipo"
        const val EXTRA_ORIGEN = "origen"
        private const val CANAL = "pruebas"
        private const val ID_NOTIFICACION = 1
        private const val PERIODO_LATIDO_MS = 30_000L

        @Volatile
        var enMarcha = false

        /** Pide al sistema que arranque el servicio y anota si lo acepta o lo rechaza. */
        fun arrancar(contexto: Context, tipo: Int, origen: String) {
            val ctx = contexto.applicationContext
            val intent = Intent(ctx, ServicioPrueba::class.java)
                .putExtra(EXTRA_TIPO, tipo)
                .putExtra(EXTRA_ORIGEN, origen)
            try {
                ctx.startForegroundService(intent)
                Registro.anota(ctx, "SERVICIO", "startForegroundService() aceptado (origen: $origen)")
            } catch (e: Throwable) {
                Registro.anota(ctx, "SERVICIO", "startForegroundService() RECHAZADO (origen: $origen): ${e.javaClass.name}: ${e.message}")
            }
        }

        fun parar(contexto: Context) {
            contexto.applicationContext.stopService(Intent(contexto.applicationContext, ServicioPrueba::class.java))
        }
    }

    private val manejador = Handler(Looper.getMainLooper())
    private var gestorUbicacion: LocationManager? = null
    private var oyente: LocationListener? = null
    private var vista: TextView? = null
    private var inicioMs = 0L
    private var fixes = 0
    private var ultimoFixMs = 0L
    private var ultimaPrecision = 0f
    private var ultimaVelocidadKmh = 0f
    private var latidos = 0

    private val latido = object : Runnable {
        override fun run() {
            latidos++
            val segundos = (SystemClock.elapsedRealtime() - inicioMs) / 1000
            val hace = if (ultimoFixMs == 0L) "nunca" else "${(SystemClock.elapsedRealtime() - ultimoFixMs) / 1000} s"
            Registro.anota(
                this@ServicioPrueba, "SERVICIO",
                "latido t=${segundos}s fixes=$fixes ultimoFix=$hace precision=${ultimaPrecision}m velocidad=${ultimaVelocidadKmh.toInt()}km/h"
            )
            if (Ajustes(this@ServicioPrueba).pitidoPeriodico && latidos % 2 == 0) {
                Sonido.avisoDePrueba(this@ServicioPrueba, "SERVICIO")
            }
            manejador.postDelayed(this, PERIODO_LATIDO_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tipo = intent?.getIntExtra(EXTRA_TIPO, Tipos.UBICACION) ?: Tipos.UBICACION
        val origen = intent?.getStringExtra(EXTRA_ORIGEN) ?: "reinicio por el sistema"
        if (enMarcha) {
            Registro.anota(this, "SERVICIO", "ya estaba en marcha; arranque repetido desde $origen")
            return START_NOT_STICKY
        }
        Registro.anota(this, "SERVICIO", "onStartCommand tipo=${nombreTipo(tipo)} origen=$origen | " + Diagnostico.estado(this))
        if (!ponerEnPrimerPlano(tipo)) {
            stopSelf()
            return START_NOT_STICKY
        }
        enMarcha = true
        inicioMs = SystemClock.elapsedRealtime()
        anotaRecorrido("# servicio iniciado ${System.currentTimeMillis()} tipo=${nombreTipo(tipo)} origen=$origen")
        arrancarUbicacion()
        arrancarVentana()
        manejador.postDelayed(latido, PERIODO_LATIDO_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val segundos = (SystemClock.elapsedRealtime() - inicioMs) / 1000
        Registro.anota(this, "SERVICIO", "onDestroy tras ${segundos}s, $fixes posiciones")
        manejador.removeCallbacks(latido)
        val oyenteActual = oyente
        if (oyenteActual != null) {
            try {
                gestorUbicacion?.removeUpdates(oyenteActual)
            } catch (e: Throwable) {
                // nada que hacer
            }
        }
        quitarVentana()
        enMarcha = false
        super.onDestroy()
    }

    /** Devuelve true si el sistema ha aceptado el servicio en primer plano. */
    private fun ponerEnPrimerPlano(tipo: Int): Boolean {
        val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Pruebas de la fase 2", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notificacion = Notification.Builder(this, CANAL)
            .setContentTitle("Avisos radares: servicio de pruebas")
            .setContentText("Tipo: " + nombreTipo(tipo))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val tipoAndroid = if (tipo == Tipos.DISPOSITIVO) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(ID_NOTIFICACION, notificacion, tipoAndroid)
            } else {
                startForeground(ID_NOTIFICACION, notificacion)
            }
            Registro.anota(this, "SERVICIO", "startForeground() aceptado")
            true
        } catch (e: Throwable) {
            Registro.anota(this, "SERVICIO", "startForeground() RECHAZADO: ${e.javaClass.name}: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun arrancarUbicacion() {
        val gestor = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gestorUbicacion = gestor
        if (!Diagnostico.concedido(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            Registro.anota(this, "GPS", "sin permiso de ubicación precisa: no se pide el GPS")
            return
        }
        if (!gestor.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Registro.anota(this, "GPS", "el GPS está apagado en los ajustes del móvil")
            return
        }
        val nuevo = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                alRecibirPosicion(location)
            }

            override fun onProviderEnabled(provider: String) {
                Registro.anota(this@ServicioPrueba, "GPS", "proveedor activado: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Registro.anota(this@ServicioPrueba, "GPS", "proveedor desactivado: $provider")
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

    private fun alRecibirPosicion(l: Location) {
        fixes++
        val ahora = SystemClock.elapsedRealtime()
        if (fixes == 1) {
            Registro.anota(this, "GPS", "primera posición tras ${(ahora - inicioMs) / 1000} s, precisión ${l.accuracy} m")
        }
        ultimoFixMs = ahora
        ultimaPrecision = l.accuracy
        ultimaVelocidadKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        val velocidad = if (l.hasSpeed()) l.speed else -1f
        val rumbo = if (l.hasBearing()) l.bearing else -1f
        anotaRecorrido(
            String.format(Locale.ROOT, "%d,%.6f,%.6f,%.1f,%.1f,%.0f", l.time, l.latitude, l.longitude, l.accuracy, velocidad, rumbo)
        )
        actualizarVentana()
    }

    private fun anotaRecorrido(linea: String) {
        try {
            File(filesDir, "recorrido.csv").appendText(linea + "\n")
        } catch (e: Throwable) {
            // nada que hacer
        }
    }

    private fun arrancarVentana() {
        if (!Ajustes(this).ventana) {
            Registro.anota(this, "VENTANA", "desactivada en los ajustes de la app")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Registro.anota(this, "VENTANA", "falta el permiso de superposición")
            return
        }
        try {
            val gestor = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val texto = TextView(this)
            texto.text = "-- km/h"
            texto.textSize = 28f
            texto.setTextColor(Color.WHITE)
            texto.setBackgroundColor(Color.parseColor("#CC1B5E20"))
            texto.setPadding(32, 16, 32, 16)
            val parametros = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            parametros.gravity = Gravity.TOP or Gravity.END
            parametros.x = 24
            parametros.y = 200
            gestor.addView(texto, parametros)
            vista = texto
            Registro.anota(this, "VENTANA", "ventana flotante añadida")
        } catch (e: Throwable) {
            Registro.anota(this, "VENTANA", "ERROR al añadir la ventana: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Verde normal; rojo si va a más de 50 km/h (solo para comprobar que el color cambia). */
    private fun actualizarVentana() {
        val texto = vista ?: return
        texto.text = "${ultimaVelocidadKmh.toInt()} km/h"
        texto.setBackgroundColor(Color.parseColor(if (ultimaVelocidadKmh > 50f) "#CCB71C1C" else "#CC1B5E20"))
    }

    private fun quitarVentana() {
        val texto = vista ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(texto)
        } catch (e: Throwable) {
            // nada que hacer
        }
        vista = null
    }
}
