package me.ri3d.cam

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app is German only (CONTRACTS §2), whatever the phone language, so library texts (Media3 controls,
 * Material 3 sheets) and date/number formats are German too.
 */
private val APP_LOCALES: LocaleList = LocaleList.forLanguageTags("de")

/**
 * Default locale for the process; on Android 13+ also the per-app language, which the system stores and applies to
 * every context of the app from then on (workers and notifications included).
 */
fun pinAppLocale(context: Context) {
    Locale.setDefault(APP_LOCALES[0])
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = context.getSystemService(LocaleManager::class.java)
        if (manager.applicationLocales != APP_LOCALES) manager.applicationLocales = APP_LOCALES
    }
}

/**
 * A German configuration for an activity's base context: below Android 13 there is no per-app language, and on 13+
 * the activity of the very first start is created before the system has applied it.
 * ponytail: below 13 the application context keeps the phone language, so library texts and file sizes resolved
 * through it (notifications) may follow the phone; override the application resources too if that shows up.
 */
fun Context.withAppLocale(): Context {
    Locale.setDefault(APP_LOCALES[0]) // again here: a system language change resets it and recreates the activity
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocales(APP_LOCALES) })
}
