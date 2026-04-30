package app.fabula.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.fabula.data.AuthUserDto
import app.fabula.data.FabulaRepository
import app.fabula.data.UserDetailDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    repository: FabulaRepository,
    onBack: () -> Unit
) {
    var me by remember { mutableStateOf<AuthUserDto?>(null) }
    var users by remember { mutableStateOf<List<UserDetailDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var newAdmin by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<UserDetailDto?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        users = runCatching { repository.listUsers() }.getOrElse { emptyList() }
    }

    LaunchedEffect(Unit) {
        runCatching { me = repository.me() }
        reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Benutzerverwaltung") },
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
            modifier = Modifier.fillMaxSize().padding(insets).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Neuen Benutzer anlegen", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Benutzername") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newPwd,
                onValueChange = { newPwd = it },
                label = { Text("Passwort (min. 6 Zeichen)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = newAdmin, onCheckedChange = { newAdmin = it })
                Text("Administrator")
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = newName.isNotBlank() && newPwd.length >= 6,
                onClick = {
                    scope.launch {
                        try {
                            repository.createUser(newName.trim(), newPwd, newAdmin)
                            newName = ""; newPwd = ""; newAdmin = false
                            error = null
                            reload()
                        } catch (t: Throwable) {
                            error = t.message
                        }
                    }
                }
            ) { Text("Anlegen") }

            HorizontalDivider()

            Text("Bestehende Benutzer", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(items = users, key = { it.id }) { u ->
                    UserRow(
                        user = u,
                        isSelf = me?.id == u.id,
                        onToggleAdmin = {
                            scope.launch {
                                runCatching { repository.setUserAdmin(u.id, !u.isAdmin) }
                                    .onFailure { error = it.message }
                                reload()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                runCatching { repository.deleteUser(u.id) }
                                    .onFailure { error = it.message }
                                reload()
                            }
                        },
                        onResetPassword = { resetTarget = u }
                    )
                }
            }
        }
    }

    resetTarget?.let { target ->
        ResetPasswordDialog(
            user = target,
            onDismiss = { resetTarget = null },
            onConfirm = { newPassword ->
                scope.launch {
                    try {
                        repository.adminResetPassword(target.id, newPassword)
                        resetTarget = null
                    } catch (t: Throwable) {
                        error = t.message
                    }
                }
            }
        )
    }
}

@Composable
private fun UserRow(
    user: UserDetailDto,
    isSelf: Boolean,
    onToggleAdmin: () -> Unit,
    onDelete: () -> Unit,
    onResetPassword: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.username + if (isSelf) " (du)" else "",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (user.isAdmin) "Administrator" else "Benutzer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            TextButton(enabled = !isSelf, onClick = onToggleAdmin) {
                Text(if (user.isAdmin) "Admin entfernen" else "Zum Admin")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(enabled = !isSelf, onClick = onDelete) { Text("Löschen") }
        }
        OutlinedButton(onClick = onResetPassword, modifier = Modifier.fillMaxWidth()) {
            Text("Passwort setzen…")
        }
    }
}

@Composable
private fun ResetPasswordDialog(
    user: UserDetailDto,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passwort für ${user.username}") },
        text = {
            Column {
                Text("Mindestens 6 Zeichen.", color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("Neues Passwort") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pwd.length >= 6, onClick = { onConfirm(pwd) }) { Text("Setzen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
