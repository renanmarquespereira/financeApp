package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GuestOptionsDialog(frozen: Boolean, onDismiss: () -> Unit, onRegister: () -> Unit, onLogin: () -> Unit,
    onCreateAccount: (String) -> Unit, onLogout: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Modo visitante")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Seus dados estão somente neste aparelho. Use a opção abaixo para entrar ou criar uma conta e transferir tudo para um workspace separado.")
            if (frozen) Text("Transferência pendente: entre na conta usada para concluir. Os registros permanecem guardados e novas alterações estão pausadas.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=onRegister,modifier=Modifier.weight(1f)) { Text("Criar conta") }
                OutlinedButton(onClick=onLogin,modifier=Modifier.weight(1f)) { Text("Fazer login") }
            }
            OutlinedTextField(name,{if(it.length<=160)name=it},label={Text("Nome da conta manual")},enabled=!frozen,singleLine=true)
            OutlinedButton(enabled=name.isNotBlank()&&!frozen,onClick={onCreateAccount(name);name=""}){Text("Adicionar conta manual")}
            Text("Cartões, categorias, metas e orçamentos podem ser cadastrados nas telas do app. Conexão bancária e workspaces adicionais ficam disponíveis após criar conta.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=onLogout,modifier=Modifier.fillMaxWidth()){Text("Sair")}
        }},confirmButton={TextButton(onClick=onDismiss){Text("Fechar")}})
}

@Composable
fun GuestTransferDialog(state: WorkspaceUiState, onDismiss: () -> Unit, onTransfer: () -> Unit) {
    AlertDialog(onDismissRequest={if(!state.busy)onDismiss()},title={Text("Levar dados do visitante")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Vamos transferir seus registros para um workspace chamado Dados do visitante nesta conta. Os dados que ela já possui serão mantidos.\n\nA cópia local fica preservada até o servidor confirmar e o app carregar os registros transferidos. Depois de iniciar, use esta mesma conta para concluir uma tentativa interrompida.")
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(onClick=onTransfer,enabled=!state.busy){Text("Transferir meus dados")}},
        dismissButton={TextButton(onClick=onDismiss,enabled=!state.busy){Text("Mais tarde")}})
}
