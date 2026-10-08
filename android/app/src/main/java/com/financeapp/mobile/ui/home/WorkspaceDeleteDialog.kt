package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkspaceDeleteDialog(
    name: String,
    state: WorkspaceUiState,
    onDismiss: () -> Unit,
    onRequest: (() -> Unit) -> Unit,
    onConfirm: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Excluir workspace?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Todos os dados financeiros de \"$name\" serão apagados deste aparelho. " +
                        "Esta ação não pode ser desfeita."
                )
                Text(
                    "Os outros workspaces e os backups do Google Drive serão mantidos.",
                    style = MaterialTheme.typography.bodySmall
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy,
                onClick = { onConfirm("LOCAL") }
            ) {
                Text("Excluir definitivamente", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancelar") }
        }
    )
}
