package com.financeapp.mobile.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val text: String,
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onSkip: () -> Unit = onFinish,
) {
    val pages = remember {
        listOf(
            OnboardingPage(
                Icons.Default.AccountBalanceWallet,
                "Sua vida financeira em um só lugar",
                "Acompanhe entradas, despesas e saldo consolidado com uma visão simples do que acontece com o seu dinheiro.",
            ),
            OnboardingPage(
                Icons.Default.CreditCard,
                "Bancos e cartões organizados",
                "Centralize contas, cartões e faturas por Workspace sem misturar compras no cartão com o saldo das contas.",
            ),
            OnboardingPage(
                Icons.Default.ShowChart,
                "Planeje antes de gastar",
                "Use categorias, metas, previsão financeira e contas a pagar para enxergar o mês antes que ele termine.",
            ),
            OnboardingPage(
                Icons.Default.AutoAwesome,
                "Inteligência para ajudar nas decisões",
                "Use os recursos de IA do FinanceApp para entender seus dados, criar planos e acompanhar cenários financeiros.",
            ),
        )
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val current = pages[page]
    val last = page == pages.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF071B33),
                        Color(0xFF0D376A),
                        Color(0xFF5145C8),
                    )
                )
            )
            .systemBarsPadding()
    ) {
        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
        ) { Text("Pular") }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(94.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.14f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.22f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        current.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                current.title,
                color = Color.White,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                current.text,
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { index ->
                    Box(
                        Modifier
                            .height(7.dp)
                            .width(if (index == page) 28.dp else 7.dp)
                            .background(
                                if (index == page) Color.White else Color.White.copy(alpha = 0.32f),
                                RoundedCornerShape(99.dp)
                            )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page > 0) {
                OutlinedButton(
                    onClick = { page-- },
                    modifier = Modifier.weight(1f).height(52.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Voltar") }
            }
            Button(
                onClick = { if (last) onFinish() else page++ },
                modifier = Modifier.weight(if (page > 0) 1f else 1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF123B6D),
                ),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (last) "Começar" else "Próximo", fontWeight = FontWeight.Bold) }
        }
    }
}
