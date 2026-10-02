package me.ri3d.cam.plates.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ri3d.cam.core.ui.UiState
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.media.MediaCategory
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.media.eventually
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.plates.PlateDetection
import me.ri3d.cam.plates.PlateFormat
import me.ri3d.cam.plates.PlateRepository
import me.ri3d.cam.plates.SightingSource
import me.ri3d.cam.recorder.RecorderSimulator
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Search, filter, detail and settings view models over a seeded Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatesViewModelsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val plates = PlateRepository(context, db.plateDao())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        listOf("media", "plates").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    /**
     * B-MK 4821: live (with a `?` reading, no confidence) and in incident clip "e" (on the phone);
     * HH-JK 553: loop clip "n" (not on the phone); M-AB 9042: live only.
     */
    private suspend fun seed() {
        db.mediaDao().insert(mediaItem(context, "e", category = MediaCategory.EVENT, downloadedAt = 1))
        db.mediaDao().insert(mediaItem(context, "n", category = MediaCategory.NORMAL))
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        plates.recordSightings(listOf(detection("B-MK 4821")), SightingSource.LIVE, seenAt = 10_000, frame = frame)
        // An unsure reading of the same plate (OCR guess M kept in normalized): its own display, no confidence.
        val unsure = PlateDetection("B ?K 4821", "BMK4821", null, RectF(0f, 0f, 1f, 1f), 0, PlateFormat.GERMAN)
        plates.recordSightings(listOf(unsure), SightingSource.LIVE, seenAt = 20_000)
        plates.recordSightings(listOf(detection("B-MK 4821")), SightingSource.CLIP, "e", 37_000, seenAt = 30_000, frame = frame)
        plates.recordSightings(listOf(detection("HH-JK 553")), SightingSource.CLIP, "n", 8_000, seenAt = 5_000)
        plates.recordSightings(listOf(detection("M-AB 9042")), SightingSource.LIVE, seenAt = 40_000)
    }

    private fun TestScope.scans() = ClipScans(
        MediaRepository(context, db, managerFor(RecorderSimulator())), backgroundScope, 0, { 0 }, MutableStateFlow(false), { _, _ -> 0 },
    )

    private fun TestScope.platesVm(query: String? = null): PlatesViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return PlatesViewModel(SavedStateHandle(mapOf("query" to query)), plates, scans()).also { vm -> backgroundScope.launch { vm.list.collect {} } }
    }

    private fun PlatesViewModel.displays() = list.value?.rows?.map { it.plate.display }

    @Test
    fun `search matches city letters or last digits, newest first`() = runTest {
        seed()
        val vm = platesVm()
        eventually { vm.displays() == listOf("M-AB 9042", "B-MK 4821", "HH-JK 553") }

        vm.setQuery("bmk")
        eventually { vm.displays() == listOf("B-MK 4821") }
        assertThat(vm.query.value).isEqualTo("BMK") // shown uppercase, like the plates
        vm.setQuery("553")
        eventually { vm.displays() == listOf("HH-JK 553") }
        vm.setQuery("b-")
        eventually { vm.displays() == listOf("M-AB 9042", "B-MK 4821") } // "B" anywhere in the normalized plate
        vm.setQuery("XY 1")
        eventually { vm.list.value?.rows?.isEmpty() == true && vm.list.value?.query == "XY 1" } // "Keine Kennzeichen passen zu …"
    }

    @Test
    fun `the query comes from the route and the incident filter keeps plates seen in event recordings`() = runTest {
        seed()
        val vm = platesVm(query = "4821")
        eventually { vm.displays() == listOf("B-MK 4821") }
        assertThat(vm.list.value!!.rows.single().incident).isTrue()

        vm.setQuery("")
        vm.setIncidentsOnly(true)
        eventually { vm.displays() == listOf("B-MK 4821") && vm.list.value!!.incidentsOnly }
        vm.setIncidentsOnly(false)
        eventually { vm.list.value?.rows?.size == 3 }
        assertThat(vm.list.value!!.rows.filter { it.incident }.map { it.plate.display }).containsExactly("B-MK 4821")
        assertThat(vm.list.value!!.rows.first { it.plate.display == "B-MK 4821" }.plate.count).isEqualTo(3)
    }

    @Test
    fun `detail lists sightings newest first with source, media copy and the unsure reading`() = runTest {
        seed()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = plates.history().first().single { it.normalized == "BMK4821" }.id
        val vm = PlateDetailViewModel(SavedStateHandle(mapOf("plateId" to id)), plates)
        backgroundScope.launch { vm.state.collect {} }
        eventually { vm.state.value is UiState.Ready }
        val data = (vm.state.value as UiState.Ready).data

        assertThat(data.plate.count).isEqualTo(3)
        assertThat(data.plate.firstSeen).isEqualTo(10_000)
        assertThat(data.plate.lastSeen).isEqualTo(30_000)
        assertThat(data.incident).isTrue()
        assertThat(data.sightings.map { it.sighting.seenAt }).containsExactly(30_000L, 20_000L, 10_000L).inOrder()
        val (clip, unsure, live) = data.sightings
        assertThat(clip.sighting.source).isEqualTo(SightingSource.CLIP)
        assertThat(clip.sighting.positionMs).isEqualTo(37_000)
        assertThat(clip.mediaCategory).isEqualTo(MediaCategory.EVENT)
        assertThat(clip.mediaLocalUri).isNotNull() // → link to Clip("e", 37 000)
        assertThat(clip.sighting.cropPath).isNotNull()
        assertThat(unsure.sighting.display).isEqualTo("B ?K 4821")
        assertThat(unsure.sighting.confidence).isNull() // → "unsicher gelesen"
        assertThat(live.mediaKind).isNull()
    }

    @Test
    fun `a sighting in a recording that is not on the phone has no local copy`() = runTest {
        seed()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = plates.history().first().single { it.normalized == "HHJK553" }.id
        val vm = PlateDetailViewModel(SavedStateHandle(mapOf("plateId" to id)), plates)
        backgroundScope.launch { vm.state.collect {} }
        eventually { vm.state.value is UiState.Ready }
        val row = (vm.state.value as UiState.Ready).data.sightings.single()
        assertThat(row.mediaCategory).isEqualTo(MediaCategory.NORMAL)
        assertThat(row.mediaLocalUri).isNull() // → "Aufnahme nicht auf dem Handy"
        assertThat((vm.state.value as UiState.Ready).data.incident).isFalse()
    }

    @Test
    fun `deleting one plate removes its sightings and crops, other plates stay`() = runTest {
        seed()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = plates.history().first().single { it.normalized == "BMK4821" }.id
        val crops = File(context.filesDir, "plates").listFiles()!!.toList()
        assertThat(crops).hasSize(2)
        val vm = PlateDetailViewModel(SavedStateHandle(mapOf("plateId" to id)), plates)
        backgroundScope.launch { vm.state.collect {} }
        eventually { vm.state.value is UiState.Ready }

        vm.delete()
        eventually { vm.state.value == UiState.Empty } // the screen closes
        assertThat(crops.none { it.exists() }).isTrue()
        assertThat(plates.history().first().map { it.normalized }).containsExactly("MAB9042", "HHJK553")
    }

    @Test
    fun `settings switch the three preferences and clear the whole history`() = runTest {
        seed()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = preferencesIn(tmp.root)
        val vm = PlatesSettingsViewModel(preferences, plates)
        backgroundScope.launch { vm.state.collect {} }
        eventually { vm.state.value != null }
        assertThat(vm.state.value!!.backupIncludePlateMetadata).isFalse() // local by default

        vm.update { it.copy(platesLive = true) }
        vm.update { it.copy(platesClips = true) }
        vm.update { it.copy(backupIncludePlateMetadata = true) }
        eventually { vm.state.value!!.let { it.platesLive && it.platesClips && it.backupIncludePlateMetadata } }

        val history = plates.history().stateIn(backgroundScope, SharingStarted.Eagerly, null)
        eventually { history.value?.size == 3 }
        vm.clearHistory()
        eventually { history.value?.isEmpty() == true }
        assertThat(File(context.filesDir, "plates").exists()).isFalse()
    }

    @Test
    fun `the incident badge goes when the recording's library row is gone, the sightings stay`() = runTest {
        seed()
        val vm = platesVm(query = "4821")
        eventually { vm.list.value?.rows?.singleOrNull()?.incident == true }
        db.mediaDao().delete("e")
        eventually { vm.list.value?.rows?.singleOrNull()?.incident == false }
        assertThat(vm.list.value!!.rows.single().plate.count).isEqualTo(3)
    }

    @Test
    fun `the live list shows the newest three plates of this visit`() = runTest {
        listOf("B-MK 4821" to 1_000L, "HH-JK 553" to 2_000L, "M-AB 9042" to 3_000L, "TF-GH 64" to 4_000L, "K-LT 207" to 5_000L)
            .forEach { (text, at) -> plates.recordSightings(listOf(detection(text)), SightingSource.LIVE, seenAt = at) }
        val history = plates.history().first()
        val seen = setOf("BMK4821", "HHJK553", "MAB9042", "TFGH64") // K-LT 207 was seen before this visit
        assertThat(recentPlates(history, seen).map { it.display }).containsExactly("TF-GH 64", "M-AB 9042", "HH-JK 553").inOrder()
        assertThat(recentPlates(history, emptySet())).isEmpty()
    }
}
