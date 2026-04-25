package app.fabula.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fabula.data.FabulaRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: FabulaRepository,
    onDone: () -> Unit
) {
    val stored by repository.baseUrlFlow.collectAsState(initial = "")
    var url by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(stored) {
        if (url.isBlank() && stored.isNotBlank()) url = stored
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
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Fabula-Server")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server-URL") },
                placeholder = { Text("http://192.168.1.20:5075") },
                singleLine = true
            )
            Text(
                "Die URL, unter der dein Fabula-Server erreichbar ist. Wenn du den Port weglässt, wird 80 verwendet.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )

            Button(
                enabled = url.isNotBlank(),
                onClick = {
                    scope.launch {
                        val normalised = FabulaRepository.normaliseBaseUrl(url) ?: return@launch
                        repository.setBaseUrl(normalised)
                        onDone()
                    }
                }
            ) { Text("Speichern") }
        }
    }
}
