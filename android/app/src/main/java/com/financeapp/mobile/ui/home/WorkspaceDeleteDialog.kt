package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkspaceDeleteDialog(name: String, state: WorkspaceUiState, onDismiss: () -> Unit,
    onRequest: (() -> Unit) -> Unit, onConfirm: (String) -> Unit) {
    var sent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Excluir definitivamente?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Todos os dados financeiros de \"$name\" serão apagados: contas, cartões, transações, categorias, orçamentos, metas e conexões. Esta ação não pode ser desfeita.")
            Text("Sua conta de login e os outros workspaces serão mantidos. Pendências offline deste workspace também serão descartadas quando os aparelhos sincronizarem.")
            if (sent) {
                Text("Código enviado ao e-mail da sua conta. Confira também o spam. Válido por 10 minutos.")
                SixDigitCodeInput(code = code, digits = 4, onCodeChange = { code = it }, enabled = !state.busy,
                    onDone = { if (!state.busy && code.length == 4) onConfirm(code) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onRequest { code = "" } }, enabled = !state.busy) { Text("Reenviar código") }
                Text("Aguarde 1 minuto entre envios.", style = MaterialTheme.typography.bodySmall)
            } else Text("Enviaremos um código ao e-mail cadastrado para confirmar a exclusão.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !state.busy && (!sent || code.length == 4),
            onClick = { if (sent) onConfirm(code) else onRequest { sent = true } }) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (sent) "Confirmando..." else "Enviando...", color = MaterialTheme.colorScheme.error)
            } else {
                Text(if (sent) "Confirmar exclusão definitiva" else "Enviar código", color = MaterialTheme.colorScheme.error)
            }
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancelar") } }
    )
}
