package es.unai.avisosradares

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Recibe las conexiones y desconexiones Bluetooth (vías A y C de la fase 2).
 * Anota siempre lo que ve; según el ajuste, arranca o para el servicio de pruebas.
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

        val ajustes = Ajustes(context)
        val modo = ajustes.modoBluetooth
        if (modo == Tipos.NINGUNO) return
        if (ajustes.direccionCoche.isNotEmpty() && ajustes.direccionCoche != direccion) {
            Registro.anota(context, "BT", "ignorado: no es el dispositivo elegido (${ajustes.nombreCoche})")
            return
        }
        if (conectado) {
            ServicioPrueba.arrancar(context, modo, "Bluetooth")
        } else {
            Registro.anota(context, "BT", "parando el servicio por desconexión")
            context.stopService(Intent(context, ServicioPrueba::class.java))
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
