package es.unai.avisosradares

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer

/**
 * Aviso emergente en la pantalla de Android Auto (fase 7), encima de Google Maps o de lo que haya: "RADAR · 500 m",
 * el límite (con la señal dibujada) y la velocidad, actualizados cada segundo (los metros y, en los tramos, la media);
 * vuelve a saltar en rojo si se pasa del límite. Solo se usa
 * mientras el móvil está conectado a Android Auto (fuera del coche ya está la ventana flotante). Sin sonido: los
 * pitidos siguen saliendo por el altavoz del móvil (decisión del 15/11). Al tocarlo se abre la app en el coche.
 *
 * Lo crea y lo alimenta [ServicioRadares] desde el hilo principal.
 */
class AvisosEnCoche(contexto: Context) {
    private val ctx = contexto.applicationContext
    private val conexion = CarConnection(ctx)
    private var enCoche = false
    private var estado: EstadoAvisoCoche? = null

    private val oyente = Observer<Int> { tipo ->
        val antes = enCoche
        enCoche = tipo == CarConnection.CONNECTION_TYPE_PROJECTION
        if (antes != enCoche) Registro.anota(ctx, "COCHE", if (enCoche) "conectado a Android Auto" else "desconectado de Android Auto")
        if (!enCoche) quitar()
    }

    fun empezar() {
        try {
            CarNotificationManager.from(ctx).createNotificationChannel(
                NotificationChannelCompat.Builder(CANAL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Avisos en Android Auto")
                    .setDescription("Aviso emergente en la pantalla del coche al acercarse a un radar")
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .build()
            )
            conexion.type.observeForever(oyente)
        } catch (e: Throwable) {
            Registro.anota(ctx, "COCHE", "ERROR al preparar los avisos de Android Auto: ${e.javaClass.name}: ${e.message}")
        }
    }

    fun terminar() {
        try {
            conexion.type.removeObserver(oyente)
        } catch (e: Throwable) {
            // nada que hacer
        }
        quitar()
    }

    /** Con cada posición: decide si mostrar, actualizar o quitar el aviso del coche. */
    fun actualizar(aviso: AvisoActivo?, velocidadKmh: Double?) {
        if (!enCoche) return
        val (titulo, texto) = aviso?.let { textosAvisoCoche(it, velocidadKmh) } ?: ("" to "")
        val (accion, nuevo) = decidirAvisoCoche(estado, aviso, "$titulo|$texto", System.currentTimeMillis())
        estado = nuevo
        when (accion) {
            AccionAvisoCoche.MOSTRAR -> publicar(aviso!!, titulo, texto, saltar = true)
            AccionAvisoCoche.ACTUALIZAR -> publicar(aviso!!, titulo, texto, saltar = false)
            AccionAvisoCoche.QUITAR -> quitar()
            AccionAvisoCoche.NADA -> {}
        }
    }

    private fun publicar(aviso: AvisoActivo, titulo: String, texto: String, saltar: Boolean) {
        try {
            val abrirApp = CarPendingIntent.getCarApp(
                ctx, 0, Intent(Intent.ACTION_VIEW).setComponent(ComponentName(ctx, CocheServicio::class.java)), 0,
            )
            val senal = imagenSenal(aviso.radar.limite)
            val notificacion = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_lanzador_monocromo)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setLargeIcon(senal)
                .setOnlyAlertOnce(!saltar)
                .extend(
                    CarAppExtender.Builder()
                        .setContentTitle(titulo)
                        .setContentText(texto)
                        .setSmallIcon(R.drawable.ic_lanzador_monocromo)
                        .setLargeIcon(senal)
                        .setContentIntent(abrirApp)
                        .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                        .build()
                )
            // Para que vuelva a saltar (radar nuevo, exceso) se quita antes el anterior.
            if (saltar) CarNotificationManager.from(ctx).cancel(ID)
            CarNotificationManager.from(ctx).notify(ID, notificacion)
            if (saltar) Registro.anota(ctx, "COCHE", "aviso emergente en Android Auto: $titulo | $texto")
        } catch (e: Throwable) {
            Registro.anota(ctx, "COCHE", "ERROR al avisar en Android Auto: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun quitar() {
        estado = null
        try {
            CarNotificationManager.from(ctx).cancel(ID)
        } catch (e: Throwable) {
            // nada que hacer
        }
    }

    private fun imagenSenal(limite: Int?): Bitmap {
        val lado = 192
        val b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        dibujarSenal(Canvas(b), lado / 2f, lado / 2f, lado.toFloat(), limite)
        return b
    }

    private companion object {
        const val CANAL = "avisos_coche"
        const val ID = 77
    }
}
