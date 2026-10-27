package id.basokanggito.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class NotebookViewModel(val repository: NotebookRepository) : ViewModel() {
    val state = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookState())
    private val draftLock = Mutex()
    suspend fun <T> withDrafts(block: suspend () -> T): T = draftLock.withLock { block() }
    val saving = MutableStateFlow(false)
    val saved = MutableStateFlow<BookRecord?>(null)
    val savedCustomer = MutableStateFlow<Customer?>(null)
    val error = MutableStateFlow("")
    fun draft(payload: String) { viewModelScope.launch { draftLock.withLock { repository.saveDraft(payload) } } }
    fun customerDraft(payload: String) { viewModelScope.launch { draftLock.withLock { repository.saveCustomerDraft(payload) } } }
    fun submitCustomer(id: String, version: Int, data: JSONObject) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try { savedCustomer.value = draftLock.withLock { repository.submitCustomer(id, version, data) }; error.value = "" }
            catch (failure: Exception) { error.value = failure.message?.takeIf { it in listOf("version_conflict", "reason_required", "review_required") } ?: "invalid_operation" }
            finally { saving.value = false }
        }
    }
    fun reopenCustomer(id: String, complete: (Customer) -> Unit) { viewModelScope.launch {
        runCatching { draftLock.withLock { repository.reopenCustomer(id) } }.onSuccess(complete).onFailure { error.value = "review_required" }
    } }
    fun reopenPayment(id: String, complete: (JSONObject) -> Unit) { viewModelScope.launch {
        runCatching { draftLock.withLock { repository.reopenPayment(id) } }.onSuccess(complete).onFailure { error.value = "linked_receipts" }
    } }
    fun discardPaymentDraft(complete: () -> Unit) { viewModelScope.launch { draftLock.withLock { repository.discardPaymentDraft() }; complete() } }
    fun submit(action: String, id: String, version: Int, data: JSONObject) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try { saved.value = draftLock.withLock { repository.submit(action, id, version, data) }; error.value = "" }
            catch (failure: Exception) { error.value = failure.message?.takeIf { it in listOf("version_conflict", "reason_required", "below_received", "linked_receipts", "overpayment", "daily_aggregate_exists", "review_required", "customer_required", "customer_unavailable") } ?: "invalid_operation" }
            finally { saving.value = false }
        }
    }
    fun review(id: String, complete: (Boolean) -> Unit) { viewModelScope.launch { complete(runCatching { repository.useServerVersion(id) }.isSuccess) } }
    fun retry() { viewModelScope.launch { repository.retryFailed() } }
    fun reopen(id: String, complete: (RecoveredEdit) -> Unit) { viewModelScope.launch {
        runCatching { draftLock.withLock { repository.reopenFailed(id) } }.onSuccess(complete).onFailure { error.value = if (it.message == "linked_receipts") "linked_receipts" else "review_required" }
    } }
}
