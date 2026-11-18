package es.unai.avisosradares

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/** Acciones que comparten la pantalla principal y el modo pro: permisos, ajustes del sistema y Bluetooth del coche. */
object Acciones {

    fun estadoMovil(a: Context, ajustes: Ajustes): EstadoMovil {
        val energia = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        return EstadoMovil(
            ubicacion = Diagnostico.concedido(a, Manifest.permission.ACCESS_FINE_LOCATION),
            bluetooth = Build.VERSION.SDK_INT < 31 || Diagnostico.concedido(a, Manifest.permission.BLUETOOTH_CONNECT),
            notificaciones = Build.VERSION.SDK_INT < 33 || Diagnostico.concedido(a, Manifest.permission.POST_NOTIFICATIONS),
            ubicacionSiempre = Diagnostico.concedido(a, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            ventanaFlotante = Settings.canDrawOverlays(a),
            quiereVentana = ajustes.ventana,
            bateria = energia.isIgnoringBatteryOptimizations(a.packageName),
            cocheElegido = ajustes.coches.isNotEmpty(),
            arranqueConCoche = ajustes.arranqueBluetooth,
        )
    }

    /** Da el paso pendiente: pide el permiso o abre el ajuste del sistema que corresponde. */
    fun dar(a: Activity, paso: PasoPendiente, ajustes: Ajustes, alElegirCoche: () -> Unit) {
        when (paso) {
            PasoPendiente.PERMISOS_BASICOS -> pedirPermisos(a)
            PasoPendiente.UBICACION_SIEMPRE -> pedirSiempre(a)
            PasoPendiente.VENTANA_FLOTANTE -> abrirVentanaFlotante(a)
            PasoPendiente.BATERIA -> pedirBateria(a)
            PasoPendiente.COCHE -> elegirCoche(a, ajustes, alElegirCoche)
        }
    }

    fun pedirPermisos(a: Activity) {
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) lista.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.POST_NOTIFICATIONS)
        a.requestPermissions(lista.toTypedArray(), 1)
    }

    fun pedirSiempre(a: Activity) {
        if (!Diagnostico.concedido(a, Manifest.permission.ACCESS_FINE_LOCATION)) {
            aviso(a, "Primero acepta la ubicación.")
            return
        }
        // Android abre sus ajustes: hay que elegir «Permitir siempre».
        aviso(a, "Elige «Permitir siempre»")
        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
    }

    fun abrirVentanaFlotante(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
    }

    @SuppressLint("BatteryLife")
    fun pedirBateria(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        } catch (e: Throwable) {
            abrirAjustesApp(a)
        }
    }

    fun abrirAjustesApp(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
    }

    /** Elegir uno o varios Bluetooth (coches, manos libres) que arrancan y paran la app. */
    @SuppressLint("MissingPermission")
    fun elegirCoche(a: Activity, ajustes: Ajustes, alElegir: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && !Diagnostico.concedido(a, Manifest.permission.BLUETOOTH_CONNECT)) {
            aviso(a, "Antes, permite el Bluetooth cercano.")
            pedirPermisos(a)
            return
        }
        val adaptador = (a.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val emparejados = try {
            adaptador?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (emparejados.isEmpty()) {
            aviso(a, "No hay dispositivos Bluetooth emparejados. Empareja antes el coche desde los ajustes del móvil.")
            return
        }
        val nombres = emparejados.map { it.name ?: it.address }
        val elegidos = ajustes.coches
        val marcados = BooleanArray(emparejados.size) { esElegido(elegidos, emparejados[it].address) }
        AlertDialog.Builder(a)
            .setTitle("¿Con qué Bluetooth arranca y para? (puedes marcar varios)")
            .setMultiChoiceItems(nombres.toTypedArray(), marcados) { _, i, marcado -> marcados[i] = marcado }
            .setPositiveButton("Guardar") { _, _ ->
                val nuevos = emparejados.indices.filter { marcados[it] }.map { DispositivoBt(emparejados[it].address, nombres[it]) }
                ajustes.coches = nuevos
                Registro.anota(a, "PANTALLA", "Bluetooth elegidos: ${textoDispositivos(nuevos)}")
                alElegir()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun aviso(a: Context, t: String) {
        Toast.makeText(a, t, Toast.LENGTH_LONG).show()
    }
}
