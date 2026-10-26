package id.basokanggito.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

data class NotebookState(val records: List<BookRecord> = emptyList(), val pending: List<PendingOperation> = emptyList(), val meta: Map<String, String> = emptyMap(), val loaded: Boolean = false, val unreadable: Boolean = false, val serverRecords: Map<String, BookRecord> = emptyMap())

class NotebookRepository(val database: NotebookDatabase, private val requestSync: () -> Unit = {}) {
    val dao = database.notebook()
    private val syncLock = Mutex()
    val state = combine(dao.observeRecords(), dao.observeQueue(), dao.observeMeta()) { _, _, _ -> database.withTransaction {
        val rows = dao.records(); val queue = dao.activeOperations(); val meta = dao.metadata()
        try { NotebookState(rows.map { BookRecord.parse(JSONObject(it.payload)) }, queue, meta.associate { it.key to it.value }, loaded = true,
            serverRecords = rows.mapNotNull { row -> row.serverPayload?.let { row.id to BookRecord.parse(JSONObject(it)) } }.toMap()) }
        catch (_: Exception) { NotebookState(pending = queue, loaded = true, unreadable = true) }
    } }
    suspend fun saveDraft(value: String) = dao.putMeta(NotebookMeta("draft", value))

    suspend fun submit(action: String, recordId: String, expectedVersion: Int, data: JSONObject): BookRecord = withContext(Dispatchers.IO) {
        val result = database.withTransaction {
            require(dao.activeFor(recordId).none { it.state == "failed" }) { "review_required" }
            val stored = dao.record(recordId); val before = stored?.let { BookRecord.parse(JSONObject(it.payload)) }
            val op = JSONObject().put("id", newId()).put("recordId", recordId).put("expectedVersion", expectedVersion).put("action", action).put("data", data)
            val after = applyLocally(op, before)
            if (after.kind == "sale" && after.channel != "langsung" && !after.voided) {
                val others = dao.records().filter { it.id != recordId }.map { BookRecord.parse(JSONObject(it.payload)) }
                require(others.none { !it.voided && it.kind == "sale" && it.date == after.date && it.channel == after.channel }) { "daily_aggregate_exists" }
            }
            dao.putRecord(StoredRecord(after.id, after.date, after.json().toString(), stored?.serverPayload))
            dao.queue(PendingOperation(operationId = op.getString("id"), recordId = recordId, payload = op.toString(), beforePayload = stored?.payload))
            after
        }
        requestSync()
        result
    }

    private suspend fun reconcile(record: BookRecord) {
        val current = dao.record(record.id)
        val previousServer = current?.serverPayload?.let { BookRecord.parse(JSONObject(it)) }
        if (previousServer != null && previousServer.version > record.version) return
        val server = record.json().toString()
        dao.putRecord(if (dao.activeFor(record.id).isNotEmpty() && current != null) current.copy(serverPayload = server)
            else StoredRecord(record.id, record.date, server, server))
    }

    suspend fun synchronize(client: NotebookHttpClient): Boolean = syncLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                dao.putMeta(NotebookMeta("syncError", ""))
                while (true) {
                    val next = dao.nextOperation() ?: break
                    val response = client.upload(JSONObject(next.payload))
                    if (response.status in 200..299) database.withTransaction {
                        val remote = BookRecord.parse(response.body.getJSONObject("record"))
                        require(remote.id == next.recordId)
                        dao.updateOperation(next.copy(state = "acknowledged"))
                        reconcile(remote)
                        // Upload cursor must never skip downloads made by other devices.
                    } else if (response.status == 429 || response.status >= 500) throw IOException("retryable_server_error")
                    else database.withTransaction {
                        response.body.optJSONObject("current")?.let { remoteJson ->
                            val remote = BookRecord.parse(remoteJson)
                            if (remote.id == next.recordId) reconcile(remote)
                            else dao.putMeta(NotebookMeta("conflict:${next.recordId}", remote.json().toString()))
                        }
                        dao.updateOperation(next.copy(state = "failed", error = response.body.optString("error", "invalid_response")))
                    }
                }
                do {
                    val response = client.download(dao.meta("cursor") ?: "0")
                    if (response.status !in 200..299) throw IOException("download_failed")
                    database.withTransaction {
                        response.body.getJSONArray("changes").objects().forEach { reconcile(BookRecord.parse(it.getJSONObject("record"))) }
                        val old = (dao.meta("cursor") ?: "0").toBigInteger()
                        val next = response.body.getString("cursor").toBigInteger()
                        require(next >= old)
                        dao.putMeta(NotebookMeta("cursor", next.toString()))
                    }
                } while (response.body.getBoolean("hasMore"))
                dao.putMeta(NotebookMeta("lastSync", java.time.Instant.now().toString()))
                true
            } catch (_: Exception) {
                dao.putMeta(NotebookMeta("syncError", "connection_failed"))
                false
            }
        }
    }

    // Explicit review archives local operations; their bodies and prior snapshots are retained.
    suspend fun useServerVersion(id: String) = database.withTransaction {
        val stored = dao.record(id) ?: return@withTransaction
        val other = dao.meta("conflict:$id")?.takeIf { it.isNotEmpty() }
        require(stored.serverPayload != null || other != null) { "server_version_unavailable" }
        dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
        val server = BookRecord.parse(JSONObject(other ?: stored.serverPayload!!))
        if (server.id != id) dao.removeRecord(id)
        reconcile(server)
        dao.putMeta(NotebookMeta("conflict:$id", ""))
    }
    suspend fun retryFailed() {
        // Same ID and body only; never silently rebase a stale financial mutation.
        dao.allOperations().filter { it.state == "failed" && it.error == "unauthorized" }.forEach { dao.updateOperation(it.copy(state = "pending")) }
        requestSync()
    }
    suspend fun reopenFailed(id: String): Pair<BookRecord, BookRecord?> = database.withTransaction {
        val stored = requireNotNull(dao.record(id))
        require(dao.activeFor(id).any { it.state == "failed" })
        require(dao.meta("conflict:$id").isNullOrEmpty()) { "Use the existing aggregate first" }
        val local = BookRecord.parse(JSONObject(stored.payload))
        val server = stored.serverPayload?.let { BookRecord.parse(JSONObject(it)) }
        dao.activeFor(id).forEach { dao.updateOperation(it.copy(state = "reviewed")) }
        if (server == null) dao.removeRecord(id) else reconcile(server)
        local to server
    }
}
