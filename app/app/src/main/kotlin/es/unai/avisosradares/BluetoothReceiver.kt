package es.unai.avisosradares

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Recibe las conexiones y desconexiones Bluetooth. Está declarado en el manifiesto y **exportado**: los avisos
 * los envía la aplicación Bluetooth del sistema y un receptor no exportado no los recibe (fase 2.1). Funciona
 * aunque la app no tenga proceso (arranque en frío comprobado el 15/11).
 */
class BluetoothReceiver : BroadcastReceiver() {

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
            context, "BT",
            (if (conectado) "CONECTADO " else "DESCONECTADO ") + nombre + " (" + direccion + ") | " + Diagnostico.estado(context)
        )
        if (conectado) ArranqueAutomatico.conectado(context, direccion) else ArranqueAutomatico.desconectado(context, direccion)
    }

    private fun dispositivoDe(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
}
