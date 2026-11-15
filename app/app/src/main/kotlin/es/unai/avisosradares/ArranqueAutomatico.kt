package es.unai.avisosradares

import android.content.Context

/**
 * Lógica común de las vías Bluetooth: qué hacer cuando "el coche" se conecta o se desconecta,
 * llegue el aviso por el receptor del manifiesto, por el dinámico o por el servicio de dispositivo compañero.
 */
object ArranqueAutomatico {

    /** El dispositivo elegido cuenta como "el coche": si no se eligió ninguno, vale cualquiera. */
    private fun cuenta(contexto: Context, ajustes: Ajustes, direccion: String, origen: String): Boolean {
        if (ajustes.modoBluetooth == Tipos.NINGUNO) {
            Registro.anota(contexto, origen, "modo «solo registrar»: no se arranca ni se para nada")
            return false
        }
        val elegido = ajustes.direccionCoche
        if (elegido.isNotEmpty() && !elegido.equals(direccion, ignoreCase = true)) {
            Registro.anota(contexto, origen, "ignorado: no es el dispositivo elegido (${ajustes.nombreCoche})")
            return false
        }
        return true
    }

    fun conectado(contexto: Context, origen: String, direccion: String) {
        val ajustes = Ajustes(contexto)
        if (!cuenta(contexto, ajustes, direccion, origen)) return
        if (ajustes.sonidosCiclo && !ServicioPrueba.enMarcha) Sonido.conexion(contexto, origen)
        ServicioPrueba.arrancar(contexto, ajustes.modoBluetooth, origen)
    }

    fun desconectado(contexto: Context, origen: String, direccion: String) {
        val ajustes = Ajustes(contexto)
        if (!cuenta(contexto, ajustes, direccion, origen)) return
        Registro.anota(contexto, origen, "parando el servicio por desconexión (estaba en marcha: ${ServicioPrueba.enMarcha})")
        if (ajustes.sonidosCiclo && ServicioPrueba.enMarcha) Sonido.desconexion(contexto, origen)
        ServicioPrueba.parar(contexto)
    }
}
