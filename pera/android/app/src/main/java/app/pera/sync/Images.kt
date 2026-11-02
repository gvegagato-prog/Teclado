package app.pera.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Imágenes copiadas. Normalmente viajan tal cual (calidad completa) por la red local, ver LanShare.
 * Solo si no hay red en común se reducen para Bluetooth LE, que es lento (decenas de KB/s): lado largo de
 * [MAX_SIDE] px y JPEG de calidad [QUALITY] (~150-300 KB una foto de móvil).
 */
object Images {
    const val MAX_SIDE = 1600
    const val QUALITY = 80
    /** Imagen original más grande que esto no se intenta leer (evita quedarse sin memoria). */
    const val MAX_SOURCE_BYTES = 40 * 1024 * 1024

    /** Reduce una imagen (cualquier formato que Android sepa leer) a JPEG; null si no es una imagen. */
    fun reduce(source: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Las fotos de cámara guardan el giro aparte (EXIF): se aplica para que no salgan tumbadas.
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val longSide = maxOf(decoded.width, decoded.height)
        val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        val m = Matrix().apply { if (scale != 1f) postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
        val out = if (scale == 1f && rotation == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        ByteArrayOutputStream().use { os ->
            out.compress(Bitmap.CompressFormat.JPEG, QUALITY, os)
            os.toByteArray()
        }
    }.getOrNull()

    /** Tipo de imagen por sus primeros bytes (lo que se declara al pegar). */
    fun sniff(b: ByteArray): String = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size >= 6 && String(b, 0, 3) == "GIF" -> "image/gif"
        b.size >= 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
        b.size >= 12 && String(b, 4, 4) == "ftyp" && String(b, 8, 4).let { it.startsWith("hei") || it.startsWith("mif") } -> "image/heic"
        b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        else -> "image/jpeg"
    }

    fun hash(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }.take(16)

    private fun dir(context: Context) = File(context.filesDir, "clip").apply { mkdirs() }

    /** Guarda la última imagen (solo se conserva una) y devuelve su archivo. */
    fun save(context: Context, img: Msg.Img): File {
        dir(context).listFiles()?.forEach { if (it.name != fileName(img.id)) it.delete() }
        return File(dir(context), fileName(img.id)).apply { writeBytes(img.data) }
    }

    fun load(context: Context, id: String): ByteArray? = File(dir(context), fileName(id)).takeIf { it.exists() }?.readBytes()

    fun uri(id: String): Uri = Uri.parse("content://${ClipProvider.AUTHORITY}/${fileName(id)}")

    private fun fileName(id: String) = id.filter { it.isLetterOrDigit() || it == '-' } + ".img"

    internal fun file(context: Context, name: String): File? =
        File(dir(context), name).takeIf { it.exists() && it.parentFile == dir(context) }
}

/**
 * Sirve la imagen recibida a la app donde pegues. No es pública: Android da permiso de lectura a quien
 * lee el portapapeles (grantUriPermissions en el manifiesto), igual que hace cualquier app al copiar fotos.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String {
        val f = Images.file(context!!, uri.lastPathSegment ?: return "image/jpeg") ?: return "image/jpeg"
        val head = ByteArray(16)
        val n = f.inputStream().use { it.read(head) }
        return Images.sniff(head.copyOf(maxOf(0, n)))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = Images.file(context!!, uri.lastPathSegment ?: return null) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = Images.file(context!!, uri.lastPathSegment ?: return null) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>("Pera", f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "app.pera.clip"
    }
}
