# Pera

Funciones al estilo Apple para tu móvil Android, tu tablet y tu Mac:

1. **Datos del móvil sin sacarlo del bolsillo** (como el «Compartir Internet instantáneo» del iPhone).
2. **Portapapeles compartido**: lo último que copias en un dispositivo se puede pegar en los demás
   (como el «Portapapeles universal»).
3. **Redes Wi-Fi compartidas**: la red a la que se une un dispositivo de la familia la conocen los demás
   (como las contraseñas Wi-Fi de iCloud).

Todo viaja por **Bluetooth LE cifrado** entre tus dispositivos emparejados. No hay servidores ni cuentas.

## 1. Datos del móvil

- **Mac**: icono de Pera en la barra de menús, junto al Wi-Fi → «Conectar a los datos del móvil».
- **Tablet Android**: botón «Datos del móvil» en el panel de ajustes rápidos.
- **Móvil**: Pera queda esperando por Bluetooth y, cuando se lo piden, enciende el hotspot.

### Cómo funciona

1. La tablet o el Mac buscan el móvil por **Bluetooth LE** (gasta muy poca batería).
2. Le envían la orden «enciende el hotspot». Solo la aceptan dispositivos **emparejados**: la primera vez
   hay que confirmar el emparejamiento en los dos.
3. El móvil enciende el hotspot mediante **Shizuku** y devuelve el nombre y la contraseña de la red.
4. La tablet o el Mac se unen a esa red.

#### ¿Por qué Shizuku?

Android no deja que una app normal encienda el hotspot: hace falta el permiso `TETHER_PRIVILEGED`, que
solo tienen las apps del sistema. Shizuku da a Pera los permisos de ADB (los de un ordenador conectado
por cable), que sí pueden encenderlo. No hace falta root.

**Que no tengas que activarlo cada vez que reinicias:** usa el fork de Shizuku de
[thedjchi](https://github.com/thedjchi/Shizuku/releases), que tiene **«Iniciar al arrancar»** sin root.
Condición: tras reiniciar, el móvil tiene que conectarse a **alguna red Wi-Fi** una vez (la de casa
vale), porque Shizuku usa la depuración inalámbrica. Con el **modo TCP** del fork basta con esa primera
vez por reinicio. Si reinicias fuera de cualquier Wi-Fi, Pera no podrá encender el hotspot hasta que
te conectes a una.

## Instalación

### En el móvil (el que tiene los datos)

1. Desinstala el Shizuku original si lo tienes (el fork no se instala encima).
2. Instala [Shizuku (fork de thedjchi)](https://github.com/thedjchi/Shizuku/releases) y sigue su
   [guía](https://github.com/thedjchi/Shizuku/wiki/Setup): opciones de desarrollador → depuración
   inalámbrica → emparejar → iniciar. Activa **Iniciar al arrancar** en sus ajustes.
3. Instala `Pera.apk`, ábrelo y activa **«Compartir los datos de este móvil»**.
4. Pulsa **«Dar permiso a Pera»** (permiso de Shizuku) y **«Permitir que Pera funcione en segundo plano»**.
5. Pulsa **«Probar: encender»**. Si el hotspot se enciende, el móvil ya está listo.
6. Samsung: en *Ajustes › Batería › Límites de uso en segundo plano*, añade Pera y Shizuku a la lista
   de apps que **nunca se suspenden** (el nombre exacto cambia según la versión de One UI).

### En la tablet (OPPO u otro Android 12+)

1. Instala el mismo `Pera.apk` y ábrelo.
2. Pulsa **«Conectar»** y acepta el permiso de dispositivos cercanos y el emparejamiento.
3. La primera vez, Android pregunta si permites las **redes sugeridas** por Pera: acepta.
4. Pulsa **«Añadir botón al panel de ajustes rápidos»** (o añádelo a mano desde el lápiz del panel).
5. OPPO/ColorOS: permite a Pera la **actividad en segundo plano** en los ajustes de batería de la app.

### En el Mac (macOS 13 o posterior)

1. Descarga `Pera-mac.zip` (ver *Compilar*), descomprímelo y mueve **Pera.app** a *Aplicaciones*.
2. La app no está firmada por Apple. La primera vez: clic derecho en Pera.app → **Abrir** → Abrir
   (o *Ajustes del Sistema › Privacidad y seguridad › Abrir igualmente*).
3. Aparece el icono en la barra de menús. Elige **«Conectar a los datos del móvil»** y acepta el permiso
   de Bluetooth y el emparejamiento con el móvil.
4. Activa **«Abrir al iniciar sesión»** en el mismo menú.

## 2 y 3. Sincronización de la familia

El **móvil es el centro**: la tablet y el Mac se mantienen conectados a él por Bluetooth LE (gasta poco y
se reconectan solos cuando vuelves a estar cerca). Lo que llega de uno, el móvil lo reenvía a los demás.
Si el móvil no está, la tablet y el Mac no se sincronizan entre ellos.

### Portapapeles

| Dispositivo | Detectar lo que copias | Recibir lo copiado en otro |
|---|---|---|
| Android con **Teclado** como teclado activo | ✓ (Teclado se lo pasa a Pera; Android 14+) | ✓ |
| Android con **Shizuku** | ✓ (Android avisa «Shell ha pegado…»; se quita desactivando «Mostrar acceso al portapapeles» en los ajustes de privacidad) | ✓ |
| Android sin ninguno | ✗: usa **Compartir → «Enviar a la familia Pera»** | ✓ |
| **Mac** | ✓ | ✓ |

- No se sincroniza lo que los gestores de contraseñas marcan como sensible (Android y Mac).
- Solo texto, hasta 32.000 caracteres. Imágenes y archivos, no.
- Gana lo más reciente (por hora de copia).

### Redes Wi-Fi

**La Wi-Fi del móvil, en todos lados**: cuando el móvil se conecta a una Wi-Fi, se la pasa al momento a
la tablet y al Mac, aunque no la conocieran:
- **Tablet con Shizuku**: la guarda como una red normal y se conecta.
- **Tablet sin Shizuku**: la añade como red sugerida (Android se conecta solo si no tiene otra Wi-Fi) y,
  si en ese momento no tiene Wi-Fi, aparece un aviso «Conectar a «…», la Wi-Fi de tu móvil»: un toque y
  queda guardada de verdad (pantalla del sistema de Android 11+, hasta 5 redes por vez).
- **Mac**: si no está conectado a ninguna Wi-Fi, se une solo (lo reintenta cada minuto).

Para esto el **móvil necesita Shizuku** (Android no deja a una app normal leer la contraseña de la red).
En la app, la línea «Redes:» dice qué ha podido leer el móvil, o el error si algo falla.

Además se sincronizan **todas** las redes guardadas:

| Dispositivo | Compartir sus redes | Recibir las de la familia |
|---|---|---|
| Android con **Shizuku** | ✓ (lee las guardadas, con contraseña) | ✓ como redes guardadas normales |
| Android sin Shizuku | ✗ (Android no deja leerlas) | ✓ como «redes sugeridas» (se conecta solo; la primera vez pide permiso) |
| **Mac** | ✗ (macOS pide la contraseña del llavero por cada red) | ✓ menú «Añadir N redes de la familia…», con una contraseña de administrador |

- Solo redes personales: WPA2, WPA3 o abiertas. Las de empresa (802.1X), WEP y ocultas no.
- En el Mac, las WPA3 no se pueden añadir: `networksetup` no admite ese tipo. Elígelas a mano una vez.

### Activarla

- **Móvil**: nada más; viene con «Compartir los datos de este móvil». Interruptores «Sincronizar lo
  copiado» y «Sincronizar redes Wi-Fi».
- **Tablet**: activa **«Mantener esta tablet conectada al móvil»**. Para enviar lo que copias, usa
  Teclado como teclado o instala también Shizuku.
- **Mac**: viene activada; los interruptores están en el menú de Pera.

## Funciona escondida

Pera no necesita estar abierta. Una vez activada:
- **No aparece en recientes**, así que no se puede cerrar por error al limpiar apps.
- **Se recupera sola** si Android la para: la reactivan un trabajo periódico del sistema (cada 15 min),
  tocar el botón del panel, cada copia que llega de Teclado, el reinicio del dispositivo o abrir la app.
  Para que Android se lo permita hay que pulsar **«Permitir que Pera funcione en segundo plano»**
  (quitar la optimización de batería): es la excepción que Android documenta para esto.
- **Aviso permanente mínimo**: Android obliga a que un servicio permanente tenga notificación; la de
  Pera va en el canal «Pera en segundo plano», de importancia mínima (sin icono en la barra de estado,
  plegada al fondo). Solo avisa de verdad si algo falla.
- En el **Mac**, la primera vez queda activada «Abrir al iniciar sesión».

Lo único que no se puede saltar es **«Forzar detención»** (en Ajustes, o la limpieza agresiva de
algunos fabricantes): Android cancela todo hasta que se vuelve a usar Pera (tocar el botón del panel
basta). En **OPPO/ColorOS**, además: ajustes de la app › batería › permitir **actividad en segundo plano**
e **inicio automático**.

### El botón del panel
- Cambia al instante al tocarlo y muestra «Conectando…» o «Apagando…» mientras trabaja.
- Si la tablet tiene activada la sincronización, el botón refleja el **estado real** del hotspot del
  móvil (también si lo enciende o apaga el Mac).
- Si al apagar el móvil no responde, el botón queda apagado igualmente (si no está cerca, tampoco estás
  usando sus datos).
- En el propio móvil, el botón enciende y apaga su hotspot directamente.

## Limitaciones (sin adornos)

- **El icono no va dentro del desplegable Wi-Fi del Mac** ni del panel de Wi-Fi de Android: Apple y
  Google no dejan que otras apps añadan cosas ahí. Pera pone su propio icono al lado.
- **Unirse al Wi-Fi en el Mac**: Pera usa `networksetup`. Apple ha recortado herramientas parecidas en
  macOS 15 y no he podido comprobar que esta siga funcionando en macOS 15 y 26. Si no se conecta solo,
  elige la red del móvil **una vez** en el menú Wi-Fi: a partir de ahí macOS se une solo cuando aparece.
- **Sin probar en tus dispositivos**: el código compila y sus pruebas pasan, pero el Bluetooth y el
  hotspot solo se pueden probar en hardware real. El método para encender el hotspot es el de
  [Delta](https://github.com/supershadoe/delta), pensada para Android «tipo Pixel»; no hay garantía de
  que One UI (Samsung) no lo bloquee. «Probar: encender» lo comprueba en un momento.
- **Ahorro de batería**: Samsung y ColorOS cierran apps en segundo plano. Si Pera deja de responder,
  revisa los pasos de batería de arriba.
- **Solo Android 12+ y macOS 13+.** iPhone, Windows y Linux no están cubiertos.
- **Portapapeles en macOS**: Apple está introduciendo un aviso cuando una app lee el portapapeles por su
  cuenta. En macOS 26 está en vista previa y desactivado por defecto según los informes que he
  encontrado; si en tu versión aparece, permite a Pera «Pegar siempre» en Privacidad y seguridad.
- **Shizuku y redes/portapapeles**: los métodos internos del sistema que usa Pera los he comprobado en el
  código fuente de Android (AOSP). Samsung y OPPO modifican partes del sistema y podrían cambiarlos.
- **Teclado → Pera** necesita Android 14 o posterior (para que Pera sepa con certeza que la copia la
  envía Teclado y no otra app).
- La operadora puede bloquear el hotspot en algunas tarifas; Pera no puede saltárselo.

## Firma
Teclado y Pera se firman con la misma clave (`keystore.properties` en la raíz de cada proyecto, fuera
del repo). Guarda el archivo `.jks` y su contraseña: sin ellos, una versión nueva no se instala encima
de la anterior y hay que desinstalar (perdiendo los ajustes).

## Compilar

- **Android**: `cd pera/android && ./gradlew assembleRelease` (JDK 17+, Android SDK 36). La firma se lee
  de `pera/android/keystore.properties` (fuera del repo); sin él, sale sin firmar.
- **Mac**: `cd pera/mac && ./build-app.sh` (en un Mac con Xcode). Genera `build/Pera.app` y `build/Pera-mac.zip`.
- **GitHub Actions** (`.github/workflows/pera.yml`) compila los dos y deja el APK y el zip del Mac
  como artefactos descargables de cada ejecución.

## Protocolo

Servicio GATT `b49ae33f-05ff-43dc-832e-1e1cbc315a8c`. El anuncio principal lleva, como datos de fabricante
(0xFFFF), 4 bytes que identifican al móvil: cada cliente se queda con el suyo. (Pera 0.2-0.3 los ponía en la
respuesta al escaneo, que en escaneos de bajo consumo puede no llegar; los clientes aún la aceptan.)
Características (todas cifradas):

| Característica | UUID | Uso |
|---|---|---|
| Orden | `c28cdba2-…-d5caf1d9b653` | escribir 1 byte: `1` encender, `0` apagar |
| Estado | `e46e38d3-…-d3a9740d2c89` | leer/notificar: 0 apagado, 1 encendiéndose, 2 encendido, 3 apagándose, 4 error, 5 sin Shizuku |
| Wi-Fi | `5e1e2505-…-36b2692ef8bb` | leer: `{"ssid":…, "pass":…, "sec":"wpa2"\|"wpa3"\|"open"}` |
| Mensajes de entrada | `4af7edca-…-e555361dd8ee` | escribir fragmentos (cliente → móvil) |
| Mensajes de salida | `f049b244-…-8c89ae98f59b` | notificar fragmentos (móvil → cliente) |

Fragmento: `[id u16][índice u16][total u16][datos]`. Mensajes JSON: `hello` (al conectar), `clip`
(texto copiado: id, hora, origen, texto), `nets` (redes) y `here` (red actual del móvil). Una versión antigua ignora los tipos que no
conoce, así que las próximas funciones de Pera pueden añadir mensajes nuevos sin romper nada.
