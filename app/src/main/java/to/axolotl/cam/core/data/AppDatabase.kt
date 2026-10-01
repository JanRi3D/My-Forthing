package to.axolotl.cam.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import to.axolotl.cam.core.model.LocalProfile
import to.axolotl.cam.core.profile.LocalProfileDao

/**
 * The app's only Room database. Features append their entities and DAOs here, bump [version] and add
 * a migration (schemas are exported to app/schemas). Never use destructive migration.
 */
@Database(entities = [LocalProfile::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localProfileDao(): LocalProfileDao
}
