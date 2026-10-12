package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val guidePages = listOf(
    "Bem-vindo ao FinanceApp" to "Conheça as principais funções. Você pode pular este guia e acessá-lo novamente em Configurações.",
    "Saldo consolidado" to "Na tela Início, consulte o saldo, altere o período Mensal/Anual e use o olhinho para proteger os valores.",
    "Ações rápidas" to "Na tela Início, use os atalhos de adicionar receita ou despesa. Os botões abrem os formulários correspondentes.",
    "Transações" to "Na barra inferior, toque em Transações para consultar, buscar e filtrar entradas e saídas.",
    "Bancos e cartões" to "Na barra inferior, toque em Bancos para cadastrar contas, cartões e consultar faturas.",
    "Dinheiro emprestado" to "Na barra inferior, toque em Emprestado para acompanhar valores emprestados e pagamentos.",
    "Previsão" to "Na barra inferior, toque em Previsão para consultar gráficos e análises financeiras.",
    "Configurações e backup" to "Toque na engrenagem no topo para configurar o app, fazer backup, restaurar dados e refazer este tutorial."
)

@Composable
internal fun FinanceFirstAccessGuide(
    step: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit
) {
    val index = step.coerceIn(0, guidePages.lastIndex)
    val page = guidePages[index]
    AlertDialog(
        onDismissRequest = onSkip,
        icon = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
        title = { Text(page.first) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Etapa ${index + 1} de ${guidePages.size}", style = MaterialTheme.typography.labelSmall)
                Text(page.second)
                if (index in 3..6) {
                    Row {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Confira a aba indicada na navegação inferior.")
                    }
                }
                LinearProgressIndicator(progress = { (index + 1).toFloat() / guidePages.size }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = onNext) {
                Text(if (index == guidePages.lastIndex) "Concluir" else "Próximo")
            }
        },
        dismissButton = {
            Row {
                if (index > 0) TextButton(onClick = onPrevious) { Text("Anterior") }
                TextButton(onClick = onSkip) { Text("Pular") }
            }
        }
    )
}
