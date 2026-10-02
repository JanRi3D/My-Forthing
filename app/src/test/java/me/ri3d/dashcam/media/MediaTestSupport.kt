package me.ri3d.dashcam.media

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import org.robolectric.shadows.ShadowLooper
import me.ri3d.dashcam.core.data.AppDatabase
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.recorder.RecorderFile
import java.io.File

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

/** A library row of a recorder copy, its `.thm` thumbnail next to it as on the recorder. */
fun recorderItem(id: String, type: Int, path: String, time: String? = "2026-10-01 12:00:00") = MediaItem(
    id = id, kind = MediaKind.ORIGINAL_VIDEO, category = MediaCategory.of(type), recorderType = type, recorderPath = path,
    recorderThumbPath = path.substringBeforeLast('.') + ".thm", originalFileName = path.substringAfterLast('/'), recorderTime = time,
    recorderTimeEpochGuess = null, localUri = null, localSizeBytes = null, localThumbPath = null, downloadedAt = null, parentId = null,
    parentPositionMs = null, driveFileId = null, backupState = BackupState.NONE, backupError = null, driveMd5 = null, createdAt = 0,
)

/** Preferences on a fresh temporary DataStore with its own IO scope (default values until updated). */
fun testPreferences(): PreferencesRepository = PreferencesRepository(
    PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
        File.createTempFile("prefs", ".preferences_pb").apply { delete() }
    },
)
