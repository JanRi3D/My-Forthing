package to.axolotl.cam.plates

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A distinct plate reading. [count], [firstSeen] and [lastSeen] summarise its sightings. */
@Entity(tableName = "plate", indices = [Index(value = ["normalized"], unique = true)])
data class Plate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalized: String,
    val display: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
)

enum class SightingSource { LIVE, CLIP }

/**
 * One sighting. [mediaId]/[positionMs] link to the recording (CLIP); [cropPath] is relative to `filesDir`.
 * The box (frame coordinates of the source frame) is stored for the backup sidecar.
 */
@Entity(
    tableName = "plate_sighting",
    foreignKeys = [ForeignKey(Plate::class, ["id"], ["plateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("plateId"), Index("mediaId"), Index("seenAt")],
)
data class PlateSighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plateId: Long,
    val mediaId: String?,
    val positionMs: Long?,
    val source: SightingSource,
    val seenAt: Long,
    val confidence: Float?,
    val cropPath: String?,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

data class PlateWithSightings(
    @Embedded val plate: Plate,
    @Relation(parentColumn = "id", entityColumn = "plateId") val sightings: List<PlateSighting>,
)

/** A sighting in one recording, joined with its plate (backup sidecar rows). */
data class MediaSighting(
    val display: String,
    val normalized: String,
    val positionMs: Long?,
    val confidence: Float?,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

@Dao
interface PlateDao {
    @Query("SELECT * FROM plate ORDER BY lastSeen DESC")
    fun history(): Flow<List<Plate>>

    /** [fragment] must be normalized (alphanumerics only), so it carries no LIKE wildcards. */
    @Query("SELECT * FROM plate WHERE normalized LIKE '%' || :fragment || '%' ORDER BY lastSeen DESC")
    fun search(fragment: String): Flow<List<Plate>>

    @Transaction
    @Query("SELECT * FROM plate WHERE id = :id")
    fun plate(id: Long): Flow<PlateWithSightings?>

    @Query(
        """SELECT EXISTS(SELECT 1 FROM plate_sighting s JOIN plate p ON p.id = s.plateId
           WHERE p.normalized = :normalized AND s.mediaId = :mediaId AND ABS(s.positionMs - :positionMs) < :windowMs)""",
    )
    suspend fun hasClipSightingNear(normalized: String, mediaId: String, positionMs: Long, windowMs: Long): Boolean

    @Query(
        """SELECT p.display, p.normalized, s.positionMs, s.confidence, s.boxLeft, s.boxTop, s.boxRight, s.boxBottom
           FROM plate_sighting s JOIN plate p ON p.id = s.plateId WHERE s.mediaId = :mediaId ORDER BY s.positionMs""",
    )
    suspend fun sightingsForMedia(mediaId: String): List<MediaSighting>

    @Query("SELECT cropPath FROM plate_sighting WHERE mediaId = :mediaId AND cropPath IS NOT NULL")
    suspend fun cropsForMedia(mediaId: String): List<String>

    @Query("SELECT * FROM plate WHERE normalized = :normalized")
    suspend fun byNormalized(normalized: String): Plate?

    @Insert
    suspend fun insert(plate: Plate): Long

    @Insert
    suspend fun insert(sighting: PlateSighting): Long

    @Query("UPDATE plate SET firstSeen = MIN(firstSeen, :seenAt), lastSeen = MAX(lastSeen, :seenAt), count = count + 1 WHERE id = :id")
    suspend fun countSighting(id: Long, seenAt: Long)

    /** Adds [sighting] to the plate [normalized] (created on first sighting); returns the sighting id. */
    @Transaction
    suspend fun addSighting(normalized: String, display: String, sighting: PlateSighting): Long {
        val plateId = byNormalized(normalized)?.id?.also { countSighting(it, sighting.seenAt) }
            ?: insert(Plate(normalized = normalized, display = display, firstSeen = sighting.seenAt, lastSeen = sighting.seenAt, count = 1))
        return insert(sighting.copy(plateId = plateId))
    }

    @Query("DELETE FROM plate_sighting WHERE mediaId = :mediaId")
    suspend fun deleteSightingsForMedia(mediaId: String)

    @Query("DELETE FROM plate WHERE id NOT IN (SELECT plateId FROM plate_sighting)")
    suspend fun deleteOrphans()

    @Query(
        """UPDATE plate SET
           count = (SELECT COUNT(*) FROM plate_sighting s WHERE s.plateId = plate.id),
           firstSeen = (SELECT MIN(s.seenAt) FROM plate_sighting s WHERE s.plateId = plate.id),
           lastSeen = (SELECT MAX(s.seenAt) FROM plate_sighting s WHERE s.plateId = plate.id)""",
    )
    suspend fun recount()

    @Transaction
    suspend fun clearForMedia(mediaId: String) {
        deleteSightingsForMedia(mediaId)
        deleteOrphans()
        recount()
    }

    /** Sightings go with their plates (foreign key cascade). */
    @Query("DELETE FROM plate")
    suspend fun clear()
}
