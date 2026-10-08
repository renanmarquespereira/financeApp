package com.financeapp.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import com.financeapp.mobile.data.repository.FinanceRepository
import com.financeapp.mobile.notifications.FinanceNotificationScheduler
import javax.inject.Inject

@HiltAndroidApp
class FinanceApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: FinanceRepository


    override fun onCreate() {
        super.onCreate()

        FinanceNotificationScheduler.schedule(this, runNow = true)

        val connectivityManager =
            getSystemService(
                ConnectivityManager::class.java
            )

        val request =
            NetworkRequest.Builder()
                .addCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                .addCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                .build()

        connectivityManager.registerNetworkCallback(
            request,
            object :
                ConnectivityManager.NetworkCallback() {
                override fun onAvailable(
                    network: Network
                ) {
                    /*
                     * Se havia um Worker em retry/backoff, REPLACE faz
                     * a fila tentar imediatamente quando a internet volta.
                     */
                    repository.scheduleOfflineSync(
                        force = true
                    )
                }
            }
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
