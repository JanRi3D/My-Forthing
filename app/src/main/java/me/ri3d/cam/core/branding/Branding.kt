package me.ri3d.cam.core.branding

import androidx.annotation.StringRes
import me.ri3d.cam.R

/** Renaming the app = this file + launcher icon (res/drawable/ic_launcher_*) + `app_name`. */
object Branding {
    @StringRes
    val appName: Int = R.string.app_name

    /** Root folder in the user's Google Drive (Drive format v1). */
    const val driveRootFolderName = "My Forthing"

    // TODO(owner): the owner sets the real support URL (still the old placeholder domain); terms/privacy links derive from it.
    const val supportUrl = "https://axolotl.to"
}
