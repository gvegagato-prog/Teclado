package app.teclado

/** Colores del teclado. Claro y oscuro, en la línea de los teclados de iOS/iPadOS. */
class KbTheme(val dark: Boolean) {
    val bg = if (dark) 0xFF1E1E20.toInt() else 0xFFD3D6DC.toInt()
    val letterKey = if (dark) 0xFF58585C.toInt() else 0xFFFFFFFF.toInt()
    val funcKey = if (dark) 0xFF38383B.toInt() else 0xFFAEB3BD.toInt()
    val letterKeyPressed = funcKey
    val funcKeyPressed = letterKey
    val shadow = if (dark) 0xFF0A0A0B.toInt() else 0xFF8E9299.toInt()
    val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val hint = if (dark) 0xFFA9A9AE.toInt() else 0xFF7A7D84.toInt()
    val subtle = if (dark) 0xFF9A9AA0.toInt() else 0xFF6B6E75.toInt()
    val accent = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val onAccent = 0xFFFFFFFF.toInt()
    val divider = if (dark) 0x33FFFFFF else 0x22000000
    val popup = if (dark) 0xFF58585C.toInt() else 0xFFFFFFFF.toInt()
}
