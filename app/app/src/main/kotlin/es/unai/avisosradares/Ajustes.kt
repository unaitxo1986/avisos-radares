package es.unai.avisosradares

import android.content.Context

/** Ajustes de las pruebas de la fase 2, guardados en el móvil. */
class Ajustes(contexto: Context) {
    private val p = contexto.applicationContext.getSharedPreferences("pruebas", Context.MODE_PRIVATE)

    /** Qué hace el receptor Bluetooth al conectarse un dispositivo: ver [Tipos]. */
    var modoBluetooth: Int
        get() = p.getInt("modoBt", Tipos.NINGUNO)
        set(valor) { p.edit().putInt("modoBt", valor).apply() }

    /** Dirección del dispositivo Bluetooth del coche; vacío = cualquiera. */
    var direccionCoche: String
        get() = p.getString("btDireccion", "") ?: ""
        set(valor) { p.edit().putString("btDireccion", valor).apply() }

    var nombreCoche: String
        get() = p.getString("btNombre", "Cualquier dispositivo") ?: "Cualquier dispositivo"
        set(valor) { p.edit().putString("btNombre", valor).apply() }

    var pitidoPeriodico: Boolean
        get() = p.getBoolean("pitidoPeriodico", false)
        set(valor) { p.edit().putBoolean("pitidoPeriodico", valor).apply() }

    var ventana: Boolean
        get() = p.getBoolean("ventana", true)
        set(valor) { p.edit().putBoolean("ventana", valor).apply() }
}
