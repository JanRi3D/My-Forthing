package me.ri3d.cam.media

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.cam.core.data.AppDatabase
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.recorder.RecorderSimulator
import java.io.File

/**
 * Migration 2 → 3 against a database built from the exported v2 schema; Room validates the result against the v3
 * entities on open (same approach as PlateMigrationTest: MigrationTestHelper breaks Robolectric here).
 */
@RunWith(RobolectricTestRunner::class)
class MediaMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
    }

    @Test
    fun `migration 2 to 3 keeps profile and plates and adds the media library`() = runTest {
        createVersion2()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertThat(db.localProfileDao().observe().first()?.displayName).isEqualTo("Mein Auto")
            assertThat(db.plateDao().history().first().single().normalized).isEqualTo("BMK4821")
            val repository = MediaRepository(context, db, managerFor(RecorderSimulator()))
            repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4")))
            val item = db.mediaDao().byRecorderPath("/sim/a.mp4")!!
            assertThat(item.kind).isEqualTo(MediaKind.ORIGINAL_VIDEO)
            assertThat(item.backupState).isEqualTo(BackupState.NONE)
        } finally {
            db.close()
        }
    }

    /** Creates [DB] exactly as schemas/…/2.json describes it, with one profile and one plate. */
    private fun createVersion2() {
        val schema = JSONObject(File(SCHEMA_V2).readText()).getJSONObject("database")
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(DB).callback(callback).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use {
            it.writableDatabase.execSQL("INSERT INTO local_profile VALUES ('p1', 'Mein Auto', NULL, 10, NULL)")
            it.writableDatabase.execSQL("INSERT INTO plate (normalized, display, firstSeen, lastSeen, count) VALUES ('BMK4821', 'B-MK 4821', 1, 1, 1)")
        }
    }

    private companion object {
        const val DB = "media-migration-test.db"
        const val SCHEMA_V2 = "schemas/me.ri3d.cam.core.data.AppDatabase/2.json" // unit tests run in app/
    }
}
