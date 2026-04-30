package app.fabula.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fabula.data.AuthUserDto
import app.fabula.data.FabulaRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    repository: FabulaRepository,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    var me by remember { mutableStateOf<AuthUserDto?>(null) }
    var current by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { me = repository.me() }
    }

    val mismatch = confirm.isNotEmpty() && confirm != pwd
    val tooShort = pwd.isNotEmpty() && pwd.length < 6
    val canSubmit = !busy && current.isNotEmpty() && pwd.length >= 6 && pwd == confirm

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mein Konto") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp)
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            me?.let { u ->
                Text(
                    "Angemeldet als ${u.username}${if (u.isAdmin) " · Admin" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text("Passwort ändern", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = current,
                onValueChange = { current = it },
                label = { Text("Aktuelles Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                label = { Text("Neues Passwort (min. 6 Zeichen)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = tooShort,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("Neues Passwort bestätigen") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = mismatch,
                modifier = Modifier.fillMaxWidth()
            )

            val message = error
                ?: when {
                    tooShort -> "Passwort muss mindestens 6 Zeichen haben."
                    mismatch -> "Passwörter stimmen nicht überein."
                    else -> null
                }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (success) {
                Text("Passwort aktualisiert.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                enabled = canSubmit,
                onClick = {
                    busy = true
                    error = null
                    success = false
                    scope.launch {
                        try {
                            repository.changeMyPassword(current, pwd)
                            current = ""; pwd = ""; confirm = ""
                            success = true
                        } catch (t: Throwable) {
                            error = t.message
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(if (busy) "Speichere…" else "Speichern") }

            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Abmelden")
            }
        }
    }
}
