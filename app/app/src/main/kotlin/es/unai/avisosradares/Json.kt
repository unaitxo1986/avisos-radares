package es.unai.avisosradares

/**
 * Lector de JSON mínimo, en Kotlin puro (sin Android), para poder probar la lectura de `radares.json`
 * en las pruebas automáticas: `org.json` de Android no funciona fuera del móvil.
 *
 * Devuelve: `Map<String, Any?>` para objetos, `List<Any?>` para listas, `String`, `Double`, `Boolean` o `null`.
 */
fun leerJson(texto: String): Any? {
    val lector = LectorJson(texto)
    lector.espacios()
    val valor = lector.valor()
    lector.espacios()
    if (lector.pos != texto.length) throw IllegalArgumentException("JSON: texto de más en la posición ${lector.pos}")
    return valor
}

private class LectorJson(private val s: String) {
    var pos = 0

    fun espacios() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
    }

    private fun error(texto: String): Nothing = throw IllegalArgumentException("JSON: $texto en la posición $pos")

    fun valor(): Any? {
        if (pos >= s.length) error("fin inesperado")
        return when (val c = s[pos]) {
            '{' -> objeto()
            '[' -> lista()
            '"' -> cadena()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') numero() else error("carácter inesperado '$c'")
        }
    }

    private fun literal(palabra: String, valor: Any?): Any? {
        if (!s.startsWith(palabra, pos)) error("se esperaba $palabra")
        pos += palabra.length
        return valor
    }

    private fun objeto(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        pos++
        espacios()
        if (pos < s.length && s[pos] == '}') { pos++; return m }
        while (true) {
            espacios()
            if (pos >= s.length || s[pos] != '"') error("se esperaba una clave")
            val clave = cadena()
            espacios()
            if (pos >= s.length || s[pos] != ':') error("se esperaba ':'")
            pos++
            espacios()
            m[clave] = valor()
            espacios()
            if (pos >= s.length) error("objeto sin cerrar")
            if (s[pos] == ',') { pos++; continue }
            if (s[pos] == '}') { pos++; return m }
            error("se esperaba ',' o '}'")
        }
    }

    private fun lista(): List<Any?> {
        val l = ArrayList<Any?>()
        pos++
        espacios()
        if (pos < s.length && s[pos] == ']') { pos++; return l }
        while (true) {
            espacios()
            l.add(valor())
            espacios()
            if (pos >= s.length) error("lista sin cerrar")
            if (s[pos] == ',') { pos++; continue }
            if (s[pos] == ']') { pos++; return l }
            error("se esperaba ',' o ']'")
        }
    }

    private fun cadena(): String {
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) error("cadena sin cerrar")
            val c = s[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= s.length) error("escape sin terminar")
                    when (val e = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) error("escape \\u incompleto")
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("escape desconocido \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun numero(): Double {
        val inicio = pos
        if (s[pos] == '-') pos++
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
        return s.substring(inicio, pos).toDoubleOrNull() ?: error("número mal formado")
    }
}

/** Ayudas para leer valores de un objeto JSON sin repetir conversiones. */
fun Map<String, Any?>.texto(clave: String): String? = this[clave] as? String
fun Map<String, Any?>.numero(clave: String): Double? = (this[clave] as? Double)
fun Map<String, Any?>.entero(clave: String): Int? = (this[clave] as? Double)?.toInt()
fun Map<String, Any?>.logico(clave: String): Boolean? = this[clave] as? Boolean
