package id.basokanggito.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import kotlinx.coroutines.CancellationException

private class NotebookAccessDenied(code: String) : IOException(code)

data class NotebookState(val records: List<BookRecord> = emptyList(), val pending: List<PendingOperation> = emptyList(), val meta: Map<String, String> = emptyMap(), val loaded: Boolean = false, val unreadable: Boolean = false, val serverRecords: Map<String, BookRecord> = emptyMap(), val customers: List<Customer> = emptyList(), val serverCustomers: Map<String, Customer> = emptyMap(), val catalog: List<CatalogItem> = emptyList())

data class RecoveredEdit(val local: BookRecord, val server: BookRecord?, val reason: String) {
    fun json() = JSONObject().put("local", local.json()).put("server", server?.json()).put("reason", reason)
    companion object {
        fun parse(value: String): RecoveredEdit = JSONObject(value).let {
            RecoveredEdit(BookRecord.parse(it.getJSONObject("local")), it.optJSONObject("server")?.let(BookRecord::parse), it.optString("reason"))
        }
    }
}

class NotebookRepository(val database: NotebookDatabase, private val requestSync: () -> Unit = {}) {
    val dao = database.notebook()
    private val syncLock = Mutex()
    private val compatibilityLock = Mutex()
    private var coveredSyncRequests = 0L
    private val running = MutableStateFlow(false)
    val syncing = running.asStateFlow()
    internal val writeLock = Mutex()
    internal suspend fun <T> restoring(block: suspend () -> T): T = syncLock.withLock { writeLock.withLock { block() } }
    val state = combine(dao.observeRecords(), dao.observeQueue(), dao.observeMeta(), dao.observeCustomers(), dao.observeCatalog()) { _, _, _, _, _ -> database.withTransaction {
        val rows = dao.records(); val queue = dao.activeOperations(); val meta = dao.metadata()
        try { NotebookState(rows.map { BookRecord.parse(JSONObject(it.payload)) }, queue, meta.associate { it.key to it.value }, loaded = true,
            serverRecords = rows.mapNotNull { row -> row.serverPayload?.let { row.id to BookRecord.parse(JSONObject(it)) } }.toMap(),
            customers = dao.customers().map { Customer.parse(JSONObject(it.payload)) },
            serverCustomers = dao.customers().mapNotNull { row -> row.serverPayload?.let { row.id to Customer.parse(JSONObject(it)) } }.toMap(),
            catalog = dao.catalog().map { CatalogItem.parse(JSONObject(it.payload)) }) }
        catch (_: Exception) { NotebookState(pending = queue, loaded = true, unreadable = true) }
    } }
    suspend fun submitCatalog(id: String, version: Int, data: JSONObject): CatalogItem = withContext(Dispatchers.IO) {
        val result = writeLock.withLock { database.withTransaction {
            require(dao.record(id) == null && dao.customer(id) == null)
            require(dao.activeFor(id).none { it.state == "failed" }) { "review_required" }
            val stored = dao.catalogItem(id)
            val op = JSONObject().put("id", newId()).put("recordId", id).put("expectedVersion", version).put("action", "save_shopping_item").put("data", JSONObject(data.toString()))
            val after = applyCatalogLocally(op, stored?.let { CatalogItem.parse(JSONObject(it.payload)) })
            require(dao.catalog().map { CatalogItem.parse(JSONObject(it.payload)) }.none { it.id != id && after.name in it.aliases }) { "catalog_name_exists" }
            dao.putCatalogItem(StoredCatalogItem(id, after.json().toString(), stored?.serverPayload))
            dao.queue(PendingOperation(operationId = op.getString("id"), recordId = id, payload = op.toString(), beforePayload = stored?.payload))
            after
        } }
        requestSync(); result
    }
    suspend fun saveCustomerDraft(value: String) = writeLock.withLock { dao.putMeta(NotebookMeta("customerDraft", value)) }
    suspend fun submitCustomer(id: String, version: Int, data: JSONObject): Customer = withContext(Dispatchers.IO) {
        val result = writeLock.withLock { database.withTransaction {
            require(dao.record(id) == null && dao.catalogItem(id) == null)
            require(dao.activeFor(id).none { it.state == "failed" }) { "review_required" }
            val stored = dao.customer(id)
            val op = JSONObject().put("id", newId()).put("recordId", id).put("expectedVersion", version).put("action", "save_customer").put("data", JSONObject(data.toString()))
            val after = applyCustomerLocally(op, stored?.let { Customer.parse(JSONObject(it.payload)) })
            dao.putCustomer(StoredCustomer(id, after.json().toString(), stored?.serverPayload))
            dao.queue(PendingOperation(operationId = op.getString("id"), recordId = id, payload = op.toString(), beforePayload = stored?.payload))
            dao.putMeta(NotebookMeta("customerDraft", ""))
            after
        }
        }
        requestSync(); result
    }
    suspend fun saveDraft(value: String) = writeLock.withLock { database.withTransaction {
        dao.putMeta(NotebookMeta("draft", value))
        val recovery = dao.meta("recoveredEdit")?.takeIf { it.isNotEmpty() }
        // Only a durable replacement for this edit (or explicit discard) retires recovery.
        if (value.isEmpty() || (recovery != null && JSONObject(value).optString("id") == RecoveredEdit.parse(recovery).local.id)) {
            dao.putMeta(NotebookMeta("recoveredEdit", ""))
        }
        val payment = dao.meta("paymentRecovery")?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        if (payment != null && value.isNotEmpty()) {
            val draft = JSONObject(value)
            if (draft.optString("route") == "receipt" && draft.optString("id") == payment.getString("recordId") &&
                draft.optString("receiptId") == payment.getJSONObject("data").getString("id")) {
                payment.getJSONObject("data").put("amount", draft.getString("receiptAmount")).put("date", draft.getString("receiptDate")).put("method", draft.getString("receiptMethod"))
                payment.put("expectedVersion", draft.getInt("version"))
                dao.putMeta(NotebookMeta("paymentRecovery", payment.toString()))
            }
        }
    }

    }
    suspend fun submit(action: String, recordId: String, expectedVersion: Int, data: JSONObject): BookRecord = withContext(Dispatchers.IO) {
        val result = writeLock.withLock { database.withTransaction {
            require(dao.activeFor(recordId).none { it.state == "failed" }) { "review_required" }
            val stored = dao.record(recordId); val before = stored?.let { BookRecord.parse(JSONObject(it.payload)) }
            require(dao.customer(recordId) == null && dao.catalogItem(recordId) == null)
            val operationData = JSONObject(data.toString())
            if (action == "save_sale") {
                val linkedId = if (operationData.has("customerId")) operationData.optString("customerId").takeUnless { it.isEmpty() || it == "null" } else before?.customerId
                if (linkedId != null) {
                    val linked = dao.customer(validId(linkedId))?.let { Customer.parse(JSONObject(it.payload)) }
                    require(linked != null && (!linked.archived || before?.customerId == linkedId)) { "customer_unavailable" }
                    require(operationData.getString("channel") == "langsung") { "customer_unavailable" }
                    operationData.put("customer", if (before?.customerId == linkedId) before.customer else linked.name)
                }
            }
            val recovery = dao.meta("recoveredEdit")?.takeIf { it.isNotEmpty() }?.let(RecoveredEdit::parse)
            val recoveredDraft = dao.meta("draft")?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it).optJSONObject("recoveredEdit") }.getOrNull() }?.let { RecoveredEdit.parse(it.toString()) }
            val edit = (recovery ?: recoveredDraft)?.takeIf { it.local.id == recordId && it.server == null }
            if (edit != null && action == "save_sale" && edit.local.receipts.isNotEmpty()) {
                val receipt = edit.local.receipts.single()
                require(operationData.getBoolean("paid") && operationData.optString("paymentAmount", operationData.getString("amount")) == receipt.amount.toString() &&
                    operationData.getString("date") == receipt.date && operationData.getString("method") == receipt.method) { "linked_receipts" }
                operationData.put("receiptId", receipt.id)
            }
            val op = JSONObject().put("id", newId()).put("recordId", recordId).put("expectedVersion", expectedVersion).put("action", action).put("data", operationData)
            val after = applyLocally(op, before)
            if (action == "save_expense" && operationData.optInt("catalogVersion") == 1) after.items.forEach { item ->
                val master = item.masterId?.let { dao.catalogItem(it) }?.let { CatalogItem.parse(JSONObject(it.payload)) }
                require(master != null && (!master.archived || before?.items?.any { it.id == item.id && it.masterId == master.id } == true)) { "catalog_item_unavailable" }
            }
            if (action == "save_sale" && after.channel == "langsung" && after.customerId == null && after.received < after.amount) {
                require(!((operationData.has("customerId") && (before == null || before.received == before.amount)) || before?.customerId != null)) { "customer_required" }
            }
            if (after.kind == "sale" && after.channel != "langsung" && !after.voided) {
                val others = dao.records().filter { it.id != recordId }.map { BookRecord.parse(JSONObject(it.payload)) }
                require(others.none { !it.voided && it.kind == "sale" && it.date == after.date && it.channel == after.channel }) { "daily_aggregate_exists" }
            }
            dao.putRecord(StoredRecord(after.id, after.date, after.json().toString(), stored?.serverPayload))
            dao.queue(PendingOperation(operationId = op.getString("id"), recordId = recordId, payload = op.toString(), beforePayload = stored?.payload))
            dao.putMeta(NotebookMeta("draft", ""))
            dao.putMeta(NotebookMeta("recoveredEdit", ""))
            if (action == "add_receipt") dao.meta("paymentRecovery")?.takeIf { it.isNotEmpty() }?.let { raw ->
                val recovery = JSONObject(raw)
                if (recovery.getString("recordId") == recordId && recovery.getJSONObject("data").getString("id") == operationData.getString("id")) {
                    val remaining = recovery.optJSONArray("remaining").objects()
                    val next = remaining.firstOrNull()?.apply {
                        put("expectedVersion", after.version); put("remaining", JSONArray(remaining.drop(1)))
                    }
                    dao.putMeta(NotebookMeta("paymentRecovery", next?.toString().orEmpty()))
                }
            }
            after
        }
        }
        requestSync()
        result
    }

    private suspend fun reconcile(record: BookRecord) {
        val current = dao.record(record.id)
        val previousServer = current?.serverPayload?.let { BookRecord.parse(JSONObject(it)) }
        if (previousServer != null && previousServer.version > record.version) return
        val server = record.withKnownChronology(previousServer)
            .withKnownChronology(current?.let { BookRecord.parse(JSONObject(it.payload)) }).json().toString()
        dao.putRecord(if (dao.activeFor(record.id).isNotEmpty() && current != null) current.copy(serverPayload = server)
            else StoredRecord(record.id, record.date, server, server))
    }

    private suspend fun reconcileSnapshot(json: JSONObject) {
        if (json.getString("kind") == "shopping_item") {
            val remote = CatalogItem.parse(json); val current = dao.catalogItem(remote.id)
            val previous = current?.serverPayload?.let { CatalogItem.parse(JSONObject(it)) }
            if (previous != null && previous.version > remote.version) return
            val server = remote.json().toString()
            dao.putCatalogItem(if (current != null && dao.activeFor(remote.id).isNotEmpty()) current.copy(serverPayload = server)
                else StoredCatalogItem(remote.id, server, server))
            return
        }
        if (json.getString("kind") != "customer") { reconcile(BookRecord.parse(json)); return }
        val remote = Customer.parse(json)
        val current = dao.customer(remote.id)
        val previous = current?.serverPayload?.let { Customer.parse(JSONObject(it)) }
        if (previous != null && previous.version > remote.version) return
        val server = remote.json().toString()
        dao.putCustomer(if (dao.activeFor(remote.id).isNotEmpty() && current != null) current.copy(serverPayload = server)
            else StoredCustomer(remote.id, server, server))
    }

    private suspend fun nextUpload(): PendingOperation? {
        val active = dao.activeOperations()
        return active.firstOrNull { candidate ->
            candidate.state == "pending" && active.none { it.recordId == candidate.recordId && (it.state == "failed" || it.sequence < candidate.sequence) } &&
                JSONObject(candidate.payload).getJSONObject("data").optString("customerId").let { linked ->
                    linked.isEmpty() || linked == "null" ||
                        (dao.customer(linked)?.serverPayload != null && active.none { it.recordId == linked })
                } && JSONObject(candidate.payload).let { op ->
                    if (op.getString("action") != "save_expense") true else op.getJSONObject("data").optJSONArray("items").objects().all { item ->
                        val linked = item.optString("masterId")
                        linked.isEmpty() || (dao.catalogItem(linked)?.serverPayload != null && dao.activeFor(linked).isEmpty())
                    }
                }
        }
    }

    suspend fun saveCompatibilityPolicy(json: JSONObject) {
        dao.putMeta(NotebookMeta("appVersionPolicy", AppVersionPolicy.parse(json).json().toString()))
    }
    suspend fun updateRequired(): Boolean = dao.meta("appVersionPolicy")?.let { runCatching { AppVersionPolicy.parse(JSONObject(it)).required() }.getOrDefault(false) } ?: false
    suspend fun checkCompatibility(client: NotebookHttpClient): Boolean = compatibilityLock.withLock { withContext(Dispatchers.IO) {
        try {
            val response = client.get("/v1/app-version")
            if (response.status != 200) throw IOException("compatibility_unavailable")
            val policy = AppVersionPolicy.parse(response.body)
            saveCompatibilityPolicy(response.body)
            dao.putMeta(NotebookMeta("compatibilityError", ""))
            if (policy.required()) dao.putMeta(NotebookMeta("syncError", "app_update_required"))
            else if (dao.meta("syncError") == "app_update_required") dao.putMeta(NotebookMeta("syncError", ""))
            !policy.required()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { dao.putMeta(NotebookMeta("compatibilityError", "unknown")); false }
    } }
    suspend fun synchronize(client: NotebookHttpClient, requestedAt: String? = null, forceCompatibility: Boolean = false): Boolean = syncLock.withLock {
        withContext(Dispatchers.IO) {
            // Queued triggers covered by a later successful round need no second network round.
            if (!forceCompatibility && requestedAt != null && dao.meta("syncError").isNullOrEmpty() && SyncRequests.covered(requestedAt, coveredSyncRequests) &&
                dao.activeOperations().none { it.state == "pending" }) return@withContext true
            val startedRequests = SyncRequests.current()
            running.value = true
            try {
                dao.putMeta(NotebookMeta("syncError", ""))
                if (!checkCompatibility(client)) {
                    if (updateRequired()) throw AppUpdateRequired(JSONObject(dao.meta("appVersionPolicy")!!))
                    throw IOException("compatibility_unavailable")
                }
                while (true) {
                    val next = nextUpload() ?: break
                    val response = client.upload(JSONObject(next.payload))
                    if (response.status == 401 || response.status == 403) throw NotebookAccessDenied(response.body.optString("error", "unauthorized"))
                    if (response.status in 200..299) database.withTransaction {
                        val remote = response.body.getJSONObject("record")
                        require(remote.getString("id") == next.recordId)
                        dao.updateOperation(next.copy(state = "acknowledged"))
                        reconcileSnapshot(remote)
                        // Upload cursor must never skip downloads made by other devices.
                    } else if (response.status == 429 || response.status >= 500) throw IOException("retryable_server_error")
                    else database.withTransaction {
                        response.body.optJSONObject("current")?.let { remoteJson ->
                            if (remoteJson.getString("id") == next.recordId) reconcileSnapshot(remoteJson)
                            else dao.putMeta(NotebookMeta("conflict:${next.recordId}", remoteJson.toString()))
                        }
                        dao.updateOperation(next.copy(state = "failed", error = response.body.optString("error", "invalid_response")))
                    }
                }
                // One bounded replay upgrades legacy cached snapshots with creation metadata.
                // Keep the durable financial cursor monotonic even while replaying earlier pages.
                // A revision avoids downloading the unchanged catalog on each foreground refresh.
                var catalogAfter = "00000000-0000-0000-0000-000000000000"
                val knownRevision = dao.meta("catalogRevision").orEmpty()
                var catalogRevision: String? = null
                var stableCatalog = true
                do {
                    val response = client.get("/v1/shopping-items?after=$catalogAfter&limit=100" + if (catalogAfter == "00000000-0000-0000-0000-000000000000" && knownRevision.isNotEmpty()) "&revision=$knownRevision" else "")
                    if (response.status == 401 || response.status == 403) throw NotebookAccessDenied(response.body.optString("error", "unauthorized"))
                    if (response.status !in 200..299) throw IOException("catalog_download_failed")
                    val revision = response.body.getString("revision")
                    if (catalogRevision != null && catalogRevision != revision) stableCatalog = false
                    catalogRevision = revision
                    database.withTransaction { response.body.getJSONArray("items").objects().forEach { reconcileSnapshot(it) } }
                    catalogAfter = response.body.optString("next").takeUnless { it.isEmpty() || it == "null" }.orEmpty()
                } while (catalogAfter.isNotEmpty())
                if (stableCatalog) dao.putMeta(NotebookMeta("catalogRevision", catalogRevision!!))
                var pullCursor = if (dao.meta("chronologyVersion") == "1" && dao.meta("catalogVersion") == "1" && dao.meta("activityVersion") == "1") dao.meta("cursor") ?: "0" else "0"
                do {
                    val response = client.download(pullCursor)
                    if (response.status == 401 || response.status == 403) throw NotebookAccessDenied(response.body.optString("error", "unauthorized"))
                    if (response.status !in 200..299) throw IOException("download_failed")
                    database.withTransaction {
                        response.body.getJSONArray("changes").objects().forEach { reconcileSnapshot(it.getJSONObject("record")) }
                        val old = (dao.meta("cursor") ?: "0").toBigInteger()
                        val next = response.body.getString("cursor").toBigInteger()
                        require(next >= pullCursor.toBigInteger())
                        dao.putMeta(NotebookMeta("cursor", maxOf(old, next).toString()))
                        pullCursor = next.toString()
                    }
                } while (response.body.getBoolean("hasMore"))
                dao.putMeta(NotebookMeta("chronologyVersion", "1"))
                dao.putMeta(NotebookMeta("catalogVersion", "1"))
                dao.putMeta(NotebookMeta("activityVersion", "1"))
                dao.putMeta(NotebookMeta("lastSync", java.time.Instant.now().toString()))
                coveredSyncRequests = startedRequests
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (update: AppUpdateRequired) {
                saveCompatibilityPolicy(update.policy)
                dao.putMeta(NotebookMeta("syncError", "app_update_required"))
                false
            } catch (denied: NotebookAccessDenied) {
                dao.putMeta(NotebookMeta("syncError", denied.message ?: "unauthorized"))
                false
            } catch (_: Exception) {
                dao.putMeta(NotebookMeta("syncError", "connection_failed"))
                false
            } finally {
                running.value = false
            }
        }
    }

    // Explicit review archives local operations; their bodies and prior snapshots are retained.
    suspend fun useServerVersion(id: String) {
        writeLock.withLock { database.withTransaction {
        val master = dao.catalogItem(id)
        if (master != null) {
            val conflict = dao.meta("conflict:$id")?.takeIf { it.isNotEmpty() }?.let { CatalogItem.parse(JSONObject(it)) }
            val server = conflict ?: master.serverPayload?.let { CatalogItem.parse(JSONObject(it)) }
            require(server != null) { "server_version_unavailable" }
            if (server.id != id) {
                require(!server.archived) { "catalog_item_unavailable" }
                reconcileSnapshot(server.json())
                dao.records().forEach { stored ->
                    val local = BookRecord.parse(JSONObject(stored.payload))
                    if (local.items.none { it.masterId == id }) return@forEach
                    val active = dao.activeFor(local.id)
                    val accepted = stored.serverPayload?.let { BookRecord.parse(JSONObject(it)) }
                    require(local.kind == "expense" && !local.voided && active.isNotEmpty() && active.all { it.state == "pending" }) { "review_required" }
                    require(accepted == null || (accepted.kind == "expense" && !accepted.voided && accepted.items.none { it.masterId == id })) { "review_required" }
                    val reason = active.asReversed().firstNotNullOfOrNull { attempt ->
                        JSONObject(attempt.payload).getJSONObject("data").optString("reason").takeIf { it.isNotBlank() }
                    } ?: "Catalog conflict reviewed"
                    val data = JSONObject().put("date", local.date).put("note", local.note).put("reason", reason).put("catalogVersion", 1)
                        .put("items", JSONArray(local.items.map { if (it.masterId == id) it.copy(masterId = server.id).json() else it.json() }))
                    val operation = JSONObject().put("id", newId()).put("recordId", local.id).put("expectedVersion", accepted?.version ?: 0)
                        .put("action", "save_expense").put("data", data)
                    val replacement = applyLocally(operation, accepted).copy(createdAt = local.createdAt)
                    active.forEach { dao.updateOperation(it.copy(state = "reviewed")) }
                    dao.putRecord(stored.copy(payload = replacement.json().toString()))
                    dao.queue(PendingOperation(operationId = operation.getString("id"), recordId = local.id,
                        payload = operation.toString(), beforePayload = stored.serverPayload))
                }
                dao.removeCatalogItem(id)
            }
            dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
            if (server.id == id) reconcileSnapshot(server.json())
            dao.putMeta(NotebookMeta("conflict:$id", ""))
            return@withTransaction
        }
        val customer = dao.customer(id)
        if (customer != null) {
            require(customer.serverPayload != null) { "server_version_unavailable" }
            dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
            reconcileSnapshot(JSONObject(customer.serverPayload))
            return@withTransaction
        }
        val stored = dao.record(id) ?: return@withTransaction
        val other = dao.meta("conflict:$id")?.takeIf { it.isNotEmpty() }
        require(stored.serverPayload != null || other != null) { "server_version_unavailable" }
        dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
        val server = BookRecord.parse(JSONObject(other ?: stored.serverPayload!!))
        if (server.id != id) dao.removeRecord(id)
        reconcile(server)
        dao.putMeta(NotebookMeta("conflict:$id", ""))
        } }
        requestSync()
    }
    suspend fun reopenCustomer(id: String): Customer = writeLock.withLock { database.withTransaction {
        val stored = requireNotNull(dao.customer(id))
        require(dao.activeFor(id).any { it.state == "failed" }) { "review_required" }
        val local = Customer.parse(JSONObject(stored.payload))
        val server = stored.serverPayload?.let { Customer.parse(JSONObject(it)) }
        val edit = local.copy(version = server?.version ?: 0)
        dao.putMeta(NotebookMeta("customerDraft", edit.json().toString()))
        dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
        if (server == null) dao.removeCustomer(id) else reconcileSnapshot(server.json())
        edit
    }
    }
    suspend fun reopenPayment(id: String): JSONObject = writeLock.withLock { database.withTransaction {
        val existing = dao.meta("paymentRecovery")?.takeIf { it.isNotEmpty() }
        require(existing == null || JSONObject(existing).getString("recordId") == id) { "review_required" }
        val pending = dao.activeFor(id)
        require(pending.isNotEmpty() && pending.first().state == "failed" && pending.all { JSONObject(it.payload).getString("action") == "add_receipt" }) { "linked_receipts" }
        val stored = requireNotNull(dao.record(id))
        val server = requireNotNull(stored.serverPayload) { "server_version_unavailable" }
        val retained = existing?.let(::JSONObject)?.let { listOf(it) + it.optJSONArray("remaining").objects() }.orEmpty()
        val attempts = (pending.map { JSONObject(it.payload) } + retained).distinctBy { it.getJSONObject("data").getString("id") }
        val op = attempts.first()
        op.put("expectedVersion", BookRecord.parse(JSONObject(server)).version)
        op.put("remaining", JSONArray(attempts.drop(1)))
        dao.putMeta(NotebookMeta("paymentRecovery", op.toString()))
        pending.forEach { dao.updateOperation(it.copy(state = "reviewed")) }
        reconcileSnapshot(JSONObject(server))
        op
    }
    }
    suspend fun discardPaymentDraft() = writeLock.withLock { database.withTransaction {
        dao.putMeta(NotebookMeta("paymentRecovery", "")); dao.putMeta(NotebookMeta("draft", ""))
    }
    }
    suspend fun retryFailed() = writeLock.withLock {
        // Same ID and body only; never silently rebase a stale financial mutation.
        dao.allOperations().filter { it.state == "failed" && it.error == "unauthorized" }.forEach { dao.updateOperation(it.copy(state = "pending")) }
        requestSync()
    }
    suspend fun reopenFailed(id: String): RecoveredEdit = writeLock.withLock { database.withTransaction {
        val stored = requireNotNull(dao.record(id))
        require(dao.activeFor(id).any { it.state == "failed" })
        require(dao.meta("conflict:$id").isNullOrEmpty()) { "Use the existing aggregate first" }
        val local = BookRecord.parse(JSONObject(stored.payload))
        require(!local.voided) { "review_required" }
        val server = stored.serverPayload?.let { BookRecord.parse(JSONObject(it)) }
        if (server != null && local.kind == "sale") {
            fun payments(record: BookRecord) = record.receipts.map { it.copy(createdAt = java.time.Instant.EPOCH.toString()) }.sortedBy { it.id }
            require(payments(local) == payments(server)) { "linked_receipts" }
        }
        // The create form can represent one same-date initial payment.
        // Keep complex offline payment chains intact for review rather than flattening them.
        if (server == null && local.kind == "sale" && local.receipts.isNotEmpty()) {
            require(local.receipts.size == 1 && local.receipts.single().let { !it.voided && it.amount <= local.amount && it.date == local.date }) { "linked_receipts" }
        }
        val reason = JSONObject(dao.activeFor(id).last().payload).getJSONObject("data").optString("reason")
        val recovery = RecoveredEdit(local, server, reason)
        // Commit the recoverable input before archiving its operations or restoring the server.
        dao.putMeta(NotebookMeta("recoveredEdit", recovery.json().toString()))
        dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
        if (server == null) dao.removeRecord(id) else reconcile(server)
        recovery
    }
    }
}
