package app.fabula.ui.book

import app.fabula.data.BookDetailDto
import app.fabula.data.parseTimeSpan

internal class ChapterDownloadFlags(
    val offline: BooleanArray,
    /** Chapter covered by the track being fetched right now. */
    val loading: BooleanArray
)

internal fun offlineChapterFlags(
    book: BookDetailDto,
    downloadedFileIds: Set<Int>,
    currentFileId: Int?
): ChapterDownloadFlags {
    val offline = BooleanArray(book.chapters.size)
    val loading = BooleanArray(book.chapters.size)
    if (book.files.isEmpty()) return ChapterDownloadFlags(offline, loading)
    if (downloadedFileIds.isEmpty() && currentFileId == null) {
        return ChapterDownloadFlags(offline, loading)
    }

    // Prefer the server's offset; fall back to a running sum the same way
    // PlayerController.loadBook builds its fileStarts.
    val starts = DoubleArray(book.files.size)
    val ends = DoubleArray(book.files.size)
    var acc = 0.0
    book.files.forEachIndexed { i, f ->
        val declared = parseTimeSpan(f.offsetInBook)
        val start = if (declared > 0.0 || i == 0) declared else acc
        starts[i] = start
        ends[i] = start + parseTimeSpan(f.duration)
        acc = ends[i]
    }

    book.chapters.forEachIndexed { ci, c ->
        val cs = parseTimeSpan(c.start)
        val ce = parseTimeSpan(c.end)
        var overlapped = false
        var all = true
        var busy = false
        for (i in book.files.indices) {
            // Same 10 ms tolerance the rest of the codebase uses for the FP
            // drift around chapter boundaries (see chapterAt).
            if (starts[i] < ce - 0.010 && ends[i] > cs + 0.010) {
                overlapped = true
                if (book.files[i].id == currentFileId) busy = true
                if (book.files[i].id !in downloadedFileIds) all = false
            }
        }
        offline[ci] = overlapped && all
        // Only "loading" while not yet complete, so the tick wins once the
        // last overlapping track lands.
        loading[ci] = busy && !(overlapped && all)
    }
    return ChapterDownloadFlags(offline, loading)
}
