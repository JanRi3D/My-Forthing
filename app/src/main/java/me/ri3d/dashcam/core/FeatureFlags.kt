package me.ri3d.dashcam.core

/**
 * Entry points into features that are not integrated yet. The owning feature flips its flag to true in the
 * same change that registers its routes.
 */
object FeatureFlags {
    /** Welcome / Offline-Profil account buttons, Settings → Konto (feature/accounts). */
    const val accounts = true

    /** Home plate search bar (feature/plates-ui). */
    const val plates = true

    /** Settings → Sicherung (feature/drive-backup). */
    const val backup = true

    /** Home → Live-Ansicht tile (feature/live-view). */
    const val live = true

    /** Home → Aufnahmen tile (feature/media). */
    const val media = true

    /** Home → SD-Karte tile (feature/recorder-connection). */
    const val dashcam = true
}
