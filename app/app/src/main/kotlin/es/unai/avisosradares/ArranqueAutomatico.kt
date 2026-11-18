package es.unai.avisosradares

import android.content.Context

/**
 * Arranque y parada automáticos con el Bluetooth del coche (vía A, probada en la fase 2). Desde el 18/11 se pueden
 * elegir varios: arranca al conectarse cualquiera y para al desconectarse el último elegido que quedaba conectado
 * (lógica pura en `CochesBluetooth.kt`). Si no hay ninguno elegido no arranca nada, para que no se ponga en marcha
 * con cualquier auricular.
 */
object ArranqueAutomatico {

    private fun cuenta(contexto: Context, ajustes: Ajustes, direccion: String): Boolean {
        if (!ajustes.arranqueBluetooth) {
            Registro.anota(contexto, "BT", "arranque automático desactivado en la app: no se hace nada")
            return false
        }
        val elegidos = ajustes.coches
        if (elegidos.isEmpty()) {
            Registro.anota(contexto, "BT", "no hay Bluetooth del coche elegido: no se hace nada")
            return false
        }
        if (!esElegido(elegidos, direccion)) {
            Registro.anota(contexto, "BT", "ignorado: no es ninguno de los elegidos (${textoDispositivos(elegidos)})")
            return false
        }
        return true
    }

    fun conectado(contexto: Context, direccion: String) {
        val ajustes = Ajustes(contexto)
        if (!cuenta(contexto, ajustes, direccion)) return
        val d = alConectarBt(ajustes.conectadosBt, direccion, ServicioRadares.enMarcha)
        ajustes.conectadosBt = d.conectados
        if (!d.arrancar) {
            Registro.anota(contexto, "BT", "ya estaba en marcha; conectados: ${d.conectados}")
            return
        }
        if (ajustes.sonidosCiclo) Sonido.conexion(contexto, "BT")
        ServicioRadares.arrancar(contexto, "Bluetooth del coche")
    }

    fun desconectado(contexto: Context, direccion: String) {
        val ajustes = Ajustes(contexto)
        if (!cuenta(contexto, ajustes, direccion)) return
        val d = alDesconectarBt(ajustes.conectadosBt, direccion)
        ajustes.conectadosBt = d.conectados
        if (!d.parar) {
            Registro.anota(contexto, "BT", "sigue conectado otro de los elegidos (${d.conectados}): no se para")
            return
        }
        Registro.anota(contexto, "BT", "parando por desconexión del coche (estaba en marcha: ${ServicioRadares.enMarcha})")
        if (ajustes.sonidosCiclo && ServicioRadares.enMarcha) Sonido.desconexion(contexto, "BT")
        ServicioRadares.parar(contexto)
    }
}
