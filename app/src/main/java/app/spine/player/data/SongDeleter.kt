package app.spine.player.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore

/** Removes audio files from the phone. */
object SongDeleter {

    sealed interface Result {
        /** The files are gone. */
        data object Deleted : Result

        /** Android wants the user's consent first; launch this and delete is done on approval. */
        data class NeedsConsent(val intentSender: IntentSender) : Result

        /** The system refused and offered no way to ask. */
        data object Refused : Result
    }

    /**
     * From Android 11 an app may not delete files it did not create without the system's own
     * consent prompt, so this normally returns [Result.NeedsConsent].
     */
    fun delete(context: Context, songs: List<Song>): Result {
        val resolver = context.contentResolver
        val uris = songs.map { it.uri }
        if (uris.isEmpty()) return Result.Deleted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Result.NeedsConsent(MediaStore.createDeleteRequest(resolver, uris).intentSender)
        }
        return try {
            uris.forEach { resolver.delete(it, null, null) }
            Result.Deleted
        } catch (error: SecurityException) {
            // Android 10 asks per file through a recoverable exception.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                Result.NeedsConsent(error.userAction.actionIntent.intentSender)
            } else {
                Result.Refused
            }
        }
    }
}
