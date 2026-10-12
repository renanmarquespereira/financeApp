package com.financeapp.mobile.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

// O identificador de demonstracao do Google garante que os testes nao gerem trafego real.
private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
private const val RELEASE_BANNER_ID = "ca-app-pub-6368197822240427/8170146312"

private tailrec fun Context.findFinanceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findFinanceActivity()
    else -> null
}

@Composable
internal fun FinanceAdBanner() {
    val context = LocalContext.current
    val activity = remember(context) { context.findFinanceActivity() }
    var canShow by remember(activity) { mutableStateOf(false) }

    LaunchedEffect(activity) {
        val host = activity ?: return@LaunchedEffect
        val info = UserMessagingPlatform.getConsentInformation(host)
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            host,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(host) {
                    // Se a coleta de consentimento falhar, so carrega se o SDK permitir.
                    canShow = info.canRequestAds()
                }
                // A UMP pode ja ter consentimento valido de uma sessao anterior.
                if (info.canRequestAds()) canShow = true
            },
            { canShow = info.canRequestAds() }
        )
    }

    if (!canShow) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        // Banner adaptavel, contido em uma altura reservada acima da navegacao.
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            factory = { ctx ->
                AdView(ctx).apply {
                    adUnitId = if (BuildConfig.DEBUG) TEST_BANNER_ID else RELEASE_BANNER_ID
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                    MobileAds.initialize(ctx)
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { view -> view.destroy() }
        )
    }
}
