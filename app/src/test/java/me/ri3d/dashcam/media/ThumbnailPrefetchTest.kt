package me.ri3d.dashcam.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Prefetch scheduling with fakes (virtual time): priority, gate (downloads, scrolling), idle dispatcher, cap. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailPrefetchTest {
    private val session = Any()

    private fun thumb(key: String) = RecorderThumb(key, "http://192.168.42.1/$key.thm", network = true)

    private class Fake {
        val fetched = mutableListOf<String>()
        val started = mutableListOf<String>()
        val cached = mutableSetOf<String>()
        var idle = true
        val notes = mutableListOf<String>()
    }

    private fun TestScope.loop(
        open: MutableStateFlow<Any?>,
        candidates: MutableStateFlow<List<RecorderThumb>>,
        fake: Fake,
        progress: PrefetchProgress = PrefetchProgress(),
        cap: Int = 300,
    ) = backgroundScope.launch {
        prefetchLoop(
            open, candidates, progress,
            isCached = { it in fake.cached },
            idle = { fake.idle },
            fetch = { fake.started += it.key; delay(100); fake.fetched += it.key },
            note = { fake.notes += it },
            cap = cap,
        )
    }

    @Test
    fun `visible items of the tab first, then the rest of the tab, then the other tabs`() {
        val rows = listOf(
            recorderItem("e1", 1, "/e1.mp4"), recorderItem("n1", 0, "/n1.mp4"), recorderItem("p1", 2, "/p1.jpg"),
            recorderItem("n2", 0, "/n2.mp4"), recorderItem("n3", 0, "/n3.mp4"), recorderItem("e2", 1, "/e2.mp4"),
        )
        val order = prefetchOrder(rows, PrefetchFocus(type = 0, visible = listOf("n3", "n2")))
        assertThat(order.map { it.id }).containsExactly("n3", "n2", "n1", "e1", "e2", "p1").inOrder()
        assertThat(prefetchOrder(rows, PrefetchFocus()).map { it.id }).containsExactly("n1", "n2", "n3", "e1", "e2", "p1").inOrder()
    }

    @Test
    fun `fetches one at a time in order and skips cached thumbnails`() = runTest {
        val fake = Fake().apply { cached += "b" }
        loop(MutableStateFlow(session), MutableStateFlow(listOf("a", "b", "c").map(::thumb)), fake)
        advanceTimeBy(150)
        assertThat(fake.started).containsExactly("a", "c") // c starts only after a finished
        advanceTimeBy(100)
        assertThat(fake.fetched).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `a download pauses the prefetch at once and it resumes afterwards`() = runTest {
        val fake = Fake()
        val open = MutableStateFlow<Any?>(null) // a download runs
        loop(open, MutableStateFlow(listOf("a", "b").map(::thumb)), fake)
        advanceTimeBy(1_000)
        assertThat(fake.started).isEmpty()

        open.value = session
        advanceTimeBy(50)
        open.value = null // a download starts while "a" is on its way: cancelled
        advanceTimeBy(1_000)
        assertThat(fake.started).containsExactly("a")
        assertThat(fake.fetched).isEmpty()

        open.value = session // download done: "a" is asked again
        advanceTimeBy(1_000)
        assertThat(fake.fetched).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `waits while other recorder requests run`() = runTest {
        val fake = Fake().apply { idle = false }
        loop(MutableStateFlow(session), MutableStateFlow(listOf(thumb("a"))), fake)
        advanceTimeBy(2_000)
        assertThat(fake.started).isEmpty()
        fake.idle = true
        advanceTimeBy(ThumbnailPrefetcher.IDLE_POLL_MS + 150)
        assertThat(fake.fetched).containsExactly("a")
    }

    @Test
    fun `stops at the cap per session, notes every 20 and starts over in a new session`() = runTest {
        val fake = Fake()
        val open = MutableStateFlow<Any?>(session)
        val candidates = MutableStateFlow((1..50).map { thumb("t$it") })
        val progress = PrefetchProgress()
        loop(open, candidates, fake, progress, cap = 40)
        advanceTimeBy(60_000)
        assertThat(fake.fetched).hasSize(40)
        assertThat(fake.notes).hasSize(2)
        assertThat(fake.notes.first()).startsWith("thumbnail prefetch: 20 fetched")

        open.value = null
        runCurrent()
        open.value = Any() // reconnected: a new session, a new budget
        advanceTimeBy(60_000)
        assertThat(fake.fetched).hasSize(80)
    }

    @Test
    fun `new rows or another tab wake the prefetch`() = runTest {
        val fake = Fake()
        val candidates = MutableStateFlow(listOf(thumb("a")))
        loop(MutableStateFlow(session), candidates, fake)
        advanceTimeBy(1_000)
        assertThat(fake.fetched).containsExactly("a")

        candidates.value = listOf(thumb("new"), thumb("a"))
        advanceTimeBy(1_000)
        assertThat(fake.fetched).containsExactly("a", "new").inOrder()
    }
}
