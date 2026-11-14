package es.unai.avisosradares

import android.annotation.SuppressLint
import android.companion.CompanionDeviceService

/**
 * Vía D de la fase 2: Android avisa a este servicio cuando aparece o desaparece un dispositivo Bluetooth
 * asociado a la app como "dispositivo compañero", aunque la app esté cerrada.
 */
@SuppressLint("NewApi")
class ServicioCompanero : CompanionDeviceService() {

    override fun onCreate() {
        super.onCreate()
        Registro.anota(this, "CDM", "servicio de dispositivo compañero creado")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        Registro.anota(this, "CDM", "APARECE $address | " + Diagnostico.estado(this))
        ArranqueAutomatico.conectado(this, "CDM", address)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        Registro.anota(this, "CDM", "DESAPARECE $address | " + Diagnostico.estado(this))
        ArranqueAutomatico.desconectado(this, "CDM", address)
    }
}
