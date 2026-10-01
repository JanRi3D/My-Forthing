package to.axolotl.cam.plates

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import to.axolotl.cam.core.data.AppDatabase
import java.io.File

/**
 * Migration 1 → 2 against a database built from the exported v1 schema. Room validates the migrated schema
 * (tables, columns, indices, foreign keys) against the v2 entities when it opens the file. MigrationTestHelper is
 * not used: it needs the schemas as Robolectric assets (includeAndroidResources), which breaks Robolectric on
 * API 36 / JDK 21 here.
 */
@RunWith(RobolectricTestRunner::class)
class PlateMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
    }

    @Test
    fun `migration 1 to 2 keeps the profile and adds the plate history`() = runTest {
        createVersion1()

        val db = open(AppDatabase.MIGRATION_1_2)
        try {
            assertThat(db.localProfileDao().observe().first()?.displayName).isEqualTo("Mein Auto")
            val sighting = PlateSighting(
                plateId = 0, mediaId = "m1", positionMs = 0, source = SightingSource.CLIP, seenAt = 1, confidence = null,
                cropPath = null, boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            )
            db.plateDao().addSighting("BMK4821", "B-MK 4821", sighting)
            assertThat(db.plateDao().history().first().single().count).isEqualTo(1)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an incomplete migration fails validation`() {
        createVersion1()
        val noIndices = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `plate` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalized` TEXT NOT NULL, " +
                        "`display` TEXT NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `count` INTEGER NOT NULL)",
                )
            }
        }
        val db = open(noIndices)
        try {
            assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase }
        } finally {
            db.close()
        }
    }

    private fun open(migration: Migration) = Room.databaseBuilder(context, AppDatabase::class.java, DB)
        .addMigrations(migration)
        .allowMainThreadQueries()
        .build()

    /** Creates [DB] exactly as schemas/…/1.json describes it, with one profile row. */
    private fun createVersion1() {
        val schema = JSONObject(File(SCHEMA_V1).readText()).getJSONObject("database")
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(DB).callback(callback).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use {
            it.writableDatabase.execSQL("INSERT INTO local_profile VALUES ('p1', 'Mein Auto', NULL, 10, NULL)")
        }
    }

    private companion object {
        const val DB = "migration-test.db"
        const val SCHEMA_V1 = "schemas/to.axolotl.cam.core.data.AppDatabase/1.json" // unit tests run in app/
    }
}
