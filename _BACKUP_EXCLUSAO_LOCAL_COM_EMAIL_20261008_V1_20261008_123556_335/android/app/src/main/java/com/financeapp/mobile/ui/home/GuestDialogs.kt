package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GuestOptionsDialog(
    frozen: Boolean,
    onDismiss: () -> Unit,
    onRegister: () -> Unit,
    onLogin: () -> Unit,
    onCreateAccount: (String) -> Unit,
    onLogout: () -> Unit,
    onBackup: () -> Unit = {},
    onAppSettings: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onLegalPrivacy: () -> Unit = {},
    appLockEnabled: Boolean = false,
    onAppLockChange: (Boolean) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FinanceApp local") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Seus dados financeiros ficam . O Google Drive é usado apenas quando você faz ou agenda um backup.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Backup e restauração")
                }
                OutlinedButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Configurações do aplicativo")
                }
                OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Notificações")
                }
                OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(8.dp)); Text("Diagnóstico e segurança")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Bloqueio do aplicativo", style = MaterialTheme.typography.titleSmall)
                        Text("Use biometria ou bloqueio do aparelho ao abrir.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = appLockEnabled, onCheckedChange = onAppLockChange)
                }
                TextButton(onClick = onLegalPrivacy, modifier = Modifier.fillMaxWidth()) { Text("Privacidade e termos") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
fun GuestTransferDialog(state: WorkspaceUiState, onDismiss: () -> Unit, onTransfer: () -> Unit) {
    // Mantido apenas para compatibilidade binaria com versoes antigas; nao e usado no modo standalone.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dados locais") },
        text = { Text("O FinanceApp agora trabalha somente com dados locais e backup no Google Drive.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
