package app.pera.host

import android.content.Context
import android.content.pm.PackageManager
import android.net.IIntResultListener
import android.net.ITetheringConnector
import android.net.TetheringRequestParcel
import android.net.wifi.IWifiManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import app.pera.Protocol
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Enciende y apaga el punto de acceso Wi-Fi con internet ("tethering") a través de Shizuku.
 *
 * Una app normal no puede: TetheringManager#startTethering exige TETHER_PRIVILEGED. Con Shizuku la
 * llamada al servicio del sistema se hace con la identidad del usuario "shell" (la de ADB), que sí
 * puede. Es el mismo método que usa Delta (https://github.com/supershadoe/delta, BSD-3-Clause).
 */
class HotspotController(private val context: Context) {

    enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }

    fun shizukuStatus(): ShizukuState = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NOT_RUNNING
        Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
        else -> ShizukuState.READY
    }

    fun shizukuInstalled(): Boolean = listOf("moe.shizuku.privileged.api").any {
        runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    /** Estado del hotspot traducido a Protocol.STATE_*. */
    fun state(): Int {
        val raw = rawApState() ?: return if (shizukuStatus() == ShizukuState.READY) Protocol.STATE_FAILED else Protocol.STATE_NO_SHIZUKU
        return when (raw) {
            WIFI_AP_STATE_ENABLED -> Protocol.STATE_ON
            WIFI_AP_STATE_ENABLING -> Protocol.STATE_TURNING_ON
            WIFI_AP_STATE_DISABLING -> Protocol.STATE_TURNING_OFF
            WIFI_AP_STATE_FAILED -> Protocol.STATE_FAILED
            else -> Protocol.STATE_OFF
        }
    }

    /** Devuelve null si ha ido bien, o un texto con el motivo del fallo. */
    fun start(): String? {
        if (shizukuStatus() != ShizukuState.READY) return "Shizuku no está listo"
        if (state() == Protocol.STATE_ON) return null
        return runCatching {
            // El Builder real rellena valores por defecto que cambian entre versiones (uid, ámbito
            // de conectividad, tipo de petición...). Por eso se usa por reflexión y no a mano.
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            val request = builderClass.getMethod("build").invoke(builder)!!
            val parcel = request.javaClass.getMethod("getParcel").invoke(request) as TetheringRequestParcel
            tethering().startTethering(parcel, SHELL_PACKAGE, null, ResultLogger("start"))
            null
        }.getOrElse { Log.e(TAG, "startTethering", it); "No se pudo encender: ${it.javaClass.simpleName}: ${it.message}" }
    }

    fun stop(): String? {
        if (shizukuStatus() != ShizukuState.READY) return "Shizuku no está listo"
        return runCatching {
            tethering().stopTethering(TETHERING_WIFI, SHELL_PACKAGE, null, ResultLogger("stop"))
            null
        }.getOrElse { Log.e(TAG, "stopTethering", it); "No se pudo apagar: ${it.javaClass.simpleName}: ${it.message}" }
    }

    /** Nombre, contraseña y seguridad del hotspot, para que la tablet o el Mac se unan solos. */
    fun wifi(): Protocol.Wifi? {
        if (shizukuStatus() != ShizukuState.READY) return null
        return runCatching {
            val config: SoftApConfiguration = wifiService().softApConfiguration
            @Suppress("DEPRECATION")
            val ssid = config.ssid ?: return null
            val sec = when (config.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> "open"
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "wpa3"
                else -> "wpa2" // WPA2 y el modo de transición WPA2/WPA3
            }
            Protocol.Wifi(ssid, config.passphrase, sec)
        }.onFailure { Log.e(TAG, "getSoftApConfiguration", it) }.getOrNull()
    }

    private fun rawApState(): Int? {
        // 1) WifiManager#getWifiApState (oculto, pero solo pide ACCESS_WIFI_STATE).
        runCatching {
            val wm = context.getSystemService(WifiManager::class.java)
            return wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int
        }
        // 2) Mismo dato pidiéndoselo al servicio a través de Shizuku.
        if (shizukuStatus() == ShizukuState.READY) {
            runCatching { return wifiService().wifiApEnabledState }
        }
        return null
    }

    private fun tethering(): ITetheringConnector =
        ITetheringConnector.Stub.asInterface(systemService("tethering"))

    private fun wifiService(): IWifiManager = IWifiManager.Stub.asInterface(systemService("wifi"))

    private fun systemService(name: String) =
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService(name) ?: error("Servicio $name no disponible"))

    private class ResultLogger(private val what: String) : IIntResultListener.Stub() {
        override fun onResult(resultCode: Int) {
            Log.i(TAG, "$what tethering: código $resultCode (0 = sin error)")
        }
    }

    companion object {
        private const val TAG = "PeraHotspot"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val TETHERING_WIFI = 0

        // WifiManager.WIFI_AP_STATE_* (constantes ocultas)
        const val WIFI_AP_STATE_DISABLING = 10
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_ENABLING = 12
        const val WIFI_AP_STATE_ENABLED = 13
        const val WIFI_AP_STATE_FAILED = 14

        /** Difusión del sistema al cambiar el estado del hotspot (WifiManager.WIFI_AP_STATE_CHANGED_ACTION). */
        const val ACTION_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    }
}
