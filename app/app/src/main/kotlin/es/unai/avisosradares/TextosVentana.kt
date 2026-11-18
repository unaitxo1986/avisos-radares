package es.unai.avisosradares

import java.util.Locale

/**
 * Textos de la ventana flotante, en código puro para poder probarlos.
 */

/** Distancia que queda hasta el radar: de 10 en 10 m por debajo de 1 km; "1,2 km" por encima. */
fun textoDistancia(metros: Double): String =
    if (metros >= 1000) String.format(Locale.ROOT, "%.1f km", metros / 1000).replace('.', ',')
    else "${(metros / 10).toInt() * 10} m"

/**
 * Qué es lo que viene, debajo de la señal: "RADAR"; "RADAR?" si el aviso es por cercanía (sentido sin
 * confirmar); "TRAMO" en los radares de tramo; "SEMÁFORO" en los foto rojo; "rotatorio" en las cabinas que
 * pueden estar vacías; "sin confirmar" en las cámaras que solo están en OpenStreetMap.
 */
fun etiquetaAviso(radar: Radar, porCarretera: Boolean): String {
    val tipo = when (radar.tipo) {
        "tramo", "tramo_extremo" -> "TRAMO"
        "fotorrojo" -> "SEMÁFORO"
        else -> "RADAR"
    }
    val sentido = if (porCarretera) "" else "?"
    val rotatoria = if (radar.rotatoria) " rotatorio" else ""
    val sinConfirmar = if (radar.sinConfirmar) " sin confirmar" else ""
    return "$tipo$sentido$rotatoria$sinConfirmar"
}
