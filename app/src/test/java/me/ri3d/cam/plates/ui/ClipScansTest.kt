package me.ri3d.cam.plates.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.media.MediaCategory
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.media.eventually
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.recorder.RecorderSimulator
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** The app-wide clip check queue: progress, cancel, refusal of derived items, and the automatic check of new downloads. */
@RunWith(RobolectricTestRunner::class)
class ClipScansTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val dao = db.mediaDao()

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "media").deleteRecursively()
    }

    /** Scans are recorded; each waits for its gate (completed with the number of plates found, or an exception). */
    private class FakeScanner {
        val started = mutableListOf<String>()
        val gates = HashMap<String, CompletableDeferred<Int>>()
        var progress: ((Float) -> Unit)? = null

        fun gate(id: String) = gates.getOrPut(id) { CompletableDeferred() }

        suspend fun scan(item: MediaItem, onProgress: (Float) -> Unit): Int {
            started += item.id
            progress = onProgress
            return gate(item.id).await()
        }
    }

    private fun TestScope.scans(scanner: FakeScanner, platesClips: Flow<Boolean> = MutableStateFlow(false), clock: () -> Long = { 5_000 }) =
        ClipScans(
            MediaRepository(context, db, managerFor(RecorderSimulator())),
            // A supervisor like the app's: a failed scan stays in its Deferred.
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
            processStartMs = 1_000, clock = clock, autoEnabled = platesClips, scan = scanner::scan,
        )

    @Test
    fun `a check reports progress, finishes with the number of plates and runs one clip at a time`() = runTest {
        val scanner = FakeScanner()
        val scans = scans(scanner)
        val a = mediaItem(context, "a", downloadedAt = 10).also { dao.insert(it) }
        val b = mediaItem(context, "b", downloadedAt = 10).also { dao.insert(it) }

        assertThat(scans.enqueue(a)).isTrue()
        assertThat(scans.enqueue(b)).isTrue()
        assertThat(scans.enqueue(a)).isTrue() // already queued: no second entry
        eventually { scans.state.value.running == "a" && scanner.started == listOf("a") }
        assertThat(scans.state.value.queued).containsExactly("b")

        scanner.progress!!(0.4f)
        assertThat(scans.state.value.fraction).isEqualTo(0.4f)
        assertThat(scanner.started).containsExactly("a") // b waits

        scanner.gate("a").complete(0)
        eventually { scans.state.value.running == "b" }
        assertThat(scans.state.value.results["a"]).isEqualTo(ClipScans.Result.Done(0)) // "Keine Kennzeichen gefunden"
        scanner.gate("b").complete(2)
        eventually { scans.state.value.results["b"] == ClipScans.Result.Done(2) }
        assertThat(scans.state.value.running).isNull()
    }

    @Test
    fun `cancel stops the running check and drops a queued one`() = runTest {
        val scanner = FakeScanner()
        val scans = scans(scanner)
        val a = mediaItem(context, "a", downloadedAt = 10).also { dao.insert(it) }
        val b = mediaItem(context, "b", downloadedAt = 10).also { dao.insert(it) }
        scans.enqueue(a)
        scans.enqueue(b)
        eventually { scans.state.value.running == "a" } // possibly before the scan itself started

        scans.cancel("b")
        assertThat(scans.state.value.queued).isEmpty()
        scans.cancel("a")
        eventually { scans.state.value.results["a"] == ClipScans.Result.Cancelled }
        assertThat(scans.state.value.running).isNull()
        assertThat(scanner.started).doesNotContain("b")
    }

    @Test
    fun `a failing clip is reported and the queue goes on`() = runTest {
        val scanner = FakeScanner()
        val scans = scans(scanner)
        val a = mediaItem(context, "a", downloadedAt = 10).also { dao.insert(it) }
        val b = mediaItem(context, "b", downloadedAt = 10).also { dao.insert(it) }
        scans.enqueue(a)
        scans.enqueue(b)
        eventually { scans.state.value.running == "a" }
        scanner.gate("a").completeExceptionally(IllegalStateException("clip has no duration"))
        eventually { scans.state.value.running == "b" }
        assertThat(scans.state.value.results["a"]).isEqualTo(ClipScans.Result.Failed)
    }

    @Test
    fun `derived outputs, photos and recordings without a phone copy are refused`() = runTest {
        val scans = scans(FakeScanner())
        assertThat(scans.enqueue(mediaItem(context, "u", MediaKind.UPSCALED_CLIP, downloadedAt = 10, parentId = "a"))).isFalse()
        assertThat(scans.enqueue(mediaItem(context, "e", MediaKind.ENHANCED_FRAME, downloadedAt = 10, parentId = "a"))).isFalse()
        assertThat(scans.enqueue(mediaItem(context, "p", MediaKind.ORIGINAL_PHOTO, downloadedAt = 10))).isFalse()
        assertThat(scans.enqueue(mediaItem(context, "r", downloadedAt = null))).isFalse()
        assertThat(scans.state.value.queued).isEmpty()
    }

    @Test
    fun `with platesClips on, only originals downloaded since process start are checked automatically`() = runTest {
        val scanner = FakeScanner()
        listOf(
            mediaItem(context, "old", downloadedAt = 999), // before process start
            mediaItem(context, "new", downloadedAt = 1_000, category = MediaCategory.EVENT),
            mediaItem(context, "derived", MediaKind.UPSCALED_CLIP, downloadedAt = 2_000, parentId = "new"),
            mediaItem(context, "photo", MediaKind.ORIGINAL_PHOTO, downloadedAt = 2_000),
            mediaItem(context, "remote", downloadedAt = null),
        ).forEach { dao.insert(it) }
        val scans = scans(scanner, MutableStateFlow(true)) // process start 1 000
        scans.startAutoScan()
        scans.startAutoScan() // idempotent

        eventually { scanner.started == listOf("new") }
        scanner.gate("new").complete(1)
        eventually { scans.state.value.results["new"] == ClipScans.Result.Done(1) }

        // A later download is checked; the finished one is not checked again when the library changes.
        dao.insert(mediaItem(context, "later", downloadedAt = 3_000))
        eventually { scanner.started == listOf("new", "later") }
        scanner.gate("later").complete(0)
        dao.update(dao.get("new")!!.copy(originalFileName = "renamed.mp4"))
        eventually { scans.state.value.results["later"] == ClipScans.Result.Done(0) }
        assertThat(scanner.started).containsExactly("new", "later")
    }

    @Test
    fun `with platesClips off nothing is checked automatically, and switching on checks only later downloads`() = runTest {
        val scanner = FakeScanner()
        val platesClips = MutableStateFlow(false)
        val scans = scans(scanner, platesClips) // clock: 5 000
        dao.insert(mediaItem(context, "while-off", downloadedAt = 2_000))
        scans.startAutoScan()
        testScheduler.runCurrent()
        assertThat(scanner.started).isEmpty()

        platesClips.value = true
        dao.insert(mediaItem(context, "before-switch", downloadedAt = 4_000))
        dao.insert(mediaItem(context, "after-switch", downloadedAt = 6_000))
        eventually { scanner.started == listOf("after-switch") }
        assertThat(scanner.started).doesNotContain("while-off")
    }

    @Test
    fun `downloads made while the switch was off are not checked when it goes on again`() = runTest {
        val scanner = FakeScanner()
        val platesClips = MutableStateFlow(true)
        var now = 2_000L
        val scans = scans(scanner, platesClips, clock = { now }) // process start 1 000
        scans.startAutoScan()
        testScheduler.runCurrent()
        platesClips.value = false
        testScheduler.runCurrent()
        dao.insert(mediaItem(context, "while-off", downloadedAt = 3_000))
        now = 4_000
        platesClips.value = true
        testScheduler.runCurrent()
        dao.insert(mediaItem(context, "after-on", downloadedAt = 5_000))
        eventually { scanner.started == listOf("after-on") }
        assertThat(scans.state.value.queued).isEmpty()
    }

    @Test
    fun `a queued clip whose phone copy went away is skipped without an outcome`() = runTest {
        val scanner = FakeScanner()
        val scans = scans(scanner)
        val a = mediaItem(context, "a", downloadedAt = 10).also { dao.insert(it) }
        val b = mediaItem(context, "b", downloadedAt = 10).also { dao.insert(it) }
        scans.enqueue(a)
        scans.enqueue(b)
        eventually { scanner.started == listOf("a") }
        b.localFile!!.delete()
        scanner.gate("a").complete(0)
        eventually { scans.state.value.running == null && scans.state.value.queued.isEmpty() && "a" in scans.state.value.results }
        assertThat(scanner.started).containsExactly("a")
        assertThat(scans.state.value.results).doesNotContainKey("b") // no "Prüfung fehlgeschlagen" for a deleted copy
    }
}
