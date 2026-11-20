package es.unai.avisosradares

import kotlin.math.min

/**
 * Reparto del espacio de la pantalla de Android Auto (fase 7). Código puro, con pruebas.
 *
 * Android Auto da a la app un hueco que puede ser ancho (los 2/3 de la pantalla, con la música al lado) o estrecho
 * (la columna de la derecha, con otra app en grande): foto de Unai del 20/11, en la que la tarjeta del aviso tapaba
 * el logo y el texto se salía. Por eso:
 * - el logo tiene su franja arriba y nada se dibuja encima;
 * - con un aviso, en un hueco ancho la velocidad va a la izquierda y la tarjeta a la derecha; en uno estrecho, la
 *   velocidad arriba y la tarjeta debajo;
 * - dentro de la tarjeta, si es mucho más ancha que alta, la señal va a la izquierda y los textos a la derecha.
 */
data class Caja(val izq: Float, val arr: Float, val der: Float, val aba: Float) {
    val ancho get() = der - izq
    val alto get() = aba - arr
    val cx get() = (izq + der) / 2
    val cy get() = (arr + aba) / 2
    fun contiene(o: Caja) = o.izq >= izq - 0.5f && o.der <= der + 0.5f && o.arr >= arr - 0.5f && o.aba <= aba + 0.5f
    fun corta(o: Caja) = izq < o.der && o.izq < der && arr < o.aba && o.arr < aba
}

class DisposicionCoche(
    val zona: Caja,
    /** Franja del logo, arriba a la izquierda, y tamaño de su letra. */
    val marca: Caja,
    val letraMarca: Float,
    val velocidad: Caja,
    val tarjeta: Caja?,
    /** Velocidad arriba y tarjeta debajo (hueco estrecho). */
    val vertical: Boolean,
    /** Dentro de la tarjeta, señal a la izquierda y textos a la derecha. */
    val tarjetaEnFila: Boolean,
)

fun disponerCoche(ancho: Float, alto: Float, conAviso: Boolean, zonaVisible: Caja? = null): DisposicionCoche {
    val base = if (zonaVisible != null && zonaVisible.ancho > 0 && zonaVisible.alto > 0) zonaVisible else Caja(0f, 0f, ancho, alto)
    val m = min(base.ancho, base.alto) * 0.04f
    val a = Caja(base.izq + m, base.arr + m, base.der - m, base.aba - m)
    // El logo ("RADAR AVISOS" y las franjas) mide unas 8 veces el tamaño de su letra.
    val letra = minOf(a.alto * 0.05f, a.ancho / 9f, 28f)
    val marca = Caja(a.izq, a.arr, a.izq + letra * 8.5f, a.arr + letra * 1.2f)
    val cont = Caja(a.izq, marca.aba + letra * 0.6f, a.der, a.aba)
    if (!conAviso) return DisposicionCoche(a, marca, letra, cont, null, vertical = false, tarjetaEnFila = false)
    val hueco = m * 0.5f
    val vertical = cont.ancho < cont.alto * 1.15f
    val (vel, tarjeta) = if (vertical) {
        val corte = cont.arr + cont.alto * 0.36f
        Caja(cont.izq, cont.arr, cont.der, corte) to Caja(cont.izq, corte + hueco, cont.der, cont.aba)
    } else {
        val corte = cont.izq + cont.ancho * 0.42f
        Caja(cont.izq, cont.arr, corte, cont.aba) to Caja(corte + hueco, cont.arr, cont.der, cont.aba)
    }
    return DisposicionCoche(a, marca, letra, vel, tarjeta, vertical, tarjetaEnFila = tarjeta.ancho > tarjeta.alto * 1.3f)
}

/**
 * Parte un texto en dos líneas por el espacio más cercano a la mitad (para los textos que no caben en la tarjeta,
 * como "SEMÁFORO? SIN CONFIRMAR"). Sin espacios, una sola línea.
 */
fun partirEnDos(texto: String): List<String> {
    val espacios = texto.indices.filter { texto[it] == ' ' }
    if (espacios.isEmpty()) return listOf(texto)
    val corte = espacios.minByOrNull { kotlin.math.abs(it - texto.length / 2) }!!
    return listOf(texto.substring(0, corte), texto.substring(corte + 1))
}
