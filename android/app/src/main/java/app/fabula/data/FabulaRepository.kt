package app.fabula.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    /** Bumps every time a bookmark is created/edited/deleted, so screens that
     *  show bookmarks (BookScreen) can re-fetch without prop drilling. */
    private val _bookmarksRevision = MutableStateFlow(0)
    val bookmarksRevision: StateFlow<Int> = _bookmarksRevision.asStateFlow()
    fun bumpBookmarksRevision() { _bookmarksRevision.value = _bookmarksRevision.value + 1 }

    /** Bumps every time the series catalog changes (create/update/delete or
     *  a book's series assignment changes), so list screens can re-fetch. */
    private val _seriesRevision = MutableStateFlow(0)
    val seriesRevision: StateFlow<Int> = _seriesRevision.asStateFlow()
    fun bumpSeriesRevision() { _seriesRevision.value = _seriesRevision.value + 1 }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Emits whenever the server responds with 401 -- the UI listens and
     *  routes back to the login screen. */
    private val _unauthorizedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorizedEvents: SharedFlow<Unit> = _unauthorizedEvents.asSharedFlow()

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = runBlocking { preferences.authToken.first() }
            val request = if (!token.isNullOrBlank()) {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            val response = chain.proceed(request)
            if (response.code == 401 && !token.isNullOrBlank()) {
                _unauthorizedEvents.tryEmit(Unit)
            }
            response
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private var currentApi: FabulaApi? = null
    private var currentBaseUrl: String? = null

    val baseUrlFlow: Flow<String> = preferences.baseUrl
    val authTokenFlow: Flow<String?> = preferences.authToken
    val sleepRepeatEnabled: Flow<Boolean> = preferences.sleepRepeatEnabled
    val sleepRepeatUntilMinutes: Flow<Int> = preferences.sleepRepeatUntilMinutes
    suspend fun setSleepRepeatEnabled(enabled: Boolean) =
        preferences.setSleepRepeatEnabled(enabled)
    suspend fun setSleepRepeatUntilMinutes(minutes: Int) =
        preferences.setSleepRepeatUntilMinutes(minutes)

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

    fun streamUrlAuthenticated(audioFileId: Int): String? {
        val base = currentBaseUrl ?: return null
        val token = runBlocking { preferences.authToken.first() }
        val url = base + "api/stream/$audioFileId"
        return if (token.isNullOrBlank()) url else "$url?access_token=$token"
    }

    // --- auth -------------------------------------------------------------

    suspend fun checkNeedsSetup(): Boolean? {
        val api = apiOrNull() ?: return null
        return runCatching { api.getSetupStatus().needsSetup }.getOrNull()
    }

    suspend fun login(username: String, password: String): AuthUserDto {
        val api = apiOrNull() ?: throw IllegalStateException("Server-URL fehlt")
        val res = api.login(LoginRequest(username.trim(), password))
        preferences.setAuthToken(res.token)
        return res.user
    }

    suspend fun setup(username: String, password: String): AuthUserDto {
        val api = apiOrNull() ?: throw IllegalStateException("Server-URL fehlt")
        val res = api.setup(SetupRequest(username.trim(), password))
        preferences.setAuthToken(res.token)
        return res.user
    }

    suspend fun me(): AuthUserDto? {
        val api = apiOrNull() ?: return null
        return runCatching { api.getMe() }.getOrNull()
    }

    suspend fun logout() {
        preferences.setAuthToken(null)
        // Force the next request to rebuild Retrofit so the interceptor
        // re-reads the (now empty) token immediately.
        currentApi = null
        currentBaseUrl = null
    }

    suspend fun changeMyPassword(current: String, newPassword: String) {
        val api = apiOrNull() ?: throw IllegalStateException("Server-URL fehlt")
        api.changeMyPassword(ChangePasswordRequest(current, newPassword))
    }

    suspend fun listUsers(): List<UserDetailDto> {
        val api = apiOrNull() ?: return emptyList()
        return api.listUsers()
    }

    suspend fun createUser(username: String, password: String, isAdmin: Boolean): UserDetailDto {
        val api = apiOrNull() ?: throw IllegalStateException("Server-URL fehlt")
        return api.createUser(CreateUserRequest(username.trim(), password, isAdmin))
    }

    suspend fun deleteUser(id: Int) {
        val api = apiOrNull() ?: return
        api.deleteUser(id)
    }

    suspend fun setUserAdmin(id: Int, isAdmin: Boolean) {
        val api = apiOrNull() ?: return
        api.setUserAdmin(id, SetAdminRequest(isAdmin))
    }

    suspend fun adminResetPassword(id: Int, newPassword: String) {
        val api = apiOrNull() ?: return
        api.adminResetPassword(id, AdminResetPasswordRequest(newPassword))
    }

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
