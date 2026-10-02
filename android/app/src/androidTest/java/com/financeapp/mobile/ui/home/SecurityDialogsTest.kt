package com.financeapp.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.financeapp.mobile.ui.auth.PasswordRecoveryDialog
import com.financeapp.mobile.ui.auth.PasswordRecoveryState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SecurityDialogsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun deletionRequiresRequestThenSixDigitCode() {
        var confirmed: String? = null
        compose.setContent { MaterialTheme {
            WorkspaceDeleteDialog("Empresa",WorkspaceUiState(),{}, { done -> done() }, { confirmed=it })
        } }
        compose.onNodeWithText("Enviar código").performClick()
        compose.onNodeWithText("Confirmar exclusão definitiva").assertIsNotEnabled()
        compose.onNodeWithText("Código de 6 dígitos").performTextInput("123456")
        compose.onNodeWithText("Confirmar exclusão definitiva").performClick()
        assertEquals("123456",confirmed)
    }
    @Test fun recoveryRequiresMatchingPasswordsAndCode() {
        compose.setContent { MaterialTheme {
            PasswordRecoveryDialog(PasswordRecoveryState(sent=true),{}, {}, { _,_,_ -> })
        } }
        compose.onNodeWithText("Salvar nova senha").assertIsNotEnabled()
        compose.onNodeWithText("Código recebido").performTextInput("123456")
        compose.onNodeWithText("Nova senha (mínimo 6 caracteres)").performTextInput("abcdef")
        compose.onNodeWithText("Confirmar nova senha").performTextInput("abcdeg")
        compose.onNodeWithText("Salvar nova senha").assertIsNotEnabled()
        compose.onNodeWithText("Confirmar nova senha").performTextReplacement("abcdef")
        compose.onNodeWithText("Salvar nova senha").assertIsEnabled()
    }
}
