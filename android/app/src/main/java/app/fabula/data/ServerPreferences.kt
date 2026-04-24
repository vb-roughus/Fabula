package app.fabula.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    /** Stable id for this device, used when reporting progress back to the server. */
    fun deviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return "android-${androidId ?: "unknown"}"
    }

    companion object {
        private val BASE_URL_KEY = stringPreferencesKey("base_url")
    }
}
