package app.pera.client

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import app.pera.Prefs
import app.pera.R

/**
 * Botón «Datos del móvil» del panel de ajustes rápidos (se añade desde el lápiz de editar el panel).
 * Un toque enciende el hotspot del móvil y conecta; otro lo apaga.
 */
class HotspotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val turnOn = !Prefs.clientLastOn(this)
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE // «trabajando»
            subtitle = getString(if (turnOn) R.string.tile_connecting else R.string.tile_disconnecting)
            updateTile()
        }
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

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs.clientLastOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }
}
