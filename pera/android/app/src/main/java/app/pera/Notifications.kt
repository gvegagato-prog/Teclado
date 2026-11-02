package app.pera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL = "pera"
    const val HOST_ID = 1
    const val CLIENT_ID = 2
    const val SYNC_ID = 3

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }
}
