package to.axolotl.cam.media

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import org.robolectric.shadows.ShadowLooper
import to.axolotl.cam.core.data.AppDatabase
import to.axolotl.cam.recorder.RecorderFile

fun memoryDb(context: Context): AppDatabase =
    Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

/**
 * Room and WorkManager work on their own threads (WorkManager starts workers on the main looper): runs the test
 * dispatcher and the main looper until [condition] holds (real time).
 */
fun TestScope.eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        if (condition()) return
        check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
        Thread.sleep(10)
    }
}

fun recorderFile(path: String, time: String? = "2026-10-01 12:00:00") =
    RecorderFile(path, path.substringBeforeLast('.') + "_thm.jpg", time, JsonObject(emptyMap()))
