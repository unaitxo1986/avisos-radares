package es.unai.avisosradares

import java.util.Locale

/**
 * Aviso emergente en la pantalla de Android Auto (fase 7). Android Auto no deja a una app ponerse al lado del mapa
 * de Google Maps, pero sí enseña un aviso emergente (notificación de importancia alta) encima de lo que haya.
 * Código puro, con pruebas: qué texto lleva y cuándo se muestra (con aviso), se actualiza (en silencio) o se quita.
 */

/** Distancia redondeada para el aviso del coche: de 100 en 100 m y por décimas de km (no cambia cada segundo). */
fun distanciaAproximada(metros: Double): String =
    if (metros >= 1000) String.format(Locale.ROOT, "%.1f km", metros / 1000).replace('.', ',')
    else "${((metros + 50) / 100).toInt().coerceAtLeast(1) * 100} m"

/** Título y texto del aviso: "RADAR · 500 m" y "Límite 80 · vas a 92 km/h"; en un tramo, la media y lo que falta. */
fun textosAvisoCoche(aviso: AvisoActivo, velocidadKmh: Double?): Pair<String, String> {
    val titulo = if (aviso.enTramo) {
        "TRAMO · media ${aviso.mediaKmh?.toInt() ?: "--"} · fin a ${distanciaAproximada(aviso.distanciaM)}"
    } else {
        "${etiquetaAviso(aviso.radar, aviso.porCarretera)} · ${distanciaAproximada(aviso.distanciaM)}"
    }
    val limite = aviso.radar.limite?.let { "Límite $it" } ?: "Límite desconocido"
    val vas = velocidadKmh?.let { " · vas a ${it.toInt()} km/h" } ?: ""
    val exceso = if (aviso.exceso) "¡POR ENCIMA! · " else ""
    return titulo to "$exceso$limite$vas"
}

enum class AccionAvisoCoche { NADA, MOSTRAR, ACTUALIZAR, QUITAR }

/** Lo que está en la pantalla del coche: de qué radar, si era de exceso, qué título y cuándo se cambió. */
data class EstadoAvisoCoche(val radarId: String, val exceso: Boolean, val titulo: String, val cambiadoMs: Long)

/**
 * - Radar nuevo: MOSTRAR (aparece con aviso emergente).
 * - El mismo radar pasa a exceso: MOSTRAR otra vez (en rojo).
 * - Cambia el título (la distancia por centenas, la media): ACTUALIZAR en silencio, como mucho cada [cadaMs].
 * - Sin aviso: QUITAR.
 */
fun decidirAvisoCoche(
    anterior: EstadoAvisoCoche?,
    aviso: AvisoActivo?,
    titulo: String,
    ahoraMs: Long,
    cadaMs: Long = 5000,
): Pair<AccionAvisoCoche, EstadoAvisoCoche?> {
    if (aviso == null) return (if (anterior != null) AccionAvisoCoche.QUITAR else AccionAvisoCoche.NADA) to null
    val nuevo = EstadoAvisoCoche(aviso.radar.id, aviso.exceso, titulo, ahoraMs)
    return when {
        anterior == null || anterior.radarId != aviso.radar.id -> AccionAvisoCoche.MOSTRAR to nuevo
        aviso.exceso && !anterior.exceso -> AccionAvisoCoche.MOSTRAR to nuevo
        titulo != anterior.titulo && ahoraMs - anterior.cambiadoMs >= cadaMs -> AccionAvisoCoche.ACTUALIZAR to nuevo
        // Sin cambios que enseñar: se conserva el estado (también si vuelve a ir por debajo del límite).
        else -> AccionAvisoCoche.NADA to anterior.copy(exceso = anterior.exceso && aviso.exceso)
    }
}
