package com.financeapp.mobile.sync

import com.financeapp.mobile.util.WorkspaceScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineSyncTracker @Inject constructor() {
    private val counts = MutableStateFlow<Map<WorkspaceScope, Int>>(emptyMap())
    fun observe(scope: WorkspaceScope) = counts.map { (it[scope] ?: 0) > 0 }
    @Synchronized
    fun setSyncing(scope: WorkspaceScope, value: Boolean) {
        val count = ((counts.value[scope] ?: 0) + if (value) 1 else -1).coerceAtLeast(0)
        counts.value = if (count == 0) counts.value - scope else counts.value + (scope to count)
    }
}
