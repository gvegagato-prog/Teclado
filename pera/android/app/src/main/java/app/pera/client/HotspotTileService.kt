package app.pera.client

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import app.pera.Prefs
import app.pera.R

/**
 * Botón «Datos del móvil» del panel de ajustes rápidos (se añade desde el lápiz de editar el panel).
 * Un toque enciende el hotspot del móvil y conecta; otro lo apaga.
 *
 * El botón cambia en el acto al tocarlo y nunca se pone «no disponible» (en ese estado Android ignora
 * los toques). Mientras hay una orden en curso, el subtítulo lo dice y los toques se ignoran.
 */
class HotspotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        live = this
        draw()
    }

    override fun onStopListening() {
        if (live === this) live = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ClientService.working) return
        ClientService.markWorking()
        val turnOn = !Prefs.clientLastOn(this)
        Prefs.setClientLastOn(this, turnOn) // respuesta inmediata; se corrige si falla
        draw(working = if (turnOn) R.string.tile_connecting else R.string.tile_disconnecting)
        app.pera.Watchdog.ensure(this) // de paso, que la sincronización vuelva si Android la cerró
        val intent = ClientService.intent(this, turnOn)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            // Si Android no deja arrancar el servicio desde el panel, se pasa por una actividad
            // invisible (que sí puede arrancarlo) y se cierra el panel.
            Log.w("PeraTile", "Servicio no permitido desde el panel; uso actividad puente", e)
            val bridge = Intent(this, BridgeActivity::class.java)
                .putExtra(ClientService.EXTRA_ON, turnOn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, bridge, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(bridge)
            }
        }
    }

    private fun draw(working: Int? = null) {
        val tile = qsTile ?: return
        val on = Prefs.clientLastOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            working ?: when {
                ClientService.working -> if (on) R.string.tile_connecting else R.string.tile_disconnecting
                on -> R.string.tile_on
                else -> R.string.tile_off
            },
        )
        tile.updateTile()
    }

    companion object {
        /** El botón mientras el panel está abierto (para repintarlo al terminar una orden). */
        @Volatile private var live: HotspotTileService? = null

        /** Repinta el botón con el estado guardado: ya mismo si el panel está abierto, si no al abrirlo. */
        fun refresh(context: Context) {
            Handler(Looper.getMainLooper()).post { live?.draw() }
            runCatching {
                requestListeningState(context, ComponentName(context, HotspotTileService::class.java))
            }
        }
    }
}
