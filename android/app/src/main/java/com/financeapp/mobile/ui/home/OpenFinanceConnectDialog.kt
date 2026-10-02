package com.financeapp.mobile.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun OpenFinanceConnectDialog(
    url: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSuccess:
        (String, String?) -> Unit
) {
    val context = LocalContext.current

    var loading by remember(url) {
        mutableStateOf(true)
    }

    var loadError by remember(url) {
        mutableStateOf<String?>(null)
    }

    var providerFailure by remember(url) {
        mutableStateOf(false)
    }

    val friendlyProviderError =
        "Não foi possível conectar ao banco no momento. " +
            "O serviço da instituição pode estar temporariamente " +
            "indisponível. Tente novamente mais tarde."

    fun openExternal(
        target: Uri
    ) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    target
                )
            )
        }.onFailure {
            loadError =
                "Não foi possível abrir o navegador."
        }
    }

    fun handleUri(
        uri: Uri
    ): Boolean {
        if (
            uri.scheme ==
            "financeapp"
        ) {
            when (uri.host) {
                "success" -> {
                    val link =
                        uri.getQueryParameter(
                            "link"
                        )
                            ?: uri.getQueryParameter(
                                "link_id"
                            )

                    val institution =
                        uri.getQueryParameter(
                            "institution"
                        )
                            ?: uri.getQueryParameter(
                                "institution_name"
                            )

                    if (
                        !link.isNullOrBlank()
                    ) {
                        onSuccess(
                            link,
                            institution
                        )
                    } else {
                        loadError =
                            "O banco autorizou o acesso, mas não retornou o identificador da conexão."
                    }
                }

                "exit" -> {
                    val reason =
                        uri.getQueryParameter(
                            "reason"
                        )
                            ?: uri.getQueryParameter(
                                "message"
                            )

                    if (
                        !reason.isNullOrBlank() &&
                        (
                            reason.contains(
                                "error",
                                ignoreCase = true
                            ) ||
                            reason.contains(
                                "fail",
                                ignoreCase = true
                            ) ||
                            reason.contains(
                                "indispon",
                                ignoreCase = true
                            )
                        )
                    ) {
                        providerFailure = true
                        loading = false
                        loadError =
                            friendlyProviderError
                    } else {
                        onDismiss()
                    }
                }

                "error" -> {
                    providerFailure = true
                    loading = false
                    loadError =
                        friendlyProviderError
                }
            }

            return true
        }

        if (
            uri.scheme != "http" &&
            uri.scheme != "https"
        ) {
            openExternal(uri)
            return true
        }

        return false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Conectar banco")
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = 440.dp,
                                max = 660.dp
                            )
                ) {
                    AndroidView(
                        modifier =
                            Modifier.fillMaxSize(),
                        factory = {
                                webContext ->
                            WebView(webContext).apply {
                                settings.javaScriptEnabled =
                                    true
                                settings.domStorageEnabled =
                                    true
                                settings.databaseEnabled =
                                    true
                                settings.javaScriptCanOpenWindowsAutomatically =
                                    true
                                settings.setSupportMultipleWindows(
                                    true
                                )
                                settings.loadsImagesAutomatically =
                                    true

                            }
                        },
                        update = {
                                webView ->
                            // Cookie config requires the real WebView instance.
                            CookieManager
                                .getInstance()
                                .apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(
                                        webView,
                                        true
                                    )
                                }

                            webView.webChromeClient =
                                object :
                                    WebChromeClient() {
                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean {
                                        val hitUrl =
                                            view?.hitTestResult
                                                ?.extra

                                        if (
                                            !hitUrl.isNullOrBlank()
                                        ) {
                                            openExternal(
                                                Uri.parse(
                                                    hitUrl
                                                )
                                            )
                                            return false
                                        }

                                        return false
                                    }
                                }

                            webView.webViewClient =
                                object :
                                    WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        pageUrl: String?,
                                        favicon: Bitmap?
                                    ) {
                                        loading = true
                                        loadError = null
                                        providerFailure = false
                                    }

                                    override fun onPageFinished(
                                        view: WebView?,
                                        pageUrl: String?
                                    ) {
                                        loading = false
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request:
                                            WebResourceRequest?
                                    ): Boolean {
                                        val uri =
                                            request?.url
                                                ?: return false

                                        return handleUri(
                                            uri
                                        )
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request:
                                            WebResourceRequest?,
                                        error:
                                            WebResourceError?
                                    ) {
                                        if (
                                            request?.isForMainFrame ==
                                            true
                                        ) {
                                            loading = false
                                            providerFailure = true
                                            loadError =
                                                friendlyProviderError +
                                                    " (" +
                                                    (
                                                        error
                                                            ?.description
                                                            ?.toString()
                                                            ?: "erro de rede"
                                                    ) +
                                                    ")"
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request:
                                            WebResourceRequest?,
                                        errorResponse:
                                            android.webkit.WebResourceResponse?
                                    ) {
                                        if (
                                            request?.isForMainFrame ==
                                            true
                                        ) {
                                            loading = false
                                            providerFailure = true
                                            loadError =
                                                friendlyProviderError +
                                                    " (HTTP " +
                                                    (
                                                        errorResponse
                                                            ?.statusCode
                                                            ?.toString()
                                                            ?: "desconhecido"
                                                    ) +
                                                    ")"
                                        }
                                    }
                                }

                            if (
                                webView.url != url
                            ) {
                                webView.loadUrl(url)
                            }
                        }
                    )

                    if (loading) {
                        Column(
                            modifier =
                                Modifier.align(
                                    Alignment.Center
                                ),
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    10.dp
                                )
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Conectando ao banco...",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }
                    }
                }

                loadError?.let {
                        message ->
                    Card(
                        modifier =
                            Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .errorContainer
                            )
                    ) {
                        Column(
                            modifier =
                                Modifier.padding(
                                    12.dp
                                ),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    8.dp
                                )
                        ) {
                            Text(
                                if (providerFailure) {
                                    "Conexão não concluída"
                                } else {
                                    "Não foi possível carregar"
                                },
                                style =
                                    MaterialTheme
                                        .typography
                                        .titleSmall
                            )

                            Text(
                                message,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onErrorContainer,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }
                    }

                    Button(
                        onClick = onRetry,
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text("Tentar novamente")
                    }

                    OutlinedButton(
                        onClick = {
                            openExternal(
                                Uri.parse(url)
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Abrir no navegador"
                        )
                    }
                }

                if (
                    loadError == null &&
                    !loading
                ) {
                    TextButton(
                        onClick = {
                            openExternal(
                                Uri.parse(url)
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Problemas para visualizar? Abrir no navegador"
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Fechar")
            }
        }
    )
}
