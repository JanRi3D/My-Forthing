package to.axolotl.cam.plates

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import to.axolotl.cam.core.data.AppDatabase
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlateRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repository = PlateRepository(context, db.plateDao())

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "plates").deleteRecursively()
    }

    private fun det(text: String, confidence: Float? = 0.9f, box: RectF = RectF(100f, 50f, 300f, 90f)) =
        PlateText.match(text)!!.let { PlateDetection(it.display, it.normalized, confidence, box, 0, it.format) }

    private suspend fun plate(normalized: String) = repository.history().first().single { it.normalized == normalized }

    @Test
    fun `live sightings within 2 s of the previous detection are one sighting`() = runTest {
        val bmk = det("B-MK 4821")
        assertThat(repository.recordSightings(listOf(bmk), SightingSource.LIVE, seenAt = 10_000)).isEqualTo(1)
        assertThat(repository.recordSightings(listOf(bmk), SightingSource.LIVE, seenAt = 11_500)).isEqualTo(0)
        assertThat(repository.recordSightings(listOf(bmk), SightingSource.LIVE, seenAt = 13_000)).isEqualTo(0) // still in view
        assertThat(repository.recordSightings(listOf(bmk, bmk), SightingSource.LIVE, seenAt = 15_000)).isEqualTo(1)

        val p = plate("BMK4821")
        assertThat(p.count).isEqualTo(2)
        assertThat(p.firstSeen).isEqualTo(10_000)
        assertThat(p.lastSeen).isEqualTo(15_000)
        assertThat(p.display).isEqualTo("B-MK 4821")
    }

    @Test
    fun `clip sightings dedupe per position, also when the clip is scanned again`() = runTest {
        val bmk = det("B-MK 4821")
        listOf(0L, 500L, 1_000L, 5_000L).forEach {
            repository.recordSightings(listOf(bmk), SightingSource.CLIP, "m1", it, seenAt = 100 + it)
        }
        repository.recordSightings(listOf(bmk), SightingSource.CLIP, "m2", 0, seenAt = 50_000) // other clip
        assertThat(plate("BMK4821").count).isEqualTo(3)

        val afterRestart = PlateRepository(context, db.plateDao())
        assertThat(afterRestart.recordSightings(listOf(bmk), SightingSource.CLIP, "m1", 333, seenAt = 1)).isEqualTo(0)
        val sightings = repository.plate(plate("BMK4821").id).first()!!.sightings
        assertThat(sightings.map { it.mediaId to it.positionMs }).containsExactly("m1" to 0L, "m1" to 5_000L, "m2" to 0L)
        assertThat(sightings.all { it.source == SightingSource.CLIP }).isTrue()
    }

    @Test
    fun `search matches parts of the normalized plate`() = runTest {
        repository.recordSightings(listOf(det("B-MK 4821"), det("HH-JK 553")), SightingSource.LIVE, seenAt = 1)
        repository.recordSightings(listOf(det("M-AB 9042")), SightingSource.LIVE, seenAt = 2)

        assertThat(repository.search("b-mk").first().map { it.normalized }).containsExactly("BMK4821")
        assertThat(repository.search(" 4821").first().map { it.normalized }).containsExactly("BMK4821")
        assertThat(repository.search("hh").first().map { it.normalized }).containsExactly("HHJK553")
        assertThat(repository.search("%").first().map { it.normalized }).hasSize(3) // no wildcard: same as empty
        assertThat(repository.search("X").first()).isEmpty()
        assertThat(repository.history().first().first().normalized).isEqualTo("MAB9042") // newest first
    }

    @Test
    fun `uncertain readings keep the question marks and no confidence`() = runTest {
        repository.recordSightings(listOf(det("8-MK 4821", confidence = null)), SightingSource.LIVE, seenAt = 1)
        val p = plate("8MK4821")
        assertThat(p.display).isEqualTo("?-MK 4821")
        assertThat(repository.plate(p.id).first()!!.sightings.single().confidence).isNull()
    }

    @Test
    fun `crops are small jpegs that clearForMedia and clear delete`() = runTest {
        val frame = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val wide = det("B-MK 4821", box = RectF(200f, 500f, 1400f, 700f))
        repository.recordSightings(listOf(wide), SightingSource.CLIP, "m1", 0, frame, seenAt = 1)
        repository.recordSightings(listOf(det("HH-JK 553")), SightingSource.CLIP, "m2", 0, frame, seenAt = 2)
        repository.recordSightings(listOf(wide), SightingSource.LIVE, frame = frame, seenAt = 3)

        val bmk = repository.plate(plate("BMK4821").id).first()!!
        val clipCrop = File(context.filesDir, bmk.sightings.first { it.mediaId == "m1" }.cropPath!!)
        assertThat(clipCrop.exists()).isTrue()
        assertThat(BitmapFactory.decodeFile(clipCrop.path).width).isAtMost(PlateRepository.CROP_MAX_WIDTH)

        repository.clearForMedia("m1")
        assertThat(clipCrop.exists()).isFalse()
        assertThat(plate("BMK4821").count).isEqualTo(1) // the live sighting stays
        assertThat(plate("BMK4821").firstSeen).isEqualTo(3)
        assertThat(repository.history().first().map { it.normalized }).containsExactly("BMK4821", "HHJK553")

        repository.clearForMedia("m2")
        assertThat(repository.history().first().map { it.normalized }).containsExactly("BMK4821") // orphan removed

        repository.clear()
        assertThat(repository.history().first()).isEmpty()
        assertThat(File(context.filesDir, "plates").exists()).isFalse()
    }

    @Test
    fun `sidecar export lists the sightings of one recording`() = runTest {
        repository.recordSightings(listOf(det("B-MK 4821", box = RectF(10.4f, 20.6f, 30f, 40f))), SightingSource.CLIP, "m1", 37_000, seenAt = 1)
        repository.recordSightings(listOf(det("8-MK 4821", confidence = null)), SightingSource.CLIP, "m1", 41_000, seenAt = 2)
        repository.recordSightings(listOf(det("HH-JK 553")), SightingSource.LIVE, seenAt = 3)

        assertThat(PlateExport(db.plateDao()).forMedia("m1")).containsExactly(
            SidecarPlate("B-MK 4821", "BMK4821", 37_000, 0.9f, listOf(10, 21, 30, 40)),
            SidecarPlate("?-MK 4821", "8MK4821", 41_000, null, listOf(100, 50, 300, 90)),
        ).inOrder()
        assertThat(PlateExport(db.plateDao()).forMedia("none")).isEmpty()
    }
}
