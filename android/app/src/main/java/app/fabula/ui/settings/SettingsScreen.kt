package app.fabula.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.FabulaApp
import app.fabula.data.FabulaRepository
import app.fabula.ui.LocalContentBottomInset
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: FabulaRepository,
    onDone: () -> Unit,
    onManageSeries: () -> Unit
) {
    val storedUrl by repository.baseUrlFlow.collectAsState(initial = "")
    val sleepRepeatEnabled by repository.sleepRepeatEnabled.collectAsState(initial = true)
    val sleepWakeMinutes by repository.sleepRepeatUntilMinutes.collectAsState(initial = 7 * 60)

    var url by remember { mutableStateOf("") }
    var wakeText by remember { mutableStateOf("") }
    var wakeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(storedUrl) {
        if (url.isBlank() && storedUrl.isNotBlank()) url = storedUrl
    }
    LaunchedEffect(sleepWakeMinutes) {
        // Only sync from prefs if the user isn't currently editing.
        if (wakeText.isBlank() || parseHhMm(wakeText) == sleepWakeMinutes) {
            wakeText = formatHhMm(sleepWakeMinutes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
        // Extend the scrollable content past the translucent bottom nav bar
        // and mini player so the last sections (e.g. Diagnose) can scroll
        // fully into view instead of being hidden behind the overlay.
        val bottomInset = LocalContentBottomInset.current.calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server-URL") },
                placeholder = { Text("http://192.168.1.20:5075") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Die URL, unter der dein Fabula-Server erreichbar ist. Wenn du den Port weglässt, wird 80 verwendet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(
                enabled = url.isNotBlank(),
                onClick = {
                    scope.launch {
                        val normalised = FabulaRepository.normaliseBaseUrl(url) ?: return@launch
                        repository.setBaseUrl(normalised)
                    }
                }
            ) { Text("Server speichern") }

            HorizontalDivider()

            Text("Schlaf-Timer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automatisch wiederholen", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Setzt den Timer beim Wiederaufnehmen der Wiedergabe neu, bis die Aufwach-Uhrzeit erreicht ist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = sleepRepeatEnabled,
                    onCheckedChange = { value ->
                        scope.launch { repository.setSleepRepeatEnabled(value) }
                    }
                )
            }

            OutlinedTextField(
                value = wakeText,
                onValueChange = {
                    wakeText = it
                    wakeError = null
                },
                label = { Text("Aufwach-Uhrzeit (HH:MM)") },
                singleLine = true,
                enabled = sleepRepeatEnabled,
                isError = wakeError != null,
                supportingText = {
                    wakeError?.let { Text(it) }
                        ?: Text(
                            "Standard: 07:00",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = sleepRepeatEnabled,
                onClick = {
                    val parsed = parseHhMm(wakeText)
                    if (parsed == null) {
                        wakeError = "Format HH:MM, z. B. 07:00"
                    } else {
                        wakeError = null
                        scope.launch { repository.setSleepRepeatUntilMinutes(parsed) }
                    }
                }
            ) { Text("Aufwach-Uhrzeit speichern") }

            HorizontalDivider()

            Text("Bibliothek", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(
                onClick = onManageSeries,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Serien verwalten") }

            HorizontalDivider()

            AppUpdateSection(repository = repository)

            HorizontalDivider()

            DiagnoseSection(repository = repository)

            Spacer(Modifier.height(8.dp))
        }
    }
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val version: app.fabula.data.AppVersionDto) : UpdateUiState
    data object Downloading : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@Composable
private fun AppUpdateSection(repository: FabulaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    val (ownCode, ownName) = remember(context) { installedVersion(context) }

    Text("App-Update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Installiert: $ownName (Build $ownCode)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )

    when (val s = state) {
        is UpdateUiState.Available -> {
            Text(
                "Neue Version verfügbar: ${s.version.versionName} (Build ${s.version.versionCode})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = {
                    state = UpdateUiState.Downloading
                    scope.launch {
                        val result = runCatching {
                            val api = repository.apiOrNull()
                                ?: throw IllegalStateException("Kein Server konfiguriert.")
                            val apk = downloadApkToCache(context, api)
                            launchInstaller(context, apk)
                        }
                        state = result.fold(
                            onSuccess = { UpdateUiState.Available(s.version) },
                            onFailure = { t ->
                                repository.logFailure("AppUpdate.download", t)
                                UpdateUiState.Error(t.message ?: "Download fehlgeschlagen")
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Herunterladen & installieren") }
        }
        UpdateUiState.Downloading -> {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Wird heruntergeladen…")
            }
        }
        else -> {
            OutlinedButton(
                enabled = state != UpdateUiState.Checking,
                onClick = {
                    state = UpdateUiState.Checking
                    scope.launch {
                        state = runCatching {
                            val api = repository.apiOrNull()
                                ?: throw IllegalStateException("Kein Server konfiguriert.")
                            api.getAppVersion()
                        }.fold(
                            onSuccess = { latest ->
                                if (latest.versionCode > ownCode) UpdateUiState.Available(latest)
                                else UpdateUiState.UpToDate
                            },
                            onFailure = { t ->
                                if (t is retrofit2.HttpException && t.code() == 404) {
                                    UpdateUiState.Error("Der Server kennt noch keine App-Version (Update-Spiegel nicht konfiguriert oder noch kein Release).")
                                } else {
                                    repository.logFailure("AppUpdate.check", t)
                                    UpdateUiState.Error(t.message ?: "Prüfung fehlgeschlagen")
                                }
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state == UpdateUiState.Checking) "Prüfe…" else "Auf Updates prüfen")
            }
            when (val st = state) {
                UpdateUiState.UpToDate -> Text(
                    "Du bist auf dem neuesten Stand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                is UpdateUiState.Error -> Text(
                    st.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Unit
            }
        }
    }
}

/** Installed versionCode/versionName, robust across API levels (minSdk 26). */
private fun installedVersion(context: Context): Pair<Long, String> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
    else @Suppress("DEPRECATION") info.versionCode.toLong()
    return code to (info.versionName ?: "?")
}

/** Streams the APK from the server into cacheDir/updates/fabula.apk. */
private suspend fun downloadApkToCache(context: Context, api: app.fabula.data.FabulaApi): File =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "fabula.apk")
        api.downloadApk().use { body ->
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target
    }

/** Hands the downloaded APK to the system package installer. Android asks for
 *  the "Install unknown apps" permission for Fabula on first use. */
private fun launchInstaller(context: Context, apk: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.logs", apk
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Composable
private fun DiagnoseSection(repository: FabulaRepository) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as FabulaApp }
    val logStore = app.logStore
    val diagnosticsEnabled by repository.diagnosticsEnabled.collectAsState(initial = false)
    val logBytes by logStore.byteCount.collectAsState()
    val scope = rememberCoroutineScope()

    Text("Diagnose", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Fehler protokollieren", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Schreibt Warnungen und Fehler beim Server-Zugriff in eine lokale Logdatei. Aus, wenn nicht gebraucht.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Switch(
            checked = diagnosticsEnabled,
            onCheckedChange = { value ->
                scope.launch { repository.setDiagnosticsEnabled(value) }
            }
        )
    }
    Text(
        "Logdatei: ${formatBytes(logBytes)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            enabled = logBytes > 0L,
            onClick = { shareLog(context, logStore.fileForSharing()) },
            modifier = Modifier.weight(1f)
        ) { Text("Log teilen") }
        OutlinedButton(
            enabled = logBytes > 0L,
            onClick = { logStore.clear() },
            modifier = Modifier.weight(1f)
        ) { Text("Log löschen") }
    }
}

private fun shareLog(context: Context, file: File) {
    if (!file.exists() || file.length() == 0L) return
    val authority = "${context.packageName}.logs"
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Fabula-Log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Log teilen").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "leer"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun parseHhMm(text: String): Int? {
    val match = Regex("^\\s*(\\d{1,2})[:.](\\d{1,2})\\s*$").matchEntire(text) ?: return null
    val h = match.groupValues[1].toIntOrNull() ?: return null
    val m = match.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

private fun formatHhMm(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)
