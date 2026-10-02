package me.ri3d.dashcam.dashcam

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.DeviceInfo
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderReply
import me.ri3d.dashcam.recorder.RecorderSettings
import me.ri3d.dashcam.recorder.StorageInfo
import me.ri3d.dashcam.recorder.parseDeviceInfo
import me.ri3d.dashcam.recorder.parseSettings
import me.ri3d.dashcam.recorder.parseStorageInfo

/**
 * The last successful reply of one read command ([CACHED_MSG_IDS]) per recorder, so screens can show it before (or
 * without) a session. [json] is the whole reply, redacted (the 4097 Wi-Fi password is never stored).
 */
@Entity(tableName = "recorder_fact", primaryKeys = ["productSN", "msgId"])
data class RecorderFact(val productSN: String, val msgId: Int, val json: String, val readAt: Long)

@Dao
interface RecorderFactDao {
    /** Every fact of the recorder read most recently. */
    @Query("SELECT * FROM recorder_fact WHERE productSN = (SELECT productSN FROM recorder_fact ORDER BY readAt DESC LIMIT 1)")
    fun observeLatestRecorder(): Flow<List<RecorderFact>>

    @Upsert
    suspend fun upsert(fact: RecorderFact)
}

/** A value read from the recorder in an earlier moment (or session), shown with "zuletzt gelesen <readAt>". */
data class Cached<T>(val value: T, val readAt: Long)

/** What the recorder last read reported, parsed with the same parsers as live replies. */
data class CachedFacts(
    val productSN: String,
    val deviceInfo: Cached<DeviceInfo>?,
    val settings: Cached<RecorderSettings>?,
    val storage: Cached<StorageInfo>?,
    val capabilities: Map<CapabilityGroup, Cached<RecorderReply>>,
) {
    companion object {
        /** 4098, 4097, 4099 and the capabilities the app uses (20481 RTSP, 20483 Wi-Fi modes, 20484 storage). */
        val CACHED_MSG_IDS = setOf(
            RecorderCommand.GetDeviceInfo.msgId, RecorderCommand.GetAllSettings.msgId, RecorderCommand.GetStorageInfo().msgId,
            CapabilityGroup.BASIC.msgId, CapabilityGroup.NETWORK.msgId, CapabilityGroup.STORAGE.msgId,
        )

        /** Null without rows; a row that no longer parses is left out. */
        fun of(rows: List<RecorderFact>): CachedFacts? {
            val byId = rows.associateBy { it.msgId }
            fun <T> parsed(msgId: Int, parse: (RecorderReply) -> T): Cached<T>? = byId[msgId]?.let { fact ->
                RecorderReply.parse(fact.json)?.let { reply -> runCatching { Cached(parse(reply), fact.readAt) }.getOrNull() }
            }
            return CachedFacts(
                productSN = rows.firstOrNull()?.productSN ?: return null,
                deviceInfo = parsed(RecorderCommand.GetDeviceInfo.msgId, ::parseDeviceInfo),
                settings = parsed(RecorderCommand.GetAllSettings.msgId, ::parseSettings),
                storage = parsed(RecorderCommand.GetStorageInfo().msgId, ::parseStorageInfo),
                capabilities = CapabilityGroup.entries.mapNotNull { group -> parsed(group.msgId) { it }?.let { group to it } }.toMap(),
            )
        }
    }
}
