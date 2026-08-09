package app.fabula.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fabula_prefs")

class ServerPreferences(private val context: Context) {

    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL_KEY].orEmpty() }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[BASE_URL_KEY] = url.trim() }
    }

    /** Authentication token issued by /api/auth/login (or /api/setup). */
    val authToken: Flow<String?> = context.dataStore.data.map { it[AUTH_TOKEN_KEY] }

    suspend fun setAuthToken(token: String?) {
        context.dataStore.edit { p ->
            if (token.isNullOrBlank()) p.remove(AUTH_TOKEN_KEY)
            else p[AUTH_TOKEN_KEY] = token
        }
    }

    /** When on, LogStore persists warn/error entries to a rolling text file
     *  under context.filesDir/logs/ so the user can share it for support. */
    val diagnosticsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[DIAGNOSTICS_KEY] ?: false }

    suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DIAGNOSTICS_KEY] = enabled }
    }

    val sleepRepeatEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[SLEEP_REPEAT_KEY] ?: true }

    /** Time of day, in minutes since midnight, when the sleep auto-repeat
     *  stops kicking in (the "wake up at" time). Default: 07:00 = 420. */
    val sleepRepeatUntilMinutes: Flow<Int> = context.dataStore.data
        .map { it[SLEEP_UNTIL_KEY] ?: (7 * 60) }

    suspend fun setSleepRepeatEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SLEEP_REPEAT_KEY] = enabled }
    }

    suspend fun setSleepRepeatUntilMinutes(minutes: Int) {
        context.dataStore.edit { it[SLEEP_UNTIL_KEY] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    /** Sleep-timer duration in minutes (5-minute steps). Default: 30. */
    val sleepTimerMinutes: Flow<Int> = context.dataStore.data
        .map { it[SLEEP_TIMER_MINUTES_KEY] ?: 30 }

    suspend fun setSleepTimerMinutes(minutes: Int) {
        // Snap to 5-minute steps within a sensible range.
        val snapped = (minutes / 5) * 5
        context.dataStore.edit { it[SLEEP_TIMER_MINUTES_KEY] = snapped.coerceIn(5, 240) }
    }

    /** Extra gain (dB) applied by LoudnessEnhancer during shower mode. 0 = off. */
    val showerBoostDb: Flow<Float> = context.dataStore.data
        .map { it[SHOWER_BOOST_KEY] ?: 0f }

    suspend fun setShowerBoostDb(db: Float) {
        context.dataStore.edit { it[SHOWER_BOOST_KEY] = db.coerceIn(0f, 15f) }
    }

    /** UI colour scheme: "system", "light" or "dark". */
    val themeMode: Flow<String> = context.dataStore.data
        .map { it[THEME_MODE_KEY] ?: "system" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE_KEY] = mode }
    }

    /** When on, opening a book plays the chapter page-flip intro that settles
     *  on the resume chapter. Default: on. */
    val chapterFlipIntroEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[CHAPTER_FLIP_INTRO_KEY] ?: true }

    suspend fun setChapterFlipIntroEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CHAPTER_FLIP_INTRO_KEY] = enabled }
    }

    /** When on, offline downloads only run on Wi-Fi/Ethernet and park
     *  themselves on mobile data. Default: on. */
    val downloadWifiOnly: Flow<Boolean> = context.dataStore.data
        .map { it[DOWNLOAD_WIFI_ONLY_KEY] ?: true }

    suspend fun setDownloadWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[DOWNLOAD_WIFI_ONLY_KEY] = enabled }
    }

    /** Stable id for this device, used when reporting progress back to the server. */
    fun deviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return "android-${androidId ?: "unknown"}"
    }

    companion object {
        private val BASE_URL_KEY = stringPreferencesKey("base_url")
        private val SLEEP_REPEAT_KEY = booleanPreferencesKey("sleep_repeat_enabled")
        private val SLEEP_UNTIL_KEY = intPreferencesKey("sleep_repeat_until_minutes")
        private val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
        private val DIAGNOSTICS_KEY = booleanPreferencesKey("diagnostics_enabled")
        private val SHOWER_BOOST_KEY = floatPreferencesKey("shower_boost_db")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val SLEEP_TIMER_MINUTES_KEY = intPreferencesKey("sleep_timer_minutes")
        private val CHAPTER_FLIP_INTRO_KEY = booleanPreferencesKey("chapter_flip_intro_enabled")
    }
}
