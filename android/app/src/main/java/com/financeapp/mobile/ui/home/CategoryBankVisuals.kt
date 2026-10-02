package com.financeapp.mobile.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import java.util.Locale

internal data class CategoryIconOption(
    val id: String,
    val label: String,
    val icon: ImageVector
)

internal val categoryIconOptions = listOf(
    CategoryIconOption("food", "Alimentação", Icons.Default.Restaurant),
    CategoryIconOption("groceries", "Mercado", Icons.Default.Fastfood),
    CategoryIconOption("transport", "Transporte", Icons.Default.DirectionsCar),
    CategoryIconOption("home", "Moradia", Icons.Default.Home),
    CategoryIconOption("health", "Saúde", Icons.Default.LocalHospital),
    CategoryIconOption("education", "Educação", Icons.Default.School),
    CategoryIconOption("shopping", "Compras", Icons.Default.ShoppingBag),
    CategoryIconOption("leisure", "Lazer", Icons.Default.SportsEsports),
    CategoryIconOption("work", "Trabalho", Icons.Default.Work),
    CategoryIconOption("travel", "Viagem", Icons.Default.Flight),
    CategoryIconOption("pets", "Pets", Icons.Default.Pets),
    CategoryIconOption("gift", "Presentes", Icons.Default.Celebration),
    CategoryIconOption("clothes", "Roupas", Icons.Default.Checkroom),
    CategoryIconOption("bills", "Contas", Icons.Default.ReceiptLong),
    CategoryIconOption("family", "Família", Icons.Default.Favorite),
    CategoryIconOption("other", "Outros", Icons.Default.Category)
)

private fun visualKey(value: String): String =
    Normalizer.normalize(value.trim().lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

internal fun inferredCategoryIconId(name: String): String {
    val key = visualKey(name)
    return when {
        listOf("aliment", "restaur", "lanche", "comida", "cafe", "padaria").any(key::contains) -> "food"
        listOf("mercado", "supermerc", "feira").any(key::contains) -> "groceries"
        listOf("transport", "uber", "combust", "gasolina", "carro", "veiculo", "oficina").any(key::contains) -> "transport"
        listOf("casa", "moradia", "aluguel", "condominio").any(key::contains) -> "home"
        listOf("saude", "farmacia", "medic", "hospital", "dent").any(key::contains) -> "health"
        listOf("educ", "curso", "faculdade", "escola", "livro").any(key::contains) -> "education"
        listOf("roupa", "vestuario", "calcado").any(key::contains) -> "clothes"
        listOf("lazer", "jogo", "cinema", "stream", "entreten").any(key::contains) -> "leisure"
        listOf("viagem", "hotel", "passagem").any(key::contains) -> "travel"
        listOf("pet", "cachorro", "gato", "veterin").any(key::contains) -> "pets"
        listOf("presente", "doacao").any(key::contains) -> "gift"
        listOf("salario", "trabalho", "renda", "pagamento").any(key::contains) -> "work"
        listOf("conta", "energia", "agua", "internet", "telefone", "boleto").any(key::contains) -> "bills"
        listOf("compra", "shopping").any(key::contains) -> "shopping"
        listOf("famil", "filho").any(key::contains) -> "family"
        else -> "other"
    }
}

internal fun categoryIconVector(iconId: String?, categoryName: String): ImageVector {
    val id = iconId?.takeIf { value -> categoryIconOptions.any { it.id == value } }
        ?: inferredCategoryIconId(categoryName)
    return categoryIconOptions.firstOrNull { it.id == id }?.icon ?: Icons.Default.Category
}

@Composable
internal fun CategoryIconPicker(
    selectedIcon: String?,
    categoryName: String,
    onSelected: (String) -> Unit
) {
    val effective = selectedIcon ?: inferredCategoryIconId(categoryName)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categoryIconOptions.forEach { option ->
            val selected = option.id == effective
            Surface(
                onClick = { onSelected(option.id) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(option.icon, option.label, modifier = Modifier.size(20.dp))
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Text(option.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BankBadge(bankName: String) {
    val key = visualKey(bankName)
    val (background, foreground) = when {
        "nubank" in key || key == "nu" -> Color(0xFF820AD1) to Color.White
        ("banco" in key && "brasil" in key) || key == "bb" || key.startsWith("bb ") || "bancodobrasil" in key || key == "001" || key.startsWith("001 ") -> Color(0xFFFFD800) to Color(0xFF163B65)
        "itau" in key || "341" in key -> Color(0xFFEC7000) to Color.White
        "santander" in key || "033" in key -> Color(0xFFEC0000) to Color.White
        "bradesco" in key || "237" in key -> Color(0xFFCC092F) to Color.White
        "caixa" in key -> Color(0xFF0066B3) to Color.White
        "inter" in key -> Color(0xFFFF7A00) to Color.White
        "c6" in key -> Color(0xFF242424) to Color.White
        "picpay" in key -> Color(0xFF21C25E) to Color(0xFF062A15)
        "mercado pago" in key -> Color(0xFF00AEEF) to Color(0xFF082A3B)
        "pagbank" in key || "pagseguro" in key -> Color(0xFF00A650) to Color.White
        "neon" in key -> Color(0xFF00E1FF) to Color(0xFF00313A)
        "btg" in key -> Color(0xFF0B2B50) to Color.White
        "safra" in key -> Color(0xFF0B345C) to Color.White
        "sicredi" in key -> Color(0xFF3FAE2A) to Color.White
        "sicoob" in key -> Color(0xFF006B62) to Color.White
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier.widthIn(max = 150.dp),
        shape = RoundedCornerShape(999.dp),
        color = background,
        contentColor = foreground
    ) {
        Text(
            text = bankName,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
