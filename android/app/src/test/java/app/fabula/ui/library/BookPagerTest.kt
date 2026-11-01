package app.fabula.ui.library

import app.fabula.data.BookSummaryDto
import app.fabula.data.LibraryType
import app.fabula.data.PagedResultDto
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A list that arrives a page at a time.
 *
 * The cases that matter are the ones at the seams: a page that fails, one that
 * is asked for twice, and one that arrives after the list it was meant for has
 * been replaced. Each of those ends in a corrupted list or a crashed grid if it
 * goes wrong.
 */
class BookPagerTest {

    private fun book(id: Int, seriesId: Int? = null, type: LibraryType = LibraryType.Audiobook) =
        BookSummaryDto(id = id, title = "Buch $id", seriesId = seriesId, type = type, duration = "01:00:00")

    /** A server answering from a script, so each test decides what comes back. */
    private class Script(
        private val total: Int,
        private val pages: Map<Int, List<BookSummaryDto>>
    ) {
        val requested = mutableListOf<Int>()
        val failing = mutableSetOf<Int>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        suspend fun fetch(@Suppress("UNUSED_PARAMETER") query: PageQuery, page: Int): PagedResultDto<BookSummaryDto> {
            requested += page
            gates[page]?.await()
            if (page in failing) throw IOException("offline")
            return PagedResultDto(pages.getValue(page), total, page, LIBRARY_PAGE_SIZE)
        }
    }

    private val query = PageQuery(search = null, standalone = true, type = null)

    /** Runs `launch` blocks to their first suspension on the calling thread. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** Two pages' worth by the server's count, though the pages themselves are short. */
    private fun twoPages() = Script(
        total = LIBRARY_PAGE_SIZE + 10,
        pages = mapOf(
            1 to listOf(book(1), book(2), book(3)),
            2 to listOf(book(4), book(5), book(6))
        )
    )

    private fun pagerFor(script: Script) = BookPager { q, page -> script.fetch(q, page) }

    private fun BookPager.ids() = listing!!.books.map { it.id }

    // --- the first page -----------------------------------------------------

    @Test
    fun `the first page lands`() = runBlocking {
        val pager = pagerFor(twoPages())

        pager.reload(query)

        assertEquals(listOf(1, 2, 3), pager.ids())
        assertEquals(1, pager.listing!!.loadedPages)
        assertTrue(pager.listing!!.hasMore)
        assertNull(pager.error)
    }

    @Test
    fun `nothing is listed before the first page lands`() {
        val pager = pagerFor(twoPages())

        assertNull(pager.listing)
        assertNull(pager.error)
    }

    @Test
    fun `a failed first page leaves an error and no list`() = runBlocking {
        val script = twoPages().apply { failing += 1 }
        val pager = pagerFor(script)

        pager.reload(query)

        assertNull(pager.listing)
        assertEquals("offline", pager.error)
    }

    /**
     * A list that can no longer be refreshed is the cue for the offline view,
     * so it is dropped rather than shown on as if it were current.
     */
    @Test
    fun `a failed reload drops the list it was refreshing`() = runBlocking {
        val script = twoPages()
        val pager = pagerFor(script)
        pager.reload(query)

        script.failing += 1
        pager.reload(query)

        assertNull(pager.listing)
        assertNotNull(pager.error)
    }

    @Test
    fun `a successful reload clears an earlier error`() = runBlocking {
        val script = twoPages().apply { failing += 1 }
        val pager = pagerFor(script)
        pager.reload(query)

        script.failing -= 1
        pager.reload(query)

        assertNull(pager.error)
        assertEquals(listOf(1, 2, 3), pager.ids())
    }

    // --- the next page ------------------------------------------------------

    @Test
    fun `the next page is added after the first`() = runBlocking {
        val pager = pagerFor(twoPages())
        pager.reload(query)

        pager.loadMore(scope)

        assertEquals(listOf(1, 2, 3, 4, 5, 6), pager.ids())
        assertEquals(2, pager.listing!!.loadedPages)
        assertFalse(pager.listing!!.loadingMore)
    }

    @Test
    fun `nothing more is asked for once the end is reached`() = runBlocking {
        val script = Script(total = 3, pages = mapOf(1 to listOf(book(1), book(2), book(3))))
        val pager = pagerFor(script)
        pager.reload(query)

        pager.loadMore(scope)

        assertEquals(listOf(1), script.requested)
        assertFalse(pager.listing!!.hasMore)
    }

    /**
     * A grid keyed by id throws on a duplicate. A book added between two
     * requests moves the page boundary, so the last book of one page can reappear
     * as the first of the next.
     */
    @Test
    fun `a book that is listed already is not added again`() = runBlocking {
        val script = Script(
            total = LIBRARY_PAGE_SIZE + 10,
            pages = mapOf(
                1 to listOf(book(1), book(2), book(3)),
                2 to listOf(book(3), book(4))
            )
        )
        val pager = pagerFor(script)
        pager.reload(query)

        pager.loadMore(scope)

        assertEquals(listOf(1, 2, 3, 4), pager.ids())
    }

    @Test
    fun `asking again while a page is on its way does not ask twice`() = runBlocking {
        val script = twoPages().apply { gates[2] = CompletableDeferred() }
        val pager = pagerFor(script)
        pager.reload(query)

        pager.loadMore(scope)
        pager.loadMore(scope)
        assertTrue(pager.listing!!.loadingMore)
        script.gates.getValue(2).complete(Unit)

        assertEquals(listOf(1, 2), script.requested)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), pager.ids())
    }

    // --- failing ------------------------------------------------------------

    @Test
    fun `a failed page keeps what is listed and says so`() = runBlocking {
        val script = twoPages()
        val pager = pagerFor(script)
        pager.reload(query)

        script.failing += 2
        pager.loadMore(scope)

        assertEquals(listOf(1, 2, 3), pager.ids())
        assertTrue(pager.listing!!.moreFailed)
        assertFalse(pager.listing!!.loadingMore)
        assertNull(pager.error)
    }

    @Test
    fun `a failed page can be tried again`() = runBlocking {
        val script = twoPages()
        val pager = pagerFor(script)
        pager.reload(query)
        script.failing += 2
        pager.loadMore(scope)

        script.failing -= 2
        pager.loadMore(scope)

        assertEquals(listOf(1, 2, 3, 4, 5, 6), pager.ids())
        assertFalse(pager.listing!!.moreFailed)
    }

    // --- being replaced -----------------------------------------------------

    /**
     * The seam that corrupts a list: the next page was requested for a list
     * that has since been replaced -- a new search, a refresh -- and lands on
     * top of the new one.
     */
    @Test
    fun `a page requested for the list being replaced is dropped`() = runBlocking {
        val script = twoPages().apply { gates[2] = CompletableDeferred() }
        val pager = pagerFor(script)
        pager.reload(query)
        pager.loadMore(scope)

        val replacement = query.copy(search = "hobbit", standalone = false)
        pager.reload(replacement)
        script.gates.getValue(2).complete(Unit)

        assertEquals(replacement, pager.listing!!.query)
        assertEquals(listOf(1, 2, 3), pager.ids())
        assertEquals(1, pager.listing!!.loadedPages)
    }

    @Test
    fun `the old list stays up while its replacement is fetched`() = runBlocking {
        val script = twoPages()
        val pager = pagerFor(script)
        pager.reload(query)
        script.gates[1] = CompletableDeferred()

        val reloading = scope.launch { pager.reload(query) }
        assertEquals(listOf(1, 2, 3), pager.ids())
        script.gates.getValue(1).complete(Unit)
        reloading.join()

        assertEquals(listOf(1, 2, 3), pager.ids())
    }

    @Test
    fun `clearing forgets the list and any page on its way`() = runBlocking {
        val script = twoPages().apply { gates[2] = CompletableDeferred() }
        val pager = pagerFor(script)
        pager.reload(query)
        pager.loadMore(scope)

        pager.clear()
        script.gates.getValue(2).complete(Unit)

        assertNull(pager.listing)
        assertNull(pager.error)
    }

    // --- an older server ----------------------------------------------------

    /**
     * A server that predates the filters sends everything. The pager filters
     * once more, so the list is right either way.
     */
    @Test
    fun `books a server did not filter out are filtered here`() = runBlocking {
        val script = Script(
            total = 4,
            pages = mapOf(
                1 to listOf(
                    book(1),
                    book(2, seriesId = 7),
                    book(3, type = LibraryType.RadioPlay),
                    book(4, seriesId = 7, type = LibraryType.RadioPlay)
                )
            )
        )
        val pager = pagerFor(script)

        pager.reload(PageQuery(search = null, standalone = true, type = LibraryType.RadioPlay))

        assertEquals(listOf(3), pager.ids())
    }

    @Test
    fun `a search keeps books of a series`() = runBlocking {
        val script = Script(total = 2, pages = mapOf(1 to listOf(book(1), book(2, seriesId = 7))))
        val pager = pagerFor(script)

        pager.reload(PageQuery(search = "hobbit", standalone = false, type = null))

        assertEquals(listOf(1, 2), pager.ids())
    }
}
