package app.fabula.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One book's listening position as this device last observed it.
 *
 * [synced] is the important field: it says whether the server has seen this
 * value. An unsynced entry was produced here and never made it out, so it is
 * newer than whatever the server holds -- that single bit is all the ordering
 * information needed to stop a stale server value from rewinding the listener.
 */
@Serializable
data class LocalProgress(
    val bookId: Int,
    val positionSec: Double,
    val finished: Boolean,
    /** Device clock, only ever compared against other values from this device. */
    val updatedAtMs: Long,
    val synced: Boolean
)

@Serializable
private data class ProgressFile(val entries: List<LocalProgress> = emptyList())

/**
 * Durable local record of listening progress.
 *
 * Progress used to live only in the player and on the server: a failed save was
 * simply lost, and re-opening a book took the server's position unconditionally,
 * which rewound the listener whenever the last saves hadn't made it out. This
 * store makes the local value the source of truth and keeps the ones the server
 * hasn't acknowledged, so they can be handed over later.
 */
class ProgressStore(context: Context, private val logStore: LogStore) {

    private val dir: File = File(context.filesDir, "progress").apply { mkdirs() }
    private val file: File = File(dir, "progress.json")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val writeLock = Any()

    @Volatile
    private var entries: Map<Int, LocalProgress> = load()

    /** Bumped on every change so the UI can re-read if it wants to. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun load(): Map<Int, LocalProgress> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<ProgressFile>(file.readText())
                .entries.associateBy { it.bookId }
        }.onFailure {
            logStore.w("Progress", "progress.json unlesbar: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun persist(next: Map<Int, LocalProgress>) {
        synchronized(writeLock) {
            entries = next
            runCatching {
                // tmp + rename so a kill mid-write can't truncate the file.
                val tmp = File(dir, "progress.json.tmp")
                tmp.writeText(json.encodeToString(ProgressFile(next.values.toList())))
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }.onFailure { logStore.w("Progress", "progress.json schreiben fehlgeschlagen", it) }
        }
        _revision.value = _revision.value + 1
    }

    fun local(bookId: Int): LocalProgress? = entries[bookId]

    /**
     * Records what is being listened to right now. Always marked unsynced --
     * [markSynced] is what promotes it once the server has confirmed.
     */
    fun record(bookId: Int, positionSec: Double, finished: Boolean, nowMs: Long) {
        val existing = entries[bookId]
        if (existing != null &&
            existing.positionSec == positionSec &&
            existing.finished == finished
        ) return  // nothing moved; don't churn the file

        persist(
            entries + (bookId to LocalProgress(
                bookId = bookId,
                positionSec = positionSec,
                finished = finished,
                updatedAtMs = nowMs,
                synced = false
            ))
        )
    }

    /**
     * Marks an entry as handed over, but only when it hasn't moved on in the
     * meantime -- otherwise a save that was already in flight would mask newer
     * listening as "synced" and it would never be sent.
     */
    fun markSynced(bookId: Int, updatedAtMs: Long) {
        val existing = entries[bookId] ?: return
        if (existing.updatedAtMs != updatedAtMs) return
        if (existing.synced) return
        persist(entries + (bookId to existing.copy(synced = true)))
    }

    /** Entries the server hasn't confirmed, oldest observation first. */
    fun pending(): List<LocalProgress> =
        entries.values.filter { !it.synced }.sortedBy { it.updatedAtMs }

    fun hasPending(): Boolean = entries.values.any { !it.synced }

    /** Used when the user resets a book's progress. */
    fun clear(bookId: Int) {
        if (!entries.containsKey(bookId)) return
        persist(entries - bookId)
    }
}
