package es.unai.avisosradares

/**
 * Qué le falta al móvil para que la app funcione sola en el coche (la "puesta a punto" de la pantalla principal).
 * Código puro, con prueba: la pantalla solo traduce cada paso en un botón.
 */
enum class PasoPendiente(val texto: String, val detalle: String) {
    PERMISOS_BASICOS("Ubicación, Bluetooth y notificaciones", "Para leer el GPS, reconocer el coche y avisar"),
    UBICACION_SIEMPRE("Ubicación «permitir siempre»", "Para avisar con la pantalla apagada"),
    VENTANA_FLOTANTE("Ventana flotante", "Para ver la velocidad encima de otras apps"),
    BATERIA("Batería sin restricciones", "Para que Android no la cierre en ruta"),
    COCHE("Bluetooth del coche", "Para arrancar y parar sola con el coche"),
}

class EstadoMovil(
    val ubicacion: Boolean,
    val bluetooth: Boolean,
    val notificaciones: Boolean,
    val ubicacionSiempre: Boolean,
    val ventanaFlotante: Boolean,
    val quiereVentana: Boolean,
    val bateria: Boolean,
    val cocheElegido: Boolean,
    val arranqueConCoche: Boolean,
)

/** Pasos que faltan, en el orden en que hay que darlos (la ubicación «siempre» exige antes la normal). */
fun pasosPendientes(e: EstadoMovil): List<PasoPendiente> {
    val pasos = mutableListOf<PasoPendiente>()
    if (!e.ubicacion || !e.bluetooth || !e.notificaciones) pasos += PasoPendiente.PERMISOS_BASICOS
    if (!e.ubicacionSiempre) pasos += PasoPendiente.UBICACION_SIEMPRE
    if (e.quiereVentana && !e.ventanaFlotante) pasos += PasoPendiente.VENTANA_FLOTANTE
    if (!e.bateria) pasos += PasoPendiente.BATERIA
    if (e.arranqueConCoche && !e.cocheElegido) pasos += PasoPendiente.COCHE
    return pasos
}

/** Estado de la pantalla principal según el servicio. */
enum class Pista(val texto: String) { BOXES("EN BOXES"), PISTA("EN PISTA"), SIMULACION("SIMULACIÓN") }

fun pista(enMarcha: Boolean, simulando: Boolean): Pista = when {
    !enMarcha -> Pista.BOXES
    simulando -> Pista.SIMULACION
    else -> Pista.PISTA
}
