package es.unai.avisosradares

import android.content.Context

/**
 * Arranque y parada automáticos con el Bluetooth del coche (vía A, probada en la fase 2): al conectarse el
 * dispositivo elegido se arranca el servicio y al desconectarse se para. Si no hay coche elegido no se
 * arranca nada, para que no se ponga en marcha con unos auriculares.
 */
object ArranqueAutomatico {

    private fun cuenta(contexto: Context, ajustes: Ajustes, direccion: String): Boolean {
        if (!ajustes.arranqueBluetooth) {
            Registro.anota(contexto, "BT", "arranque automático desactivado en la app: no se hace nada")
            return false
        }
        val elegido = ajustes.direccionCoche
        if (elegido.isEmpty()) {
            Registro.anota(contexto, "BT", "no hay Bluetooth del coche elegido: no se hace nada")
            return false
        }
        if (!elegido.equals(direccion, ignoreCase = true)) {
            Registro.anota(contexto, "BT", "ignorado: no es el coche (${ajustes.nombreCoche})")
            return false
        }
        return true
    }

    fun conectado(contexto: Context, direccion: String) {
        val ajustes = Ajustes(contexto)
        if (!cuenta(contexto, ajustes, direccion)) return
        if (ajustes.sonidosCiclo && !ServicioRadares.enMarcha) Sonido.conexion(contexto, "BT")
        ServicioRadares.arrancar(contexto, "Bluetooth del coche")
    }

    fun desconectado(contexto: Context, direccion: String) {
        val ajustes = Ajustes(contexto)
        if (!cuenta(contexto, ajustes, direccion)) return
        Registro.anota(contexto, "BT", "parando por desconexión del coche (estaba en marcha: ${ServicioRadares.enMarcha})")
        if (ajustes.sonidosCiclo && ServicioRadares.enMarcha) Sonido.desconexion(contexto, "BT")
        ServicioRadares.parar(contexto)
    }
}
