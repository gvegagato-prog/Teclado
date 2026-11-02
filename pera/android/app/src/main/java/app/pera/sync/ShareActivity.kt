package app.pera.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.pera.R

/**
 * «Compartir → Pera»: manda un texto a la familia a mano. Útil donde no hay Teclado ni Shizuku para
 * detectar las copias.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                val app = applicationContext
                // Se lee aquí (el permiso de lectura es de esta actividad) y se reduce en segundo plano.
                val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes != null && bytes.size <= Images.MAX_SOURCE_BYTES) {
                    Thread { SyncBus.localImage(app, bytes) }.start()
                    Toast.makeText(this, R.string.share_sent, Toast.LENGTH_SHORT).show()
                }
            }
            finish()
            return
        }
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        val delivered = SyncBus.onLocalClip != null
        SyncBus.localCopy(this, text)
        Toast.makeText(this, if (delivered) R.string.share_sent else R.string.share_no_service, Toast.LENGTH_SHORT).show()
        finish()
    }
}
