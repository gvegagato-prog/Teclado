package app.teclado

enum class KeyType { CHAR, SHIFT, DELETE, SYM1, SYM2, ABC, SPACE, ENTER, SPACER }
enum class Layer { LETTERS, SYM1, SYM2, NUMPAD }
enum class PadKind { NUMBER, PHONE, DATETIME }

/**
 * Una tecla. [hint] es lo que se escribe al deslizar hacia abajo (como en el iPad) y se ve pequeño
 * en la parte alta de la tecla. [alts] son las variantes de la pulsación larga.
 */
class KeyDef(
    val type: KeyType,
    val label: String = "",
    val hint: String? = null,
    val weight: Float = 1f,
    val alts: List<String> = emptyList(),
)

object Layouts {
    // Variantes (tildes primero las del castellano)
    private val ALTS = mapOf(
        "a" to listOf("á", "à", "â", "ä", "ã", "å", "æ"),
        "e" to listOf("é", "è", "ê", "ë", "ē"),
        "i" to listOf("í", "ì", "î", "ï"),
        "o" to listOf("ó", "ò", "ô", "ö", "õ", "œ"),
        "u" to listOf("ú", "ü", "ù", "û"),
        "n" to listOf("ñ"),
        "c" to listOf("ç"),
        "y" to listOf("ý", "ÿ"),
        "s" to listOf("ß", "ś", "š"),
        "z" to listOf("ž"),
        "$" to listOf("€", "£", "¥", "¢"),
        "-" to listOf("–", "—", "•"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "'" to listOf("‘", "’", "‹", "›"),
        "&" to listOf("§"),
        "0" to listOf("°"),
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        "." to listOf("?", "!", "¿", "¡", "…", ":", ";"),
        "," to listOf(";", ":", "!", "¡"),
    )

    private fun c(label: String, hint: String? = null, weight: Float = 1f) =
        KeyDef(KeyType.CHAR, label, hint, weight, ALTS[label] ?: emptyList())

    private val ROW1 = "qwertyuiop"
    private val ROW1_HINTS = "1234567890"
    private val ROW2 = "asdfghjklñ"
    private val ROW2_HINTS = listOf("@", "#", "$", "&", "*", "(", ")", "'", "\"", "¿")
    private val ROW3 = "zxcvbnm"
    private val ROW3_HINTS = listOf("%", "-", "+", "=", "/", ";", ":")

    fun rows(layer: Layer): List<List<KeyDef>> = when (layer) {
        Layer.LETTERS -> listOf(
            ROW1.mapIndexed { i, ch -> c(ch.toString(), ROW1_HINTS[i].toString()) },
            ROW2.mapIndexed { i, ch -> c(ch.toString(), ROW2_HINTS[i]) },
            listOf(KeyDef(KeyType.SHIFT, weight = 1.5f)) +
                ROW3.mapIndexed { i, ch -> c(ch.toString(), ROW3_HINTS[i]) } +
                KeyDef(KeyType.DELETE, weight = 1.5f),
            bottom(KeyType.SYM1, "123", spaceW = 4.6f),
        )
        Layer.SYM1 -> listOf(
            "1234567890".map { c(it.toString()) },
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").map { c(it) },
            listOf(KeyDef(KeyType.SYM2, "#+=", weight = 1.5f)) +
                listOf(".", ",", "?", "!", "'").map { c(it, weight = 1.4f) } +
                KeyDef(KeyType.DELETE, weight = 1.5f),
            bottom(KeyType.ABC, "ABC", spaceW = 6.6f, punctuation = false),
        )
        Layer.NUMPAD -> numpad(PadKind.NUMBER, signed = false, decimal = false)
        Layer.SYM2 -> listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { c(it) },
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { c(it) },
            listOf(KeyDef(KeyType.SYM1, "123", weight = 1.5f)) +
                listOf(".", ",", "?", "!", "'").map { c(it, weight = 1.4f) } +
                KeyDef(KeyType.DELETE, weight = 1.5f),
            bottom(KeyType.ABC, "ABC", spaceW = 6.6f, punctuation = false),
        )
    }

    private fun bottom(layerKey: KeyType, label: String, spaceW: Float, punctuation: Boolean = true) =
        buildList {
            add(KeyDef(layerKey, label, weight = 1.6f))
            if (punctuation) add(c(",", "!"))
            add(KeyDef(KeyType.SPACE, weight = spaceW))
            if (punctuation) add(c(".", "?"))
            add(KeyDef(KeyType.ENTER, weight = 1.8f))
        }

    /**
     * Teclado numérico para campos de número, teléfono y fecha/hora. 4 columnas:
     * la última lleva borrar y las teclas que el campo permite (signo, decimales...).
     */
    fun numpad(kind: PadKind, signed: Boolean, decimal: Boolean): List<List<KeyDef>> {
        fun k(l: String, alts: List<String> = emptyList()) = KeyDef(KeyType.CHAR, l, alts = alts)
        val gap = KeyDef(KeyType.SPACER)
        val x: List<KeyDef> = when (kind) {
            PadKind.NUMBER -> listOf(
                if (signed) k("-") else gap, if (decimal) k(".") else gap, if (decimal) k(",") else gap, gap,
            )
            PadKind.PHONE -> listOf(k("+"), k("-"), k("*"), k("#"))
            PadKind.DATETIME -> listOf(k("/"), k(":"), k("-"), k("."))
        }
        val zero = k("0", if (kind == PadKind.PHONE) listOf("+") else emptyList())
        return listOf(
            listOf(k("1"), k("2"), k("3"), KeyDef(KeyType.DELETE)),
            listOf(k("4"), k("5"), k("6"), x[0]),
            listOf(k("7"), k("8"), k("9"), x[1]),
            listOf(x[2], zero, x[3], KeyDef(KeyType.ENTER)),
        )
    }

    /**
     * Teclado dividido: mitad izquierda / mitad derecha de cada fila, con el mismo peso total (5) para que
     * el hueco central quede alineado. La barra espaciadora se duplica (cada pulgar tiene la suya).
     */
    fun splitRows(layer: Layer): List<Pair<List<KeyDef>, List<KeyDef>>> {
        fun s(l: List<String>) = l.map { c(it) }
        fun space(w: Float) = KeyDef(KeyType.SPACE, weight = w)
        return when (layer) {
            Layer.LETTERS -> listOf(
                Pair(ROW1.take(5).mapIndexed { i, ch -> c(ch.toString(), ROW1_HINTS[i].toString()) },
                    ROW1.drop(5).mapIndexed { i, ch -> c(ch.toString(), ROW1_HINTS[i + 5].toString()) }),
                Pair(ROW2.take(5).mapIndexed { i, ch -> c(ch.toString(), ROW2_HINTS[i]) },
                    ROW2.drop(5).mapIndexed { i, ch -> c(ch.toString(), ROW2_HINTS[i + 5]) }),
                Pair(listOf(KeyDef(KeyType.SHIFT, weight = 1f)) + ROW3.take(4).mapIndexed { i, ch -> c(ch.toString(), ROW3_HINTS[i]) },
                    ROW3.drop(4).mapIndexed { i, ch -> c(ch.toString(), ROW3_HINTS[i + 4]) } + KeyDef(KeyType.DELETE, weight = 2f)),
                Pair(listOf(KeyDef(KeyType.SYM1, "123", weight = 1.4f), c(",", "!"), space(2.6f)),
                    listOf(space(2.6f), c(".", "?"), KeyDef(KeyType.ENTER, weight = 1.4f))),
            )
            Layer.SYM1, Layer.SYM2 -> {
                val r = rows(layer)
                val toggle = if (layer == Layer.SYM1) KeyDef(KeyType.SYM2, "#+=", weight = 1.4f) else KeyDef(KeyType.SYM1, "123", weight = 1.4f)
                listOf(
                    Pair(r[0].take(5), r[0].drop(5)),
                    Pair(r[1].take(5), r[1].drop(5)),
                    Pair(listOf(toggle) + s(listOf(".", ",", "?")).map { KeyDef(KeyType.CHAR, it.label, weight = 1.2f, alts = it.alts) },
                        s(listOf("!", "'")).map { KeyDef(KeyType.CHAR, it.label, weight = 1.2f, alts = it.alts) } + KeyDef(KeyType.DELETE, weight = 2.6f)),
                    Pair(listOf(KeyDef(KeyType.ABC, "ABC", weight = 1.4f), space(3.6f)),
                        listOf(space(3.6f), KeyDef(KeyType.ENTER, weight = 1.4f))),
                )
            }
            Layer.NUMPAD -> emptyList()
        }
    }
}
