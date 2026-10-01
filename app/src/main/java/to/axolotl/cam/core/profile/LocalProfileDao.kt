package to.axolotl.cam.core.profile

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import to.axolotl.cam.core.model.LocalProfile

@Dao
interface LocalProfileDao {
    /** The profile of this phone, or null before onboarding. */
    @Query("SELECT * FROM local_profile ORDER BY createdAt LIMIT 1")
    fun observe(): Flow<LocalProfile?>

    @Upsert
    suspend fun upsert(profile: LocalProfile)
}
