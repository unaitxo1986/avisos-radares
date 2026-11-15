package es.unai.avisosradares

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Recibe las conexiones y desconexiones Bluetooth (vías A y C de la fase 2).
 * Anota siempre lo que ve; según el ajuste, arranca el servicio al conectarse y lo para al desconectarse.
 *
 * Hay dos copias en juego: la del manifiesto (la crea el sistema, incluso con la app cerrada) y otra
 * registrada en [AppRadares] mientras el proceso está vivo, que solo anota ([soloRegistrar]) para saber
 * si Android avisa a la app de las conexiones aunque el receptor del manifiesto no reciba nada.
 */
class BluetoothReceiver(private val soloRegistrar: Boolean) : BroadcastReceiver() {

    /** El sistema crea el receptor del manifiesto con este constructor sin parámetros. */
    constructor() : this(false)

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action ?: return
        if (accion != BluetoothDevice.ACTION_ACL_CONNECTED && accion != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val conectado = accion == BluetoothDevice.ACTION_ACL_CONNECTED
        val origen = if (soloRegistrar) "BT-dinámico" else "BT"

        val dispositivo = dispositivoDe(intent)
        val direccion = dispositivo?.address ?: "?"
        val nombre = try {
            dispositivo?.name ?: "sin nombre"
        } catch (e: SecurityException) {
            "(sin permiso de Bluetooth cercano)"
        }

        Registro.anota(
            context, origen,
            (if (conectado) "CONECTADO " else "DESCONECTADO ") + nombre + " (" + direccion + ") | " + Diagnostico.estado(context)
        )
        if (soloRegistrar) return

        val ajustes = Ajustes(context)
        val modo = ajustes.modoBluetooth
        if (modo == Tipos.NINGUNO) return
        if (ajustes.direccionCoche.isNotEmpty() && ajustes.direccionCoche != direccion) {
            Registro.anota(context, origen, "ignorado: no es el dispositivo elegido (${ajustes.nombreCoche})")
            return
        }
        if (conectado) {
            ServicioPrueba.arrancar(context, modo, "Bluetooth")
        } else {
            val enMarcha = if (ServicioPrueba.enMarcha) "sí" else "no"
            Registro.anota(context, origen, "desconexión del dispositivo elegido: servicio en marcha=$enMarcha, se pide pararlo")
            ServicioPrueba.parar(context)
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
