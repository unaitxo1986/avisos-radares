package es.unai.avisosradares

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modo pro: todo lo que no hace falta para conducir (estado, lista de radares, permisos uno a uno, arranque con el
 * coche, ventana y sonido, pruebas sin conducir, viajes grabados y registro de diagnóstico). La pantalla principal
 * ([MainActivity]) solo arranca y para, elige el coche y avisa si falta algo.
 */
class ModoProActivity : Activity() {

    private lateinit var ajustes: Ajustes
    private lateinit var raiz: LinearLayout
    private lateinit var textoServicio: TextView
    private lateinit var textoLista: TextView
    private lateinit var textoAvisosLista: TextView
    private lateinit var textoPermisos: TextView
    private lateinit var textoViajes: TextView
    private lateinit var textoRegistro: TextView
    private lateinit var botonServicio: TextView
    private lateinit var botonCoche: TextView
    private lateinit var botonArranque: TextView
    private lateinit var botonCiclo: TextView
    private lateinit var botonVentana: TextView
    private lateinit var textoTomTom: TextView
    private lateinit var botonComprobarTomTom: TextView
    private lateinit var botonLimiteVia: TextView
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
        val m = Estilo.dp(this, 20f)
        raiz.setPadding(m, m, m, m * 2)

        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        val cabecera = LinearLayout(this)
        cabecera.orientation = LinearLayout.HORIZONTAL
        cabecera.gravity = Gravity.CENTER_VERTICAL
        val volver = Estilo.texto(this, "‹", 34f, Estilo.TEXTO, Estilo.ESTRECHA)
        volver.setPadding(0, 0, Estilo.dp(this, 14f), 0)
        volver.setOnClickListener { finish() }
        volver.contentDescription = "Volver"
        cabecera.addView(volver)
        val columna = LinearLayout(this)
        columna.orientation = LinearLayout.VERTICAL
        columna.addView(Estilo.texto(this, "MODO PRO", 30f, Estilo.TEXTO, Estilo.RACING))
        @Suppress("DEPRECATION")
        columna.addView(Estilo.etiqueta(this, etiquetaVersion(info.versionName ?: "?", info.versionCode.toLong())))
        cabecera.addView(columna, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(FranjasRacing(this))
        raiz.addView(cabecera)

        // Servicio
        textoServicio = texto("", 16f)
        raiz.addView(textoServicio)
        botonServicio = boton("") {
            if (ServicioRadares.enMarcha) ServicioRadares.parar(this) else ServicioRadares.arrancar(this, "botón de la pantalla")
        }

        // Lista de radares
        titulo("Lista de radares")
        textoLista = texto("", 14f)
        raiz.addView(textoLista)
        textoAvisosLista = texto("", 14f)
        textoAvisosLista.setTextColor(Estilo.AMARILLO)
        raiz.addView(textoAvisosLista)
        boton("Buscar datos nuevos ahora") { GestorLista.comprobar(this, forzar = true) }

        // Arranque automático
        titulo("Arranque con el coche")
        botonCoche = boton("") { Acciones.elegirCoche(this, ajustes) { refrescar() } }
        botonArranque = boton("") { ajustes.arranqueBluetooth = !ajustes.arranqueBluetooth }
        botonCiclo = boton("") { ajustes.sonidosCiclo = !ajustes.sonidosCiclo }

        // Permisos
        titulo("Permisos")
        textoPermisos = texto("", 13f)
        textoPermisos.typeface = Typeface.MONOSPACE
        raiz.addView(textoPermisos)
        boton("1. Ubicación, Bluetooth y notificaciones") { Acciones.pedirPermisos(this) }
        boton("2. Ubicación «permitir siempre»") { Acciones.pedirSiempre(this) }
        boton("3. Ventana flotante") { Acciones.abrirVentanaFlotante(this) }
        boton("4. Batería sin restricciones") { Acciones.pedirBateria(this) }
        boton("5. Ajustes de la app") { Acciones.abrirAjustesApp(this) }

        // Ventana y sonido
        titulo("Ventana y sonido")
        botonVentana = boton("") { ajustes.ventana = !ajustes.ventana }
        boton("Oír el aviso de radar (5 pitidos)") { Sonido.radar(this, "PANTALLA") }
        boton("Oír el pitido de exceso, cada 100 m (3 pitidos)") { Sonido.exceso(this, "PANTALLA") }

        // TomTom (7.2)
        titulo("TomTom")
        textoTomTom = texto("", 13f)
        raiz.addView(textoTomTom)
        botonComprobarTomTom = boton("") { ajustes.comprobarTomTom = !ajustes.comprobarTomTom }
        botonLimiteVia = boton("") { ajustes.limiteViaTomTom = !ajustes.limiteViaTomTom }
        boton("Probar TomTom aquí (límite de la vía donde estás)") { probarTomTom() }

        // Pruebas sin conducir
        titulo("Probar sin conducir")
        raiz.addView(texto("Pasa por el motor de avisos un recorrido inventado sobre el radar con tramo de aproximación más " +
            "cercano a donde estás, a 10 km/h por encima de su límite: suena el aviso, la ventana se pone roja y pita cada 100 m. Suena y se ve igual que en el coche.", 13f))
        boton("Simular paso por un radar (debe avisar)") { simular(alReves = false) }
        boton("Simular paso en sentido contrario (no debe avisar)") { simular(alReves = true) }
        boton("Simular paso por un radar de tramo (velocidad media)") { simularTramo() }
        boton("Reproducir el último viaje grabado (×4)") { reproducirUltimo() }

        // Viajes
        titulo("Viajes grabados")
        textoViajes = texto("", 13f)
        raiz.addView(textoViajes)
        boton("Exportar el último viaje a Descargas") { exportarUltimo() }
        boton("Copiar los avisos del último viaje") { copiarAvisosUltimo() }

        // Diagnóstico
        titulo("Registro de diagnóstico")
        boton("Copiar el registro al portapapeles", Estilo.VERDE) { copiar("registro", Registro.lee(this, 400)) }
        boton("Anotar motivos de cierre recientes de la app", Estilo.GRIS) { anotarMotivosDeCierre() }
        boton("Borrar el registro", Estilo.ROJO) { Registro.borra(this) }
        textoRegistro = texto("", 10f)
        textoRegistro.setTextColor(Estilo.GRIS)
        textoRegistro.typeface = Typeface.MONOSPACE
        textoRegistro.setTextIsSelectable(true)
        raiz.addView(textoRegistro)

        val desplazable = ScrollView(this)
        desplazable.fitsSystemWindows = true
        desplazable.setBackgroundColor(Estilo.FONDO)
        desplazable.addView(raiz)
        window.decorView.setBackgroundColor(Estilo.FONDO)
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
        val v = Estilo.texto(this, t, tamano, Estilo.TEXTO)
        v.setPadding(0, 8, 0, 8)
        v.setLineSpacing(0f, 1.15f)
        return v
    }

    /** Cada sección empieza con un título en cursiva y una raya de color debajo. */
    private var colorSeccion = Estilo.CIAN
    private val coloresSeccion = intArrayOf(Estilo.CIAN, Estilo.NARANJA, Estilo.VERDE, Estilo.AMARILLO, Estilo.ROJO)
    private var numeroSeccion = 0

    private fun titulo(t: String) {
        colorSeccion = coloresSeccion[numeroSeccion++ % coloresSeccion.size]
        val v = Estilo.texto(this, t.uppercase(), 20f, Estilo.TEXTO, Estilo.RACING)
        v.letterSpacing = 0.04f
        v.setPadding(0, Estilo.dp(this, 30f), 0, Estilo.dp(this, 4f))
        raiz.addView(v)
        val raya = View(this)
        raya.background = Estilo.redondeado(colorSeccion, Estilo.dp(this, 2f).toFloat())
        raiz.addView(raya, LinearLayout.LayoutParams(Estilo.dp(this, 44f), Estilo.dp(this, 4f)).apply { bottomMargin = Estilo.dp(this@ModoProActivity, 10f) })
    }

    private fun boton(t: String, acento: Int = colorSeccion, accion: () -> Unit): TextView {
        val b = Estilo.botonPro(this, t, acento)
        b.setOnClickListener {
            Estilo.vibra(b)
            try {
                accion()
            } catch (e: Throwable) {
                Registro.anota(this, "PANTALLA", "ERROR: ${e.javaClass.name}: ${e.message}")
                aviso("Error: ${e.message}")
            }
            manejador.postDelayed({ refrescar() }, 300)
        }
        raiz.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Estilo.dp(this@ModoProActivity, 8f)
        })
        return b
    }

    private fun si(valor: Boolean) = if (valor) "sí" else "NO"

    private fun refrescarServicio() {
        val enMarcha = ServicioRadares.enMarcha
        textoServicio.text = if (enMarcha) "EN MARCHA: " + ServicioRadares.estadoTexto else "Parado"
        textoServicio.setTextColor(if (enMarcha) Estilo.VERDE else Estilo.GRIS)
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

        botonCoche.text = "Bluetooth que arrancan y paran: " + ajustes.coches.let { if (it.isEmpty()) "SIN ELEGIR (toca para elegir)" else textoDispositivos(it) }
        botonArranque.text = "Arrancar y parar solo con el coche: " + (if (ajustes.arranqueBluetooth) "SÍ" else "NO")
        botonCiclo.text = "Pitidos al arrancar (1) y al parar (2): " + (if (ajustes.sonidosCiclo) "SÍ" else "NO")
        botonVentana.text = "Ventana flotante con la velocidad: " + (if (ajustes.ventana) "SÍ" else "NO")
        textoTomTom.text = (if (ClienteTomTom.hayClave) "Clave de TomTom incluida en la app." else "Esta compilación no lleva clave de TomTom: no se le pregunta nada.") +
            "\nConsultas hoy desde este móvil: ${ajustes.consultasHoy("snap")} de ${ClienteTomTom.MAX_SNAP_DIA} comprobaciones, " +
            "${ajustes.consultasHoy("via")} de ${ClienteTomTom.MAX_VIA_DIA} límites de la vía. Pasado el tope, la app avisa como antes."
        botonComprobarTomTom.text = "Comprobar la carretera con TomTom antes de avisar: " + (if (ajustes.comprobarTomTom) "SÍ" else "NO")
        botonLimiteVia.text = "Límite de la vía en la ventana (TomTom): " + (if (ajustes.limiteViaTomTom) "SÍ" else "NO")

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

    /**
     * Fase 5: recorre el radar de tramo con línea más cercano (o Trabakua) a 10 km/h por encima de su límite: avisa
     * al llegar al inicio, la ventana enseña la media y lo que falta, se pone roja y pita al pasar la media del límite.
     */
    @SuppressLint("MissingPermission")
    private fun simularTramo() {
        if (ServicioRadares.enMarcha) {
            aviso("Para antes el servicio (botón Parar).")
            return
        }
        val tramos = GestorLista.lista(this).radares.filter { it.conTramo }
        if (tramos.isEmpty()) return aviso("No hay radares de tramo con su línea en la lista.")
        val gestor = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ultima = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
                .mapNotNull { if (gestor.allProviders.contains(it)) gestor.getLastKnownLocation(it) else null }
                .maxByOrNull { it.time }
        } catch (e: Throwable) {
            null
        }
        val radar = if (ultima == null) tramos.firstOrNull { it.id.contains("trabakua") } ?: tramos.first()
        else tramos.minByOrNull { distanciaM(ultima.latitude, ultima.longitude, it.lat, it.lon) }!!
        val velocidad = (radar.limite ?: 90) + 10.0
        val recorrido = recorridoSimulado(lineaParaSimularTramo(radar), velocidad, ruidoM = 8.0, semilla = System.currentTimeMillis())
        val texto = radar.descripcion() + " (${textoDistancia(radar.tramoLargoM ?: 0.0)}) a ${velocidad.toInt()} km/h"
        Registro.anota(this, "PANTALLA", "simulación de tramo: $texto (${radar.id})")
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

    private fun aviso(t: String) = Acciones.aviso(this, t)

    /** Pregunta a TomTom el límite de la vía en la última posición conocida, para ver que la clave funciona. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun probarTomTom() {
        if (!ClienteTomTom.hayClave) { aviso("Esta compilación no lleva clave de TomTom"); return }
        if (!Diagnostico.concedido(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) { aviso("Falta el permiso de ubicación"); return }
        val gestor = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val ultima = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { gestor.getLastKnownLocation(it) } catch (e: Throwable) { null } }
            .maxByOrNull { it.time }
        if (ultima == null) { aviso("No hay ninguna posición conocida todavía; arranca la app un momento con el GPS"); return }
        val p = Posicion(ultima.time, ultima.latitude, ultima.longitude)
        val cliente = ClienteTomTom(this)
        aviso("Preguntando a TomTom…")
        cliente.limiteVia(p, if (ultima.hasBearing()) ultima.bearing.toDouble() else null) { ok, via, detalle ->
            cliente.cierra()
            Registro.anota(this, "TOMTOM", "prueba desde el modo pro: $detalle")
            aviso(if (ok) "TomTom responde: ${via?.numeros?.joinToString("/")?.ifEmpty { null } ?: via?.calle ?: "sin nombre"}, " +
                "límite ${via?.limiteKmh?.let { "$it km/h" } ?: "desconocido"}" else "TomTom no responde: $detalle")
            refrescar()
        }
    }
}
