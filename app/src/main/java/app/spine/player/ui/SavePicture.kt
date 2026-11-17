package app.spine.player.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a picture the app drew (the stats, for "Save as an image") into the phone's
 * Pictures, in a folder named Spine, as a PNG. It needs no permission: an app may always
 * add its own pictures there. Returns where it went, or null if the phone would not take it.
 */
suspend fun savePicture(context: Context, image: ImageBitmap, what: String): Uri? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$what $stamp.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Spine")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@withContext null
    try {
        // The picture comes straight off the screen; a plain copy is what can be written to a file.
        val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        if (!written) error("The picture could not be written")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (fault: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}
