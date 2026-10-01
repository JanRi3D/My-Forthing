package to.axolotl.cam.plates

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import to.axolotl.cam.core.data.PreferencesRepository
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
 * Plate metadata for the backup sidecar, each sighting with its own reading. Local by default: empty unless
 * `AppPreferences.backupIncludePlateMetadata` is on (the backup feature checks it as well).
 */
class PlateExport @Inject constructor(private val dao: PlateDao, private val preferences: PreferencesRepository) {
    suspend fun forMedia(mediaId: String): List<SidecarPlate> {
        if (!preferences.preferences.first().backupIncludePlateMetadata) return emptyList()
        return dao.sightingsForMedia(mediaId).map {
            SidecarPlate(
                text = it.display,
                normalized = it.normalized,
                positionMs = it.positionMs,
                confidence = it.confidence,
                box = listOf(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom).map(Float::roundToInt),
            )
        }
    }
}
