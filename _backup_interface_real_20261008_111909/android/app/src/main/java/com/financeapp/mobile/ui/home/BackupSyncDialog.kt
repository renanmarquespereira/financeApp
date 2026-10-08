package com.financeapp.mobile.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val BACKUP_PREFS = "financeapp_google_drive_backup"
private const val KEY_INTERVAL = "interval_days"
private const val KEY_URI = "automatic_backup_uri"
private const val KEY_LAST = "last_backup_at"

private fun Context.backupPrefs() = getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

data class FinanceBackupStatus(
    val intervalDays: Int,
    val lastBackupAt: Long,
    val hasAutomaticDestination: Boolean
)

fun readFinanceBackupStatus(context: Context): FinanceBackupStatus {
    val prefs = context.backupPrefs()
    return FinanceBackupStatus(
        intervalDays = prefs.getInt(KEY_INTERVAL, 0),
        lastBackupAt = prefs.getLong(KEY_LAST, 0L),
        hasAutomaticDestination = !prefs.getString(KEY_URI, null).isNullOrBlank()
    )
}

private fun formatBackupTime(epoch: Long): String {
    if (epoch <= 0L) return "Ainda não realizado"
    return runCatching {
        Instant.ofEpochMilli(epoch)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm"))
    }.getOrDefault("Ainda não realizado")
}

private fun isGoogleDriveUri(uri: Uri): Boolean {
    val authority = uri.authority.orEmpty().lowercase()
    return authority.contains("google") || authority == "com.google.android.apps.docs.storage"
}

private fun takeWritePermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

private suspend fun writeBackup(context: Context, uri: Uri, json: String) {
    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
        ?: error("Não foi possível gravar o backup no Google Drive.")
}

@Composable
fun BackupAutoRunner(
    workspaceId: String,
    onExportBackup: suspend () -> String
) {
    val context = LocalContext.current
    LaunchedEffect(workspaceId) {
        val prefs = context.backupPrefs()
        val days = prefs.getInt(KEY_INTERVAL, 0)
        val uriText = prefs.getString(KEY_URI, null)
        if (days <= 0 || uriText.isNullOrBlank()) return@LaunchedEffect
        val last = prefs.getLong(KEY_LAST, 0L)
        val due = System.currentTimeMillis() - last >= days * 86_400_000L
        if (!due) return@LaunchedEffect
        runCatching {
            writeBackup(context, Uri.parse(uriText), onExportBackup())
        }.onSuccess {
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSyncDialog(
    onDismiss: () -> Unit,
    onExportBackup: suspend () -> String,
    onRestoreBackup: suspend (String, String) -> Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.backupPrefs() }
    var intervalDays by remember { mutableStateOf(prefs.getInt(KEY_INTERVAL, 0)) }
    var lastBackupAt by remember { mutableStateOf(prefs.getLong(KEY_LAST, 0L)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedManageUri by remember { mutableStateOf<Uri?>(null) }
    var pendingAutomaticDays by remember { mutableStateOf<Int?>(null) }
    var pendingRestoreRaw by remember { mutableStateOf<String?>(null) }
    var pendingReplaceRaw by remember { mutableStateOf<String?>(null) }
    var intervalExpanded by remember { mutableStateOf(false) }

    val manualBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            if (!isGoogleDriveUri(uri)) {
                message = "Selecione o Google Drive como destino do backup."
                return@rememberLauncherForActivityResult
            }
            takeWritePermission(context, uri)
            scope.launch {
                busy = true
                runCatching { writeBackup(context, uri, onExportBackup()) }
                    .onSuccess {
                        lastBackupAt = System.currentTimeMillis()
                        prefs.edit().putLong(KEY_LAST, lastBackupAt).apply()
                        message = "Backup salvo no Google Drive."
                    }
                    .onFailure { message = it.message ?: "Não foi possível fazer o backup." }
                busy = false
            }
        }
    }

    val automaticDestinationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val days = pendingAutomaticDays
        pendingAutomaticDays = null
        if (uri != null && days != null) {
            if (!isGoogleDriveUri(uri)) {
                message = "Selecione o Google Drive para ativar o backup automático."
                return@rememberLauncherForActivityResult
            }
            takeWritePermission(context, uri)
            intervalDays = days
            prefs.edit()
                .putInt(KEY_INTERVAL, days)
                .putString(KEY_URI, uri.toString())
                .apply()
            scope.launch {
                busy = true
                runCatching { writeBackup(context, uri, onExportBackup()) }
                    .onSuccess {
                        lastBackupAt = System.currentTimeMillis()
                        prefs.edit().putLong(KEY_LAST, lastBackupAt).apply()
                        message = "Backup automático configurado para cada $days dias."
                    }
                    .onFailure { message = it.message ?: "Não foi possível configurar o backup automático." }
                busy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (!isGoogleDriveUri(uri)) { message = "Selecione um backup no Google Drive."; return@rememberLauncherForActivityResult }
            scope.launch {
                busy = true
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Não foi possível ler o arquivo de backup.")
                }.onSuccess { raw -> pendingRestoreRaw = raw }
                    .onFailure { message = it.message ?: "Não foi possível ler o backup." }
                busy = false
            }
        }
    }

    val manageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !isGoogleDriveUri(uri)) { message = "Selecione um backup no Google Drive." }
        else selectedManageUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup e restauração") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "O backup inclui todos os Workspaces e os dados financeiros deste aparelho. O Google Drive é usado apenas para backup e restauração.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text("Último backup: ${formatBackupTime(lastBackupAt)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                FilledTonalButton(
                    onClick = { manualBackupLauncher.launch("FinanceApp_Backup_${System.currentTimeMillis()}.json") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Fazer backup agora")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Restore, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restaurar backup")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { manageLauncher.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gerenciar backups")
                }

                Spacer(Modifier.height(16.dp))
                Text("Backup automático", style = MaterialTheme.typography.labelLarge)
                Text("Escolha a periodicidade. O arquivo é atualizado quando o FinanceApp for aberto após o prazo.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = intervalExpanded,
                    onExpandedChange = { intervalExpanded = !intervalExpanded }
                ) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        onClick = { intervalExpanded = true }
                    ) {
                        Text(
                            when (intervalDays) {
                                7 -> "A cada 7 dias"
                                15 -> "A cada 15 dias"
                                30 -> "A cada 30 dias"
                                else -> "Desativado"
                            }
                        )
                    }
                    ExposedDropdownMenu(expanded = intervalExpanded, onDismissRequest = { intervalExpanded = false }) {
                        listOf(0 to "Desativado", 7 to "A cada 7 dias", 15 to "A cada 15 dias", 30 to "A cada 30 dias").forEach { (days, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    intervalExpanded = false
                                    if (days == 0) {
                                        intervalDays = 0
                                        prefs.edit().putInt(KEY_INTERVAL, 0).remove(KEY_URI).apply()
                                        message = "Backup automático desativado."
                                    } else {
                                        pendingAutomaticDays = days
                                        automaticDestinationLauncher.launch("FinanceApp_Backup_Automatico.json")
                                    }
                                }
                            )
                        }
                    }
                }
                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, enabled = !busy) { Text("Concluir") } }
    )

    pendingRestoreRaw?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingRestoreRaw = null },
            title = { Text("Como deseja restaurar?") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Já existem dados neste aparelho. Escolha como o backup deve ser aplicado.")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Manter dados existentes: preserva o que já existe e adiciona somente o que estiver faltando.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Substituir pelos dados do backup: apaga os dados existentes no aparelho e restaura exatamente o conteúdo do arquivo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            pendingRestoreRaw = null
                            scope.launch {
                                busy = true
                                runCatching { onRestoreBackup(raw, "merge") }
                                    .onSuccess { count -> message = "Backup mesclado: $count registros processados." }
                                    .onFailure { message = it.message ?: "Não foi possível restaurar." }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Manter dados existentes")
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            pendingRestoreRaw = null
                            pendingReplaceRaw = raw
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Substituir pelos dados do backup",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    TextButton(
                        onClick = { pendingRestoreRaw = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelar")
                    }
                }
            },
            confirmButton = {}
        )
    }

    pendingReplaceRaw?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingReplaceRaw = null },
            title = { Text("Substituir dados existentes?") },
            text = {
                Text(
                    "Você tem certeza? Isso apagará os dados existentes neste aparelho e restaurará somente os dados do backup."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingReplaceRaw = null
                        scope.launch {
                            busy = true
                            runCatching { onRestoreBackup(raw, "replace") }
                                .onSuccess { count -> message = "Backup restaurado: $count registros." }
                                .onFailure { message = it.message ?: "Não foi possível restaurar." }
                            busy = false
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Sim, substituir")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplaceRaw = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
    selectedManageUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { selectedManageUri = null },
            title = { Text("Gerenciar backup") },
            text = { Text("Escolha o que deseja fazer com o arquivo selecionado no Google Drive.") },
            confirmButton = {
                Button(onClick = {
                    selectedManageUri = null
                    scope.launch {
                        busy = true
                        runCatching {
                            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                ?: error("Não foi possível ler o backup.")
                            run { pendingRestoreRaw = raw; 0 }
                        }.onSuccess { count -> message = "Backup restaurado: $count registros." }
                            .onFailure { message = it.message ?: "Não foi possível restaurar." }
                        busy = false
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedManageUri = null
                    scope.launch {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                            .onSuccess { message = "Backup excluído." }
                            .onFailure { message = "O provedor não permitiu excluir esse arquivo." }
                    }
                }) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Excluir")
                }
            }
        )
    }
}

