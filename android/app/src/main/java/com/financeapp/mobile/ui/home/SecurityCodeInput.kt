package com.financeapp.mobile.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Entrada padronizada para códigos de segurança em caixas individuais. */
@Composable
fun SixDigitCodeInput(
    code: String,
    digits: Int = 6,
    onCodeChange: (String) -> Unit,
    onDone: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var hasFocus by remember { mutableStateOf(false) }
    val cursorTransition = rememberInfiniteTransition(label = "securityCodeCursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(animation = tween(520), repeatMode = RepeatMode.Reverse),
        label = "securityCodeCursorAlpha"
    )
    val activeIndex = when {
        !hasFocus || !enabled -> -1
        code.length >= digits -> (digits - 1).coerceAtLeast(0)
        else -> code.length
    }

    BasicTextField(
        value = code,
        onValueChange = { typed -> onCodeChange(typed.filter(Char::isDigit).take(digits)) },
        modifier = modifier.onFocusChanged { hasFocus = it.isFocused },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.length == digits) onDone() }),
        decorationBox = { innerTextField ->
            Box {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(digits) { index ->
                        val digit = code.getOrNull(index)?.toString().orEmpty()
                        val isActive = index == activeIndex
                        val shape = MaterialTheme.shapes.medium
                        OutlinedCard(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .then(
                                    if (isActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                                    else Modifier
                                ),
                            shape = shape
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (digit.isNotEmpty()) {
                                    Text(digit, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                } else if (isActive) {
                                    Box(
                                        Modifier
                                            .width(2.dp)
                                            .height(26.dp)
                                            .alpha(cursorAlpha)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                    }
                }
                // Mantem um unico campo real para teclado, colagem e acessibilidade.
                // As caixas acima sao apenas a representacao visual do codigo e aceitam colagem pelo campo real.
                Box(Modifier.matchParentSize().alpha(0f)) { innerTextField() }
            }
        }
    )
}
