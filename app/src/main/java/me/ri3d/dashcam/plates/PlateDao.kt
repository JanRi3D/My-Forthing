package me.ri3d.dashcam.plates

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
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.MediaKind

/**
 * A distinct plate, keyed by [normalized]. [display] is the best reading so far (one without `?` replaces one
 * with `?`); [count], [firstSeen] and [lastSeen] summarise its sightings.
 */
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
 * One sighting with its own reading [display] (may contain `?` where the plate's best reading does not).
 * [mediaId]/[positionMs] link to the recording (CLIP); [cropPath] is relative to `filesDir`. The box (pixels of
 * the source frame, video pixels for clips) is stored for the backup sidecar.
 */
@Entity(
    tableName = "plate_sighting",
    foreignKeys = [ForeignKey(Plate::class, ["id"], ["plateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("plateId"), Index("mediaId"), Index("seenAt")],
)
data class PlateSighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plateId: Long,
    val display: String,
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

/** A sighting with what the plates UI needs of its recording (null columns: live, or the recording is gone). */
data class SightingRow(
    @Embedded val sighting: PlateSighting,
    val mediaKind: MediaKind?,
    val mediaCategory: MediaCategory?,
    val mediaRecorderTime: String?,
    val mediaLocalUri: String?,
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

    /** Newest first, each with its recording's kind, category, raw time and phone copy. */
    @Query(
        """SELECT s.*, m.kind AS mediaKind, m.category AS mediaCategory, m.recorderTime AS mediaRecorderTime,
           m.localUri AS mediaLocalUri FROM plate_sighting s LEFT JOIN media_item m ON m.id = s.mediaId
           WHERE s.plateId = :plateId ORDER BY s.seenAt DESC""",
    )
    fun sightingRows(plateId: Long): Flow<List<SightingRow>>

    @Query("SELECT * FROM plate_sighting WHERE mediaId = :mediaId ORDER BY positionMs")
    fun observeForMedia(mediaId: String): Flow<List<PlateSighting>>

    /** Plates with a sighting in an incident recording (category EVENT). */
    @Query("SELECT DISTINCT s.plateId FROM plate_sighting s JOIN media_item m ON m.id = s.mediaId WHERE m.category = 'EVENT'")
    fun incidentPlateIds(): Flow<List<Long>>

    @Query(
        """SELECT EXISTS(SELECT 1 FROM plate_sighting s JOIN plate p ON p.id = s.plateId
           WHERE p.normalized = :normalized AND s.mediaId = :mediaId AND ABS(s.positionMs - :positionMs) < :windowMs)""",
    )
    suspend fun hasClipSightingNear(normalized: String, mediaId: String, positionMs: Long, windowMs: Long): Boolean

    @Query(
        """SELECT s.display, p.normalized, s.positionMs, s.confidence, s.boxLeft, s.boxTop, s.boxRight, s.boxBottom
           FROM plate_sighting s JOIN plate p ON p.id = s.plateId WHERE s.mediaId = :mediaId ORDER BY s.positionMs""",
    )
    suspend fun sightingsForMedia(mediaId: String): List<MediaSighting>

    @Query("SELECT cropPath FROM plate_sighting WHERE mediaId = :mediaId AND cropPath IS NOT NULL")
    suspend fun cropsForMedia(mediaId: String): List<String>

    @Query("SELECT cropPath FROM plate_sighting WHERE plateId = :plateId AND cropPath IS NOT NULL")
    suspend fun cropsForPlate(plateId: Long): List<String>

    @Query("SELECT normalized FROM plate WHERE id = :id")
    suspend fun normalizedOf(id: Long): String?

    /** Sightings go with the plate (foreign key cascade). */
    @Query("DELETE FROM plate WHERE id = :id")
    suspend fun deletePlate(id: Long)

    @Query("SELECT * FROM plate WHERE normalized = :normalized")
    suspend fun byNormalized(normalized: String): Plate?

    @Insert
    suspend fun insert(plate: Plate): Long

    @Insert
    suspend fun insert(sighting: PlateSighting): Long

    @Query("UPDATE plate SET firstSeen = MIN(firstSeen, :seenAt), lastSeen = MAX(lastSeen, :seenAt), count = count + 1 WHERE id = :id")
    suspend fun countSighting(id: Long, seenAt: Long)

    /** A recording's row was replaced by another id (drive-restore merge): its sightings follow. */
    @Query("UPDATE plate_sighting SET mediaId = :to WHERE mediaId = :from")
    suspend fun moveSightings(from: String, to: String)

    @Query("UPDATE plate SET display = :display WHERE id = :id")
    suspend fun updateDisplay(id: Long, display: String)

    /**
     * Adds [sighting] to the plate [normalized] (created on first sighting); returns the sighting id. Readings with
     * `?` merge by [normalized] but keep their own text; the plate's display upgrades to the first reading without `?`.
     */
    @Transaction
    suspend fun addSighting(normalized: String, sighting: PlateSighting): Long {
        val existing = byNormalized(normalized)
        val plateId = if (existing != null) {
            countSighting(existing.id, sighting.seenAt)
            if ('?' in existing.display && '?' !in sighting.display) updateDisplay(existing.id, sighting.display)
            existing.id
        } else {
            insert(Plate(normalized = normalized, display = sighting.display, firstSeen = sighting.seenAt, lastSeen = sighting.seenAt, count = 1))
        }
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
