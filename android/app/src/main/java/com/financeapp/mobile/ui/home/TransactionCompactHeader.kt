package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun TransactionCommandsToggle(expanded:Boolean,onToggle:()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        TextButton(onClick=onToggle) {
            Text(
                if(expanded) "Ocultar comandos" else "Mostrar comandos",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.width(4.dp))
            Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
        }
    }
}

@Composable
internal fun TransactionPeriodHeading(title:String,count:Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier=Modifier.weight(1f),
            style=MaterialTheme.typography.titleLarge,
            maxLines=1,
            overflow=TextOverflow.Ellipsis
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "$count ${if(count==1) "transação" else "transações"}",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style=MaterialTheme.typography.labelMedium
            )
        }
    }
}
