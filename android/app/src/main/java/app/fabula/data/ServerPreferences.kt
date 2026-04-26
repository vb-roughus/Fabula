package app.fabula.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    }
}
