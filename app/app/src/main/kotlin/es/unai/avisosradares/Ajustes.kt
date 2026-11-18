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

    /** Dirección del dispositivo Bluetooth del coche; vacío = ninguno elegido (no arranca solo). */
    var direccionCoche: String
        get() = p.getString("btDireccion", "") ?: ""
        set(valor) { p.edit().putString("btDireccion", valor).apply() }

    var nombreCoche: String
        get() = p.getString("btNombre", "") ?: ""
        set(valor) { p.edit().putString("btNombre", valor).apply() }

    /** Arrancar y parar solo con el Bluetooth del coche. */
    var arranqueBluetooth: Boolean
        get() = p.getBoolean("arranqueBt", true)
        set(valor) { p.edit().putBoolean("arranqueBt", valor).apply() }

    /** 1 pitido al arrancar con el Bluetooth y 2 al parar (decisión de Unai, 18/11: se mantienen). */
    var sonidosCiclo: Boolean
        get() = p.getBoolean("sonidosCiclo", true)
        set(valor) { p.edit().putBoolean("sonidosCiclo", valor).apply() }

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
