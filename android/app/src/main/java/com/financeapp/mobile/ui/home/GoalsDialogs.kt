package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.remote.GoalContributionDto
import com.financeapp.mobile.data.remote.GoalDto
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun GoalHistoryDialog(
    goal: GoalDto,
    contributions: List<GoalContributionDto>,
    onAddValue: () -> Unit,
    onDeleteContribution:
        (GoalContributionDto) -> Unit,
    onDismiss: () -> Unit
) {
    val currency = remember {
        NumberFormat.getCurrencyInstance(
            Locale("pt", "BR")
        )
    }

    val sorted = remember(contributions) {
        contributions.sortedByDescending {
            it.createdAt
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(goal.name)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier =
                            Modifier.padding(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Total acumulado",
                            style =
                                MaterialTheme.typography
                                    .labelMedium
                        )
                        Text(
                            currency.format(
                                goal.currentAmount
                            ),
                            style =
                                MaterialTheme.typography
                                    .headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Meta: ${
                                currency.format(
                                    goal.targetAmount
                                )
                            }",
                            style =
                                MaterialTheme.typography
                                    .bodySmall
                        )
                    }
                }

                Button(
                    onClick = onAddValue,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null
                    )
                    Spacer(
                        Modifier.width(6.dp)
                    )
                    Text("Adicionar valor")
                }

                Text(
                    "Histórico de aportes",
                    fontWeight = FontWeight.Bold
                )

                if (sorted.isEmpty()) {
                    Text(
                        "Nenhum aporte individual registrado ainda.",
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )
                } else {
                    LazyColumn(
                        verticalArrangement =
                            Arrangement.spacedBy(7.dp)
                    ) {
                        items(
                            items = sorted,
                            key = { it.id }
                        ) { contribution ->
                            val displayDate =
                                runCatching {
                                    OffsetDateTime.parse(
                                        contribution.createdAt
                                    )
                                        .atZoneSameInstant(
                                            ZoneId.systemDefault()
                                        )
                                        .format(
                                            DateTimeFormatter
                                                .ofPattern(
                                                    "dd/MM/yyyy HH:mm"
                                                )
                                        )
                                }.getOrElse {
                                    contribution.createdAt
                                        .replace("T", " ")
                                        .take(16)
                                }

                            Card(
                                modifier =
                                    Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier =
                                        Modifier.padding(
                                            10.dp
                                        ),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default
                                            .AddCircleOutline,
                                        contentDescription =
                                            null,
                                        tint =
                                            Color(
                                                0xFF178A3A
                                            )
                                    )
                                    Spacer(
                                        Modifier.width(8.dp)
                                    )
                                    Column(
                                        modifier =
                                            Modifier.weight(1f)
                                    ) {
                                        Text(
                                            displayDate,
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall
                                        )
                                        Text(
                                            "Valor adicionado",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .labelSmall
                                        )
                                    }
                                    Text(
                                        "+ ${
                                            currency.format(
                                                contribution.amount
                                            )
                                        }",
                                        fontWeight =
                                            FontWeight.Bold,
                                        color =
                                            Color(
                                                0xFF178A3A
                                            )
                                    )
                                    IconButton(
                                        onClick = {
                                            onDeleteContribution(
                                                contribution
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription =
                                                "Excluir aporte",
                                            tint =
                                                MaterialTheme
                                                    .colorScheme
                                                    .error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Fechar")
            }
        }
    )
}

@Composable
internal fun AddGoalValueDialog(
    goal: GoalDto,
    onDismiss: () -> Unit,
    onAdd: (Double) -> Unit
) {
    var amountDigits by remember(goal.id) {
        mutableStateOf("")
    }

    val amount = BrlMoney.toDouble(amountDigits)
    val currency = remember {
        NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Adicionar valor")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    goal.name,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Acumulado atual: ${currency.format(goal.currentAmount)}",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = amountDigits,
                    onValueChange = { typed ->
                        amountDigits = typed
                            .filter(Char::isDigit)
                            .trimStart('0')
                            .take(12)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("Valor a adicionar")
                    },
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (amount > 0.0) {
                                onAdd(amount)
                            }
                        }
                    )
                )
                if (amount > 0.0) {
                    Text(
                        "Novo acumulado: ${
                            currency.format(goal.currentAmount + amount)
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(amount)
                },
                enabled = amount > 0.0
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Adicionar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalDialog(
    goal: GoalDto?,
    onDismiss: () -> Unit,
    onSave: (
        String,
        Double,
        Double,
        String?
    ) -> Unit
) {
    var name by remember(goal?.id) {
        mutableStateOf(goal?.name ?: "")
    }
    var targetDigits by remember(goal?.id) {
        mutableStateOf(
            goal?.targetAmount
                ?.let { (it * 100).toLong().toString() }
                ?: ""
        )
    }
    var currentDigits by remember(goal?.id) {
        mutableStateOf(
            goal?.currentAmount
                ?.let { (it * 100).toLong().toString() }
                ?: ""
        )
    }

    var targetDate by remember(goal?.id) {
        mutableStateOf(
            goal?.targetDate?.let {
                runCatching {
                    LocalDate.parse(it)
                }.getOrNull()
            }
        )
    }
    var showDatePicker by remember {
        mutableStateOf(false)
    }

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }

    val targetAmount =
        BrlMoney.toDouble(targetDigits)
    val currentAmount =
        BrlMoney.toDouble(currentDigits)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (goal == null) {
                    "Nova meta financeira"
                } else {
                    "Editar meta"
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(160)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("Nome da meta")
                    },
                    placeholder = {
                        Text("Ex.: Reserva de emergência")
                    }
                )

                OutlinedTextField(
                    value = targetDigits,
                    onValueChange = { typed ->
                        targetDigits = typed
                            .filter(Char::isDigit)
                            .trimStart('0')
                            .take(12)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("Valor da meta")
                    },
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )

                OutlinedTextField(
                    value = currentDigits,
                    onValueChange = { typed ->
                        currentDigits = typed
                            .filter(Char::isDigit)
                            .trimStart('0')
                            .take(12)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("Valor já acumulado")
                    },
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )

                OutlinedButton(
                    onClick = {
                        showDatePicker = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        targetDate?.let {
                            "Prazo: ${it.format(dateFormatter)}"
                        } ?: "Definir prazo (opcional)"
                    )
                }

                if (targetDate != null) {
                    TextButton(
                        onClick = {
                            targetDate = null
                        }
                    ) {
                        Text("Remover prazo")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        targetAmount,
                        currentAmount,
                        targetDate?.toString()
                    )
                },
                enabled =
                    name.isNotBlank() &&
                        targetAmount > 0.0
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancelar")
            }
        }
    )

    if (showDatePicker) {
        val zone = ZoneOffset.UTC
        val initialDate =
            targetDate ?: LocalDate.now()

        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis =
                initialDate
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
        )

        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState
                            .selectedDateMillis
                            ?.let { millis ->
                                targetDate =
                                    Instant
                                        .ofEpochMilli(millis)
                                        .atZone(zone)
                                        .toLocalDate()
                            }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                    }
                ) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        "Prazo da meta",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            )
        }
    }
}

private fun goalFormatCurrencyFromDigits(
    digits: String
): String {
    val numericDigits =
        digits.filter(Char::isDigit)

    if (numericDigits.isEmpty()) {
        return "R$ 0,00"
    }

    val cents =
        numericDigits.toLongOrNull()
            ?: 0L

    val value =
        cents / 100.0

    return NumberFormat
        .getCurrencyInstance(
            Locale("pt", "BR")
        )
        .format(value)
}

