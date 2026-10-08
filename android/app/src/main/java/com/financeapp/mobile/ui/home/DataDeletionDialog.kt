package com.financeapp.mobile.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.financeapp.mobile.data.deletion.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DataDeletionEntryPoint {
    fun deletionRepository(): LocalDataDeletionRepository
}

private fun Context.deletionActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.deletionActivity()
    else -> null
}

/** Shown by the ACTUAL local-mode gear menu and by the Workspace center. */
@Composable
fun DataDeletionDialog(
    onDismiss: () -> Unit,
    initialChoice: DeletionChoice? = null,
    userKey: String = "local"
) {
    val context = LocalContext.current
    val repository = remember { EntryPointAccessors.fromApplication(context.applicationContext,
        DataDeletionEntryPoint::class.java).deletionRepository() }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<DeletionSnapshot?>(null) }
    var choice by remember { mutableStateOf(initialChoice ?: DeletionChoice()) }
    var plan by remember { mutableStateOf<DeletionPlan?>(null) }
    var step by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var selectWorkspace by remember { mutableStateOf(false) }
    var fromInput by remember { mutableStateOf("") }
    var toInput by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val centralDeletion = initialChoice?.deleteWorkspaces == true
    LaunchedEffect(Unit) {
        try {
            val loaded = repository.snapshot(userKey)
            snapshot = loaded
            if(choice.workspaceIds.isEmpty()) choice = choice.copy(workspaceIds = setOf(loaded.activeWorkspaceId))
        }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { error = e.message ?: "N\u00e3o foi poss\u00edvel carregar os dados." }
        finally { busy = false }
    }
    fun back() {
        error = null
        when {
            success -> { onDismiss(); context.deletionActivity()?.recreate() }
            step == 2 -> { step = 1 }
            step == 1 -> { plan = null; step = 0 }
            else -> onDismiss()
        }
    }
    fun date(raw: String): String {
        if(raw.isBlank()) return ""
        return try { LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT)).toString() }
        catch(e: Exception) { kotlin.error("Data inv\u00e1lida. Use dd/mm/aaaa.") }
    }
    fun preview() {
        scope.launch {
            busy = true; error = null
            try {
                val fresh = repository.snapshot(userKey)
                val selected = choice.copy(fromDate = date(fromInput), toDate = date(toInput))
                val calculated = DeletionPlanner.build(fresh, selected)
                require(calculated.total > 0) { "Nenhum dado encontrado para a sele\u00e7\u00e3o. Marque o que deseja excluir." }
                snapshot = fresh; plan = calculated; step = 1
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "N\u00e3o foi poss\u00edvel montar a pr\u00e9via." }
            finally { busy = false }
        }
    }
    // The user explicitly confirms LOCAL SQLite deletion. No email/API request is made.
    fun confirm() {
        val frozen = plan ?: return
        scope.launch {
            busy = true
            error = null
            try {
                repository.confirmLocally(frozen)
                success = true
                step = 3
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "A exclusão não foi concluída." }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if(!busy) back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("data-deletion-screen")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }, enabled = !busy) { Icon(Icons.Default.ArrowBack, "Voltar") }
                    Text("Gerenciar e excluir dados", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    if(step == 0) {
                        val data = snapshot
                        item {
                            Text("1. Escolha os dados", style = MaterialTheme.typography.titleMedium)
                            Text("Nada ser\u00e1 apagado ao selecionar. Confira a pr\u00e9via antes de confirmar a exclus\u00e3o local.", style = MaterialTheme.typography.bodyMedium)
                        }
                        if(data != null) {
                            val wid = choice.workspaceIds.firstOrNull() ?: data.activeWorkspaceId
                            val ws = data.workspaces.firstOrNull { it["id"] == wid }
                            fun localRows(table: String) = data.tables[table].orEmpty().filter { it["workspaceId"] == wid }
                            item {
                                Box {
                                    OutlinedButton(onClick = { selectWorkspace = true }, enabled = !busy && !centralDeletion,
                                        modifier = Modifier.fillMaxWidth()) { Text("Workspace: ${ws?.get("name") ?: wid}") }
                                    DropdownMenu(expanded = selectWorkspace, onDismissRequest = { selectWorkspace = false }) {
                                        data.workspaces.forEach { row -> DropdownMenuItem(text = { Text(row["name"].orEmpty()) }, onClick = {
                                            choice = DeletionChoice(workspaceIds = setOf(requireNotNull(row["id"])))
                                            fromInput = ""; toInput = ""; query = ""; selectWorkspace = false
                                        }) }
                                    }
                                }
                            }
                            if(centralDeletion) {
                                item { Text("Workspaces selecionados: " + data.workspaces.filter { it["id"] in choice.workspaceIds }.joinToString { it["name"].orEmpty() }) }
                            }
                            item {
                                DeleteToggle("Workspace selecionado", choice.deleteWorkspaces, !busy && !centralDeletion,
                                    "Apaga o workspace e todos os dados vinculados. Os outros workspaces ficam intactos.") {
                                    choice = DeletionChoice(workspaceIds = choice.workspaceIds, deleteWorkspaces = it)
                                }
                            }
                            if(!choice.deleteWorkspaces) {
                                item {
                                    DeleteToggle("Todos os dados financeiros", choice.allFinancial, !busy,
                                        "Apaga bancos, cart\u00f5es, transa\u00e7\u00f5es, categorias, cen\u00e1rios, planos, metas e d\u00edvidas. Mant\u00e9m os workspaces e as configura\u00e7\u00f5es.") {
                                        choice = DeletionChoice(workspaceIds = choice.workspaceIds, allFinancial = it)
                                    }
                                }
                                if(choice.allFinancial) {
                                    item { DeleteToggle("Incluir todos os meus Workspaces", choice.allWorkspaces, !busy,
                                        "Desmarcado: somente o workspace indicado acima.") { choice = choice.copy(allWorkspaces = it) } }
                                } else {
                                    val accounts = localRows("accounts")
                                    item { DeleteSection("Contas banc\u00e1rias", "Tamb\u00e9m remove as transa\u00e7\u00f5es vinculadas \u00e0s contas selecionadas.") }
                                    item { DeleteToggle("Todas as contas (${accounts.size})", accounts.isNotEmpty() && choice.accountIds.size == accounts.size, !busy && accounts.isNotEmpty()) {
                                        choice = choice.copy(accountIds = if(it) accounts.mapNotNull { r -> r.int("id") }.toSet() else emptySet())
                                    } }
                                    items(accounts, key = { "account-" + it["id"] }) { row ->
                                        val id = row.int("id")!!
                                        DeleteToggle(row["institutionName"].orEmpty() + " \u00b7 " + row["accountName"].orEmpty(), id in choice.accountIds, !busy) {
                                            choice = choice.copy(accountIds = if(it) choice.accountIds + id else choice.accountIds - id)
                                        }
                                    }
                                    val cards = localRows("credit_cards_local")
                                    item { DeleteSection("Cart\u00f5es de cr\u00e9dito", "Tamb\u00e9m remove as compras e parcelas vinculadas aos cart\u00f5es selecionados.") }
                                    item { DeleteToggle("Todos os cart\u00f5es (${cards.size})", cards.isNotEmpty() && choice.cardIds.size == cards.size, !busy && cards.isNotEmpty()) {
                                        choice = choice.copy(cardIds = if(it) cards.mapNotNull { r -> r.int("id") }.toSet() else emptySet())
                                    } }
                                    items(cards, key = { "card-" + it["id"] }) { row ->
                                        val id = row.int("id")!!
                                        DeleteToggle((row["nickname"]?.takeIf { it.isNotBlank() } ?: row["bankName"].orEmpty()) + " \u00b7 " + row["lastFour"].orEmpty(), id in choice.cardIds, !busy) {
                                            choice = choice.copy(cardIds = if(it) choice.cardIds + id else choice.cardIds - id)
                                        }
                                    }
                                    item { DeleteSection("Transa\u00e7\u00f5es", "Inclui entradas, despesas, contas a pagar e compras de cart\u00e3o conforme o filtro escolhido.") }
                                    item { DeleteToggle("Excluir transa\u00e7\u00f5es", choice.transactions, !busy) { choice = choice.copy(transactions = it) } }
                                    if(choice.transactions) {
                                        item {
                                            Column {
                                                listOf("all" to "Todas no per\u00edodo", "cards" to "Somente compras de cart\u00e3o", "selected" to "Escolher individualmente").forEach { (mode, label) ->
                                                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { choice = choice.copy(transactionMode = mode) }, verticalAlignment = Alignment.CenterVertically) {
                                                        RadioButton(selected = choice.transactionMode == mode, onClick = { choice = choice.copy(transactionMode = mode) }, enabled = !busy)
                                                        Text(label)
                                                    }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedTextField(fromInput, { fromInput = it }, modifier = Modifier.weight(1f), label = { Text("De (opcional)") }, placeholder = { Text("dd/mm/aaaa") }, enabled = !busy, singleLine = true)
                                                    OutlinedTextField(toInput, { toInput = it }, modifier = Modifier.weight(1f), label = { Text("At\u00e9 (opcional)") }, placeholder = { Text("dd/mm/aaaa") }, enabled = !busy, singleLine = true)
                                                }
                                            }
                                        }
                                        if(choice.transactionMode == "selected") {
                                            val filtered = localRows("transactions").filter { query.isBlank() || it["description"].orEmpty().contains(query, ignoreCase = true) }.sortedByDescending { it["date"] }
                                            item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Buscar descri\u00e7\u00e3o") }, enabled = !busy, singleLine = true) }
                                            items(filtered.take(150), key = { "tx-" + it["id"] }) { row ->
                                                val id = row.int("id")!!
                                                DeleteToggle(row["description"].orEmpty(), id in choice.transactionIds, !busy,
                                                    "${row["date"].orEmpty().take(10)} \u00b7 R$ ${row["amount"]}") { choice = choice.copy(transactionIds = if(it) choice.transactionIds + id else choice.transactionIds - id) }
                                            }
                                            if(filtered.size > 150) item { Text("Mostrando 150 registros. Use a busca para localizar os demais.") }
                                        }
                                    }
                                    val categories = localRows("categories")
                                    item { DeleteSection("Categorias", "As transa\u00e7\u00f5es s\u00e3o preservadas, sem categoria. Os limites associados ser\u00e3o removidos.") }
                                    item { DeleteToggle("Todas as categorias (${categories.size})", categories.isNotEmpty() && choice.categoryIds.size == categories.size, !busy && categories.isNotEmpty()) {
                                        choice = choice.copy(categoryIds = if(it) categories.mapNotNull { r -> r.int("id") }.toSet() else emptySet())
                                    } }
                                    items(categories, key = { "cat-" + it["id"] }) { row ->
                                        val id = row.int("id")!!
                                        DeleteToggle(row["name"].orEmpty(), id in choice.categoryIds, !busy) {
                                            choice = choice.copy(categoryIds = if(it) choice.categoryIds + id else choice.categoryIds - id)
                                        }
                                    }
                                    item { DeleteToggle("Previs\u00f5es e cen\u00e1rios", choice.forecasts, !busy,
                                        "Remove cen\u00e1rios salvos. Proje\u00e7\u00f5es calculadas a partir das transa\u00e7\u00f5es podem continuar aparecendo.") { choice = choice.copy(forecasts = it) } }
                                    item { DeleteToggle("Planejamentos e metas", choice.planning, !busy,
                                        "Remove limites, metas, aportes e planos salvos. N\u00e3o apaga as transa\u00e7\u00f5es financeiras.") { choice = choice.copy(planning = it) } }
                                }
                            }
                            item {
                                DeleteSection("Conta de usu\u00e1rio", "Este Android est\u00e1 no modo FinanceApp local, sem um fluxo de conta online. Para zerar o conte\u00fado financeiro deste perfil, use Todos os dados financeiros. Esta tela n\u00e3o encerra uma eventual conta antiga no servidor.")
                            }
                            item { Text("Backups j\u00e1 exportados e arquivos no Google Drive n\u00e3o ser\u00e3o apagados por esta tela.", style = MaterialTheme.typography.bodySmall) }
                        }
                    } else if(step == 1 || step == 2) {
                        val p = plan
                        if(p != null) {
                            item { Text(if(step == 1) "2. Confira antes de excluir" else "Confirmar exclusão?", style = MaterialTheme.typography.titleMedium) }
                            item { Text("Workspaces: " + p.snapshot.workspaces.filter { it["id"] in p.scopeIds }.joinToString { it["name"].orEmpty() }) }
                            items(p.counts.filterValues { it > 0 }.entries.toList(), key = { it.key }) { (key, value) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(DeletionPlanner.countLabels[key].orEmpty()); Text(value.toString(), fontWeight = FontWeight.Bold)
                                }
                            }
                            item {
                                Text("Esta a\u00e7\u00e3o \u00e9 definitiva. Revise os itens acima. Refer\u00eancia: ${p.planHash.take(8)}.", color = MaterialTheme.colorScheme.error)
                                if(p.choice.deleteWorkspaces) Text("Se n\u00e3o restar nenhum workspace ativo, ser\u00e1 criado um Principal vazio para voc\u00ea continuar usando o app.")
                                if(p.choice.accountIds.isNotEmpty() || p.choice.cardIds.isNotEmpty()) Text("A contagem inclui as transa\u00e7\u00f5es vinculadas aos bancos e cart\u00f5es selecionados.")
                                Text("Outros workspaces e backups existentes n\u00e3o ser\u00e3o alterados, salvo os workspaces explicitamente selecionados acima.", style = MaterialTheme.typography.bodySmall)
                            }
                            if(step == 2) {
                                item {
                                    Text("Deseja excluir definitivamente os dados selecionados?", fontWeight = FontWeight.Bold)
                                    Text("Sim, excluir: remove apenas os registros locais indicados na prévia. Não, cancelar: retorna sem apagar nada.",
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    } else if(success) {
                        item {
                            Text("Exclus\u00e3o conclu\u00edda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Os dados selecionados foram removidos. O aplicativo atualizar\u00e1 as telas ao tocar em Concluir.")
                            Text("Os backups existentes no Google Drive n\u00e3o foram apagados.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("deletion-error"))
                }
                Button(
                    onClick = { when(step) { 0 -> preview(); 1 -> { error = null; step = 2 }; 2 -> confirm(); else -> back() } },
                    enabled = !busy && snapshot != null,
                    modifier = Modifier.fillMaxWidth().testTag("deletion-primary-action"),
                    colors = if(step == 2) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) {
                    if(step == 2) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(8.dp)) }
                    Text(when(step) { 0 -> "Revisar sele\u00e7\u00e3o"; 1 -> "Continuar para confirma\u00e7\u00e3o"; 2 -> "Sim, excluir"; else -> "Concluir" })
                }
                if(!success) TextButton(onClick = { back() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(when(step) { 0 -> "Cancelar"; 2 -> "Não, cancelar"; else -> "Voltar sem excluir" })
                }
            }
        }
    }
}

@Composable
private fun DeleteSection(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeleteToggle(title: String, checked: Boolean, enabled: Boolean, detail: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if(detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
