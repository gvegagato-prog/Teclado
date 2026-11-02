@file:Suppress("DEPRECATION") // WifiConfiguration: obsoleta para apps, pero es la que usa el servicio del sistema

package app.pera.shell

import android.content.ClipData
import android.content.IOnPrimaryClipChangedListener
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import app.pera.Protocol
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Servicios del sistema llamados con la identidad «shell» de ADB a través de Shizuku.
 *
 * Comprobado en el código de AOSP: el paquete Shell tiene READ_CLIPBOARD_IN_BACKGROUND (puede leer el
 * portapapeles sin estar en primer plano), READ_WIFI_CREDENTIAL y NETWORK_SETTINGS (puede leer y guardar
 * redes Wi-Fi con su contraseña). Las firmas de los métodos cambian entre Android 12, 13 y 14+, así que
 * se buscan por nombre y se rellenan los argumentos según su tipo.
 */
object Shell {
    private const val TAG = "PeraShell"
    const val PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    private val userId get() = android.os.Process.myUid() / 100_000

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ---------- Portapapeles ----------

    private fun clipboard(): Any = asInterface("android.content.IClipboard", binder("clipboard"))

    /** Texto copiado ahora mismo, o null. Aviso: Android puede mostrar «Shell ha pegado…» (ver README). */
    fun clipboardText(): String? = runCatching {
        val clip = call(clipboard(), "getPrimaryClip") as ClipData? ?: return null
        if (clip.itemCount == 0) return null
        // Los gestores de contraseñas marcan lo copiado como sensible: no se sincroniza.
        if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return null
        clip.getItemAt(0).text?.toString()
    }.onFailure { Log.w(TAG, "getPrimaryClip", it) }.getOrNull()

    /** Avisa en cada copia. Devuelve una función para dejar de escuchar. */
    fun listenClipboard(onChange: () -> Unit): (() -> Unit)? = runCatching {
        val listener = object : IOnPrimaryClipChangedListener.Stub() {
            override fun dispatchPrimaryClipChanged() = onChange()
        }
        val cb = clipboard()
        call(cb, "addPrimaryClipChangedListener", listener)
        val stop: () -> Unit = { runCatching { call(cb, "removePrimaryClipChangedListener", listener) } }
        stop
    }.onFailure { Log.w(TAG, "addPrimaryClipChangedListener", it) }.getOrNull()

    // ---------- Wi-Fi ----------

    private fun wifi(): Any = asInterface("android.net.wifi.IWifiManager", binder("wifi"))

    /** Último error al leer las redes (para el diagnóstico de la app). */
    @Volatile var lastWifiError: String? = null
        private set

    /** Redes Wi-Fi guardadas, con contraseña (solo las personales que se pueden sincronizar). */
    fun savedNetworks(): List<Protocol.Wifi> = savedConfigs().mapNotNull(::toWifi)

    /** La red a la que está conectado este dispositivo ahora mismo, con su contraseña; null si no hay. */
    fun currentNetwork(): Protocol.Wifi? = runCatching {
        val info = call(wifi(), "getConnectionInfo") as android.net.wifi.WifiInfo? ?: return null
        val id = info.networkId
        if (id < 0) return null
        savedConfigs().firstOrNull { it.networkId == id }?.let(::toWifi)
    }.onFailure { lastWifiError = "red actual: ${it.javaClass.simpleName}: ${it.message}"; Log.w(TAG, "getConnectionInfo", it) }
        .getOrNull()

    private fun savedConfigs(): List<WifiConfiguration> = runCatching {
        val slice = call(wifi(), "getPrivilegedConfiguredNetworks", shellAttributionExtras())
            ?: error("Android devolvió una lista vacía")
        @Suppress("UNCHECKED_CAST")
        (slice.javaClass.getMethod("getList").invoke(slice) as List<WifiConfiguration>).also { lastWifiError = null }
    }.onFailure { lastWifiError = "redes guardadas: ${it.javaClass.simpleName}: ${it.message}"; Log.w(TAG, "getPrivilegedConfiguredNetworks", it) }
        .getOrDefault(emptyList())

    /** Nombre de la Wi-Fi a la que está conectado este dispositivo (sin censurar), o null. */
    fun currentSsid(): String? = runCatching {
        val info = call(wifi(), "getConnectionInfo") as android.net.wifi.WifiInfo? ?: return null
        if (info.networkId < 0) return null
        @Suppress("DEPRECATION")
        info.ssid?.removeSurrounding("\"")
    }.getOrNull()

    /**
     * Guarda la red y se conecta a ella ya (como al tocarla en Ajustes), aunque haya otra Wi-Fi.
     * enableNetwork(netId, disableOthers = true) lanza la conexión para llamantes con NETWORK_SETTINGS
     * (comprobado en WifiServiceImpl de AOSP); la firma es la misma en Android 12 a 16.
     */
    fun connectTo(w: Protocol.Wifi): Boolean = runCatching {
        val id = addNetworkId(w)
        if (id < 0) return false
        call(wifi(), "enableNetwork", IntArg(id), BoolArg(true)) as Boolean
    }.onFailure { Log.w(TAG, "enableNetwork", it) }.getOrDefault(false)

    /** Guarda una red como si la hubieras añadido en Ajustes. true si Android la aceptó. */
    fun addNetwork(w: Protocol.Wifi): Boolean = addNetworkId(w) >= 0

    private fun addNetworkId(w: Protocol.Wifi): Int = runCatching {
        val c = WifiConfiguration()
        c.SSID = "\"${w.ssid}\""
        when (w.sec) {
            "open" -> c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN)
            "wpa3" -> {
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                c.preSharedKey = "\"${w.pass}\""
            }
            else -> {
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK)
                c.preSharedKey = "\"${w.pass}\""
            }
        }
        call(wifi(), "addOrUpdateNetwork", c, Bundle()) as Int
    }.onFailure { Log.w(TAG, "addOrUpdateNetwork", it) }.getOrDefault(-1)

    private fun toWifi(c: WifiConfiguration): Protocol.Wifi? {
        val raw = c.SSID ?: return null
        if (!raw.startsWith("\"") || !raw.endsWith("\"") || raw.length < 3) return null // SSID en hexadecimal: se omite
        val ssid = raw.substring(1, raw.length - 1)
        if (c.hiddenSSID || c.isPasspoint) return null
        val psk = c.preSharedKey
        val pass = if (psk != null && psk.length >= 2 && psk.startsWith("\"") && psk.endsWith("\"")) psk.substring(1, psk.length - 1) else null
        val sec = securityOf(c) ?: return null
        return when (sec) {
            "open" -> Protocol.Wifi(ssid, null, "open")
            else -> Protocol.Wifi(ssid, pass ?: return null, sec)
        }
    }

    /**
     * Tipo de seguridad. Desde Android 12 la fuente fiable es la lista de SecurityParams (los campos
     * antiguos se derivan de ella); si no se puede leer, se usan los campos antiguos.
     * null = no sincronizable (empresa, WEP, OWE…).
     */
    private fun securityOf(c: WifiConfiguration): String? {
        val types = runCatching {
            (c.javaClass.getMethod("getSecurityParamsList").invoke(c) as List<*>).mapNotNull { p ->
                p?.javaClass?.getMethod("getSecurityType")?.invoke(p) as? Int
            }
        }.getOrDefault(emptyList())
        if (types.isNotEmpty()) {
            return when {
                WifiConfiguration.SECURITY_TYPE_PSK in types -> "wpa2" // incluye WPA2/WPA3 de transición
                WifiConfiguration.SECURITY_TYPE_SAE in types -> "wpa3"
                WifiConfiguration.SECURITY_TYPE_OPEN in types -> "open"
                else -> null
            }
        }
        val km = c.allowedKeyManagement
        return when {
            km.get(WifiConfiguration.KeyMgmt.WPA_EAP) || km.get(WifiConfiguration.KeyMgmt.IEEE8021X) -> null
            km.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "wpa2"
            km.get(KEY_MGMT_SAE) -> "wpa3"
            c.wepKeys?.any { it != null } == true -> null
            c.preSharedKey != null -> "wpa2" // campos antiguos vacíos pero con contraseña
            else -> "open"
        }
    }

    private const val KEY_MGMT_SAE = 8 // WifiConfiguration.KeyMgmt.SAE (oculto)

    // ---------- Utilidades ----------

    private fun binder(name: String): IBinder =
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService(name) ?: error("Servicio $name no disponible"))

    private fun asInterface(iface: String, binder: IBinder): Any =
        Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

    private fun shellAttributionExtras(): Bundle = Bundle().apply {
        val source = android.content.AttributionSource.Builder(SHELL_UID).setPackageName(PACKAGE).build()
        putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE", source)
    }

    /**
     * Llama al método [name] eligiendo la variante con más parámetros que se pueda rellenar:
     * objetos de [given] por tipo, el 1.er String = paquete shell (el resto null), el 1.er int = usuario,
     * el 2.º int = dispositivo (0 = el propio), Bundle = el de [given] o uno vacío.
     */
    internal fun call(target: Any, name: String, vararg given: Any): Any? {
        val candidates = target.javaClass.methods.filter { it.name == name }.sortedByDescending { it.parameterCount }
        var last: Throwable? = null
        for (m in candidates) {
            val args = fill(m, given) ?: continue
            try {
                return m.invoke(target, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: NoSuchMethodException("$name con argumentos compatibles")
    }

    /** Valores explícitos para parámetros int / boolean (si no, se rellenan por defecto). */
    internal class IntArg(val v: Int)
    internal class BoolArg(val v: Boolean)

    private fun fill(m: Method, given: Array<out Any>): Array<Any?>? {
        var strings = 0
        var ints = 0
        val used = BooleanArray(given.size)
        val args = arrayOfNulls<Any>(m.parameterCount)
        for (i in 0 until m.parameterCount) {
            val t = m.parameterTypes[i]
            val idx = given.indices.firstOrNull { !used[it] && t.isInstance(given[it]) }
            val intIdx = if (t == Int::class.javaPrimitiveType) given.indices.firstOrNull { !used[it] && given[it] is IntArg } else null
            val boolIdx = if (t == Boolean::class.javaPrimitiveType) given.indices.firstOrNull { !used[it] && given[it] is BoolArg } else null
            args[i] = when {
                intIdx != null -> { used[intIdx] = true; (given[intIdx] as IntArg).v }
                boolIdx != null -> { used[boolIdx] = true; (given[boolIdx] as BoolArg).v }
                idx != null -> { used[idx] = true; given[idx] }
                t == String::class.java -> if (strings++ == 0) PACKAGE else null
                t == Int::class.javaPrimitiveType -> if (ints++ == 0) userId else 0
                t == Bundle::class.java -> Bundle()
                t == Boolean::class.javaPrimitiveType -> false
                else -> return null
            }
        }
        // Un Bundle dado es opcional (Android 12 no lo lleva); el resto de objetos, obligatorios.
        return args.takeIf { given.indices.all { used[it] || given[it] is Bundle } }
    }
}
