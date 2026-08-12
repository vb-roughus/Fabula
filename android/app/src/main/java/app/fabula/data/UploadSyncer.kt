package app.fabula.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Hands pending bookmarks and highlights to the server.
 *
 * Mirrors the progress sync: a single conflated worker, one request in flight,
 * entries kept until the server confirms and retried oldest-first. Creating an
 * entry writes it down first and only then tries to send, so nothing depends on
 * the request succeeding.
 *
 * Handovers happen at app start and after a successful manual reconnect -- never
 * on a timer, matching "no automatic reconnecting". While the offline latch is
 * set the attempt fails locally without touching the network, so a create can
 * still ask for one cheaply.
 */
class UploadSyncer(
    private val repository: FabulaRepository,
    private val store: PendingUploadStore,
    private val logStore: LogStore,
    private val scope: CoroutineScope
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            for (unused in requests) drain()
        }
    }

    fun requestSync() {
        if (store.hasPending()) requests.trySend(Unit)
    }

    /**
     * Records a bookmark and asks for a handover. The revision bump makes the
     * lists redraw immediately, so the new entry is visible even offline.
     */
    fun createBookmark(bookId: Int, position: String, note: String?) {
        store.addBookmark(bookId, position, note)
        repository.bumpBookmarksRevision()
        requests.trySend(Unit)
    }

    fun createHighlight(bookId: Int, start: String, end: String, title: String?, note: String?) {
        store.addHighlight(bookId, start, end, title, note)
        repository.bumpHighlightsRevision()
        requests.trySend(Unit)
    }

    private suspend fun drain() {
        val api = repository.apiOrNull() ?: return
        var uploadedBookmark = false
        var uploadedHighlight = false

        for (entry in store.allBookmarks()) {
            val ok = runCatching {
                api.createBookmark(
                    entry.bookId,
                    CreateBookmarkRequest(position = entry.position, note = entry.note)
                )
            }.onFailure {
                logStore.w("Pending", "Lesezeichen buch=${entry.bookId} noch offen: ${it.message}")
            }.isSuccess
            if (!ok) break  // offline or failing; keep this and the rest queued
            store.removeBookmark(entry.localId)
            uploadedBookmark = true
        }

        for (entry in store.allHighlights()) {
            val ok = runCatching {
                api.createHighlight(
                    entry.bookId,
                    CreateHighlightRequest(
                        start = entry.start,
                        end = entry.end,
                        title = entry.title,
                        note = entry.note
                    )
                )
            }.onFailure {
                logStore.w("Pending", "Markierung buch=${entry.bookId} noch offen: ${it.message}")
            }.isSuccess
            if (!ok) break
            store.removeHighlight(entry.localId)
            uploadedHighlight = true
        }

        // Only bump once at the end, so the lists reload the real server rows
        // in place of the pending ones exactly once.
        if (uploadedBookmark) repository.bumpBookmarksRevision()
        if (uploadedHighlight) repository.bumpHighlightsRevision()
    }
}
