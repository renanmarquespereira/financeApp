package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.financeapp.mobile.data.local.WorkspaceEntity
import java.util.UUID

private val workspaceKinds = listOf("personal" to "Pessoal", "business" to "Empresa", "family" to "Família", "other" to "Outro")

@Composable
fun WorkspaceManagerDialog(
    workspaces: List<WorkspaceEntity>, activeId: String?, state: WorkspaceUiState,
    onDismiss: () -> Unit, onRefresh: () -> Unit, onClearError: () -> Unit,
    onSelect: (String, () -> Unit) -> Unit,
    onCreate: (String, String, String, () -> Unit) -> Unit,
    onEdit: (String, String, String, () -> Unit) -> Unit,
    onArchive: (String, () -> Unit) -> Unit,
    onRestore: (String) -> Unit,
    onGuestTransfer: (() -> Unit)? = null,
    onDeleteCode: (String, () -> Unit) -> Unit = { _, _ -> },
    onConfirmDelete: (String, String, () -> Unit) -> Unit = { _, _, _ -> },
    onDeleteCodeBatch: (List<String>, () -> Unit) -> Unit = { _, _ -> },
    onConfirmDeleteBatch: (List<String>, String, () -> Unit) -> Unit = { _, _, _ -> }
) {
    var deleting by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var archiving by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var selectedArchived by remember { mutableStateOf(setOf<String>()) }
    var deletingBatch by remember { mutableStateOf<List<WorkspaceEntity>?>(null) }

    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Workspaces", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, enabled = !state.busy) { Icon(Icons.Default.Close, "Fechar workspaces") }
                }
                Text("Separe suas finanças pessoais, da empresa e da família.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Ativos") })
                    FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Arquivados") })
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val visible = workspaces.filter { (it.archivedAt != null) == archived }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (visible.isEmpty()) item {
                        Text(if (archived) "Nenhum workspace arquivado." else "Crie seu primeiro workspace.")
                    }
                    items(visible, key = { it.id }) { row ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text((workspaceKinds.firstOrNull { it.first == row.kind }?.second ?: "Outro") +
                                    if (row.isDefault) " · Padrão" else "", style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (archived) {
                                        Checkbox(checked = row.id in selectedArchived, enabled = !state.busy,
                                            onCheckedChange = { checked -> selectedArchived = if (checked) selectedArchived + row.id else selectedArchived - row.id })
                                        TextButton(onClick = { onRestore(row.id) }, enabled = !state.busy) { Text("Restaurar") }
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { onClearError(); deleting = row }, enabled = !state.busy) {
                                            Text("Excluir", color = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        TextButton(onClick = { onSelect(row.id, onDismiss) }, enabled = !state.busy && row.id != activeId) {
                                            Text(if (row.id == activeId) "Atual" else "Abrir")
                                        }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { onClearError(); editing = row }, enabled = !state.busy) {
                                            Icon(Icons.Default.Edit, "Editar ${row.name}")
                                        }
                                        IconButton(onClick = { onClearError(); archiving = row }, enabled = !state.busy) {
                                            Icon(Icons.Default.Archive, "Arquivar ${row.name}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (selectedArchived.isNotEmpty()) {
                    Button(onClick = {
                        onClearError()
                        deletingBatch = workspaces.filter { it.id in selectedArchived }
                    }, enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Excluir selecionados (${selectedArchived.size})")
                    }
                }
                Text("",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onClearError(); creating = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Criar workspace")
                }
                onGuestTransfer?.let { transfer ->
                    TextButton(onClick=transfer,enabled=!state.busy) { Text("Transferir dados do visitante") }
                }
            }
        }
    }
    deleting?.let { row ->
        WorkspaceDeleteDialog(row.name, state,
            onDismiss = { deleting = null; onClearError() },
            onRequest = { done -> onDeleteCode(row.id, done) },
            onConfirm = { code -> onConfirmDelete(row.id, code) { deleting = null } })
    }
    deletingBatch?.let { rows ->
        val ids = rows.map { it.id }
        WorkspaceDeleteDialog("${rows.size} workspaces selecionados", state,
            onDismiss = { deletingBatch = null; onClearError() },
            onRequest = { done -> onDeleteCodeBatch(ids, done) },
            onConfirm = { code -> onConfirmDeleteBatch(ids, code) { selectedArchived = emptySet(); deletingBatch = null } })
    }
    if (creating || editing != null) {
        WorkspaceEditor(editing, state, onDismiss = { creating = false; editing = null; onClearError() }) { name, kind, clientId ->
            val row = editing
            if (row == null) onCreate(name, kind, clientId) { creating = false; onDismiss() }
            else onEdit(row.id, name, kind) { editing = null }
        }
    }
    archiving?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) archiving = null },
            title = { Text("Arquivar ${row.name}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Os dados serão mantidos neste aparelho e no próximo backup. Você poderá restaurar este workspace depois." +
                    if (row.id == activeId) " O app abrirá automaticamente outro workspace ativo." else "")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { onArchive(row.id) { archiving = null } }) { Text("Arquivar") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { archiving = null; onClearError() }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun WorkspaceEditor(row: WorkspaceEntity?, state: WorkspaceUiState, onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable(row?.id) { mutableStateOf(row?.name ?: "") }
    var kind by rememberSaveable(row?.id) { mutableStateOf(row?.kind ?: "personal") }
    val clientId = rememberSaveable { UUID.randomUUID().toString() }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(if (row == null) "Criar workspace" else "Editar workspace") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 160) name = it }, enabled = !state.busy,
                singleLine = true, label = { Text("Nome") }, placeholder = { Text("Ex.: Empresa, Esposa") })
            Text("Tipo", style = MaterialTheme.typography.labelLarge)
            workspaceKinds.chunked(2).forEach { options ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (value, label) ->
                        FilterChip(selected = kind == value, onClick = { kind = value }, enabled = !state.busy, label = { Text(label) })
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !state.busy && name.isNotBlank(), onClick = { onSave(name.trim(), kind, clientId) }) {
            Text(if (row == null) "Criar e abrir" else "Salvar")
        } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}
