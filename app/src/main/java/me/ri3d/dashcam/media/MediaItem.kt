package me.ri3d.dashcam.media

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ri3d.dashcam.recorder.RecorderValues
import java.io.File
import java.net.URI

/**
 * One recording, photo, screenshot or derived output (CONTRACTS §8). Up to three copies exist independently: on the
 * recorder ([recorderPath]), on the phone ([localUri]) and on Drive ([driveFileId]); deleting one never touches the
 * others. The row lives as long as one copy is known.
 */
@Entity(
    tableName = "media_item",
    indices = [Index(value = ["recorderPath"], unique = true), Index("parentId")],
)
data class MediaItem(
    /** UUID v4, stable, used in the Drive format. */
    @PrimaryKey val id: String,
    val kind: MediaKind,
    /** From the recorder listing type 0/1/2 (other → UNKNOWN); derived outputs inherit their original's. */
    val category: MediaCategory,
    /** 4100 `type` exactly as requested; null for files that never were on the recorder. */
    val recorderType: Int?,
    /** 4100 `fileName` (device path, cursor and 4101 argument); null once deleted on the recorder or unknown. */
    val recorderPath: String?,
    val recorderThumbPath: String?,
    val originalFileName: String,
    /** Raw `fileTime` ("yyyy-MM-dd HH:mm:ss"), time zone UNKNOWN. */
    val recorderTime: String?,
    /** [recorderTime] read in the phone's zone: a guess, labelled as such wherever it is shown. */
    val recorderTimeEpochGuess: Long?,
    /** `file:` URI of the phone copy in app storage ([File.toURI]); read it with [localFile]. */
    val localUri: String?,
    val localSizeBytes: Long?,
    /** Absolute path of the locally generated thumbnail (JPEG). */
    val localThumbPath: String?,
    val downloadedAt: Long?,
    /** For ENHANCED_FRAME / UPSCALED_CLIP: the original's id (the original may have been deleted since). */
    val parentId: String?,
    val parentPositionMs: Long?,
    val driveFileId: String?,
    val backupState: BackupState,
    val backupError: String?,
    val driveMd5: String?,
    val createdAt: Long,
) {
    val localFile: File? get() = localUri?.let { runCatching { File(URI(it)) }.getOrNull() }
    val isVideo: Boolean get() = kind == MediaKind.ORIGINAL_VIDEO || kind == MediaKind.UPSCALED_CLIP
    val isDerived: Boolean get() = kind == MediaKind.ENHANCED_FRAME || kind == MediaKind.UPSCALED_CLIP
}

enum class MediaKind { ORIGINAL_VIDEO, ORIGINAL_PHOTO, SCREENSHOT, ENHANCED_FRAME, UPSCALED_CLIP }

/** Recorder listing type 0/1/2 (the report's "normal", "event", "user data"); anything else UNKNOWN, raw kept. */
enum class MediaCategory(val recorderType: Int?) {
    NORMAL(RecorderValues.FILES_NORMAL),
    EVENT(RecorderValues.FILES_EVENT),
    USER(RecorderValues.FILES_USER),
    UNKNOWN(null);

    companion object {
        fun of(recorderType: Int?): MediaCategory = entries.firstOrNull { it.recorderType != null && it.recorderType == recorderType } ?: UNKNOWN
    }
}

enum class BackupState { NONE, QUEUED, UPLOADING, DONE, FAILED }

/** When a 4100 listing of [type] last ran to its end ("Stand" of the recorder tabs). */
@Entity(tableName = "listing_stamp")
data class ListingStamp(@PrimaryKey val type: Int, val listedAt: Long)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_item WHERE id = :id")
    fun observe(id: String): Flow<MediaItem?>

    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun get(id: String): MediaItem?

    @Query("SELECT * FROM media_item WHERE recorderPath = :path")
    suspend fun byRecorderPath(path: String): MediaItem?

    /** Kind/category filters are optional (null = any). Newest first: recorder time guess, else creation. */
    @Query(
        "SELECT * FROM media_item WHERE (:kind IS NULL OR kind = :kind) AND (:category IS NULL OR category = :category) " +
            "ORDER BY COALESCE(recorderTimeEpochGuess, createdAt) DESC",
    )
    fun observe(kind: MediaKind?, category: MediaCategory?): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_item WHERE localUri IS NOT NULL ORDER BY COALESCE(recorderTimeEpochGuess, createdAt) DESC")
    fun observeLocal(): Flow<List<MediaItem>>

    /** Everything with a Drive copy (Drive tab), newest first like [observe]. */
    @Query("SELECT * FROM media_item WHERE driveFileId IS NOT NULL ORDER BY COALESCE(recorderTimeEpochGuess, createdAt) DESC")
    fun observeDrive(): Flow<List<MediaItem>>

    /** Other rows of the same recorder file (type, name, raw time) without a Drive copy: candidates for a Drive import merge. */
    @Query(
        "SELECT * FROM media_item WHERE id != :id AND driveFileId IS NULL AND recorderType = :type AND originalFileName = :name " +
            "AND recorderTime = :time",
    )
    suspend fun twins(id: String, type: Int, name: String, time: String): List<MediaItem>

    @Query("SELECT COUNT(*) FROM media_item WHERE parentId = :id")
    suspend fun childCount(id: String): Int

    /** The recorder copies of one listing type as last known, newest recorder time first (unknown times last). */
    @Query("SELECT * FROM media_item WHERE recorderType = :type AND recorderPath IS NOT NULL $RECORDER_ORDER")
    fun observeRecorderType(type: Int): Flow<List<MediaItem>>

    /** Every known recorder copy in the same order (thumbnail prefetch). */
    @Query("SELECT * FROM media_item WHERE recorderPath IS NOT NULL $RECORDER_ORDER")
    fun observeRecorderCopies(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_item WHERE recorderType = :type AND recorderPath IS NOT NULL")
    suspend fun recorderType(type: Int): List<MediaItem>

    /** A row whose recorder copy was forgotten, matching a listed file by type, name and raw time. */
    @Query(
        "SELECT * FROM media_item WHERE recorderPath IS NULL AND recorderType = :type AND originalFileName = :name " +
            "AND recorderTime IS :time LIMIT 1",
    )
    suspend fun detached(type: Int, name: String, time: String?): MediaItem?

    @Query("SELECT * FROM media_item WHERE parentId = :id ORDER BY createdAt")
    fun observeChildren(id: String): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_item WHERE localUri IS NOT NULL AND kind IN (:kinds)")
    suspend fun localOf(kinds: List<MediaKind>): List<MediaItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaItem): Long

    @Update
    suspend fun update(item: MediaItem)

    @Query("DELETE FROM media_item WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT listedAt FROM listing_stamp WHERE type = :type")
    fun observeListedAt(type: Int): Flow<Long?>

    @Upsert
    suspend fun setListedAt(stamp: ListingStamp)
}

/** Newest raw recorder time first ("yyyy-MM-dd HH:mm:ss" sorts as text), unknown times last; path as tie-break. */
private const val RECORDER_ORDER = "ORDER BY recorderTime IS NULL, recorderTime DESC, recorderPath DESC"
