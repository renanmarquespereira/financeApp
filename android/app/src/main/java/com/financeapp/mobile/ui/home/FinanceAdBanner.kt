package com.financeapp.mobile.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.financeapp.mobile.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

private const val TAG = "FinanceAdMob"
private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
private const val RELEASE_BANNER_ID = "ca-app-pub-6368197822240427/8170146312"

private tailrec fun Context.findFinanceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findFinanceActivity()
    else -> null
}

/** Banner na Home. Diagnostico visual somente em builds Debug; nunca ignora UMP. */
@Composable
internal fun FinanceAdBanner() {
    val context = LocalContext.current
    val activity = remember(context) { context.findFinanceActivity() }
    var canShow by remember(activity) { mutableStateOf(false) }
    var status by remember(activity) { mutableStateOf("Aguardando consentimento UMP") }

    LaunchedEffect(activity) {
        val host = activity
        if (host == null) {
            Log.e(TAG, "Nao foi possivel localizar Activity no Context")
            status = "Sem Activity para consentimento"
            return@LaunchedEffect
        }
        val info = UserMessagingPlatform.getConsentInformation(host)
        Log.i(TAG, "Solicitando atualizacao do consentimento UMP; debug=${BuildConfig.DEBUG}")
        status = "Verificando consentimento UMP"
        info.requestConsentInfoUpdate(
            host,
            ConsentRequestParameters.Builder().build(),
            {
                Log.i(TAG, "UMP atualizada: status=${info.consentStatus} canRequestAds=${info.canRequestAds()}")
                if (info.canRequestAds()) {
                    canShow = true
                    status = "Consentimento OK; iniciando banner"
                }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(host) { error ->
                    if (error != null) {
                        Log.w(TAG, "Formulario UMP: code=${error.errorCode} message=${error.message}")
                    }
                    val allowed = info.canRequestAds()
                    Log.i(TAG, "Formulario UMP finalizado: canRequestAds=$allowed")
                    canShow = allowed
                    status = if (allowed) "Consentimento OK; iniciando banner" else "UMP nao permite anuncios"
                }
            },
            { error ->
                Log.e(TAG, "Falha ao atualizar UMP: code=${error.errorCode} message=${error.message}")
                val allowed = info.canRequestAds()
                canShow = allowed
                status = if (allowed) "Consentimento anterior valido" else "Erro UMP ${error.errorCode}: ${error.message.take(90)}"
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // So existe no APK Debug; no Release nenhuma mensagem interna aparece.
        if (BuildConfig.DEBUG) {
            Text(
                text = "AdMob teste: $status",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canShow) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    factory = { ctx ->
                        AdView(ctx).apply {
                            val unit = if (BuildConfig.DEBUG) TEST_BANNER_ID else RELEASE_BANNER_ID
                            adUnitId = unit
                            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    Log.i(TAG, "Banner carregado com sucesso; debug=${BuildConfig.DEBUG}")
                                    status = "Banner carregado"
                                }
                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    Log.e(TAG, "Banner falhou: code=${error.code} domain=${error.domain} message=${error.message} response=${error.responseInfo}")
                                    status = "Falha banner ${error.code}: ${error.message.take(100)}"
                                }
                            }
                            Log.i(TAG, "Inicializando SDK e solicitando banner; unidade=$unit largura=${widthDp}dp")
                            status = "Solicitando anuncio de teste"
                            MobileAds.initialize(ctx) { initStatus ->
                                Log.i(TAG, "Inicializacao SDK concluida: $initStatus")
                            }
                            loadAd(AdRequest.Builder().build())
                        }
                    },
                    onRelease = { view ->
                        Log.i(TAG, "Destruindo AdView")
                        view.destroy()
                    }
                )
            }
        }
    }
}
