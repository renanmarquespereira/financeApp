package com.financeapp.mobile.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.financeapp.mobile.data.repository.FinanceRepository
import com.financeapp.mobile.data.repository.AuthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import com.financeapp.mobile.util.SessionManager
import com.financeapp.mobile.util.WorkspaceScope

@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: FinanceRepository,
    private val authRepository: AuthRepository,
    private val session: SessionManager,
    private val syncTracker: OfflineSyncTracker
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val userId = inputData.getInt("userId", 0)
        val workspaceId = inputData.getString("workspaceId") ?: return Result.success()
        if (userId <= 0 || session.localUserId() != userId) return Result.success()
        syncTracker.setSyncing(WorkspaceScope(userId, workspaceId), true)

        return try {
            /*
             * Renova a sessão antes de enviar a fila. Isso é
             * necessário quando o usuário entrou offline e o
             * access token anterior já expirou.
             */
            authRepository.refreshSessionForSync()

            /*
             * Ao retornar a internet, algumas APIs podem levar
             * alguns segundos para voltar a responder.
             * Fazemos até 3 passagens curtas pela fila.
             */
            return repository.withWorkspace(WorkspaceScope(userId, workspaceId)) {
            repeat(3) { attempt ->
                try {
                    repository.syncPendingNow()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Forecast/IA can be pending even with an empty Room operation queue.
                    return@withWorkspace Result.retry()
                }

                val pending =
                    repository.pendingSyncCountNow()

                if (pending == 0) {
                    return@withWorkspace Result.success()
                }

                if (attempt < 2) {
                    delay(
                        if (attempt == 0) {
                            1_500L
                        } else {
                            3_000L
                        }
                    )
                }
            }

            /*
             * Se sobrou uma operação, fazemos a mesma reconciliação
             * que antes só acontecia no login completo.
             *
             * Isso é importante especialmente quando o usuário entra
             * por biometria ou quando um item depende de IDs/estado
             * atualizados pelo servidor.
             */
            if (
                repository.pendingSyncCountNow() > 0
            ) {
                try {
                    repository.reconcilePendingAfterSync()
                    delay(750L)
                    repository.syncPendingNow()
                } catch (_: Exception) {
                    // Se a reconciliação falhar, o retry normal
                    // do WorkManager continua preservando a fila.
                }
            }

            if (
                repository.pendingSyncCountNow() > 0
            ) {
                Result.retry()
            } else {
                Result.success()
            }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        } finally {
            syncTracker.setSyncing(WorkspaceScope(userId, workspaceId), false)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME =
            "offline-data-sync"
    }
}
