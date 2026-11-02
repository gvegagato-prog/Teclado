package app.pera

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PeraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Permite usar por reflexión las APIs ocultas de tethering (igual que Delta y EasySpot).
        HiddenApiBypass.addHiddenApiExemptions("L")
        Notifications.createChannel(this)
    }
}
