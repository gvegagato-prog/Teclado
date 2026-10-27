package app.teclado

enum class KeyType { CHAR, SHIFT, DELETE, SYM1, SYM2, ABC, SPACE, ENTER, SPACER }
enum class Layer { LETTERS, SYM1, SYM2 }

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
}
