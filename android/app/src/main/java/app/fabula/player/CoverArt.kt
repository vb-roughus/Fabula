package app.fabula.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Longest edge of the artwork embedded into the media metadata.
 *
 * The bitmap travels to the system media session over Binder and from there to
 * whatever displays it -- a car head unit over Bluetooth, the notification, the
 * lock screen. A full-size cover is both wasteful and risky there, and no
 * dashboard shows more detail than this.
 */
internal const val COVER_ART_MAX_EDGE_PX = 640

/** Below this, re-encoding buys nothing and the original bytes are kept. */
internal const val COVER_ART_KEEP_AS_IS_BYTES = 96 * 1024

/**
 * The largest power-of-two reduction that brings the longest edge to at most
 * [maxEdgePx]. Returns 1 when the image is already small enough, or when the
 * bounds are unknown -- a failed bounds decode reports zero, and shrinking by a
 * guess would be worse than not shrinking at all.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
    if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / sample > maxEdgePx) sample *= 2
    return sample
}

/**
 * Shrinks cover bytes to something a media session can carry comfortably.
 *
 * Every failure path returns the original bytes: artwork that is bigger than
 * ideal still shows, artwork that failed to convert does not.
 */
internal fun downscaleCoverArt(raw: ByteArray, maxEdgePx: Int = COVER_ART_MAX_EDGE_PX): ByteArray {
    if (raw.isEmpty()) return raw
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
        if (sample <= 1 && raw.size <= COVER_ART_KEEP_AS_IS_BYTES) return raw

        val bitmap = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return raw

        val out = ByteArrayOutputStream()
        val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        if (encoded && out.size() > 0) out.toByteArray() else raw
    }.getOrDefault(raw)
}
