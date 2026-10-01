package to.axolotl.cam.core.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe destinations (CONTRACTS §4). A feature registers the routes it owns in its own
 * `NavGraphBuilder.xxxGraph()` and adds one line to [AxoNavHost]; navigating to an unregistered route crashes,
 * so entry points stay behind [to.axolotl.cam.core.FeatureFlags] until the owning feature lands.
 */
sealed interface Route

// Onboarding and accounts
@Serializable data object Welcome : Route
@Serializable data object SignIn : Route
@Serializable data object CreateAccount : Route
@Serializable data object VerifyEmail : Route
@Serializable data object ForgotPassword : Route
@Serializable data object ResetSent : Route
@Serializable data object OfflineProfile : Route
@Serializable data object Upgrade : Route

// Dashcam
@Serializable data object Home : Route
@Serializable data object Connection : Route
@Serializable data object Live : Route
@Serializable data class Recordings(val tab: String? = null) : Route
@Serializable data object SdCard : Route
@Serializable data class SdFiles(val category: String) : Route

// Settings
@Serializable data object Settings : Route
@Serializable data object Appearance : Route

// Plates, media, enhancement
@Serializable data class Plates(val query: String? = null) : Route
@Serializable data class PlateDetail(val plateId: Long) : Route
@Serializable data class Clip(val mediaId: String, val positionMs: Long = 0) : Route
@Serializable data class Enhance(val mediaId: String, val positionMs: Long) : Route
@Serializable data class Upscale(val mediaId: String) : Route

// Backup and storage
@Serializable data object Backup : Route
@Serializable data object Storage : Route

/** Home once a profile exists on this phone, otherwise onboarding. */
fun startDestination(hasProfile: Boolean): Route = if (hasProfile) Home else Welcome
