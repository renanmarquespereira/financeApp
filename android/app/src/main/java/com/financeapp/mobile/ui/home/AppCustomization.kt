package com.financeapp.mobile.ui.home

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AppCustomization(
    val navOrder: List<String> = listOf("DASHBOARD","TRANSACTIONS","FORECAST","INTELLIGENCE","ACCOUNTS"),
    val hiddenNav: Set<String> = emptySet(),
    val dashboardOrder: List<String> = listOf("CURRENT_VALUE","INCOME","EXPENSES","INVOICES","BUDGETS","BIGGEST_SPEND","GOALS","LATEST","QUICK_ACTIONS"),
    val hiddenDashboard: Set<String> = emptySet(),
    val themeMode: String = "SYSTEM"
)

private const val PREFS = "financeapp_ui_customization_v1"
fun loadCustomization(context: Context, userKey: String): AppCustomization {
    val p=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val prefix=userKey.ifBlank{"local"}+"_"
    fun list(k:String, def:List<String>)=p.getString(prefix+k,null)?.split(',')?.filter{it.isNotBlank()} ?: def
    val defaults = AppCustomization()
    val storedNavOrder = list("nav_order", defaults.navOrder)
    val normalizedNavOrder = (storedNavOrder + defaults.navOrder).distinct()
    return AppCustomization(
        navOrder=normalizedNavOrder,
        hiddenNav=list("nav_hidden", emptyList()).toSet() - setOf("TRANSACTIONS"),
        dashboardOrder=(list("dash_order", defaults.dashboardOrder) + defaults.dashboardOrder).distinct(),
        hiddenDashboard=list("dash_hidden", emptyList()).toSet().let { hidden ->
            if ("SUMMARY" in hidden) (hidden - "SUMMARY") + setOf("CURRENT_VALUE", "INCOME", "EXPENSES") else hidden - "SUMMARY"
        },
        themeMode=p.getString(prefix+"theme_mode", "SYSTEM") ?: "SYSTEM"
    )
}
fun saveCustomization(context: Context, userKey:String, c:AppCustomization){
    val prefix=userKey.ifBlank{"local"}+"_"
    context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
        .putString(prefix+"nav_order",c.navOrder.joinToString(","))
        .putString(prefix+"nav_hidden",(c.hiddenNav - setOf("TRANSACTIONS")).joinToString(","))
        .putString(prefix+"dash_order",c.dashboardOrder.joinToString(","))
        .putString(prefix+"dash_hidden",c.hiddenDashboard.joinToString(","))
        .putString(prefix+"theme_mode",c.themeMode).apply()
}

@Composable
fun AppCustomizationDialog(value:AppCustomization,onThemeChange:(String)->Unit,onSave:(AppCustomization)->Unit,onDismiss:()->Unit){
    var c by remember(value){ mutableStateOf(value) }
    val navLabels=mapOf("DASHBOARD" to "Dashboard","FORECAST" to "Previsão / Planejamento","INTELLIGENCE" to "Inteligência / IA","ACCOUNTS" to "Contas / Cartões")
    val dashLabels=mapOf("CURRENT_VALUE" to "Valor atual","INCOME" to "Entradas","EXPENSES" to "Saídas","INVOICES" to "Faturas de cartão","BUDGETS" to "Orçamentos","BIGGEST_SPEND" to "Maior gasto do mês","GOALS" to "Metas financeiras","LATEST" to "Últimas transações","QUICK_ACTIONS" to "Ações rápidas")
    AlertDialog(onDismissRequest=onDismiss,title={Text("Configurações do aplicativo")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Tema do aplicativo", style=MaterialTheme.typography.titleSmall)
            Text("Escolha como o FinanceApp deve aparecer neste aparelho.", style=MaterialTheme.typography.bodySmall)
            listOf("SYSTEM" to "Seguir o sistema", "LIGHT" to "Claro", "DARK" to "Escuro").forEach { (mode, label) ->
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = c.themeMode == mode, onClick = { c = c.copy(themeMode = mode); onThemeChange(mode) })
                    Text(label, Modifier.padding(top = 12.dp))
                }
            }
            HorizontalDivider()
            Text("Navegação inferior",style=MaterialTheme.typography.titleSmall)
            c.navOrder.filterNot { it == "TRANSACTIONS" }.forEach{ id -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Row(Modifier.weight(1f)){ Checkbox(checked=id !in c.hiddenNav,onCheckedChange={ checked-> c=c.copy(hiddenNav=if(checked)c.hiddenNav-id else c.hiddenNav+id)}); Text(navLabels[id]?:id,Modifier.padding(top=12.dp)) }
            }}
            HorizontalDivider(); Text("Dashboard",style=MaterialTheme.typography.titleSmall)
            Text("A ordem abaixo acompanha o Dashboard. Para reorganizar, pressione e arraste os blocos diretamente no Dashboard.", style=MaterialTheme.typography.bodySmall)
            c.dashboardOrder.forEach{ id -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Row(Modifier.weight(1f)){Checkbox(checked=id !in c.hiddenDashboard,onCheckedChange={checked->c=c.copy(hiddenDashboard=if(checked)c.hiddenDashboard-id else c.hiddenDashboard+id)});Text(dashLabels[id]?:id,Modifier.padding(top=12.dp))}
            }}
        }
    },confirmButton={Button(onClick={onSave(c.copy(hiddenNav=c.hiddenNav - setOf("TRANSACTIONS")))}){Text("Salvar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}
