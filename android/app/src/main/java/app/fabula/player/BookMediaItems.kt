package app.fabula.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.fabula.data.BookDetailDto
import app.fabula.data.FabulaRepository
import app.fabula.data.OfflineStore
import app.fabula.data.parseTimeSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turning a book into what the player actually holds.
 *
 * Shared between [PlayerController], which loads a book because the user asked,
 * and [PlaybackService], which has to rebuild the same queue when the system
 * resumes playback into a process that just started. Two copies of this would
 * drift, and the one nobody looks at -- the resumption path -- is the copy that
 * would drift unnoticed.
 */

/** Cumulative start second of each file, matching [BookDetailDto.files] order. */
internal fun fileStartsOf(book: BookDetailDto): DoubleArray {
    val starts = DoubleArray(book.files.size)
    var acc = 0.0
    book.files.forEachIndexed { i, f ->
        starts[i] = acc
        acc += parseTimeSpan(f.duration)
    }
    return starts
}

/**
 * Maps a book-wide position to the media item holding it, plus the offset
 * within that item.
 *
 * The tolerance covers floating-point drift between the server's exact
 * OffsetInBook and this client's cumulative sum of file durations. Without it,
 * seeking to the start of file N can land on the last millisecond of file N-1
 * when the sum overshoots by a few microseconds.
 */
internal fun mapBookPositionToMedia(starts: DoubleArray, positionSec: Double): Pair<Int, Long> {
    if (starts.isEmpty()) return 0 to 0L
    val clamped = maxOf(0.0, positionSec)
    var index = starts.size - 1
    val epsilon = 0.010
    for (i in starts.indices) {
        val nextStart = if (i + 1 < starts.size) starts[i + 1] else Double.MAX_VALUE
        if (clamped + epsilon < nextStart) {
            index = i
            break
        }
    }
    val localMs = ((clamped - starts[index]) * 1000.0).toLong().coerceAtLeast(0L)
    return index to localMs
}

/**
 * Cover bytes for the media metadata, downscaled for the trip through the
 * system session.
 *
 * Prefers the downloaded copy, so a synced book needs no network at all --
 * which is the case where a car head unit matters most. Any failure returns
 * null and simply leaves the metadata without embedded artwork.
 */
internal suspend fun coverArtwork(
    book: BookDetailDto,
    repository: FabulaRepository,
    offlineStore: OfflineStore
): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val local = offlineStore.localCover(book.id)
        val raw = if (local != null) {
            local.readBytes()
        } else {
            repository.apiOrNull()?.downloadCover(book.id)
                ?.takeIf { it.isSuccessful }?.body()?.bytes()
        }
        raw?.takeIf { it.isNotEmpty() }?.let { downscaleCoverArt(it) }
    }.getOrNull()
}

/**
 * The media items for a book, one per audio file, all carrying the same
 * book-level metadata.
 *
 * Empty when no stream URL can be built -- there is no configured server -- in
 * which case the caller has nothing to play.
 */
internal suspend fun buildPlaybackItems(
    book: BookDetailDto,
    repository: FabulaRepository,
    offlineStore: OfflineStore
): List<MediaItem> {
    // Embedded as bytes, not just as a URI. A URI leaves it to whoever displays
    // the metadata to fetch the image, and the two consumers that matter cannot:
    // a car head unit reads the cover over Bluetooth from the system session and
    // has no route to this server, and a downloaded cover sits in the app's
    // private storage where no other process may look.
    val artwork = coverArtwork(book, repository, offlineStore)

    // Built once and shared: every track carries the same title, author and
    // cover, so re-encoding the artwork per track would be pure waste.
    val metadata = MediaMetadata.Builder()
        .setTitle(book.title)
        .setArtist(book.authors.joinToString(", "))
        .setAlbumTitle(book.series ?: book.title)
        .setArtworkUri(repository.coverUrl(book)?.let { android.net.Uri.parse(it) })
        .apply {
            if (artwork != null) setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        .build()

    return book.files.mapNotNull { f ->
        val url = repository.streamUrl(f.id) ?: return@mapNotNull null
        MediaItem.Builder()
            .setMediaId("book-${book.id}-file-${f.id}")
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }
}
