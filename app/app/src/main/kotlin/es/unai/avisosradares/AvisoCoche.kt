package es.unai.avisosradares

/**
 * Aviso emergente en la pantalla de Android Auto (fase 7). Android Auto no deja a una app ponerse al lado del mapa
 * de Google Maps, pero sí enseña un aviso emergente (notificación de importancia alta) encima de lo que haya.
 * Código puro, con pruebas: qué texto lleva y cuándo salta (con aviso), se actualiza (en silencio) o se quita.
 *
 * Unai (18/11): el aviso tiene que ir cambiando con los metros que quedan y, en los tramos, con la media. Se
 * actualiza cada segundo con la misma distancia que la ventana flotante (de 10 en 10 m). Como Android Auto quita
 * el aviso emergente al cabo de unos segundos, en los radares vuelve a saltar cada [ParametrosAvisoCoche.repetirMs]
 * mientras dure el aviso; en los tramos (que duran minutos) solo salta al empezar y al pasarse del límite.
 */
class ParametrosAvisoCoche(
    /** Como mucho una actualización por segundo. */
    val actualizarMs: Long = 1000,
    /** Vuelve a saltar en los radares (no en los tramos) si sigue el aviso. Criterio propio [sin verificar]. */
    val repetirMs: Long = 10_000,
)

/** Título y texto del aviso: "RADAR · 460 m" y "Límite 80 · vas a 92 km/h"; en un tramo, la media y lo que falta. */
fun textosAvisoCoche(aviso: AvisoActivo, velocidadKmh: Double?): Pair<String, String> {
    val titulo = if (aviso.enTramo) {
        "TRAMO · media ${aviso.mediaKmh?.toInt() ?: "--"} · fin a ${textoDistancia(aviso.distanciaM)}"
    } else {
        "${etiquetaAviso(aviso.radar, aviso.porCarretera)} · ${textoDistancia(aviso.distanciaM)}"
    }
    val limite = aviso.radar.limite?.let { "Límite $it" } ?: "Límite desconocido"
    val vas = velocidadKmh?.let { " · vas a ${it.toInt()} km/h" } ?: ""
    val exceso = if (aviso.exceso) "¡POR ENCIMA! · " else ""
    return titulo to "$exceso$limite$vas"
}

enum class AccionAvisoCoche { NADA, MOSTRAR, ACTUALIZAR, QUITAR }

/**
 * Lo que está en la pantalla del coche: de qué radar, si era de exceso, qué textos, cuándo se cambió y cuándo
 * saltó por última vez.
 */
data class EstadoAvisoCoche(
    val radarId: String,
    val exceso: Boolean,
    val textos: String,
    val cambiadoMs: Long,
    val saltadoMs: Long,
)

/**
 * - Radar nuevo, o el mismo radar pasa a exceso: MOSTRAR (salta el aviso emergente).
 * - Radar (no tramo) que sigue avisado [ParametrosAvisoCoche.repetirMs] después de saltar: MOSTRAR otra vez.
 * - Cambian los textos (metros, media, velocidad): ACTUALIZAR en silencio, como mucho cada segundo.
 * - Sin aviso: QUITAR.
 */
fun decidirAvisoCoche(
    anterior: EstadoAvisoCoche?,
    aviso: AvisoActivo?,
    textos: String,
    ahoraMs: Long,
    p: ParametrosAvisoCoche = ParametrosAvisoCoche(),
): Pair<AccionAvisoCoche, EstadoAvisoCoche?> {
    if (aviso == null) return (if (anterior != null) AccionAvisoCoche.QUITAR else AccionAvisoCoche.NADA) to null
    val salta = EstadoAvisoCoche(aviso.radar.id, aviso.exceso, textos, ahoraMs, ahoraMs)
    return when {
        anterior == null || anterior.radarId != aviso.radar.id -> AccionAvisoCoche.MOSTRAR to salta
        aviso.exceso && !anterior.exceso -> AccionAvisoCoche.MOSTRAR to salta
        !aviso.enTramo && ahoraMs - anterior.saltadoMs >= p.repetirMs -> AccionAvisoCoche.MOSTRAR to salta
        textos != anterior.textos && ahoraMs - anterior.cambiadoMs >= p.actualizarMs ->
            AccionAvisoCoche.ACTUALIZAR to anterior.copy(exceso = aviso.exceso, textos = textos, cambiadoMs = ahoraMs)
        // Nada que enseñar: se conserva el estado (también si vuelve a ir por debajo del límite).
        else -> AccionAvisoCoche.NADA to anterior.copy(exceso = anterior.exceso && aviso.exceso)
    }
}
