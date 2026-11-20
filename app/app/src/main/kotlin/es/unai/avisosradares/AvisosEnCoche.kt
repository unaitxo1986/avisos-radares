package es.unai.avisosradares

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Observer

/**
 * Aviso en la pantalla de Android Auto (fase 7), como mensaje de "Radar Avisos" desde el 20/11 (ver [publicar]): "RADAR · 500 m",
 * el límite (con la señal dibujada) y la velocidad, actualizados cada segundo (los metros y, en los tramos, la media);
 * se renueva cada 3 s para que no se esconda y vuelve a saltar en rojo si se pasa del límite. Solo se usa
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
            AccionAvisoCoche.MOSTRAR -> publicar(aviso!!, titulo, texto, saltar = true, nuevo = true)
            AccionAvisoCoche.RENOVAR -> publicar(aviso!!, titulo, texto, saltar = true, nuevo = false)
            AccionAvisoCoche.ACTUALIZAR -> publicar(aviso!!, titulo, texto, saltar = false, nuevo = false)
            AccionAvisoCoche.QUITAR -> quitar()
            AccionAvisoCoche.NADA -> {}
        }
    }

    /**
     * Publica el aviso como un **mensaje** de "Radar Avisos" (Unai, 20/11): Android Auto enseña los mensajes como
     * los de WhatsApp, saltando arriba y en la columna de la derecha junto a la música, sin quitar Google Maps de
     * la zona grande (la pantalla propia de la app, de navegación, sí lo quita). El "remitente" lleva como foto la
     * señal del límite (o el semáforo) y el mensaje dice el radar, los metros, el límite y la velocidad.
     * Android Auto exige a los mensajes las acciones "Responder" y "Marcar como leído": responder no hace nada y
     * marcar como leído lo quita. [saltar]: con aviso emergente. [nuevo]: se quita antes el anterior.
     */
    @SuppressLint("MissingPermission")
    private fun publicar(aviso: AvisoActivo, titulo: String, texto: String, saltar: Boolean, nuevo: Boolean) {
        try {
            val senal = imagenSenal(aviso.radar.limite, aviso.radar.semaforo)
            val yo = Person.Builder().setName("Yo").setKey("yo").build()
            val radar = Person.Builder().setName("Radar Avisos").setKey("radar-avisos")
                .setIcon(IconCompat.createWithBitmap(senal)).build()
            val estilo = NotificationCompat.MessagingStyle(yo)
                .addMessage("$titulo · $texto", System.currentTimeMillis(), radar)
            val responder = NotificationCompat.Action.Builder(
                R.drawable.ic_lanzador_monocromo, "Responder", accion(ACCION_RESPONDER, PendingIntent.FLAG_MUTABLE),
            )
                .addRemoteInput(RemoteInput.Builder("respuesta").setLabel("Responder").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
            val leido = NotificationCompat.Action.Builder(
                R.drawable.ic_lanzador_monocromo, "Marcar como leído", accion(ACCION_LEIDO, PendingIntent.FLAG_IMMUTABLE),
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
            val notificacion = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_lanzador_monocromo)
                .setLargeIcon(senal)
                .setStyle(estilo)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(responder)
                .addInvisibleAction(leido)
                .setOnlyAlertOnce(!saltar)
                .build()
            val nm = NotificationManagerCompat.from(ctx)
            if (nuevo) nm.cancel(ID)
            nm.notify(ID, notificacion)
            if (nuevo) Registro.anota(ctx, "COCHE", "mensaje en Android Auto: $titulo | $texto")
        } catch (e: Throwable) {
            Registro.anota(ctx, "COCHE", "ERROR al avisar en Android Auto: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun accion(que: String, mutable: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, que.hashCode(), Intent(ctx, AccionesMensajeCoche::class.java).setAction(que),
        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
    )

    private fun quitar() {
        estado = null
        try {
            NotificationManagerCompat.from(ctx).cancel(ID)
        } catch (e: Throwable) {
            // nada que hacer
        }
    }

    private fun imagenSenal(limite: Int?, semaforo: Boolean): Bitmap {
        val lado = 192
        val b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        dibujarSenal(Canvas(b), lado / 2f, lado / 2f, lado.toFloat(), limite, semaforo)
        return b
    }

    companion object {
        private const val CANAL = "avisos_coche"
        const val ID = 77
        const val ACCION_RESPONDER = "es.unai.avisosradares.RESPONDER"
        const val ACCION_LEIDO = "es.unai.avisosradares.LEIDO"
    }
}

/** Acciones del mensaje del coche: "Marcar como leído" lo quita; "Responder" no hace nada (no hay a quién). */
class AccionesMensajeCoche : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        Registro.anota(contexto, "COCHE", "acción del mensaje en Android Auto: ${intent.action?.substringAfterLast('.')}")
        NotificationManagerCompat.from(contexto).cancel(AvisosEnCoche.ID)
    }
}
