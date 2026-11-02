package app.pera.client

import android.app.Activity
import android.os.Bundle

/** Actividad invisible: arranca ClientService (desde una actividad visible sí está permitido) y se cierra. */
class BridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val turnOn = intent.getBooleanExtra(ClientService.EXTRA_ON, true)
        startForegroundService(ClientService.intent(this, turnOn))
        finish()
    }
}
