package es.unai.avisosradares

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Android Auto (fase 7). Android Auto busca este servicio (declarado en el manifiesto con la categoría de
 * navegación, la única que deja dibujar en la pantalla del coche lo que se quiera) y abre la [PantallaCoche].
 *
 * La app no está en Google Play: en Android Auto hay que activar el modo desarrollador y "Fuentes desconocidas".
 * Por eso se acepta cualquier anfitrión (`ALLOW_ALL_HOSTS_VALIDATOR`): lo único que expone es la velocidad y el
 * aviso en curso, y solo a quien se conecte al servicio en este móvil.
 */
class CocheServicio : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = SesionCoche()

    // Anfitriones antiguos (antes de la API 1.3 de la librería).
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCreateSession(): Session = SesionCoche()
}

class SesionCoche : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Registro.anota(carContext, "COCHE", "app abierta en Android Auto (API del coche ${carContext.carAppApiLevel})")
        // Quien abre la app en el coche quiere los avisos: si no estaban en marcha (no llegó el Bluetooth), se arrancan.
        if (!ServicioRadares.enMarcha) ServicioRadares.arrancar(carContext, "Android Auto")
        return PantallaCoche(carContext)
    }
}

/**
 * Pantalla de la app en el coche: plantilla de navegación (mapa propio) en la que, en lugar de un mapa, se dibuja
 * con [PintorCoche] la velocidad y el aviso, dos veces por segundo. Un botón arriba arranca o para los avisos.
 */
class PantallaCoche(contexto: CarContext) : Screen(contexto), SurfaceCallback {

    private val manejador = Handler(Looper.getMainLooper())
    private var superficie: SurfaceContainer? = null
    private var zonaVisible: Rect? = null
    private var enMarchaPintado: Boolean? = null

    private val repintar = object : Runnable {
        override fun run() {
            pintar()
            // La plantilla (el botón Arrancar/Parar) solo se renueva cuando cambia el estado del servicio.
            if (enMarchaPintado != ServicioRadares.enMarcha) {
                enMarchaPintado = ServicioRadares.enMarcha
                invalidate()
            }
            manejador.postDelayed(this, 500)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@PantallaCoche)
                manejador.post(repintar)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                manejador.removeCallbacks(repintar)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val enMarcha = ServicioRadares.enMarcha
        val boton = Action.Builder()
            .setTitle(if (enMarcha) "Parar" else "Arrancar")
            .setOnClickListener {
                if (ServicioRadares.enMarcha) ServicioRadares.parar(carContext) else ServicioRadares.arrancar(carContext, "botón de Android Auto")
                manejador.postDelayed({ invalidate() }, 500)
            }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(boton).build())
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        superficie = surfaceContainer
        Registro.anota(carContext, "COCHE", "pantalla del coche de ${surfaceContainer.width}×${surfaceContainer.height}")
        pintar()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        zonaVisible = Rect(visibleArea)
        pintar()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        superficie = null
    }

    private fun pintar() {
        val s = superficie ?: return
        val surface = s.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Throwable) {
            return
        } ?: return
        try {
            PintorCoche.dibujar(
                canvas, s.width, s.height, zonaVisible,
                ServicioRadares.enMarcha, ServicioRadares.simulando, ServicioRadares.velocidadKmh, ServicioRadares.avisoActual,
            )
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
