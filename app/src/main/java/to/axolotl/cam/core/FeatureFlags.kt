package to.axolotl.cam.core

/**
 * Entry points into features that are not integrated yet. The owning feature flips its flag to true in the
 * same change that registers its routes.
 */
object FeatureFlags {
    /** Welcome / Offline-Profil account buttons, Settings → Konto (feature/accounts). */
    const val accounts = false

    /** Home plate search bar (feature/plates-ui). */
    const val plates = false

    /** Settings → Sicherung (feature/drive-backup). */
    const val backup = false

    /** Home → Live-Ansicht tile (feature/live-view). */
    const val live = false

    /** Home → Aufnahmen tile (feature/media). */
    const val media = false

    /** Home → SD-Karte tile (feature/recorder-connection). */
    const val dashcam = true
}
