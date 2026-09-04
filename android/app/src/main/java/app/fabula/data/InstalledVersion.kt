package app.fabula.data

import android.content.Context

/**
 * The build installed on this device: code and name.
 *
 * Read in two places -- the update section shows it, and the banner compares
 * against it -- so it lives here rather than being written twice with two
 * chances of getting the API-level branch wrong.
 */
fun installedVersion(context: Context): Pair<Long, String> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
    else @Suppress("DEPRECATION") info.versionCode.toLong()
    return code to (info.versionName ?: "?")
}
