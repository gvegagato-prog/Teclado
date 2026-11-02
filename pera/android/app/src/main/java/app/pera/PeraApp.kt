package app.pera

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PeraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Permite usar por reflexión las APIs ocultas de tethering (igual que Delta y EasySpot).
        HiddenApiBypass.addHiddenApiExemptions("L")
        Notifications.createChannel(this)
        // El proceso puede arrancar por el botón del panel, una copia de Teclado, el vigilante…:
        // en todos los casos, que vuelvan los servicios activados.
        Watchdog.ensure(this)
    }
}
