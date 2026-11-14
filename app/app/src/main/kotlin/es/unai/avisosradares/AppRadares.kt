package es.unai.avisosradares

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Process

/**
 * Anota cada vez que Android crea el proceso de la app (sirve para detectar cierres y arranques en frío)
 * y registra el receptor Bluetooth dinámico mientras el proceso esté vivo.
 */
class AppRadares : Application() {
    override fun onCreate() {
        super.onCreate()
        Registro.anota(this, "PROCESO", "proceso creado pid=${Process.myPid()}")
        try {
            val filtro = IntentFilter()
            filtro.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            filtro.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            val receptor = BluetoothReceiver("BT-dinámico")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receptor, filtro, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receptor, filtro)
            }
            Registro.anota(this, "PROCESO", "receptor Bluetooth dinámico registrado")
        } catch (e: Throwable) {
            Registro.anota(this, "PROCESO", "ERROR al registrar el receptor dinámico: ${e.javaClass.name}: ${e.message}")
        }
    }
}
