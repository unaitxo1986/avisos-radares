package es.unai.avisosradares

import android.app.Application
import android.os.Process

/** Anota cada vez que Android crea el proceso de la app: sirve para detectar cierres y arranques en frío. */
class AppRadares : Application() {
    override fun onCreate() {
        super.onCreate()
        Registro.anota(this, "PROCESO", "proceso creado pid=${Process.myPid()}")
    }
}
