package es.unai.avisosradares

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Process

/** Anota cada vez que Android crea el proceso de la app y registra el receptor Bluetooth de diagnóstico. */
class AppRadares : Application() {
    override fun onCreate() {
        super.onCreate()
        Registro.anota(this, "PROCESO", "proceso creado pid=${Process.myPid()}")

        // Receptor "dinámico": solo anota, y solo funciona mientras el proceso está vivo. Sirve para
        // distinguir si Android no avisa a la app o si falla el receptor del manifiesto.
        val filtro = IntentFilter()
        filtro.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        filtro.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(BluetoothReceiver(true), filtro, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(BluetoothReceiver(true), filtro)
            }
            Registro.anota(this, "PROCESO", "receptor Bluetooth dinámico registrado")
        } catch (e: Throwable) {
            Registro.anota(this, "PROCESO", "ERROR al registrar el receptor dinámico: ${e.javaClass.name}: ${e.message}")
        }
    }
}
