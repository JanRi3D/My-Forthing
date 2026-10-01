package to.axolotl.cam.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import to.axolotl.cam.core.model.LocalProfile
import to.axolotl.cam.core.profile.LocalProfileDao
import to.axolotl.cam.plates.Plate
import to.axolotl.cam.plates.PlateDao
import to.axolotl.cam.plates.PlateSighting

/**
 * The app's only Room database. Features append their entities and DAOs here, bump [version] and add
 * a migration to [MIGRATIONS] (schemas are exported to app/schemas). Never use destructive migration.
 */
@Database(entities = [LocalProfile::class, Plate::class, PlateSighting::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localProfileDao(): LocalProfileDao
    abstract fun plateDao(): PlateDao

    companion object {
        /** 1 → 2: plate history (feature/plates-core); SQL identical to schemas/…/2.json. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plate` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`normalized` TEXT NOT NULL, `display` TEXT NOT NULL, `firstSeen` INTEGER NOT NULL, " +
                        "`lastSeen` INTEGER NOT NULL, `count` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_plate_normalized` ON `plate` (`normalized`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plate_sighting` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`plateId` INTEGER NOT NULL, `display` TEXT NOT NULL, `mediaId` TEXT, `positionMs` INTEGER, " +
                        "`source` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, `confidence` REAL, `cropPath` TEXT, `boxLeft` REAL NOT NULL, " +
                        "`boxTop` REAL NOT NULL, `boxRight` REAL NOT NULL, `boxBottom` REAL NOT NULL, " +
                        "FOREIGN KEY(`plateId`) REFERENCES `plate`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plate_sighting_plateId` ON `plate_sighting` (`plateId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plate_sighting_mediaId` ON `plate_sighting` (`mediaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plate_sighting_seenAt` ON `plate_sighting` (`seenAt`)")
            }
        }

        /** Registered in CoreDataModule; append new migrations here. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
