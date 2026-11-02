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
