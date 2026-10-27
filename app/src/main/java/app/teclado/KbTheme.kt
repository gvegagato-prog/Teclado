package app.teclado

/** Colores del teclado. */
class KbTheme(
    val dark: Boolean,
    val bg: Int,
    val letterKey: Int,
    val funcKey: Int,
    val shadow: Int,
    val text: Int,
    val hint: Int,
    val subtle: Int,
    val accent: Int,
    val popup: Int,
) {
    val letterKeyPressed get() = funcKey
    val funcKeyPressed get() = letterKey
    val onAccent = 0xFFFFFFFF.toInt()
    val divider get() = if (dark) 0x33FFFFFF else 0x22000000

    companion object {
        val LIGHT = KbTheme(false, 0xFFD0D3D9.toInt(), 0xFFF3F4F6.toInt(), 0xFFABB0BA.toInt(), 0xFF8A8E96.toInt(),
            0xFF111114.toInt(), 0xFF7A7D84.toInt(), 0xFF6B6E75.toInt(), 0xFF007AFF.toInt(), 0xFFF8F8FA.toInt())
        val SAND = KbTheme(false, 0xFFDCD5C7.toInt(), 0xFFF0EBDF.toInt(), 0xFFB9B0A0.toInt(), 0xFF8F8777.toInt(),
            0xFF2B2620.toInt(), 0xFF857D6E.toInt(), 0xFF6F685B.toInt(), 0xFFC2662D.toInt(), 0xFFF5F1E8.toInt())
        val ROSE = KbTheme(false, 0xFFE4D2D7.toInt(), 0xFFF6EDEF.toInt(), 0xFFC8A9B2.toInt(), 0xFF9C7E87.toInt(),
            0xFF3A2328.toInt(), 0xFF98767F.toInt(), 0xFF7F626A.toInt(), 0xFFD6336C.toInt(), 0xFFF9F2F4.toInt())
        val DARK = KbTheme(true, 0xFF1E1E20.toInt(), 0xFF58585C.toInt(), 0xFF38383B.toInt(), 0xFF0A0A0B.toInt(),
            0xFFFFFFFF.toInt(), 0xFFA9A9AE.toInt(), 0xFF9A9AA0.toInt(), 0xFF0A84FF.toInt(), 0xFF76767B.toInt())
        val MIDNIGHT = KbTheme(true, 0xFF0F1626.toInt(), 0xFF2B3652.toInt(), 0xFF1B2540.toInt(), 0xFF05080F.toInt(),
            0xFFFFFFFF.toInt(), 0xFF8D9BBF.toInt(), 0xFF8794B5.toInt(), 0xFF5AA2FF.toInt(), 0xFF445273.toInt())
        val BLACK = KbTheme(true, 0xFF000000.toInt(), 0xFF2C2C2E.toInt(), 0xFF161618.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFF8E8E93.toInt(), 0xFF8E8E93.toInt(), 0xFF0A84FF.toInt(), 0xFF48484A.toInt())

        val NAMES = listOf("system", "light", "sand", "rose", "dark", "midnight", "black")

        fun byName(name: String, systemDark: Boolean): KbTheme = when (name) {
            "light" -> LIGHT
            "sand" -> SAND
            "rose" -> ROSE
            "dark" -> DARK
            "midnight" -> MIDNIGHT
            "black" -> BLACK
            else -> if (systemDark) DARK else LIGHT
        }
    }
}
