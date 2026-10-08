package com.financeapp.mobile.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.TransactionAttachmentDto
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import retrofit2.HttpException

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun attachmentFailureMessage(t: Throwable, fallback: String): String {
    if (t is HttpException) {
        val code = t.code()
        val raw = runCatching { t.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val detail = Regex(""""detail"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.getOrNull(1)
        return when {
            !detail.isNullOrBlank() -> "$detail (HTTP $code)"
            code == 404 -> "O arquivo do comprovante não foi encontrado no servidor (HTTP 404)."
            else -> "$fallback (HTTP $code)"
        }
    }
    return t.message?.takeIf { it.isNotBlank() } ?: fallback
}

@Composable
internal fun TransactionAttachmentsDialog(
    tx: TransactionEntity,
    onDismiss: () -> Unit,
    onList: suspend (Int) -> List<TransactionAttachmentDto>,
    onUpload: suspend (Int, String, String, ByteArray) -> TransactionAttachmentDto,
    onDownload: suspend (Int, Int) -> ByteArray,
    onDelete: suspend (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember(tx.id) { mutableStateOf<List<TransactionAttachmentDto>>(emptyList()) }
    var loading by remember(tx.id) { mutableStateOf(true) }
    var error by remember(tx.id) { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<TransactionAttachmentDto?>(null) }
    var addMenuExpanded by remember { mutableStateOf(false) }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(10)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember(scannerOptions) { GmsDocumentScanning.getClient(scannerOptions) }

    fun refresh() {
        scope.launch {
            loading = true; error = null
            runCatching { onList(tx.id) }
                .onSuccess { rows = it }
                .onFailure { error = attachmentFailureMessage(it, "Não foi possível carregar os comprovantes.") }
            loading = false
        }
    }

    LaunchedEffect(tx.id) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true; error = null
            runCatching {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                require(mime == "application/pdf" || mime.startsWith("image/")) { "Selecione uma imagem ou PDF." }
                var name = "comprovante"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Não foi possível ler o arquivo.")
                onUpload(tx.id, name, mime, bytes)
            }.onSuccess { refresh() }
             .onFailure { error = attachmentFailureMessage(it, "Não foi possível anexar o comprovante."); loading = false }
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
        val pdfUri = scanResult?.pdf?.uri
        if (pdfUri == null) {
            error = "Não foi possível gerar o PDF do documento digitalizado."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            loading = true; error = null
            runCatching {
                val bytes = context.contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
                    ?: error("Não foi possível ler o documento digitalizado.")
                require(bytes.isNotEmpty()) { "O documento digitalizado está vazio." }
                val fileName = "comprovante_digitalizado_${System.currentTimeMillis()}.pdf"
                onUpload(tx.id, fileName, "application/pdf", bytes)
            }.onSuccess { refresh() }
             .onFailure { error = attachmentFailureMessage(it, "Não foi possível anexar o documento digitalizado."); loading = false }
        }
    }

    fun startDocumentScan() {
        addMenuExpanded = false
        val activity = context.findActivity()
        if (activity == null) {
            error = "Não foi possível abrir a câmera para digitalizar o documento."
            return
        }
        loading = true; error = null
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                loading = false
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { throwable ->
                loading = false
                error = attachmentFailureMessage(throwable, "Não foi possível iniciar o digitalizador de documentos.")
            }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val row = pendingSave
        val uri = result.data?.data
        pendingSave = null
        if (row == null || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true; error = null
            runCatching {
                val bytes = onDownload(tx.id, row.id)
                require(bytes.isNotEmpty()) { "O comprovante recebido está vazio." }
                val pfd = context.contentResolver.openFileDescriptor(uri, "rwt")
                    ?: error("Não foi possível abrir o arquivo de destino.")
                pfd.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { out ->
                        out.channel.truncate(0)
                        out.write(bytes)
                        out.flush()
                        runCatching { descriptor.fileDescriptor.sync() }
                    }
                }
                val savedSize = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull()
                if (savedSize != null && savedSize >= 0L) {
                    require(savedSize == bytes.size.toLong()) {
                        "O arquivo foi recebido com ${bytes.size} bytes, mas o Android gravou $savedSize bytes."
                    }
                }
            }.onFailure { error = attachmentFailureMessage(it, "Não foi possível baixar o comprovante.") }
            loading = false
        }
    }

    fun saveAttachment(row: TransactionAttachmentDto) {
        pendingSave = row
        saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = row.contentType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, row.originalName)
        })
    }

    fun shareAttachment(row: TransactionAttachmentDto) {
        scope.launch {
            loading = true; error = null
            runCatching {
                val bytes = onDownload(tx.id, row.id)
                require(bytes.isNotEmpty()) { "O comprovante recebido está vazio." }
                val dir = File(context.cacheDir, "transaction_attachments").apply { mkdirs() }
                val safeName = row.originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, "${row.id}_$safeName")
                FileOutputStream(file, false).use { out ->
                    out.write(bytes)
                    out.flush()
                    runCatching { out.fd.sync() }
                }
                require(file.length() == bytes.size.toLong()) {
                    "O comprovante foi recebido com ${bytes.size} bytes, mas o cache gravou ${file.length()} bytes."
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = row.contentType.ifBlank { "application/octet-stream" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Compartilhar comprovante"))
            }.onFailure { error = attachmentFailureMessage(it, "Não foi possível compartilhar o comprovante.") }
            loading = false
        }
    }

    fun openAttachment(row: TransactionAttachmentDto) {
        scope.launch {
            loading = true; error = null
            runCatching {
                val bytes = onDownload(tx.id, row.id)
                require(bytes.isNotEmpty()) { "O comprovante recebido está vazio." }
                val dir = File(context.cacheDir, "transaction_attachments").apply { mkdirs() }
                val safeName = row.originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, "${row.id}_$safeName")
                FileOutputStream(file, false).use { out ->
                    out.write(bytes)
                    out.flush()
                    runCatching { out.fd.sync() }
                }
                require(file.length() == bytes.size.toLong()) {
                    "O comprovante foi recebido com ${bytes.size} bytes, mas o cache gravou ${file.length()} bytes."
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, row.contentType.ifBlank { "application/octet-stream" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: ActivityNotFoundException) {
                    throw IllegalStateException("Nenhum aplicativo instalado consegue visualizar este tipo de comprovante. Use Baixar para salvar o arquivo.")
                }
            }.onFailure { error = attachmentFailureMessage(it, "Não foi possível visualizar o comprovante.") }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comprovantes • ${tx.description}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loading && rows.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!loading && rows.isEmpty()) Text("Nenhum comprovante anexado.")
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(rows, key = { it.id }) { row ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(if (row.contentType == "application/pdf") Icons.Default.PictureAsPdf else Icons.Default.Image, null)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(row.originalName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (row.sizeBytes < 1048576) "${row.sizeBytes / 1024} KB" else "%.1f MB".format(row.sizeBytes / 1048576.0),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    IconButton(enabled = !loading, onClick = { openAttachment(row) }) { Icon(Icons.Default.Visibility, "Visualizar") }
                                    IconButton(enabled = !loading, onClick = { saveAttachment(row) }) { Icon(Icons.Default.Download, "Baixar") }
                                    IconButton(enabled = !loading, onClick = { shareAttachment(row) }) { Icon(Icons.Default.Share, "Compartilhar") }
                                    IconButton(enabled = !loading, onClick = {
                                        scope.launch {
                                            loading = true
                                            runCatching { onDelete(tx.id, row.id) }
                                                .onSuccess { refresh() }
                                                .onFailure { error = attachmentFailureMessage(it, "Não foi possível excluir."); loading = false }
                                        }
                                    }) { Icon(Icons.Default.DeleteOutline, "Excluir") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box {
                Button(
                    onClick = { addMenuExpanded = true },
                    enabled = !loading
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar")
                }
                DropdownMenu(
                    expanded = addMenuExpanded,
                    onDismissRequest = { addMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Digitalizar documento") },
                        leadingIcon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                        onClick = { startDocumentScan() }
                    )
                    DropdownMenuItem(
                        text = { Text("Escolher imagem ou PDF") },
                        leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                        onClick = {
                            addMenuExpanded = false
                            picker.launch(arrayOf("image/*", "application/pdf"))
                        }
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}
