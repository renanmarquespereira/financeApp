package com.financeapp.mobile.ui.entry

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeapp.mobile.R
import kotlin.math.roundToInt

@Composable
fun EntrySliderScreen(onEnter: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animated by animateFloatAsState(progress, label = "entrySlider")

    val navy = Color(0xFF06182C)
    val navyMid = Color(0xFF0A2747)
    val navyLight = Color(0xFF0E3A68)
    val accent = Color(0xFF1687FF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(navy, navyMid, navyLight)))
            .safeDrawingPadding()
            .padding(horizontal = 28.dp)
    ) {
        Box(
            Modifier
                .size(300.dp)
                .align(Alignment.TopEnd)
                .offset(x = 120.dp, y = (-90).dp)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.20f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Surface(
                modifier = Modifier.size(112.dp),
                shape = RoundedCornerShape(30.dp),
                color = Color.White,
                shadowElevation = 12.dp
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.financeapp_mark),
                    contentDescription = "FinanceApp",
                    modifier = Modifier.padding(18.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "FinanceApp",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Seu dinheiro, do seu jeito",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFB9D1E8)
            )

            Spacer(Modifier.weight(1.35f))

            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
            ) {
                val density = LocalDensity.current
                val thumb = 68.dp
                val travelPx = with(density) { (maxWidth - thumb).toPx().coerceAtLeast(1f) }
                val offsetPx = animated * travelPx

                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(38.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animated.coerceAtLeast(0.01f))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        accent.copy(alpha = 0.55f),
                                        Color(0xFF65B7FF).copy(alpha = 0.18f)
                                    )
                                )
                            )
                    )

                    Text(
                        "Deslize para entrar",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )

                    Surface(
                        modifier = Modifier
                            .offset { IntOffset(offsetPx.roundToInt(), 0) }
                            .size(68.dp)
                            .align(Alignment.CenterStart)
                            .pointerInput(travelPx) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        progress = (progress + amount / travelPx).coerceIn(0f, 1f)
                                    },
                                    onDragCancel = { progress = 0f },
                                    onDragEnd = {
                                        if (progress >= 0.88f) {
                                            progress = 1f
                                            onEnter()
                                        } else {
                                            progress = 0f
                                        }
                                    }
                                )
                            },
                        shape = CircleShape,
                        color = accent,
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowForward,
                                contentDescription = "Entrar",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}
