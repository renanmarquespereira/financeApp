package com.financeapp.mobile.ui.auth

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
internal fun GuestNoticeDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("Usar sem cadastro")},
        text={Text("Seus dados ficarão , sem backup no servidor. Desinstalar o app, limpar o armazenamento ou perder o celular poderá apagar esses dados.\n\nVocê poderá criar uma conta depois e transferir seus registros. O modo visitante permite lançamentos manuais; Open Finance e sincronização exigem conta.")},
        confirmButton={TextButton(onClick=onAccept){Text("Entendi, continuar")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}
