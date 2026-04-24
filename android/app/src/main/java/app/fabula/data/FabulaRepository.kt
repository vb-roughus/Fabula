package app.fabula.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class FabulaRepository(private val preferences: ServerPreferences) {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private var currentApi: FabulaApi? = null
    private var currentBaseUrl: String? = null

    val baseUrlFlow: Flow<String> = preferences.baseUrl

    suspend fun apiOrNull(): FabulaApi? {
        val raw = preferences.baseUrl.first()
        val normalised = normaliseBaseUrl(raw) ?: return null
        if (currentBaseUrl != normalised) {
            currentApi = Retrofit.Builder()
                .baseUrl(normalised)
                .client(okHttp)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FabulaApi::class.java)
            currentBaseUrl = normalised
            _baseUrl.value = normalised
        }
        return currentApi
    }

    suspend fun setBaseUrl(url: String) {
        preferences.setBaseUrl(url)
        currentBaseUrl = null
        currentApi = null
        _baseUrl.value = url
    }

    fun coverUrl(book: BookSummaryDto): String? = book.coverUrl?.let { resolveRelative(it) }
    fun coverUrl(book: BookDetailDto): String? = book.coverUrl?.let { resolveRelative(it) }
    fun coverUrl(series: SeriesSummaryDto): String? = series.coverUrl?.let { resolveRelative(it) }

    fun resolveUrl(path: String): String? = resolveRelative(path)
    fun streamUrl(audioFileId: Int): String? {
        val base = currentBaseUrl ?: return null
        return base + "api/stream/$audioFileId"
    }

    fun deviceId(): String = preferences.deviceId()

    private fun resolveRelative(path: String): String? {
        val base = currentBaseUrl ?: return null
        return if (path.startsWith("http")) path else base + path.trimStart('/')
    }

    companion object {
        fun normaliseBaseUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }
    }
}
