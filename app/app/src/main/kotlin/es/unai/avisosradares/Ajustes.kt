package es.unai.avisosradares

import android.content.Context

/** Ajustes de la app, guardados en el móvil. */
class Ajustes(contexto: Context) {
    private val ctx = contexto.applicationContext
    private val p = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    init {
        // El coche elegido en la app de pruebas de la fase 2 se conserva, para no tener que elegirlo otra vez.
        if (!p.contains("btDireccion")) {
            val viejo = ctx.getSharedPreferences("pruebas", Context.MODE_PRIVATE)
            val direccion = viejo.getString("btDireccion", "") ?: ""
            if (direccion.isNotEmpty()) {
                p.edit()
                    .putString("btDireccion", direccion)
                    .putString("btNombre", viejo.getString("btNombre", direccion))
                    .apply()
            }
        }
    }

    /**
     * Bluetooth que arrancan y paran la app (varios desde el 18/11: varios coches, o el coche y un manos libres);
     * lista vacía = ninguno elegido (no arranca sola). El único coche que se elegía antes pasa a ser el primero.
     */
    var coches: List<DispositivoBt>
        get() {
            val guardado = p.getString("btCoches", null)
            if (guardado != null) return decodificarDispositivos(guardado)
            val direccion = p.getString("btDireccion", "") ?: ""
            return if (direccion.isEmpty()) emptyList() else listOf(DispositivoBt(direccion, p.getString("btNombre", direccion) ?: direccion))
        }
        set(valor) { p.edit().putString("btCoches", codificarDispositivos(valor)).apply() }

    /** Direcciones de los elegidos que están conectados ahora (para parar solo cuando se desconecte el último). */
    var conectadosBt: Set<String>
        get() = p.getStringSet("btConectados", emptySet())?.toSet() ?: emptySet()
        set(valor) { p.edit().putStringSet("btConectados", valor).apply() }

    /** Arrancar y parar solo con el Bluetooth del coche. */
    var arranqueBluetooth: Boolean
        get() = p.getBoolean("arranqueBt", true)
        set(valor) { p.edit().putBoolean("arranqueBt", valor).apply() }

    /** 1 pitido al arrancar con el Bluetooth y 2 al parar (decisión de Unai, 18/11: se mantienen). */
    var sonidosCiclo: Boolean
        get() = p.getBoolean("sonidosCiclo", true)
        set(valor) { p.edit().putBoolean("sonidosCiclo", valor).apply() }

    /** Volumen de los avisos en % (selector de la pantalla principal, 23/11): ver [volumenParaAviso]. */
    var volumenAvisos: Int
        get() = volumenAvisosValido(p.getInt("volumenAvisos", VOLUMEN_AVISOS_DEFECTO))
        set(valor) { p.edit().putInt("volumenAvisos", volumenAvisosValido(valor)).apply() }

    var ventana: Boolean
        get() = p.getBoolean("ventana", true)
        set(valor) { p.edit().putBoolean("ventana", valor).apply() }

    /** Posición de la ventana flotante (se puede arrastrar). */
    var ventanaX: Int
        get() = p.getInt("ventanaX", 24)
        set(valor) { p.edit().putInt("ventanaX", valor).apply() }

    var ventanaY: Int
        get() = p.getInt("ventanaY", 200)
        set(valor) { p.edit().putInt("ventanaY", valor).apply() }

    /** TomTom (7.2): comprobar la carretera antes de avisar de un radar y enseñar el límite de la vía. */
    var comprobarTomTom: Boolean
        get() = p.getBoolean("tomtomComprobar", true)
        set(valor) { p.edit().putBoolean("tomtomComprobar", valor).apply() }

    var limiteViaTomTom: Boolean
        get() = p.getBoolean("tomtomLimiteVia", true)
        set(valor) { p.edit().putBoolean("tomtomLimiteVia", valor).apply() }

    /** Consultas a TomTom hechas hoy desde este móvil ([tipo]: "snap" o "via"), para no gastar la cuota común. */
    fun consultasHoy(tipo: String): Int =
        if (p.getString("tomtomDia", "") == hoy()) p.getInt("tomtom_$tipo", 0) else 0

    fun sumaConsulta(tipo: String) {
        val dia = hoy()
        val e = p.edit()
        if (p.getString("tomtomDia", "") != dia) {
            e.putString("tomtomDia", dia).putInt("tomtom_snap", 0).putInt("tomtom_via", 0)
            e.putInt("tomtom_$tipo", 1)
        } else {
            e.putInt("tomtom_$tipo", p.getInt("tomtom_$tipo", 0) + 1)
        }
        e.apply()
    }

    private fun hoy(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())

    /** Lista de radares: etiqueta HTTP de la última descarga y momento de la última comprobación que funcionó. */
    var etiquetaLista: String
        get() = p.getString("etag", "") ?: ""
        set(valor) { p.edit().putString("etag", valor).apply() }

    var ultimaComprobacionOkMs: Long
        get() = p.getLong("ultimaComprobacionOk", 0L)
        set(valor) { p.edit().putLong("ultimaComprobacionOk", valor).apply() }

    var ultimoIntentoMs: Long
        get() = p.getLong("ultimoIntento", 0L)
        set(valor) { p.edit().putLong("ultimoIntento", valor).apply() }

    var ultimoResultado: String
        get() = p.getString("ultimoResultado", "") ?: ""
        set(valor) { p.edit().putString("ultimoResultado", valor).apply() }
}
