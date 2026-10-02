package me.ri3d.dashcam.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.ri3d.dashcam.core.model.LocalProfile
import me.ri3d.dashcam.core.profile.LocalProfileDao
import me.ri3d.dashcam.dashcam.RecorderFact
import me.ri3d.dashcam.dashcam.RecorderFactDao
import me.ri3d.dashcam.media.ListingStamp
import me.ri3d.dashcam.media.MediaDao
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.plates.Plate
import me.ri3d.dashcam.plates.PlateDao
import me.ri3d.dashcam.plates.PlateSighting

/**
 * The app's only Room database. Features append their entities and DAOs here, bump [version] and add
 * a migration to [MIGRATIONS] (schemas are exported to app/schemas). Never use destructive migration.
 */
@Database(
    entities = [LocalProfile::class, Plate::class, PlateSighting::class, MediaItem::class, ListingStamp::class, RecorderFact::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localProfileDao(): LocalProfileDao
    abstract fun plateDao(): PlateDao
    abstract fun mediaDao(): MediaDao
    abstract fun recorderFactDao(): RecorderFactDao

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

        /** 2 → 3: media library (feature/media); SQL identical to schemas/…/3.json. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_item` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`recorderType` INTEGER, `recorderPath` TEXT, `recorderThumbPath` TEXT, `originalFileName` TEXT NOT NULL, " +
                        "`recorderTime` TEXT, `recorderTimeEpochGuess` INTEGER, `localUri` TEXT, `localSizeBytes` INTEGER, " +
                        "`localThumbPath` TEXT, `downloadedAt` INTEGER, `parentId` TEXT, `parentPositionMs` INTEGER, `driveFileId` TEXT, " +
                        "`backupState` TEXT NOT NULL, `backupError` TEXT, `driveMd5` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_media_item_recorderPath` ON `media_item` (`recorderPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_item_parentId` ON `media_item` (`parentId`)")
            }
        }

        /** 3 → 4: listing time per recorder type (feature/media) and cached recorder facts (dashcam); SQL as schemas/…/4.json. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `listing_stamp` (`type` INTEGER NOT NULL, `listedAt` INTEGER NOT NULL, PRIMARY KEY(`type`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recorder_fact` (`productSN` TEXT NOT NULL, `msgId` INTEGER NOT NULL, `json` TEXT NOT NULL, " +
                        "`readAt` INTEGER NOT NULL, PRIMARY KEY(`productSN`, `msgId`))",
                )
            }
        }

        /** Registered in CoreDataModule; append new migrations here. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
