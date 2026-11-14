package es.unai.avisosradares

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de pruebas de la fase 2: permisos, arranque automático, ventana flotante, sonido y registro.
 * Es una pantalla de diagnóstico provisional, no la pantalla final de la app.
 */
class MainActivity : Activity() {

    private companion object {
        const val CODIGO_ASOCIAR = 42
    }

    private lateinit var ajustes: Ajustes
    private lateinit var textoEstado: TextView
    private lateinit var textoRegistro: TextView
    private lateinit var botonModo: Button
    private lateinit var botonCoche: Button
    private lateinit var botonVentana: Button
    private lateinit var botonPeriodico: Button
    private lateinit var raiz: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)

        raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(24, 24, 24, 24)

        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val codigo = info.versionCode.toLong()
        val cabecera = TextView(this)
        cabecera.text = "Avisos de radares: pruebas de la fase 2\n" + etiquetaVersion(info.versionName ?: "?", codigo)
        cabecera.textSize = 18f
        raiz.addView(cabecera)

        // 1. Permisos
        raiz.addView(titulo("1. Permisos y estado"))
        textoEstado = TextView(this)
        textoEstado.typeface = Typeface.MONOSPACE
        textoEstado.textSize = 12f
        raiz.addView(textoEstado)
        anade("Paso 1: pedir ubicación, Bluetooth y notificaciones") { pedirPermisos() }
        anade("Paso 2: ubicación «permitir siempre»") { pedirSiempre() }
        anade("Paso 3: permiso de ventana flotante") { abrirSuperposicion() }
        anade("Paso 4: ajustes de la app (batería, ajustes restringidos)") { abrirAjustesApp() }

        // 2. Arranque automático
        raiz.addView(titulo("2. Arranque automático (pruebas 2.1 a 2.3)"))
        botonModo = anade("") { ajustes.modoBluetooth = (ajustes.modoBluetooth + 1) % 3 }
        botonCoche = anade("") { elegirCoche() }
        anade("Arrancar el servicio ahora desde aquí (control)") {
            ServicioPrueba.arrancar(this, Tipos.UBICACION, "botón de la pantalla")
        }
        anade("Parar el servicio") { ServicioPrueba.parar(this) }
        anade("Vía D: asociar el Bluetooth del coche (dispositivo compañero)") { asociarCompanero() }
        anade("Vía D: comprobar asociaciones y activar la detección") { observarAsociaciones() }
        val ayuda = TextView(this)
        ayuda.text = "Qué hace cada modo:\n" +
            "• Solo registrar: apunta en el registro cada conexión Bluetooth, sin arrancar nada.\n" +
            "• Vía A: al conectarse el Bluetooth elegido, intenta arrancar el servicio de GPS (tipo ubicación).\n" +
            "• Vía C: igual, pero con el servicio declarado como dispositivo conectado.\n" +
            "• Vía B: rutina de Samsung (Ajustes > Modos y rutinas > «Abrir app» > «Radares (arranque)»).\n" +
            "• Vía D: se asocia el dispositivo con Android una vez; Android avisa a la app cuando aparece o desaparece, " +
            "aunque esté cerrada. Usa el modo elegido arriba (A o C) para decidir el tipo de servicio.\n" +
            "En los modos A, C y D el servicio se para al desconectarse el dispositivo."
        ayuda.textSize = 12f
        raiz.addView(ayuda)

        // 3. Ventana y sonido
        raiz.addView(titulo("3. Ventana flotante y sonido (2.4 y 2.6)"))
        botonVentana = anade("") { ajustes.ventana = !ajustes.ventana }
        botonPeriodico = anade("") { ajustes.pitidoPeriodico = !ajustes.pitidoPeriodico }
        anade("Pitido de prueba ahora") { Sonido.avisoDePrueba(this, "PANTALLA") }

        // 4. Registro
        raiz.addView(titulo("4. Registro"))
        anade("Actualizar") { }
        anade("Copiar el registro al portapapeles") { copiar("registro", Registro.lee(this, 400)) }
        anade("Copiar el recorrido GPS (últimas 400 filas)") { copiar("recorrido", ultimasLineasRecorrido()) }
        anade("Anotar motivos de cierre recientes de la app") { anotarMotivosDeCierre() }
        anade("Borrar el registro") { Registro.borra(this) }
        textoRegistro = TextView(this)
        textoRegistro.typeface = Typeface.MONOSPACE
        textoRegistro.textSize = 10f
        textoRegistro.setTextIsSelectable(true)
        raiz.addView(textoRegistro)

        val desplazable = ScrollView(this)
        desplazable.fitsSystemWindows = true
        desplazable.addView(raiz)
        setContentView(desplazable)
    }

    override fun onResume() {
        super.onResume()
        Registro.anota(this, "PANTALLA", "pantalla de pruebas visible")
        refrescar()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val resultado = permissions.indices.joinToString(", ") { i ->
            permissions[i].substringAfterLast('.') + "=" + (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED)
        }
        Registro.anota(this, "PERMISOS", "respuesta: $resultado")
        refrescar()
    }

    private fun titulo(texto: String): TextView {
        val t = TextView(this)
        t.text = texto
        t.textSize = 16f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, 40, 0, 8)
        return t
    }

    private fun anade(texto: String, accion: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.isAllCaps = false
        b.setOnClickListener {
            accion()
            refrescar()
        }
        raiz.addView(b)
        return b
    }

    private fun refrescar() {
        botonModo.text = "Al conectar Bluetooth: " + nombreModo(ajustes.modoBluetooth)
        botonCoche.text = "Dispositivo Bluetooth: " + ajustes.nombreCoche
        botonVentana.text = "Ventana flotante: " + (if (ajustes.ventana) "SÍ" else "NO")
        botonPeriodico.text = "Pitido cada minuto con el servicio en marcha: " + (if (ajustes.pitidoPeriodico) "SÍ" else "NO")
        textoEstado.text = construirEstado()
        textoRegistro.text = Registro.lee(this, 120)
    }

    private fun si(valor: Boolean) = if (valor) "sí" else "NO"

    private fun construirEstado(): String {
        val energia = getSystemService(Context.POWER_SERVICE) as PowerManager
        val sb = StringBuilder()
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
            .append("), ").append(Build.MODEL).append("\n")
        sb.append("Ubicación precisa: ").append(si(Diagnostico.concedido(this, Manifest.permission.ACCESS_FINE_LOCATION))).append("\n")
        sb.append("Ubicación «siempre»: ").append(si(Diagnostico.concedido(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION))).append("\n")
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("Bluetooth cercano: ").append(si(Diagnostico.concedido(this, Manifest.permission.BLUETOOTH_CONNECT))).append("\n")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append("Notificaciones: ").append(si(Diagnostico.concedido(this, Manifest.permission.POST_NOTIFICATIONS))).append("\n")
        }
        sb.append("Ventana flotante permitida: ").append(si(Settings.canDrawOverlays(this))).append("\n")
        sb.append("Batería sin restricciones: ").append(si(energia.isIgnoringBatteryOptimizations(packageName))).append("\n")
        sb.append("Servicio en marcha: ").append(si(ServicioPrueba.enMarcha))
        return sb.toString()
    }

    private fun pedirPermisos() {
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) lista.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(lista.toTypedArray(), 1)
    }

    private fun pedirSiempre() {
        if (!Diagnostico.concedido(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            aviso("Primero haz el paso 1 y acepta la ubicación.")
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
    }

    private fun abrirSuperposicion() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun abrirAjustesApp() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("MissingPermission")
    private fun elegirCoche() {
        if (Build.VERSION.SDK_INT >= 31 && !Diagnostico.concedido(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            aviso("Falta el permiso de Bluetooth cercano (paso 1).")
            return
        }
        val adaptador = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val emparejados = try {
            adaptador?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val nombres = mutableListOf("Cualquier dispositivo")
        val direcciones = mutableListOf("")
        for (d in emparejados) {
            nombres.add(d.name ?: d.address)
            direcciones.add(d.address)
        }
        AlertDialog.Builder(this)
            .setTitle("¿Qué dispositivo Bluetooth es el coche?")
            .setItems(nombres.toTypedArray()) { _, i ->
                ajustes.direccionCoche = direcciones[i]
                ajustes.nombreCoche = nombres[i]
                refrescar()
            }
            .show()
    }

    /** Vía D: pide a Android que asocie un dispositivo Bluetooth a la app (sale un cuadro del sistema). */
    @SuppressLint("NewApi")
    private fun asociarCompanero() {
        if (Build.VERSION.SDK_INT < 31) {
            aviso("Esta vía necesita Android 12 o superior.")
            return
        }
        val gestor = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val peticion = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .build()
        Registro.anota(this, "CDM", "pidiendo asociación: Android busca dispositivos Bluetooth")
        gestor.associate(peticion, object : CompanionDeviceManager.Callback() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                try {
                    startIntentSenderForResult(chooserLauncher, CODIGO_ASOCIAR, null, 0, 0, 0)
                } catch (e: Throwable) {
                    Registro.anota(this@MainActivity, "CDM", "ERROR al abrir el selector: ${e.javaClass.name}: ${e.message}")
                }
            }

            override fun onFailure(error: CharSequence?) {
                Registro.anota(this@MainActivity, "CDM", "la búsqueda ha fallado: $error")
            }
        }, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CODIGO_ASOCIAR) return
        if (resultCode != RESULT_OK) {
            Registro.anota(this, "CDM", "asociación cancelada o sin dispositivo elegido (código $resultCode)")
        } else {
            Registro.anota(this, "CDM", "dispositivo asociado")
            observarAsociaciones()
        }
        refrescar()
    }

    /** Vía D: lista los dispositivos asociados y pide a Android que avise de su presencia. */
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun observarAsociaciones() {
        if (Build.VERSION.SDK_INT < 31) {
            aviso("Esta vía necesita Android 12 o superior.")
            return
        }
        val gestor = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val direcciones = try {
            gestor.associations
        } catch (e: Throwable) {
            Registro.anota(this, "CDM", "ERROR al leer las asociaciones: ${e.javaClass.name}: ${e.message}")
            return
        }
        Registro.anota(this, "CDM", "asociaciones actuales: $direcciones")
        for (direccion in direcciones) {
            try {
                gestor.startObservingDevicePresence(direccion)
                Registro.anota(this, "CDM", "detección de presencia activada para $direccion")
            } catch (e: Throwable) {
                Registro.anota(this, "CDM", "ERROR al activar la detección de $direccion: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    private fun copiar(etiqueta: String, texto: String) {
        if (texto.isEmpty()) {
            aviso("No hay nada que copiar todavía.")
            return
        }
        val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        portapapeles.setPrimaryClip(ClipData.newPlainText(etiqueta, texto))
        aviso("Copiado: ${texto.lines().size} líneas. Pégalo en la conversación.")
    }

    private fun ultimasLineasRecorrido(): String {
        val f = File(filesDir, "recorrido.csv")
        return if (f.exists()) ultimasLineas(f.readText(), 400) else ""
    }

    private fun anotarMotivosDeCierre() {
        if (Build.VERSION.SDK_INT < 30) {
            Registro.anota(this, "CIERRES", "no disponible antes de Android 11")
            return
        }
        val gestor = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lista = gestor.getHistoricalProcessExitReasons(packageName, 0, 8)
        if (lista.isEmpty()) {
            Registro.anota(this, "CIERRES", "Android no ha registrado ningún cierre del proceso")
            return
        }
        val formato = SimpleDateFormat("dd HH:mm:ss", Locale.ROOT)
        for (c in lista) {
            Registro.anota(
                this, "CIERRES",
                "cierre a las " + formato.format(Date(c.timestamp)) + " motivo=" + nombreMotivoCierre(c.reason) +
                    " importancia=" + nombreImportancia(c.importance) + " detalle=" + c.description
            )
        }
    }

    private fun aviso(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }
}
