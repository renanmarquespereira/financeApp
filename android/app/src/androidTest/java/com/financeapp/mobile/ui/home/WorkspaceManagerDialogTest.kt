package com.financeapp.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.financeapp.mobile.data.local.WorkspaceEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceManagerDialogTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        WorkspaceEntity(1, "default-1", "Pessoal", "personal", true, null),
        WorkspaceEntity(1, "company", "Empresa", "business", false, null),
        WorkspaceEntity(1, "old", "Antigo", "other", false, "2026-09-05")
    )
    private var selected: String? = null
    private var created: String? = null
    private var archived: String? = null
    private var restored: String? = null
    private fun show() {
        compose.setContent { MaterialTheme {
            WorkspaceManagerDialog(rows, "default-1", WorkspaceUiState(), {}, {}, {},
                onSelect = { id, done -> selected = id; done() },
                onCreate = { name, kind, id, done -> created = "$name:$kind"; assertTrue(id.isNotBlank()); done() },
                onEdit = { _, _, _, done -> done() },
                onArchive = { id, done -> archived = id; done() }, onRestore = { restored = it })
        } }
    }
    @Test fun opensSelectedWorkspace() {
        show()
        compose.onNodeWithText("Abrir").performClick()
        assertEquals("company", selected)
    }
    @Test fun createsWorkspaceFromVisibleButton() {
        show()
        compose.onNodeWithText("Criar workspace").performClick()
        compose.onNodeWithText("Criar e abrir").assertIsNotEnabled()
        compose.onNodeWithText("Nome").performTextInput("Esposa")
        compose.onNodeWithText("Família").performClick()
        compose.onNodeWithText("Criar e abrir").performClick()
        assertEquals("Esposa:family", created)
    }
    @Test fun archiveRequiresConfirmationAndDefaultIsProtected() {
        show()
        compose.onNodeWithContentDescription("Arquivar Pessoal").assertDoesNotExist()
        compose.onNodeWithContentDescription("Arquivar Empresa").performClick()
        assertNull(archived)
        compose.onNodeWithText("Arquivar").performClick()
        assertEquals("company", archived)
    }
    @Test fun restoresFromArchivedTab() {
        show()
        compose.onNodeWithText("Arquivados").performClick()
        compose.onNodeWithText("Restaurar").performClick()
        assertEquals("old", restored)
    }
}
