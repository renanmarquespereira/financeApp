package com.financeapp.mobile.ui.auth

import com.financeapp.mobile.ui.home.SixDigitCodeInput

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PasswordRecoveryDialog(state: PasswordRecoveryState, onDismiss: () -> Unit,
    onRequest: (String) -> Unit, onConfirm: (String, String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Esqueci minha senha") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.complete) {
                Text("Informe o e-mail da sua conta para receber um código de 6 dígitos.")
                OutlinedTextField(email, { email = it }, enabled = !state.busy && !state.sent,
                    singleLine = true, label = { Text("E-mail") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (state.sent) {
                    SixDigitCodeInput(code = code, onCodeChange = { code = it }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { if (it.length <= 128) password = it }, enabled = !state.busy,
                        singleLine = true, label = { Text("Nova senha (mínimo 6 caracteres)") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(repeated, { repeated = it }, enabled = !state.busy,
                        singleLine = true, label = { Text("Confirmar nova senha") }, visualTransformation = PasswordVisualTransformation())
                    if (repeated.isNotEmpty() && password != repeated) Text("As senhas não coincidem.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { code = ""; onRequest(email) }, enabled = !state.busy) { Text("Reenviar código") }
                    Text("O código vence em 10 minutos. Aguarde 1 minuto entre envios. Para corrigir o e-mail, feche e abra novamente.", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.message?.let { Text(it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = {
            TextButton(enabled = !state.busy && (state.complete || (!state.sent && email.isNotBlank()) ||
                (state.sent && code.length == 6 && password.length >= 6 && password == repeated)),
                onClick = { when { state.complete -> onDismiss(); state.sent -> onConfirm(email, code, password); else -> onRequest(email) } }) {
                Text(when { state.complete -> "Voltar ao login"; state.sent -> "Salvar nova senha"; else -> "Enviar código" })
            }
        },
        dismissButton = { if (!state.complete) TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancelar") } }
    )
}
