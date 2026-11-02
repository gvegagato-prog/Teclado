package app.pera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL = "pera"
    /** Avisos permanentes de los servicios: importancia mínima (sin icono en la barra, plegados). */
    const val CHANNEL_BG = "pera_fondo"
    const val HOST_ID = 1
    const val CLIENT_ID = 2
    const val SYNC_ID = 3

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BG, context.getString(R.string.channel_bg_name), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        )
    }
}
