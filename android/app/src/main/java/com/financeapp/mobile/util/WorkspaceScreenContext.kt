package com.financeapp.mobile.util

import kotlinx.coroutines.asContextElement

// Carries only the screen's owner/workspace; credentials are captured anew per operation.
object WorkspaceScreenContext {
    private val local = ThreadLocal<WorkspaceScope?>()
    fun current(): WorkspaceScope? = local.get()
    fun element(scope: WorkspaceScope) = local.asContextElement(scope)
}
