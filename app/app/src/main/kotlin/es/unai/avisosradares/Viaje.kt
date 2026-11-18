package es.unai.avisosradares

import java.util.Locale

/**
 * Formato del registro de cada viaje (fase 4.4), un fichero CSV por viaje. Código puro, con prueba.
 *
 * ```
 * # cabecera (líneas que empiezan por #)
 * P,tiempo_ms,lat,lon,precision_m,velocidad_kmh      una por posición del GPS (vacío si no hay dato)
 * E,tiempo_ms,TIPO,id_radar,distancia_m,texto        una por aviso (AVISO, EXCESO, PASADO, CANCELADO)
 * ```
 */
object FormatoViaje {
    fun posicion(p: Posicion): String = String.format(
        Locale.ROOT, "P,%d,%.6f,%.6f,%s,%s",
        p.tMs, p.lat, p.lon,
        p.precisionM?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
        p.velocidadKmh?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
    )

    fun evento(tMs: Long, e: Evento): String =
        String.format(Locale.ROOT, "E,%d,%s,%s,%.0f,%s", tMs, e.tipo.name, e.radar.id, e.distanciaM, e.motivo.replace(',', ';'))

    /** Las posiciones de un viaje grabado, para reproducirlo. Ignora las líneas que no entiende. */
    fun posiciones(texto: String): List<Posicion> = texto.lineSequence().mapNotNull { linea ->
        val c = linea.split(',')
        if (c.size < 6 || c[0] != "P") return@mapNotNull null
        val t = c[1].toLongOrNull() ?: return@mapNotNull null
        val lat = c[2].toDoubleOrNull() ?: return@mapNotNull null
        val lon = c[3].toDoubleOrNull() ?: return@mapNotNull null
        Posicion(t, lat, lon, c[4].toDoubleOrNull(), c[5].toDoubleOrNull())
    }.toList()

    /** Solo las líneas de avisos, para copiarlas en una conversación sin todo el recorrido. */
    fun resumenEventos(texto: String): List<String> =
        texto.lineSequence().filter { it.startsWith("E,") || it.startsWith("#") }.toList()
}
