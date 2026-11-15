package es.unai.avisosradares

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Recibe las conexiones y desconexiones Bluetooth (vías A y C de la fase 2).
 * Se usa de dos formas: declarado en el manifiesto (etiqueta "BT") y registrado en tiempo de ejecución
 * desde [AppRadares] (etiqueta "BT-dinámico"), para comprobar cuál de los dos recibe los avisos.
 */
class BluetoothReceiver(private val etiqueta: String = "BT") : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action ?: return
        if (accion != BluetoothDevice.ACTION_ACL_CONNECTED && accion != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val conectado = accion == BluetoothDevice.ACTION_ACL_CONNECTED

        val dispositivo = dispositivoDe(intent)
        val direccion = dispositivo?.address ?: "?"
        val nombre = try {
            dispositivo?.name ?: "sin nombre"
        } catch (e: SecurityException) {
            "(sin permiso de Bluetooth cercano)"
        }

        Registro.anota(
            context, etiqueta,
            (if (conectado) "CONECTADO " else "DESCONECTADO ") + nombre + " (" + direccion + ") | " + Diagnostico.estado(context)
        )

        if (conectado) {
            ArranqueAutomatico.conectado(context, etiqueta, direccion)
        } else {
            ArranqueAutomatico.desconectado(context, etiqueta, direccion)
        }
    }

    private fun dispositivoDe(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
}
