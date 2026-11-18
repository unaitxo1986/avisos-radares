package es.unai.avisosradares

/**
 * Varios Bluetooth para arrancar y parar (petición de Unai, 18/11): por ejemplo, dos coches, o el coche y un
 * manos libres. Código puro, con pruebas; [Ajustes] guarda la lista y [ArranqueAutomatico] aplica las decisiones.
 */
data class DispositivoBt(val direccion: String, val nombre: String)

/** Una línea por dispositivo: dirección, tabulador y nombre. */
fun codificarDispositivos(lista: List<DispositivoBt>): String =
    lista.joinToString("\n") { it.direccion + "\t" + it.nombre.replace('\t', ' ').replace('\n', ' ') }

fun decodificarDispositivos(texto: String): List<DispositivoBt> =
    texto.lines().filter { it.isNotBlank() }.map { linea ->
        val partes = linea.split('\t', limit = 2)
        DispositivoBt(partes[0], partes.getOrElse(1) { partes[0] }.ifEmpty { partes[0] })
    }.distinctBy { it.direccion.uppercase() }

fun esElegido(elegidos: List<DispositivoBt>, direccion: String): Boolean =
    elegidos.any { it.direccion.equals(direccion, ignoreCase = true) }

/** Qué hacer tras una conexión o desconexión, y qué dispositivos elegidos quedan conectados. */
class DecisionBt(val arrancar: Boolean, val parar: Boolean, val conectados: Set<String>)

/**
 * Se conecta uno de los elegidos: hay que arrancar si no estaba en marcha. Si el servicio estaba parado, lo que
 * hubiera apuntado como conectado es de antes (un reinicio, una desconexión perdida) y se olvida.
 */
fun alConectarBt(conectados: Set<String>, direccion: String, enMarcha: Boolean): DecisionBt {
    val dir = direccion.uppercase()
    val nuevos = if (enMarcha) conectados + dir else setOf(dir)
    return DecisionBt(arrancar = !enMarcha, parar = false, conectados = nuevos)
}

/** Se desconecta uno de los elegidos: se para solo si no queda ningún otro elegido conectado. */
fun alDesconectarBt(conectados: Set<String>, direccion: String): DecisionBt {
    val nuevos = conectados - direccion.uppercase()
    return DecisionBt(arrancar = false, parar = nuevos.isEmpty(), conectados = nuevos)
}

/** Texto de la tarjeta del coche: "Elige su Bluetooth", el nombre, o "Seat · Kit manos libres". */
fun textoDispositivos(lista: List<DispositivoBt>): String = when (lista.size) {
    0 -> "Elige su Bluetooth"
    else -> lista.joinToString(" · ") { it.nombre }
}
