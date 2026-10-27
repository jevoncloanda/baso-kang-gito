package id.basokanggito.app.data

import android.content.Context
import androidx.room.withTransaction
import id.basokanggito.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BackupSummary(val records: Int, val customers: Int, val operations: Int, val pending: Int, val failed: Int, val hasDrafts: Boolean)

class PreparedBackup internal constructor(val header: BackupCrypto.Header, val summary: BackupSummary,
    internal val staging: File, internal val owner: Any) : Closeable {
    internal var closed = false
    override fun close() { closed = true; staging.delete() }
}

class NotebookBackup(context: Context, private val repository: NotebookRepository) {
    private val folder = File(context.applicationContext.cacheDir, "notebook-backup")
    private val identity = Any()
    private data class Snapshot(val records: List<StoredRecord>, val customers: List<StoredCustomer>,
        val operations: List<PendingOperation>, val metadata: List<NotebookMeta>) {
        fun summary() = BackupSummary(records.size, customers.size, operations.size, operations.count { it.state == "pending" },
            operations.count { it.state == "failed" }, metadata.any { it.key in setOf("draft", "customerDraft", "recoveredEdit", "paymentRecovery") && it.value.isNotEmpty() })
    }

    suspend fun create(password: CharArray): File {
        var owned: File? = null
        try { return withContext(Dispatchers.IO) {
        val snapshot = repository.restoring { repository.database.withTransaction { capture() } }
        validate(snapshot)
        val file = temporary(".bkgbak")
        owned = file
        try {
            file.outputStream().buffered().use { output ->
                BackupCrypto.encrypt(output, password, BackupCrypto.newHeader(BuildConfig.VERSION_NAME)) { writeSnapshot(it, snapshot) }
            }
            file
        } catch (failure: Throwable) { file.delete(); throw failure }
        } } catch (failure: Throwable) { owned?.delete(); throw failure }
    }

    suspend fun inspect(input: InputStream, password: CharArray): PreparedBackup {
        var owned: File? = null
        try { return withContext(Dispatchers.IO) {
        val staging = temporary(".stage")
        owned = staging
        try {
            val header = staging.outputStream().buffered().use { BackupCrypto.decrypt(input, it, password) }
            val snapshot = readSnapshot(staging)
            validate(snapshot)
            PreparedBackup(header, snapshot.summary(), staging, identity)
        } catch (failure: Throwable) { staging.delete(); throw failure }
        } } catch (failure: Throwable) { owned?.delete(); throw failure }
    }

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) {
        repository.restoring { repository.database.withTransaction { empty(capture(includeAllMetadata = true)) } }
    }

    suspend fun restore(prepared: PreparedBackup) = withContext(Dispatchers.IO) {
        if (prepared.owner !== identity || prepared.closed || !prepared.staging.isFile) throw BackupException("backup_invalid")
        val snapshot = readSnapshot(prepared.staging)
        validate(snapshot)
        repository.restoring {
            repository.database.withTransaction {
                if (!empty(capture(includeAllMetadata = true))) throw BackupException("backup_not_empty")
                val dao = repository.dao
                snapshot.records.forEach { dao.putRecord(it) }; snapshot.customers.forEach { dao.putCustomer(it) }
                snapshot.operations.forEach { dao.queue(it) }
                snapshot.metadata.forEach { dao.putMeta(it) }
                dao.putMeta(NotebookMeta("cursor", "0")); dao.putMeta(NotebookMeta("chronologyVersion", ""))
                dao.putMeta(NotebookMeta("lastSync", "")); dao.putMeta(NotebookMeta("syncError", ""))
                dao.putMeta(NotebookMeta("restoredAt", java.time.Instant.now().toString()))
            }
        }
        prepared.close()
    }

    private fun temporary(suffix: String): File {
        if (!folder.exists() && !folder.mkdirs()) throw java.io.IOException("backup_io")
        return File.createTempFile("notebook-", suffix, folder)
    }

    private suspend fun capture(includeAllMetadata: Boolean = false): Snapshot = repository.dao.let { dao ->
        Snapshot(dao.records(), dao.customers(), dao.allOperations(), dao.metadata().filter { includeAllMetadata || allowed(it.key) })
    }

    private fun allowed(key: String) = key in setOf("draft", "customerDraft", "recoveredEdit", "paymentRecovery", "cursor", "chronologyVersion") ||
        (key.startsWith("conflict:") && runCatching { validId(key.removePrefix("conflict:")) }.isSuccess)

    private fun empty(snapshot: Snapshot): Boolean = snapshot.records.isEmpty() && snapshot.customers.isEmpty() && snapshot.operations.isEmpty() &&
        snapshot.metadata.all { meta ->
            when (meta.key) {
                "draft" -> meta.value.isEmpty() || runCatching { emptyDraft(JSONObject(meta.value)) }.getOrDefault(false)
                "customerDraft" -> meta.value.isEmpty() || runCatching { emptyCustomerDraft(JSONObject(meta.value)) }.getOrDefault(false)
                "cursor" -> meta.value.isEmpty() || meta.value.matches(Regex("0|[1-9][0-9]{0,17}"))
                "lastSync", "syncError", "chronologyVersion", "lastBackupAt", "restoredAt" -> true
                else -> meta.value.isEmpty()
            }
        }

    private fun emptyCustomerDraft(draft: JSONObject): Boolean {
        validId(draft.getString("id")); version(draft, "version")
        return draft.optInt("version", -1) == 0 && listOf("name", "phone", "address", "notes", "reason").all { draft.optString(it).isEmpty() } && !draft.optBoolean("archived")
    }

    private fun emptyDraft(draft: JSONObject): Boolean {
        validateDraft(draft)
        if (draft.optInt("version", -1) != 0 || draft.optString("reason").isNotEmpty() || draft.has("recoveredEdit")) return false
        val sale = draft.optJSONObject("sale") ?: return false
        if (listOf("amount", "customer", "paymentAmount", "note").any { sale.optString(it).isNotEmpty() } || !sale.isNull("customerId")) return false
        if (draft.optString("receiptAmount").isNotEmpty()) return false
        return draft.optJSONArray("expense").objects().all { it.optString("name").isEmpty() && it.optString("amount").isEmpty() }
    }

    private fun writeSnapshot(output: OutputStream, snapshot: Snapshot) {
        val out = DataOutputStream(output)
        fun text(value: String?) {
            if (value == null) { out.writeInt(-1); return }
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MaxFieldBytes) throw BackupException("backup_limit")
            out.writeInt(bytes.size); out.write(bytes)
        }
        out.writeInt(1)
        out.writeInt(snapshot.records.size)
        snapshot.records.forEach { text(it.id); text(it.date); text(it.payload); text(it.serverPayload) }
        out.writeInt(snapshot.customers.size)
        snapshot.customers.forEach { text(it.id); text(it.payload); text(it.serverPayload) }
        out.writeInt(snapshot.operations.size)
        snapshot.operations.forEach { out.writeLong(it.sequence); text(it.operationId); text(it.recordId); text(it.payload); text(it.beforePayload); text(it.state); text(it.error) }
        out.writeInt(snapshot.metadata.size); snapshot.metadata.forEach { text(it.key); text(it.value) }
    }

    private fun readSnapshot(file: File): Snapshot {
        if (file.length() > BackupCrypto.MaxPayloadBytes) throw BackupException("backup_limit")
        try {
            return file.inputStream().buffered().use { stream ->
                val input = DataInputStream(stream)
                fun text(nullable: Boolean = false): String? {
                    val length = input.readInt()
                    if (nullable && length == -1) return null
                    if (length < 0) throw BackupException("backup_invalid")
                    if (length > MaxFieldBytes || length > file.length()) throw BackupException("backup_limit")
                    val bytes = ByteArray(length); input.readFully(bytes)
                    return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                    catch (_: java.nio.charset.CharacterCodingException) { throw BackupException("backup_invalid") }
                }
                fun count(): Int = input.readInt().also { if (it !in 0..MaxRows) throw BackupException("backup_limit") }
                if (input.readInt() != 1) throw BackupException("backup_incompatible")
                val records = List(count()) { StoredRecord(text()!!, text()!!, text()!!, text(true)) }
                val customers = List(count()) { StoredCustomer(text()!!, text()!!, text(true)) }
                val operations = List(count()) { PendingOperation(input.readLong(), text()!!, text()!!, text()!!, text(true), text()!!, text()!!) }
                val meta = List(count()) { NotebookMeta(text()!!, text()!!) }
                if (input.read() != -1) throw BackupException("backup_invalid")
                Snapshot(records, customers, operations, meta)
            }
        } catch (_: EOFException) { throw BackupException("backup_invalid") }
    }

    private fun validate(snapshot: Snapshot) {
        if (listOf(snapshot.records.size, snapshot.customers.size, snapshot.operations.size, snapshot.metadata.size).any { it > MaxRows })
            throw BackupException("backup_limit")
        try {
            fun unique(values: List<String>) { require(values.distinct().size == values.size) }
            unique(snapshot.records.map { it.id } + snapshot.customers.map { it.id })
            unique(snapshot.operations.map { it.operationId }); unique(snapshot.metadata.map { it.key })
            require(snapshot.operations.map { it.sequence }.let { it.all { sequence -> sequence > 0 } && it.zipWithNext().all { (a, b) -> a < b } })
            val customers = snapshot.customers.associate { stored ->
                val value = Customer.parse(JSONObject(stored.payload)); require(value.id == stored.id)
                stored.serverPayload?.let { require(Customer.parse(JSONObject(it)).id == stored.id) }
                stored.id to value
            }
            snapshot.operations.forEach { stored ->
                validId(stored.operationId); validId(stored.recordId)
                require(stored.state in setOf("pending", "failed", "acknowledged", "reviewed") && stored.error.length <= 200)
                val operation = operation(JSONObject(stored.payload))
                require(operation.getString("id") == stored.operationId && operation.getString("recordId") == stored.recordId)
                val beforeVersion = stored.beforePayload?.let { before ->
                    if (operation.getString("action") == "save_customer") Customer.parse(JSONObject(before)).also { require(it.id == stored.recordId) }.version
                    else record(JSONObject(before)).also { require(it.id == stored.recordId) }.version
                }
                require((beforeVersion ?: 0) == operation.getInt("expectedVersion"))
            }
            val metadata = snapshot.metadata.associate { require(allowed(it.key)); it.key to it.value }
            val draftCustomer = metadata["customerDraft"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                val draft = JSONObject(raw); validId(draft.getString("id")); version(draft, "version")
                for (key in listOf("name", "phone", "address", "notes", "reason")) require(!draft.has(key) || draft.opt(key) is String)
                require(!draft.has("archived") || draft.opt("archived") is Boolean)
                draft.getString("id")
            }
            // A discarded reopened create can leave a real dependent sale; keep its identity evidence.
            val reviewedCreates = snapshot.operations.filter { stored ->
                if (stored.state != "reviewed" || stored.beforePayload != null) false else {
                    val op = JSONObject(stored.payload); val data = op.getJSONObject("data")
                    op.getString("action") == "save_customer" && op.getInt("expectedVersion") == 0 &&
                        data.opt("name") is String && data.getString("name").trim().length in 1..200
                }
            }.map { it.recordId }
            val knownCustomers = customers.keys + listOfNotNull(draftCustomer) + reviewedCreates
            val receipts = mutableSetOf<String>(); val items = mutableSetOf<String>()
            snapshot.records.forEach { stored ->
                val value = record(JSONObject(stored.payload)); require(value.id == stored.id && value.date == stored.date)
                require(value.customerId == null || value.customerId in knownCustomers)
                require(value.receipts.all { receipts.add(it.id) } && value.items.all { items.add(it.id) })
                stored.serverPayload?.let { raw -> val server = record(JSONObject(raw)); require(server.id == value.id && server.kind == value.kind); require(server.customerId == null || server.customerId in knownCustomers) }
            }
            require(receipts.intersect(snapshot.records.map { it.id }.toSet() + customers.keys).isEmpty())
            metadata.forEach { (key, raw) -> if (raw.isNotEmpty()) when {
                key == "cursor" -> require(raw.matches(Regex("0|[1-9][0-9]{0,17}")))
                key == "chronologyVersion" -> require(raw == "1")
                key == "recoveredEdit" -> {
                    val edit = RecoveredEdit.parse(raw); record(edit.local.json())
                    require(edit.local.customerId == null || edit.local.customerId in knownCustomers)
                    edit.server?.let { record(it.json()); require(it.id == edit.local.id && it.kind == edit.local.kind); require(it.customerId == null || it.customerId in knownCustomers) }
                }
                key == "paymentRecovery" -> {
                    val recovery = JSONObject(raw); val payments = listOf(recovery) + recovery.optJSONArray("remaining").objects()
                    require(payments.size <= MaxRows)
                    val ids = payments.map { payment ->
                        operation(payment); require(payment.getString("action") == "add_receipt" && payment.getString("recordId") == recovery.getString("recordId"))
                        val data = payment.getJSONObject("data"); validDate(data.getString("date")); require(data.getString("method") in listOf("cash", "transfer"))
                        require(data.opt("amount") is String); validId(data.getString("id"))
                    }; unique(ids)
                    require(snapshot.records.any { it.id == recovery.getString("recordId") })
                }
                key == "draft" -> validateDraft(JSONObject(raw), knownCustomers)
                key.startsWith("conflict:") -> {
                    val id = key.removePrefix("conflict:")
                    require(snapshot.records.any { it.id == id } && snapshot.operations.any { it.recordId == id && it.state == "failed" })
                    val conflict = record(JSONObject(raw)); require(conflict.customerId == null || conflict.customerId in knownCustomers)
                }
            } }
            snapshot.operations.filter { it.state in setOf("pending", "failed") }.groupBy { it.recordId }.forEach { (id, active) ->
                val root = snapshot.records.find { it.id == id }?.let { BookRecord.parse(JSONObject(it.payload)) }
                val customer = customers[id]
                require(root != null || customer != null)
                val envelopes = active.map { JSONObject(it.payload) }
                envelopes.forEach { op ->
                    when (op.getString("action")) {
                        "save_customer" -> require(customer != null)
                        "save_sale", "add_receipt" -> require(root?.kind == "sale")
                        "save_expense" -> require(root?.kind == "expense")
                        "void" -> require(root != null)
                    }
                }
                val versions = envelopes.map { it.getInt("expectedVersion") }
                require(versions.zipWithNext().all { (a, b) -> b == a + 1 })
                require((root?.version ?: customer!!.version) == versions.last() + 1)
                active.forEachIndexed { index, attempt ->
                    val op = envelopes[index]
                    val expected = active.getOrNull(index + 1)?.beforePayload
                        ?: if (customer != null) snapshot.customers.single { it.id == id }.payload else snapshot.records.single { it.id == id }.payload
                    require(index == active.lastIndex || active[index + 1].beforePayload != null)
                    if (customer != null) {
                        val before = attempt.beforePayload?.let { Customer.parse(JSONObject(it)) }
                        val after = applyCustomerLocally(op, before)
                        require(withoutChronology(after.json()) == withoutChronology(Customer.parse(JSONObject(expected)).json()))
                    } else {
                        val before = attempt.beforePayload?.let { record(JSONObject(it)) }
                        val after = applyLocally(op, before)
                        require(withoutChronology(after.json()) == withoutChronology(record(JSONObject(expected)).json()))
                    }
                }
            }
        } catch (failure: BackupException) { throw failure }
        catch (_: Exception) { throw BackupException("backup_invalid") }
    }

    private fun operation(op: JSONObject): JSONObject {
        validId(op.getString("id")); validId(op.getString("recordId")); version(op, "expectedVersion")
        require(op.getString("action") in setOf("save_sale", "save_expense", "add_receipt", "void", "save_customer")); op.getJSONObject("data")
        return op
    }

    private fun withoutChronology(json: JSONObject): String {
        json.remove("createdAt")
        json.optJSONArray("receipts").objects().forEach { it.remove("createdAt") }
        return json.toString()
    }

    private fun version(json: JSONObject, key: String) { val number = json.get(key); require(number is Number && number.toLong() in 0..2147483646L && number.toDouble() == number.toLong().toDouble()) }

    private fun record(json: JSONObject): BookRecord = BookRecord.parse(json).also { value ->
        require(value.received <= value.amount)
        require(!value.voided || value.received == 0L)
        if (value.kind == "expense") require(value.receipts.isEmpty() && value.customerId == null)
        else require(value.items.isEmpty() && (value.customerId == null || value.channel == "langsung"))
        require(value.receipts.map { it.id }.distinct().size == value.receipts.size && value.items.map { it.id }.distinct().size == value.items.size)
    }

    private fun validateDraft(draft: JSONObject, knownCustomers: Set<String>? = null) {
        validId(draft.getString("id")); version(draft, "version"); validDate(draft.getString("date"))
        require(draft.opt("route") is String && draft.opt("reason") is String)
        require(draft.getString("kind") in listOf("", "sale", "expense", "receipt"))
        val sale = draft.getJSONObject("sale")
        require(listOf("amount", "customer", "channel", "note", "method").all { sale.opt(it) is String })
        require(sale.getString("channel") in listOf("Langsung", "GoFood", "GrabFood", "ShopeeFood") && sale.opt("paid") is Boolean)
        if (!sale.isNull("customerId")) validId(sale.getString("customerId")).also { if (knownCustomers != null) require(it in knownCustomers) }
        draft.getJSONArray("expense").objects().forEach { validId(it.getString("id")); require(it.opt("name") is String && it.opt("amount") is String) }
        require(draft.opt("receiptAmount") is String); validDate(draft.getString("receiptDate")); validId(draft.getString("receiptId"))
        require(draft.getString("receiptMethod") in listOf("cash", "transfer"))
        draft.optJSONObject("recoveredEdit")?.let {
            val edit = RecoveredEdit.parse(it.toString()); record(edit.local.json())
            if (knownCustomers != null) require(edit.local.customerId == null || edit.local.customerId in knownCustomers)
            edit.server?.let { server -> record(server.json()); require(server.id == edit.local.id && server.kind == edit.local.kind)
                if (knownCustomers != null) require(server.customerId == null || server.customerId in knownCustomers) }
        }
    }

    private companion object { const val MaxFieldBytes = 2 * 1024 * 1024; const val MaxRows = 100_000 }
}
