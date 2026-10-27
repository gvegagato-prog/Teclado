# Teclado EN/ES

Teclado para Android, rápido y limpio, con corrección en castellano e inglés y detección automática del idioma.

## Instalar
1. En GitHub, pestaña **Actions** → último "Build APK" → artefacto `teclado-debug-apk` → descarga `app-debug.apk`
   (el mismo workflow sube también `capturas-teclado`, con imágenes del teclado).
   O compila: `./gradlew assembleDebug` (JDK 17+, Android SDK 36) → `app/build/outputs/apk/debug/app-debug.apk`.
2. Instala el APK (permite "instalar apps desconocidas" para el navegador/gestor de archivos).
3. Abre la app **Teclado**: *Activa el teclado* → *Cámbiate a él*.

El APK está firmado con la clave de depuración incluida en el repo (`app/debug.keystore`), para que las
actualizaciones se instalen encima. Sirve para uso personal, no para publicar en una tienda.

## Funciones
- Corrección en inglés y castellano; detecta en cuál escribes y corrige con ese diccionario.
  Una palabra válida en el otro idioma no se toca. Borrar justo después revierte la corrección.
- Deslizar una tecla hacia abajo escribe el número/símbolo pequeño (como en iPad).
- Pulsación larga: tildes y variantes (á é í ó ú ü ñ ç…); en `.` también `? ! ¿ ¡ …`.
- Atajos de texto (ajustes de la app): p. ej. `@@@` + espacio → tu correo.
- Arrastrar sobre la barra espaciadora mueve el cursor. Doble espacio escribe un punto.
- Siempre bilingüe: no hay tecla de idioma; la barra espaciadora muestra el idioma detectado.
- Mantener Shift pulsado muestra y escribe los símbolos pequeños de cada tecla (con otro dedo).
- 7 temas (sistema, claro, arena, rosa, oscuro, medianoche, negro), altura y margen inferior ajustables, vibración opcional.

## Desarrollo
- `./gradlew testDebugUnitTest` — pruebas del motor, del flujo de edición y generación de capturas (`app/build/previews`).
- `tools/gen_dicts.py` regenera los diccionarios (ver `NOTICE.md` para licencias).
