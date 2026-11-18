package es.unai.avisosradares

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla principal de la versión 1: estado, permisos, Bluetooth del coche, lista de radares, pruebas sin
 * conducir (simulación y reproducción de viajes), viajes grabados y registro de diagnóstico.
 */
class MainActivity : Activity() {

    private lateinit var ajustes: Ajustes
    private lateinit var raiz: LinearLayout
    private lateinit var textoServicio: TextView
    private lateinit var textoLista: TextView
    private lateinit var textoAvisosLista: TextView
    private lateinit var textoPermisos: TextView
    private lateinit var textoViajes: TextView
    private lateinit var textoRegistro: TextView
    private lateinit var botonServicio: Button
    private lateinit var botonCoche: Button
    private lateinit var botonArranque: Button
    private lateinit var botonCiclo: Button
    private lateinit var botonVentana: Button
    private val manejador = Handler(Looper.getMainLooper())
    private val refrescoPeriodico = object : Runnable {
        override fun run() {
            refrescarServicio()
            manejador.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)
        raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(28, 28, 28, 28)

        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val cabecera = texto("Avisos de radares\n" + etiquetaVersion(info.versionName ?: "?", info.versionCode.toLong()), 20f)
        cabecera.setTypeface(cabecera.typeface, Typeface.BOLD)
        raiz.addView(cabecera)

        // Servicio
        textoServicio = texto("", 16f)
        raiz.addView(textoServicio)
        botonServicio = boton("") {
            if (ServicioRadares.enMarcha) ServicioRadares.parar(this) else ServicioRadares.arrancar(this, "botón de la pantalla")
        }

        // Lista de radares
        raiz.addView(titulo("Lista de radares"))
        textoLista = texto("", 14f)
        raiz.addView(textoLista)
        textoAvisosLista = texto("", 14f)
        textoAvisosLista.setTextColor(Color.parseColor("#B71C1C"))
        raiz.addView(textoAvisosLista)
        boton("Buscar datos nuevos ahora") { GestorLista.comprobar(this, forzar = true) }

        // Arranque automático
        raiz.addView(titulo("Arranque con el coche"))
        botonCoche = boton("") { elegirCoche() }
        botonArranque = boton("") { ajustes.arranqueBluetooth = !ajustes.arranqueBluetooth }
        botonCiclo = boton("") { ajustes.sonidosCiclo = !ajustes.sonidosCiclo }

        // Permisos
        raiz.addView(titulo("Permisos"))
        textoPermisos = texto("", 13f)
        textoPermisos.typeface = Typeface.MONOSPACE
        raiz.addView(textoPermisos)
        boton("1. Ubicación, Bluetooth y notificaciones") { pedirPermisos() }
        boton("2. Ubicación «permitir siempre»") { pedirSiempre() }
        boton("3. Ventana flotante") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        boton("4. Ajustes de la app (batería sin restricciones)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        // Ventana y sonido
        raiz.addView(titulo("Ventana y sonido"))
        botonVentana = boton("") { ajustes.ventana = !ajustes.ventana }
        boton("Oír el aviso de radar") { Sonido.radar(this, "PANTALLA") }
        boton("Oír el aviso de exceso de velocidad") { Sonido.exceso(this, "PANTALLA") }

        // Pruebas sin conducir
        raiz.addView(titulo("Probar sin conducir"))
        raiz.addView(texto("Pasa por el motor de avisos un recorrido inventado sobre el radar con tramo de aproximación más " +
            "cercano a donde estás, a 10 km/h por encima de su límite. Suena y se ve igual que en el coche.", 13f))
        boton("Simular paso por un radar (debe avisar)") { simular(alReves = false) }
        boton("Simular paso en sentido contrario (no debe avisar)") { simular(alReves = true) }
        boton("Reproducir el último viaje grabado (×4)") { reproducirUltimo() }

        // Viajes
        raiz.addView(titulo("Viajes grabados"))
        textoViajes = texto("", 13f)
        raiz.addView(textoViajes)
        boton("Exportar el último viaje a Descargas") { exportarUltimo() }
        boton("Copiar los avisos del último viaje") { copiarAvisosUltimo() }

        // Diagnóstico
        raiz.addView(titulo("Registro de diagnóstico"))
        boton("Copiar el registro al portapapeles") { copiar("registro", Registro.lee(this, 400)) }
        boton("Anotar motivos de cierre recientes de la app") { anotarMotivosDeCierre() }
        boton("Borrar el registro") { Registro.borra(this) }
        textoRegistro = texto("", 10f)
        textoRegistro.typeface = Typeface.MONOSPACE
        textoRegistro.setTextIsSelectable(true)
        raiz.addView(textoRegistro)

        val desplazable = ScrollView(this)
        desplazable.fitsSystemWindows = true
        desplazable.addView(raiz)
        setContentView(desplazable)
    }

    override fun onResume() {
        super.onResume()
        GestorLista.alCambiar = { refrescar() }
        GestorLista.comprobar(this, forzar = false)
        refrescar()
        manejador.post(refrescoPeriodico)
    }

    override fun onPause() {
        super.onPause()
        GestorLista.alCambiar = null
        manejador.removeCallbacks(refrescoPeriodico)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val resultado = permissions.indices.joinToString(", ") { i ->
            permissions[i].substringAfterLast('.') + "=" + (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED)
        }
        Registro.anota(this, "PERMISOS", "respuesta: $resultado")
        refrescar()
    }

    // ---------- Construcción de la pantalla ----------

    private fun texto(t: String, tamano: Float): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = tamano
        v.setPadding(0, 6, 0, 6)
        return v
    }

    private fun titulo(t: String): TextView {
        val v = texto(t, 17f)
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.setPadding(0, 40, 0, 8)
        return v
    }

    private fun boton(t: String, accion: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener {
            try {
                accion()
            } catch (e: Throwable) {
                Registro.anota(this, "PANTALLA", "ERROR: ${e.javaClass.name}: ${e.message}")
                aviso("Error: ${e.message}")
            }
            manejador.postDelayed({ refrescar() }, 300)
        }
        raiz.addView(b)
        return b
    }

    private fun si(valor: Boolean) = if (valor) "sí" else "NO"

    private fun refrescarServicio() {
        val enMarcha = ServicioRadares.enMarcha
        textoServicio.text = if (enMarcha) "EN MARCHA: " + ServicioRadares.estadoTexto else "Parado"
        textoServicio.setTextColor(Color.parseColor(if (enMarcha) "#1B5E20" else "#555555"))
        botonServicio.text = if (enMarcha) "Parar" else "Arrancar ahora (sin esperar al coche)"
    }

    private fun refrescar() {
        refrescarServicio()
        val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT)
        val lista = try { GestorLista.lista(this) } catch (e: Throwable) { null }
        val estado = GestorLista.estado(this)
        val sb = StringBuilder()
        if (lista == null) {
            sb.append("No se puede leer la lista de radares.\n")
        } else {
            val conLinea = lista.radares.count { it.porCarretera }
            sb.append("${lista.radares.size} radares ($conLinea con aviso por carretera y sentido, ${lista.radares.size - conLinea} por cercanía).\n")
            sb.append("Datos cambiados por última vez: ${isoAMs(lista.version)?.let { formato.format(Date(it)) } ?: lista.version}\n")
        }
        estado?.generado?.let { g -> sb.append("Última ejecución del proceso de datos: ${isoAMs(g)?.let { formato.format(Date(it)) } ?: g}\n") }
        val ok = ajustes.ultimaComprobacionOkMs
        sb.append("Última comprobación desde el móvil: ${if (ok > 0) formato.format(Date(ok)) else "nunca"}")
        if (GestorLista.comprobando) sb.append(" (comprobando…)")
        if (ajustes.ultimoResultado.isNotEmpty()) sb.append("\nResultado: ${ajustes.ultimoResultado}")
        textoLista.text = sb.toString()
        val avisos = GestorLista.avisos(this)
        textoAvisosLista.text = avisos.joinToString("\n") { "⚠ $it" }

        botonCoche.text = "Bluetooth del coche: " + ajustes.nombreCoche.ifEmpty { "SIN ELEGIR (toca para elegirlo)" }
        botonArranque.text = "Arrancar y parar solo con el coche: " + (if (ajustes.arranqueBluetooth) "SÍ" else "NO")
        botonCiclo.text = "Pitidos al arrancar (1) y al parar (2): " + (if (ajustes.sonidosCiclo) "SÍ" else "NO")
        botonVentana.text = "Ventana flotante con la velocidad: " + (if (ajustes.ventana) "SÍ" else "NO")

        val energia = getSystemService(Context.POWER_SERVICE) as PowerManager
        val p = StringBuilder()
        p.append("Ubicación precisa:       ").append(si(Diagnostico.concedido(this, Manifest.permission.ACCESS_FINE_LOCATION))).append("\n")
        p.append("Ubicación «siempre»:     ").append(si(Diagnostico.concedido(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION))).append("\n")
        if (Build.VERSION.SDK_INT >= 31) {
            p.append("Bluetooth cercano:       ").append(si(Diagnostico.concedido(this, Manifest.permission.BLUETOOTH_CONNECT))).append("\n")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.append("Notificaciones:          ").append(si(Diagnostico.concedido(this, Manifest.permission.POST_NOTIFICATIONS))).append("\n")
        }
        p.append("Ventana flotante:        ").append(si(Settings.canDrawOverlays(this))).append("\n")
        p.append("Batería sin restricción: ").append(si(energia.isIgnoringBatteryOptimizations(packageName)))
        textoPermisos.text = p.toString()

        val viajes = Viajes.lista(this)
        textoViajes.text = if (viajes.isEmpty()) "Ninguno todavía." else
            "${viajes.size} guardados. Último: ${viajes.first().name} (${viajes.first().length() / 1024} KB)"
        textoRegistro.text = Registro.lee(this, 80)
    }

    // ---------- Acciones ----------

    private fun pedirPermisos() {
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) lista.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(lista.toTypedArray(), 1)
    }

    private fun pedirSiempre() {
        if (!Diagnostico.concedido(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            aviso("Primero el paso 1: acepta la ubicación.")
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
    }

    @SuppressLint("MissingPermission")
    private fun elegirCoche() {
        if (Build.VERSION.SDK_INT >= 31 && !Diagnostico.concedido(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            aviso("Falta el permiso de Bluetooth cercano (permiso 1).")
            return
        }
        val adaptador = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val emparejados = try {
            adaptador?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (emparejados.isEmpty()) {
            aviso("No hay dispositivos Bluetooth emparejados.")
            return
        }
        val nombres = emparejados.map { it.name ?: it.address }
        AlertDialog.Builder(this)
            .setTitle("¿Cuál es el Bluetooth del coche?")
            .setItems(nombres.toTypedArray()) { _, i ->
                ajustes.direccionCoche = emparejados[i].address
                ajustes.nombreCoche = nombres[i]
                Registro.anota(this, "PANTALLA", "coche elegido: ${nombres[i]}")
                refrescar()
            }
            .show()
    }

    /** El radar con tramo de aproximación más cercano a la última posición conocida (o uno de Bizkaia). */
    @SuppressLint("MissingPermission")
    private fun radarParaSimular(): Radar? {
        val radares = GestorLista.lista(this).radares.filter { it.porCarretera }
        if (radares.isEmpty()) return null
        val gestor = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ultima = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
                .mapNotNull { if (gestor.allProviders.contains(it)) gestor.getLastKnownLocation(it) else null }
                .maxByOrNull { it.time }
        } catch (e: Throwable) {
            null
        }
        if (ultima == null) return radares.firstOrNull { it.id.startsWith("eus-a-8") && it.limite != null } ?: radares.first()
        return radares.minByOrNull { distanciaM(ultima.latitude, ultima.longitude, it.lat, it.lon) }
    }

    private fun simular(alReves: Boolean) {
        if (ServicioRadares.enMarcha) {
            aviso("Para antes el servicio (botón Parar).")
            return
        }
        val radar = radarParaSimular() ?: return aviso("No hay radares con tramo de aproximación en la lista.")
        val velocidad = (radar.limite ?: 90) + 10.0
        val recorrido = recorridoSimulado(radar.linea!!, velocidad, ruidoM = 8.0, semilla = System.currentTimeMillis(), alReves = alReves)
        val texto = (if (alReves) "sentido contrario de " else "") + radar.descripcion() + " a ${velocidad.toInt()} km/h"
        Registro.anota(this, "PANTALLA", "simulación: $texto (${radar.id})")
        aviso("Simulando ${recorrido.size} s: $texto")
        ServicioRadares.reproducir(this, recorrido, texto, 1.0)
    }

    private fun reproducirUltimo() {
        if (ServicioRadares.enMarcha) {
            aviso("Para antes el servicio (botón Parar).")
            return
        }
        val ultimo = Viajes.lista(this).firstOrNull() ?: return aviso("No hay viajes grabados.")
        val posiciones = FormatoViaje.posiciones(ultimo.readText())
        if (posiciones.size < 2) return aviso("El último viaje no tiene posiciones.")
        ServicioRadares.reproducir(this, posiciones, ultimo.name, 4.0)
    }

    private fun exportarUltimo() {
        val ultimo = Viajes.lista(this).firstOrNull() ?: return aviso("No hay viajes grabados.")
        val donde = Viajes.exportar(this, ultimo)
        Registro.anota(this, "PANTALLA", "viaje exportado a $donde")
        aviso("Guardado en $donde")
    }

    private fun copiarAvisosUltimo() {
        val ultimo = Viajes.lista(this).firstOrNull() ?: return aviso("No hay viajes grabados.")
        val lineas = FormatoViaje.resumenEventos(ultimo.readText())
        copiar("avisos", (listOf("# " + ultimo.name) + lineas.takeLast(300)).joinToString("\n"))
    }

    private fun copiar(etiqueta: String, t: String) {
        if (t.isEmpty()) return aviso("No hay nada que copiar todavía.")
        val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        portapapeles.setPrimaryClip(ClipData.newPlainText(etiqueta, t))
        aviso("Copiado: ${t.lines().size} líneas. Pégalo en la conversación.")
    }

    private fun anotarMotivosDeCierre() {
        if (Build.VERSION.SDK_INT < 30) return
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

    private fun aviso(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }
}
