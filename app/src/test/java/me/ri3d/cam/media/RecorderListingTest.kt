package me.ri3d.cam.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.recorder.FileList
import me.ri3d.cam.recorder.RecorderReply
import me.ri3d.cam.recorder.RecorderSimulator
import me.ri3d.cam.recorder.SimulatedFiles

/** Cursor paging (report "Cursor-based browsing") as a pure fold and against simulated recorders [SIM]. */
@RunWith(RobolectricTestRunner::class)
class RecorderListingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)

    @After
    fun tearDown() = db.close()

    private fun page(vararg names: String?, total: Int? = null) =
        FileList(total, null, names.map { recorderFile(it ?: "x").copy(fileName = it) }, JsonObject(emptyMap()))

    @Test
    fun `the cursor is the exact last fileName and a short page completes`() {
        val first = Listing().append(page("/a", "/b", "/c", total = 5), pageNum = 3)
        assertThat(first.cursor).isEqualTo("/c")
        assertThat(first.end).isNull()
        val second = first.append(page("/d", "/e"), pageNum = 3)
        assertThat(second.end).isEqualTo(ListingEnd.COMPLETE)
        assertThat(second.files.map { it.fileName }).containsExactly("/a", "/b", "/c", "/d", "/e").inOrder()
        assertThat(second.totalFileNum).isEqualTo(5) // kept from the page that reported it
        assertThat(Listing().append(page(), pageNum = 3).end).isEqualTo(ListingEnd.COMPLETE)
    }

    @Test
    fun `a short page below the reported total asks again until an empty page`() {
        val short = Listing().append(page("/a", "/b", total = 5), pageNum = 3)
        assertThat(short.end).isNull() // 2 of 5: the recorder may page in smaller batches
        assertThat(short.reachedTotal).isFalse()
        val done = short.append(page(), pageNum = 3)
        assertThat(done.end).isEqualTo(ListingEnd.COMPLETE)
        assertThat(done.reachedTotal).isFalse() // ended below the total: missing files do not count as gone
        assertThat(Listing().append(page("/a", total = 1), pageNum = 3).run { end to reachedTotal }).isEqualTo(ListingEnd.COMPLETE to true)
        assertThat(Listing().append(page("/a"), pageNum = 3).end).isEqualTo(ListingEnd.COMPLETE) // no total reported
    }

    @Test
    fun `a listing below the total does not forget recorder copies`() = runTest {
        val all = SimulatedFiles.series(0, "normal", "N", ".mp4", 60, java.time.LocalDateTime.of(2026, 10, 1, 1, 0), 60, 1000)
        var calls = 0
        val sim = RecorderSimulator().apply {
            // 30 per request, reports 100 files, then an empty page.
            handlers[4100] = { calls++.let { SimulatedFiles.listReply(all, all.drop(it * 30).take(30)).replace("\"totalFileNum\":60", "\"totalFileNum\":100") } }
        }
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val repository = MediaRepository(context, db, manager)
        repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/old.mp4")))
        val browser = RecorderBrowser(0, manager, repository, backgroundScope)

        browser.refresh()
        repeat(3) {
            eventually { !browser.state.value.loading }
            browser.loadMore()
        }
        eventually { browser.state.value.listing.end != null }

        assertThat(browser.state.value.listing.end).isEqualTo(ListingEnd.COMPLETE)
        assertThat(browser.state.value.listing.files).hasSize(60)
        assertThat(cursors(sim)).hasSize(3)
        assertThat(db.mediaDao().byRecorderPath("/sim/old.mp4")).isNotNull() // 60 of 100 listed: no reconcile
    }

    @Test
    fun `an inclusive cursor is deduplicated and paging continues`() {
        val listing = Listing().append(page("/a", "/b", "/c"), 3).append(page("/c", "/d", "/e"), 3)
        assertThat(listing.files.map { it.fileName }).containsExactly("/a", "/b", "/c", "/d", "/e").inOrder()
        assertThat(listing.end).isNull()
        assertThat(listing.cursor).isEqualTo("/e")
    }

    @Test
    fun `an inclusive cursor repeated alone on a short page completes`() {
        val listing = Listing().append(page("/a", "/b", "/c"), 3).append(page("/c"), 3)
        assertThat(listing.end).isEqualTo(ListingEnd.COMPLETE)
    }

    @Test
    fun `a repeated last fileName or a missing one stops paging`() {
        val first = Listing().append(page("/a", "/b", "/c"), 3)
        assertThat(first.append(page("/a", "/b", "/c"), 3).end).isEqualTo(ListingEnd.STOPPED) // cursor ignored
        val loop = first.append(page("/d", "/e", "/f"), 3).append(page("/b", "/x", "/c"), 3) // back to an old cursor
        assertThat(loop.end).isEqualTo(ListingEnd.STOPPED)
        assertThat(loop.files.map { it.fileName }).containsExactly("/a", "/b", "/c", "/d", "/e", "/f", "/x").inOrder()
        assertThat(first.append(page("/d", "/e", null), 3).end).isEqualTo(ListingEnd.STOPPED)
    }

    private fun cursors(sim: RecorderSimulator) = sim.received.filter { it.msgId == 4100 }
        .map { (RecorderReply.parse(it.json)!!.param as JsonObject)["lastFileName"]!!.jsonPrimitive.content }

    @Test
    fun `120 simulated files are listed in three requests with exact cursors`() = runTest {
        val files = SimulatedFiles()
        val sim = RecorderSimulator().apply { handlers[4100] = files::listReply }
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val browser = RecorderBrowser(0, manager, MediaRepository(context, db, manager), backgroundScope)

        browser.refresh()
        repeat(3) {
            eventually { !browser.state.value.loading }
            browser.loadMore()
        }
        eventually { browser.state.value.listing.end != null }

        val state = browser.state.value
        val names = files.entries(0).map { it.fileName }
        assertThat(state.listing.end).isEqualTo(ListingEnd.COMPLETE)
        assertThat(state.listing.files.map { it.fileName }).isEqualTo(names)
        assertThat(state.listing.totalFileNum).isEqualTo(120)
        assertThat(cursors(sim)).containsExactly("", names[49], names[99]).inOrder()
        assertThat(db.mediaDao().recorderType(0).map { it.recorderPath }).containsExactlyElementsIn(names)
    }

    @Test
    fun `a recorder that ignores the cursor is stopped after the second page`() = runTest {
        val files = SimulatedFiles()
        val first = SimulatedFiles.listReply(files.entries(0), files.entries(0).take(50))
        val sim = RecorderSimulator().apply { handlers[4100] = { first } }
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val browser = RecorderBrowser(0, manager, MediaRepository(context, db, manager), backgroundScope)

        browser.refresh()
        eventually { !browser.state.value.loading }
        browser.loadMore()
        eventually { browser.state.value.listing.end != null }
        browser.loadMore() // ended: no further request

        assertThat(browser.state.value.listing.end).isEqualTo(ListingEnd.STOPPED)
        assertThat(browser.state.value.listing.files).hasSize(50)
        assertThat(cursors(sim)).hasSize(2)
    }

    @Test
    fun `a failed page keeps the listing and waits for retry`() = runTest {
        val sim = RecorderSimulator().apply { rvalOverrides[4100] = 302 }
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val browser = RecorderBrowser(0, manager, MediaRepository(context, db, manager), backgroundScope)

        browser.refresh()
        eventually { browser.state.value.error != null }
        browser.loadMore() // not polled while failed
        assertThat(browser.state.value.error!!.code).isEqualTo(302)
        assertThat(cursors(sim)).hasSize(1)

        sim.rvalOverrides.clear()
        sim.replies[4100] = SimulatedFiles.listReply(emptyList(), emptyList())
        browser.retry()
        eventually { browser.state.value.listing.end != null }
        assertThat(browser.state.value.error).isNull()
        assertThat(browser.state.value.listing.end).isEqualTo(ListingEnd.COMPLETE)
    }
}
