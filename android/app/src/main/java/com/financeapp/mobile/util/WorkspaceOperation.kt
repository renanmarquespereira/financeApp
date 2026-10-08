package com.financeapp.mobile.util

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

data class WorkspaceScope(val userId: Int, val workspaceId: String) {
    init { require(userId >= 0 && workspaceId.isNotBlank()) }
}

data class WorkspaceOperation(val scope: WorkspaceScope, val accessToken: String?) {
    val userId get() = scope.userId
    val workspaceId get() = scope.workspaceId
    val authorization get() = "Bearer ${accessToken ?: error("Sessão online indisponível")}" 

    companion object {
        private val local = ThreadLocal<WorkspaceOperation?>()
        fun current(): WorkspaceOperation = local.get()
            ?: error("Operação financeira sem workspace")
        fun currentOrNull(): WorkspaceOperation? = local.get()
        suspend fun <T> run(operation: WorkspaceOperation, block: suspend () -> T): T =
            withContext(local.asContextElement(operation)) { block() }
    }
}
