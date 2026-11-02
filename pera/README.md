# Pera

Funciones al estilo Apple para tu móvil Android, tu tablet y tu Mac. La primera:
**usar los datos del móvil desde la tablet o el Mac sin sacar el móvil del bolsillo**
(como el «Compartir Internet instantáneo» del iPhone).

- **Mac**: icono de Pera en la barra de menús, junto al Wi-Fi → «Conectar a los datos del móvil».
- **Tablet Android**: botón «Datos del móvil» en el panel de ajustes rápidos.
- **Móvil**: Pera queda esperando por Bluetooth y, cuando se lo piden, enciende el hotspot.

## Cómo funciona

1. La tablet o el Mac buscan el móvil por **Bluetooth LE** (gasta muy poca batería).
2. Le envían la orden «enciende el hotspot». Solo la aceptan dispositivos **emparejados**: la primera vez
   hay que confirmar el emparejamiento en los dos.
3. El móvil enciende el hotspot mediante **Shizuku** y devuelve el nombre y la contraseña de la red.
4. La tablet o el Mac se unen a esa red.

### ¿Por qué Shizuku?

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
- La operadora puede bloquear el hotspot en algunas tarifas; Pera no puede saltárselo.

## Compilar

- **Android**: `cd pera/android && ./gradlew assembleRelease` (JDK 17+, Android SDK 36). La firma se lee
  de `pera/android/keystore.properties` (fuera del repo); sin él, sale sin firmar.
- **Mac**: `cd pera/mac && ./build-app.sh` (en un Mac con Xcode). Genera `build/Pera.app` y `build/Pera-mac.zip`.
- **GitHub Actions** (`.github/workflows/pera.yml`) compila los dos y deja el APK y el zip del Mac
  como artefactos descargables de cada ejecución.

## Protocolo

Servicio GATT `b49ae33f-05ff-43dc-832e-1e1cbc315a8c`, todas las características cifradas:

| Característica | UUID | Uso |
|---|---|---|
| Orden | `c28cdba2-…-d5caf1d9b653` | escribir 1 byte: `1` encender, `0` apagar |
| Estado | `e46e38d3-…-d3a9740d2c89` | leer/notificar: 0 apagado, 1 encendiéndose, 2 encendido, 3 apagándose, 4 error, 5 sin Shizuku |
| Wi-Fi | `5e1e2505-…-36b2692ef8bb` | leer: `{"ssid":…, "pass":…, "sec":"wpa2"\|"wpa3"\|"open"}` |

Las próximas funciones de Pera pueden añadir características a este mismo servicio.
