package com.financeapp.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.mobile.data.repository.FinanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

data class WorkspaceUiState(val busy: Boolean = false, val error: String? = null, val refreshing: Boolean = false)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(private val repository: FinanceRepository) : ViewModel() {
    val active = repository.activeWorkspace
    val workspaces = repository.workspaces.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state = mutableState.asStateFlow()

    fun guestTransferPending() = repository.guestTransferPending()
    fun guestTransferFrozen() = repository.guestTransferFrozen()
    fun transferGuest(done: () -> Unit) = action(done) { repository.transferGuestData() }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    private fun action(done: () -> Unit = {}, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                block()
                mutableState.value = mutableState.value.copy(busy = false, error = null)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = if (e is HttpException) runCatching {
                    JSONObject(e.response()?.errorBody()?.string().orEmpty()).optString("detail")
                }.getOrNull() else null
                mutableState.value = mutableState.value.copy(busy = false, error = when {
                    e is IOException -> "Não foi possível acessar os dados locais agora."
                    !detail.isNullOrBlank() -> detail
                    else -> e.message ?: "Não foi possível concluir. Tente novamente."
                })
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                repository.refreshWorkspaces()
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        refreshing = false,
                        error = null
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        refreshing = false,
                        error = e.message ?: "Não foi possível carregar os workspaces."
                    )
            }
        }
    }

        fun select(id: String, done: () -> Unit) = action(done) { repository.selectWorkspace(id) }
    fun create(name: String, kind: String, clientId: String, done: () -> Unit) = action(done) {
        repository.createWorkspace(name, kind, clientId)
    }
    fun edit(id: String, name: String, kind: String, done: () -> Unit) = action(done) {
        repository.editWorkspace(id, name, kind)
    }
    fun archive(id: String, done: () -> Unit) = action(done) { repository.archiveWorkspace(id) }
    fun requestDeleteBatch(ids: List<String>, done: () -> Unit) = action(done) { repository.requestWorkspaceDeleteBatch(ids) }
    fun confirmDeleteBatch(ids: List<String>, code: String, done: () -> Unit) = action(done) { repository.confirmWorkspaceDeleteBatch(ids, code) }
    fun requestDelete(id: String, done: () -> Unit) = action(done) { repository.requestWorkspaceDelete(id) }
    fun confirmDelete(id: String, code: String, done: () -> Unit) = action(done) { repository.confirmWorkspaceDelete(id, code) }
    fun restore(id: String) = action { repository.restoreWorkspace(id) }
}
