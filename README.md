# Teclado EN/ES

Teclado para Android, rápido y limpio, con corrección en castellano e inglés y detección automática del idioma.

## Instalar
El APK firmado (versión release) se entrega directamente en la conversación con Claude. Instálalo y abre la app
**Teclado**: *Activa el teclado* → *Cámbiate a él*.

Si tenías la versión de pruebas (0.1, firmada con la clave de depuración), desinstálala antes una vez:
la firma ha cambiado y Android no deja instalar encima.

### Compilar
`./gradlew assembleRelease` (JDK 17+, Android SDK 36). La firma se lee de `keystore.properties` en la raíz
(no está en el repo porque es público):
```
storeFile=/ruta/teclado-release.jks
storePassword=...
keyAlias=teclado
keyPassword=...
```
Sin ese fichero, la release se genera sin firmar (es lo que hace GitHub Actions para verificar que compila).

## Funciones
- Corrección en inglés y castellano con detección automática del idioma. Nunca corrige una palabra que exista
  en cualquiera de los dos idiomas (~280.000 palabras por idioma, conjugaciones raras, tecnicismos, coloquialismos,
  verbos con pronombres pegados como «escanéalo»). Tampoco toca nombres propios a mitad de frase ni palabras que
  ya hayas escrito antes en el mismo texto. Corrige errores de tecla vecina, letras cambiadas, tildes y ñ,
  y errores "por sonido" del castellano (nesecito → necesito).
- Tras corregir, la barra muestra «↶ original» para deshacer con un toque (o borra justo después).
  Las palabras que mantienes, o que escribes dos veces, se aprenden; se gestionan en los ajustes.
- Mantener borrar: primero letra a letra y, pasado un momento, palabra a palabra.
- Deslizar una tecla hacia abajo escribe el número/símbolo pequeño (como en iPad).
- Pulsación larga: tildes y variantes (á é í ó ú ü ñ ç…); en `.` también `? ! ¿ ¡ …`.
- Atajos de texto (ajustes de la app): p. ej. `@@@` + espacio → tu correo.
- Arrastrar sobre la barra espaciadora mueve el cursor. Doble espacio escribe un punto.
- Campos de número, teléfono y fecha: teclado numérico propio (con signo y decimales cuando el campo los admite).
- Teclado dividido (ajustes): en tablets (vertical y horizontal) las teclas se separan hacia los lados para escribir con ambas manos; hueco regulable.
- Barra superior con dos botones: **pegar** (lo último copiado) y **portapapeles** (historial de las últimas 50 copias de texto, sin caducidad; tocar pega, la ✕ borra una, «Borrar todo» pide confirmación). No se guardan los textos que los gestores de contraseñas marcan como sensibles.
- Siempre bilingüe: no hay tecla de idioma; la barra espaciadora muestra el idioma detectado.
- 7 temas (sistema, claro, arena, rosa, oscuro, medianoche, negro), altura y margen inferior ajustables, vibración opcional.

## Desarrollo
- `./gradlew testDebugUnitTest` — pruebas del motor, del flujo de edición y generación de capturas (`app/build/previews`).
- `tools/gen_dicts.py` regenera los diccionarios; usa Hunspell (LibreOffice) solo para validar al generarlos (ver `NOTICE.md`).
