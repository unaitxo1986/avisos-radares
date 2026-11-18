package es.unai.avisosradares

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Recorridos simulados para probar los avisos sin conducir (fase 4.4): en las pruebas automáticas y con el
 * botón "Simular paso por un radar" de la app. Código puro.
 *
 * Construye un recorrido que sigue [linea] (puntos `[lat, lon]`), prolongado [antesM] metros antes del
 * primer punto y [despuesM] después del último en la dirección de sus segmentos extremos, y devuelve una
 * posición cada [pasoMs] a [velocidadKmh]. Con [alReves] lo recorre en sentido contrario. [ruidoM] desplaza
 * cada posición al azar (con semilla fija) para imitar el error del GPS.
 */
fun recorridoSimulado(
    linea: List<DoubleArray>,
    velocidadKmh: Double,
    antesM: Double = 300.0,
    despuesM: Double = 200.0,
    alReves: Boolean = false,
    ruidoM: Double = 0.0,
    semilla: Long = 1L,
    t0Ms: Long = 0L,
    pasoMs: Long = 1000L,
    desplazamientoLateralM: Double = 0.0,
): List<Posicion> {
    require(linea.size >= 2)
    val lat0 = linea.last()[0]
    val lon0 = linea.last()[1]
    val mPorGrado = RADIO_TIERRA_M * PI / 180
    val cosLat = cos(lat0 * PI / 180)
    fun xy(p: DoubleArray) = doubleArrayOf((p[1] - lon0) * mPorGrado * cosLat, (p[0] - lat0) * mPorGrado)

    val puntos = linea.map { xy(it) }.toMutableList()
    fun prolonga(a: DoubleArray, b: DoubleArray, metros: Double): DoubleArray {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val l = hypot(dx, dy)
        return if (l == 0.0) b else doubleArrayOf(b[0] + dx / l * metros, b[1] + dy / l * metros)
    }
    if (antesM > 0) puntos.add(0, prolonga(puntos[1], puntos[0], antesM))
    if (despuesM > 0) puntos.add(prolonga(puntos[puntos.size - 2], puntos.last(), despuesM))
    if (alReves) puntos.reverse()

    val paso = velocidadKmh / 3.6 * pasoMs / 1000.0
    val azar = Random(semilla)
    val salida = ArrayList<Posicion>()
    var t = t0Ms
    var avance = 0.0
    var i = 0
    var acumuladoSegmento = 0.0
    while (i < puntos.size - 1) {
        val a = puntos[i]
        val b = puntos[i + 1]
        val l = hypot(b[0] - a[0], b[1] - a[1])
        if (l == 0.0 || avance > acumuladoSegmento + l) {
            acumuladoSegmento += l
            i++
            continue
        }
        val f = (avance - acumuladoSegmento) / l
        // Desplazamiento lateral fijo (a la derecha de la marcha) para simular otra calzada o carretera paralela.
        val nx = (b[1] - a[1]) / l
        val ny = -(b[0] - a[0]) / l
        var x = a[0] + f * (b[0] - a[0]) + nx * desplazamientoLateralM
        var y = a[1] + f * (b[1] - a[1]) + ny * desplazamientoLateralM
        if (ruidoM > 0) {
            x += (azar.nextDouble() * 2 - 1) * ruidoM
            y += (azar.nextDouble() * 2 - 1) * ruidoM
        }
        salida += Posicion(
            tMs = t,
            lat = lat0 + y / mPorGrado,
            lon = lon0 + x / (mPorGrado * cosLat),
            precisionM = if (ruidoM > 0) ruidoM else 5.0,
            velocidadKmh = velocidadKmh,
        )
        t += pasoMs
        avance += paso
    }
    return salida
}

/** Recorrido recto que pasa por un punto: sirve para los radares sin línea (aviso por cercanía). */
fun recorridoRectoPor(lat: Double, lon: Double, rumboGrados: Double, velocidadKmh: Double, mitadM: Double = 800.0): List<Posicion> {
    val mPorGrado = RADIO_TIERRA_M * PI / 180
    val cosLat = cos(lat * PI / 180)
    val r = rumboGrados * PI / 180
    val ex = kotlin.math.sin(r)
    val ey = cos(r)
    val a = doubleArrayOf(lat - ey * mitadM / mPorGrado, lon - ex * mitadM / (mPorGrado * cosLat))
    val b = doubleArrayOf(lat + ey * mitadM / mPorGrado, lon + ex * mitadM / (mPorGrado * cosLat))
    return recorridoSimulado(listOf(a, b), velocidadKmh, antesM = 0.0, despuesM = 0.0)
}
