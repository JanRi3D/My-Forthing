package to.axolotl.cam.plates

import kotlinx.serialization.Serializable
import javax.inject.Inject
import kotlin.math.roundToInt

/** One `plates` entry of the Drive sidecar (CONTRACTS §10). [box] = [left, top, right, bottom] in frame pixels. */
@Serializable
data class SidecarPlate(
    val text: String,
    val normalized: String,
    val positionMs: Long?,
    val confidence: Float?,
    val box: List<Int>,
)

/**
 * Plate metadata for the backup sidecar. Local by default: the backup feature calls [forMedia] only while
 * `AppPreferences.backupIncludePlateMetadata` is on.
 */
class PlateExport @Inject constructor(private val dao: PlateDao) {
    suspend fun forMedia(mediaId: String): List<SidecarPlate> = dao.sightingsForMedia(mediaId).map {
        SidecarPlate(
            text = it.display,
            normalized = it.normalized,
            positionMs = it.positionMs,
            confidence = it.confidence,
            box = listOf(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom).map(Float::roundToInt),
        )
    }
}
