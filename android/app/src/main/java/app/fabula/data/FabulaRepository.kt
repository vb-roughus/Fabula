package app.fabula.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit

class FabulaRepository(
    private val context: Context,
    private val preferences: ServerPreferences,
    private val logStore: LogStore,
    /** Consulted first for cover art, so downloaded books keep their artwork
     *  with no server. Constructed before the repository in FabulaApp. */
    private val offlineStore: OfflineStore
) {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    /** Bumps every time a bookmark is created/edited/deleted, so screens that
     *  show bookmarks (BookScreen) can re-fetch without prop drilling. */
    private val _bookmarksRevision = MutableStateFlow(0)
    val bookmarksRevision: StateFlow<Int> = _bookmarksRevision.asStateFlow()
    fun bumpBookmarksRevision() { _bookmarksRevision.value = _bookmarksRevision.value + 1 }

    /** Bumps every time a highlight is created/edited/deleted, so screens that
     *  show highlights (player, BookScreen) can re-fetch without prop drilling. */
    private val _highlightsRevision = MutableStateFlow(0)
    val highlightsRevision: StateFlow<Int> = _highlightsRevision.asStateFlow()
    fun bumpHighlightsRevision() { _highlightsRevision.value = _highlightsRevision.value + 1 }

    /** Bumps every time the series catalog changes (create/update/delete or
     *  a book's series assignment changes), so list screens can re-fetch. */
    private val _seriesRevision = MutableStateFlow(0)
    val seriesRevision: StateFlow<Int> = _seriesRevision.asStateFlow()
    fun bumpSeriesRevision() { _seriesRevision.value = _seriesRevision.value + 1 }

    /** Funnel for UI-layer `runCatching {}` swallowed errors. The HTTP
     *  interceptor already logs all non-2xx responses, but exceptions
     *  thrown after a successful response (JSON parsing, serialization
     *  surprises) would otherwise vanish silently. */
    fun logFailure(context: String, t: Throwable) {
        logStore.e("Ui", "$context: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Thrown instead of dialling out while the offline latch is set. */
    class OfflineException : java.io.IOException(
        "Offline – Verbindung über das Menü herstellen."
    )

    /** Emits whenever the server responds with 401 -- the UI listens and
     *  routes back to the login screen. */
    private val _unauthorizedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorizedEvents: SharedFlow<Unit> = _unauthorizedEvents.asSharedFlow()

    /**
     * Whether the server answered the last request. `null` until the first one
     * has been made, so the UI doesn't flash "offline" during startup.
     *
     * Fed by the interceptor below rather than by polling: every call already
     * passes through it, so the status is a by-product of normal traffic.
     * A reply of any kind -- including 4xx/5xx -- counts as reachable; only a
     * transport-level failure means offline.
     */
    private val _serverOnline = MutableStateFlow<Boolean?>(null)
    val serverOnline: StateFlow<Boolean?> = _serverOnline.asStateFlow()

    /**
     * Set once requests have genuinely failed to reach the server. Not on the
     * first stumble, and not on every kind of failure: see
     * [shouldRetryBeforeLatching], [OFFLINE_FAILURE_THRESHOLD] and
     * [failureCountsAsOffline] for the three filters in front of it.
     *
     * While it is set, requests are refused before they reach the network --
     * which is the point on a dead connection: no screen sits in a ten-second
     * timeout and no battery is spent dialling a number that does not answer.
     *
     * It is no longer the user's job to clear it. After a cooling-off period
     * ([OFFLINE_RETRY_DELAYS_MS], lengthening while the server stays away) the
     * next request through goes out as a probe. Success clears everything. The
     * drawer's "Verbinden" button still works and skips the wait; so does a
     * fresh process, since this is plain in-memory state, and so does the
     * device gaining a network, which makes the latch's knowledge stale.
     */
    @Volatile
    private var offlineLatched = false

    /** Earliest moment a request may go out again to test the latch. */
    @Volatile
    private var latchedUntilMs = 0L

    /** Counted failures since the last success; see OFFLINE_FAILURE_THRESHOLD. */
    @Volatile
    private var countedFailures = 0

    /** Position in [OFFLINE_RETRY_DELAYS_MS] -- grows while the server stays away. */
    @Volatile
    private var backoffStep = 0

    /** Everything is fine again: forget the failures and the waiting. */
    private fun clearOfflineState() {
        offlineLatched = false
        latchedUntilMs = 0L
        countedFailures = 0
        backoffStep = 0
        _serverOnline.value = true
    }

    /**
     * Notes a failure, if it is the kind that gets a vote. Background traffic
     * that nobody is waiting for fails quietly -- see [failureCountsAsOffline].
     */
    private fun noteFailure(method: String, path: String): Boolean {
        if (!failureCountsAsOffline(method, path)) return false
        noteCountedFailure()
        return true
    }

    /**
     * Records a failure that gets a say. Declares the app offline once enough
     * of them have come in a row, and each time it does, waits longer before
     * letting the next one through.
     */
    private fun noteCountedFailure() {
        countedFailures += 1
        if (countedFailures < OFFLINE_FAILURE_THRESHOLD) return
        val wait = offlineRetryDelayMs(backoffStep)
        offlineLatched = true
        latchedUntilMs = System.currentTimeMillis() + wait
        backoffStep += 1
        _serverOnline.value = false
        logStore.i("Http", "Offline – nächster Versuch in ${wait / 1000} s.")
    }

    /** Set around the explicit probe so it is allowed past the latch. */
    @Volatile
    private var probeInFlight = false

    /** True while an explicit reconnect attempt is in flight. */
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /** Bumped after a manual reconnect succeeds, so screens can refetch. */
    private val _reconnects = MutableStateFlow(0)
    val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

    /**
     * Explicit connection attempt behind the drawer's "Verbinden" button.
     * Returns true when the server answered.
     */
    suspend fun probeServer(): Boolean {
        _probing.value = true
        probeInFlight = true
        return try {
            val reachable = checkNeedsSetup() != null
            if (reachable) {
                clearOfflineState()
                _reconnects.value = _reconnects.value + 1
            } else {
                // Asked for explicitly, so the answer is trusted at once
                // rather than needing a second opinion -- but the waiting
                // starts over from the top instead of continuing to grow.
                _serverOnline.value = false
                offlineLatched = true
                countedFailures = OFFLINE_FAILURE_THRESHOLD
                backoffStep = 0
                latchedUntilMs = System.currentTimeMillis() + offlineRetryDelayMs(0)
            }
            reachable
        } finally {
            probeInFlight = false
            _probing.value = false
        }
    }

    /** True when requests are being refused locally. */
    fun isOfflineLatched(): Boolean = offlineLatched

    // --- app update notice --------------------------------------------------

    private val _latestAppVersion = MutableStateFlow<AppVersionDto?>(null)

    /**
     * The newer build waiting on the server, or null when there is none, the
     * check has not run, or the user has put this one away.
     *
     * Combining the two here keeps the decision in one place: the flow the
     * banner reads is already the answer to "should I be shown", so no screen
     * has to remember the rule.
     */
    val appUpdateBanner: Flow<AppVersionDto?> =
        combine(_latestAppVersion, preferences.dismissedUpdateCode) { latest, dismissed ->
            latest?.takeIf { it.versionCode > dismissed }
        }

    /**
     * Asks the server what the newest build is and remembers it if it is newer
     * than the one running.
     *
     * Failure is silent by design: this runs unasked at startup, and a server
     * too old to know the endpoint answers 404. Neither is worth a word to the
     * user, and neither counts towards going offline -- see
     * [failureCountsAsOffline].
     */
    suspend fun refreshAppUpdate() {
        val latest = runCatching { apiOrNull()?.getAppVersion() }.getOrNull() ?: return
        val (installedCode, _) = runCatching { installedVersion(context) }.getOrNull() ?: return
        _latestAppVersion.value = latest.takeIf { it.versionCode > installedCode }
    }

    /** Puts the notice away for this build; anything newer brings it back. */
    suspend fun dismissAppUpdate(versionCode: Int) {
        preferences.setDismissedUpdateCode(versionCode)
    }

    /**
     * Whether the device has a network at all right now.
     *
     * Deliberately does not require the network to be validated: during a
     * handover the replacement is usable well before Android has confirmed it
     * reaches the internet, and a server on the local network never needs the
     * internet in the first place. Being unable to ask counts as yes -- the
     * point here is to be forgiving.
     */
    private fun hasUsableNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Releases the offline latch when the device gains a network.
     *
     * The latch means "we know the server is unreachable". A new default
     * network makes that knowledge stale -- it was measured over a connection
     * that no longer exists. So we stop refusing requests.
     *
     * What this deliberately does NOT do is contact the server or bump
     * [reconnects]. Nothing reconnects on its own and no screen refetches; the
     * next request the user's own actions produce simply goes out instead of
     * being turned away in advance. `serverOnline` goes back to unknown rather
     * than to true, because that is honestly all we know.
     */
    private fun watchForNetworkChanges() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!offlineLatched) return
                    // Drop the waiting time and the failure count with it: the
                    // failures were measured over a connection that is gone, so
                    // the next request should not have to serve out its
                    // sentence. Still contacts nothing by itself.
                    offlineLatched = false
                    latchedUntilMs = 0L
                    countedFailures = 0
                    backoffStep = 0
                    _serverOnline.value = null
                    logStore.i(
                        "Http",
                        "Neues Netzwerk verfügbar – Offline-Sperre gelöst (ohne Verbindungsversuch)."
                    )
                }
            })
        }.onFailure {
            logStore.w("Http", "Netzwerküberwachung nicht möglich: ${it.message}")
        }
    }

    init {
        watchForNetworkChanges()
    }

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
            // Refuse before touching the network while we believe the server
            // is unreachable -- but only until the cooling-off period is up.
            // After that the next request goes out as a probe: if it works,
            // everything below clears the state, and the user never had to ask.
            if (offlineLatched && !probeInFlight &&
                System.currentTimeMillis() < latchedUntilMs
            ) {
                throw OfflineException()
            }
            val started = System.currentTimeMillis()
            val response = try {
                chain.proceed(request)
            } catch (first: Throwable) {
                val elapsed = System.currentTimeMillis() - started
                if (!shouldRetryBeforeLatching(first, elapsed, hasUsableNetwork())) {
                    // Network-level failure (DNS, timeout, TLS, server down).
                    // Whether it counts towards declaring the app offline is a
                    // separate question from whether it failed.
                    val counted = noteFailure(request.method, request.url.encodedPath)
                    val note = "${request.method} ${request.url} -> network error after $elapsed ms"
                    // Background traffic hiccups are expected on a weak link
                    // and would drown the log; only a counted failure is news.
                    if (counted) logStore.e("Http", note, first)
                    else logStore.w("Http", "$note (Hintergrund, ohne Folgen)")
                    throw first
                }

                // Looks like a Wi-Fi <-> mobile handover: the socket died on an
                // interface that just went away, and the replacement is already
                // up. One more attempt costs a moment; getting it wrong cost the
                // user their connection until they pressed a button.
                logStore.i(
                    "Http",
                    "${request.method} ${request.url} -> ${first.javaClass.simpleName} after " +
                        "$elapsed ms, ein erneuter Versuch (Netzwechsel?)"
                )
                Thread.sleep(HANDOVER_RETRY_DELAY_MS)
                try {
                    chain.proceed(request)
                } catch (second: Throwable) {
                    val counted = noteFailure(request.method, request.url.encodedPath)
                    val note = "${request.method} ${request.url} -> auch der zweite Versuch scheiterte"
                    if (counted) logStore.e("Http", note, second)
                    else logStore.w("Http", "$note (Hintergrund, ohne Folgen)")
                    throw second
                }
            }
            // Any reply means the server is reachable, even an error one --
            // and it counts wherever it came from, background traffic included.
            // Proof is proof.
            clearOfflineState()
            if (response.code == 401 && !token.isNullOrBlank()) {
                _unauthorizedEvents.tryEmit(Unit)
            }
            if (!response.isSuccessful) {
                val snippet = runCatching { response.peekBody(2048).string() }.getOrDefault("")
                logStore.w(
                    "Http",
                    "${request.method} ${request.url} -> ${response.code} ${response.message} (${System.currentTimeMillis() - started} ms)" +
                        if (snippet.isNotBlank()) " body=$snippet" else ""
                )
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
    val diagnosticsEnabled: Flow<Boolean> = preferences.diagnosticsEnabled
    suspend fun setDiagnosticsEnabled(enabled: Boolean) = preferences.setDiagnosticsEnabled(enabled)
    val themeMode: Flow<String> = preferences.themeMode
    suspend fun setThemeMode(mode: String) = preferences.setThemeMode(mode)
    val chapterFlipIntroEnabled: Flow<Boolean> = preferences.chapterFlipIntroEnabled

    /** When on, the end of a book continues with the next one in its series. */
    val seriesModeEnabled: Flow<Boolean> = preferences.seriesModeEnabled

    suspend fun setSeriesModeEnabled(enabled: Boolean) = preferences.setSeriesModeEnabled(enabled)
    suspend fun setChapterFlipIntroEnabled(enabled: Boolean) =
        preferences.setChapterFlipIntroEnabled(enabled)
    val downloadWifiOnly: Flow<Boolean> = preferences.downloadWifiOnly
    suspend fun setDownloadWifiOnly(enabled: Boolean) = preferences.setDownloadWifiOnly(enabled)
    val showerBoostDb: Flow<Float> = preferences.showerBoostDb
    suspend fun setShowerBoostDb(db: Float) = preferences.setShowerBoostDb(db)
    suspend fun setSleepRepeatEnabled(enabled: Boolean) =
        preferences.setSleepRepeatEnabled(enabled)
    suspend fun setSleepRepeatUntilMinutes(minutes: Int) =
        preferences.setSleepRepeatUntilMinutes(minutes)
    val sleepTimerMinutes: Flow<Int> = preferences.sleepTimerMinutes
    suspend fun setSleepTimerMinutes(minutes: Int) = preferences.setSleepTimerMinutes(minutes)

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

    // A downloaded cover wins over the server URL: it is the same image, works
    // offline, and costs no request. Coil and media3 both read file:// URIs.
    fun coverUrl(book: BookSummaryDto): String? =
        localCoverUri(book.id) ?: book.coverUrl?.let { resolveRelative(it) }

    fun coverUrl(book: BookDetailDto): String? =
        localCoverUri(book.id) ?: book.coverUrl?.let { resolveRelative(it) }

    private fun localCoverUri(bookId: Int): String? =
        offlineStore.localCover(bookId)?.let { android.net.Uri.fromFile(it).toString() }
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

    /** Cached admin flag; drives the admin-only entries in the UI. The server
     *  enforces the real thing -- this only decides what is offered. */
    val isAdmin: Flow<Boolean> = preferences.isAdmin

    suspend fun login(username: String, password: String): AuthUserDto {
        val api = apiOrNull() ?: throw IllegalStateException("Server-URL fehlt")
        val res = api.login(LoginRequest(username.trim(), password))
        preferences.setAuthToken(res.token)
        preferences.setIsAdmin(res.user.isAdmin)
        return res.user
    }

    suspend fun setup(username: String, password: String): AuthUserDto {
        val api = apiOrNull() ?: throw IllegalStateException("Server-URL fehlt")
        val res = api.setup(SetupRequest(username.trim(), password))
        preferences.setAuthToken(res.token)
        preferences.setIsAdmin(res.user.isAdmin)
        return res.user
    }

    suspend fun me(): AuthUserDto? {
        val api = apiOrNull() ?: return null
        return runCatching { api.getMe() }.getOrNull()
            ?.also { preferences.setIsAdmin(it.isAdmin) }
    }

    suspend fun logout() {
        preferences.setAuthToken(null)
        preferences.setIsAdmin(false)
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
