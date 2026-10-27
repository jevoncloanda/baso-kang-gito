package id.basokanggito.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class BackupDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password get() = "Baso family backup 2095".toCharArray()
    private fun database() = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
    private fun sale(customerId: String) = JSONObject().put("date", "2095-05-01").put("channel", "langsung").put("amount", "100000")
        .put("paid", true).put("receiptId", newId()).put("method", "cash").put("paymentAmount", "40000").put("customerId", customerId)
    private fun payment(value: String) = JSONObject().put("id", newId()).put("date", "2095-05-02").put("amount", value).put("method", "transfer").put("reason", "Installment")
    private fun blankDraft() = JSONObject().put("route", "journal").put("date", "2095-05-01").put("kind", "").put("id", newId()).put("version", 0).put("reason", "")
        .put("sale", JSONObject().put("amount", "").put("customer", "").put("customerId", JSONObject.NULL).put("paymentAmount", "").put("channel", "Langsung").put("paid", true).put("note", "").put("method", "cash"))
        .put("expense", JSONArray(listOf(JSONObject().put("id", newId()).put("name", "").put("amount", ""))))
        .put("receiptAmount", "").put("receiptDate", "2095-05-01").put("receiptMethod", "cash").put("receiptId", newId())
    private suspend fun failure(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: BackupException) { assertEquals(code, error.code) }
    }

    @Test fun exportsDuringConcurrentLocalWritesContainAtomicRootsAndTheirCompleteOutbox() = runBlocking {
        val sourceDb = database(); val source = NotebookRepository(sourceDb); val rounds = Channel<Unit>()
        val writer = launch(Dispatchers.Default) {
            repeat(3) { round ->
                rounds.receive()
                repeat(8) { index ->
                    val customer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Concurrent $round $index"))
                    source.submit("save_sale", newId(), 0, sale(customer.id))
                    yield()
                }
            }
        }
        try {
            repeat(3) {
                rounds.send(Unit)
                val file = NotebookBackup(context, source).create(password)
                val targetDb = database()
                try {
                    val target = NotebookRepository(targetDb); val restore = NotebookBackup(context, target)
                    file.inputStream().use { restore.inspect(it, password) }.use { restore.restore(it) }
                    val records = target.dao.records().map { row -> BookRecord.parse(JSONObject(row.payload)) }
                    val customers = target.dao.customers().map { row -> Customer.parse(JSONObject(row.payload)) }
                    val operations = target.dao.allOperations()
                    val roots = records.associate { record -> record.id to record.version } + customers.associate { customer -> customer.id to customer.version }
                    assertEquals(roots.size, operations.size)
                    assertTrue(records.all { record -> customers.any { customer -> customer.id == record.customerId } })
                    operations.forEach { op ->
                        assertTrue(op.recordId in roots); assertEquals("pending", op.state)
                        assertEquals(roots.getValue(op.recordId), JSONObject(op.payload).getInt("expectedVersion") + 1)
                        assertEquals(op.operationId, JSONObject(op.payload).getString("id"))
                    }
                } finally { file.delete(); targetDb.close() }
            }
            writer.join(); assertEquals(24, source.dao.records().size); assertEquals(24, source.dao.customers().size)
            assertEquals(48, source.dao.allOperations().size)
        } finally { writer.cancelAndJoin(); rounds.close(); sourceDb.close() }
    }

    @Test fun authenticatedMalformedRelationshipsAreRejectedWithoutChangingNonemptyTarget() = runBlocking {
        val db = database(); val repository = NotebookRepository(db)
        try {
            repository.submitCustomer(newId(), 0, JSONObject().put("name", "Preserved target"))
            val expectedCustomers = repository.dao.customers(); val expectedOperations = repository.dao.allOperations()
            val child = Payment(newId(), "2095-05-02", 20000, "cash")
            fun root() = BookRecord(newId(), "sale", "2095-05-01", 1, 100000, channel = "langsung")
            val fixtures = listOf(
                listOf(root().json().put("amount", "0")),
                listOf(root().copy(customerId = newId()).json()),
                listOf(root().copy(receipts = listOf(child)).json(), root().copy(receipts = listOf(child)).json()),
            )
            for (records in fixtures) {
                val file = File.createTempFile("invalid-relationship-", ".bkgbak", context.cacheDir)
                try {
                    file.outputStream().use { output -> BackupCrypto.encrypt(output, password, BackupCrypto.newHeader("test")) { plaintext ->
                        val data = java.io.DataOutputStream(plaintext)
                        fun text(value: String?) { if (value == null) data.writeInt(-1) else value.toByteArray(Charsets.UTF_8).let { bytes -> data.writeInt(bytes.size); data.write(bytes) } }
                        data.writeInt(1); data.writeInt(records.size)
                        records.forEach { record -> text(record.getString("id")); text(record.getString("date")); text(record.toString()); text(null) }
                        data.writeInt(0); data.writeInt(0); data.writeInt(0)
                    } }
                    file.inputStream().use { input -> failure("backup_invalid") { NotebookBackup(context, repository).inspect(input, password) } }
                    assertEquals(expectedCustomers, repository.dao.customers()); assertEquals(expectedOperations, repository.dao.allOperations())
                    assertTrue(repository.dao.records().isEmpty())
                } finally { file.delete() }
            }
        } finally { db.close() }
    }

    @Test fun discardedReopenedCustomerDraftRetainsDependentSaleAndReviewedIdentityEvidence() = runBlocking {
        val sourceDb = database(); val targetDb = database(); var file: File? = null
        try {
            val source = NotebookRepository(sourceDb); val target = NotebookRepository(targetDb)
            val customer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Reviewed customer creation"))
            source.submit("save_sale", newId(), 0, sale(customer.id))
            source.dao.activeFor(customer.id).forEach { source.dao.updateOperation(it.copy(state = "failed", error = "invalid_operation")) }
            source.reopenCustomer(customer.id); source.saveCustomerDraft("")
            assertNull(source.dao.customer(customer.id))
            val rows = source.dao.records(); val operations = source.dao.allOperations()
            file = NotebookBackup(context, source).create(password)
            val restore = NotebookBackup(context, target)
            file!!.inputStream().use { restore.inspect(it, password) }.use { restore.restore(it) }
            assertEquals(rows, target.dao.records()); assertEquals(operations, target.dao.allOperations())
            assertTrue(target.dao.customers().isEmpty()); assertTrue(target.dao.meta("customerDraft").isNullOrEmpty())
            assertEquals(customer.id, BookRecord.parse(JSONObject(target.dao.records().single().payload)).customerId)
        } finally { file?.delete(); sourceDb.close(); targetDb.close() }
    }

    @Test fun authenticatedPendingBodyMustReproduceItsLocalSnapshotWithoutChangingExistingTarget() = runBlocking {
        val db = database(); val file = File.createTempFile("mismatched-operation-", ".bkgbak", context.cacheDir)
        try {
            val repository = NotebookRepository(db)
            repository.submitCustomer(newId(), 0, JSONObject().put("name", "Protected existing notebook"))
            val customers = repository.dao.customers(); val operations = repository.dao.allOperations(); val metadata = repository.dao.metadata()
            val root = BookRecord(newId(), "sale", "2095-05-01", 1, 100000, channel = "langsung")
            val op = JSONObject().put("id", newId()).put("recordId", root.id).put("expectedVersion", 0).put("action", "save_sale")
                .put("data", JSONObject().put("date", root.date).put("channel", "langsung").put("amount", "90000").put("paid", false))
            file.outputStream().use { output -> BackupCrypto.encrypt(output, password, BackupCrypto.newHeader("test")) { plaintext ->
                val data = java.io.DataOutputStream(plaintext)
                fun text(value: String?) { if (value == null) data.writeInt(-1) else value.toByteArray(Charsets.UTF_8).let { bytes -> data.writeInt(bytes.size); data.write(bytes) } }
                data.writeInt(1); data.writeInt(1)
                text(root.id); text(root.date); text(root.json().toString()); text(null)
                data.writeInt(0); data.writeInt(1)
                data.writeLong(1); text(op.getString("id")); text(root.id); text(op.toString()); text(null); text("pending"); text("")
                data.writeInt(0)
            } }
            file.inputStream().use { input -> failure("backup_invalid") { NotebookBackup(context, repository).inspect(input, password) } }
            assertEquals(customers, repository.dao.customers()); assertEquals(operations, repository.dao.allOperations())
            assertEquals(metadata, repository.dao.metadata()); assertTrue(repository.dao.records().isEmpty())
        } finally { file.delete(); db.close() }
    }

    @Test fun encryptedLogicalRoundtripPreservesRawSnapshotsAllOperationsAndRecoveryMetadata() = runBlocking {
        val sourceDb = database(); val targetDb = database(); var file: File? = null
        try {
            val source = NotebookRepository(sourceDb); val target = NotebookRepository(targetDb)
            val customer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Backup customer").put("notes", "Private family notes"))
            val sale = source.submit("save_sale", newId(), 0, sale(customer.id))
            // First two operations are server-acknowledged; the later payment chain conflicts.
            source.dao.allOperations().forEach { source.dao.updateOperation(it.copy(state = "acknowledged")) }
            source.dao.putCustomer(source.dao.customer(customer.id)!!.copy(serverPayload = customer.json().toString()))
            source.dao.putRecord(source.dao.record(sale.id)!!.copy(serverPayload = sale.json().toString()))
            source.submit("add_receipt", sale.id, 1, payment("20000")); source.submit("add_receipt", sale.id, 2, payment("10000"))
            val attempts = source.dao.activeFor(sale.id)
            source.dao.updateOperation(attempts.first().copy(state = "failed", error = "version_conflict"))
            source.reopenPayment(sale.id)
            // Reopened failed customer creation is a valid draft at version zero without a customer row.
            val failedCustomer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Retry customer"))
            source.dao.activeFor(failedCustomer.id).forEach { source.dao.updateOperation(it.copy(state = "failed", error = "invalid_operation")) }
            source.reopenCustomer(failedCustomer.id)
            source.saveDraft(blankDraft().put("sale", blankDraft().getJSONObject("sale").put("note", "Keep unfinished draft")).toString())
            source.dao.putMeta(NotebookMeta("cursor", "123")); source.dao.putMeta(NotebookMeta("chronologyVersion", "1"))
            source.dao.putMeta(NotebookMeta("lastSync", "2095-05-01T00:00:00Z")); source.dao.putMeta(NotebookMeta("proofSecret", "Never exported"))
            val expectedRows = source.dao.records(); val expectedCustomers = source.dao.customers(); val expectedOps = source.dao.allOperations()
            val backup = NotebookBackup(context, source); file = backup.create(password)
            assertFalse(file!!.readBytes().toString(Charsets.UTF_8).contains("Private family notes"))
            val restore = NotebookBackup(context, target)
            file!!.inputStream().use { input -> restore.inspect(input, password) }.use { prepared ->
                assertEquals(expectedRows.size, prepared.summary.records); assertEquals(expectedOps.size, prepared.summary.operations)
                assertTrue(prepared.summary.hasDrafts); restore.restore(prepared)
            }
            assertEquals(expectedRows, target.dao.records()); assertEquals(expectedCustomers, target.dao.customers()); assertEquals(expectedOps, target.dao.allOperations())
            for (key in listOf("draft", "customerDraft", "paymentRecovery")) assertEquals(source.dao.meta(key), target.dao.meta(key))
            assertEquals("0", target.dao.meta("cursor")); assertEquals("", target.dao.meta("lastSync")); assertNull(target.dao.meta("proofSecret"))
            assertFalse(target.dao.meta("restoredAt").isNullOrEmpty()); assertFalse(restore.isEmpty())
        } finally { file?.delete(); sourceDb.close(); targetDb.close() }
    }

    @Test fun safeEmptyAllowsDefaultUiDraftButRefusesRealDraftsCustomersHistoryAndLateWrites() = runBlocking {
        val sourceDb = database(); val targetDb = database(); var file: File? = null
        try {
            val source = NotebookRepository(sourceDb); val target = NotebookRepository(targetDb)
            val backup = NotebookBackup(context, target)
            assertTrue(backup.isEmpty())
            target.saveDraft(blankDraft().toString()); assertTrue(backup.isEmpty())
            target.saveDraft(blankDraft().put("sale", blankDraft().getJSONObject("sale").put("amount", "1")).toString()); assertFalse(backup.isEmpty())
            target.saveDraft(""); target.saveCustomerDraft(JSONObject().put("id", newId()).put("version", 0).toString()); assertTrue(backup.isEmpty())
            target.saveCustomerDraft(JSONObject().put("id", newId()).put("version", 0).put("name", "Unsaved parent input").toString()); assertFalse(backup.isEmpty())
            target.saveCustomerDraft("")
            target.dao.putMeta(NotebookMeta("cursor", "100")); target.dao.putMeta(NotebookMeta("lastBackupAt", "2026-10-27T00:00:00Z"))
            target.dao.putMeta(NotebookMeta("restoredAt", "2026-10-26T00:00:00Z")); assertTrue(backup.isEmpty())
            val historicalId = newId()
            target.dao.queue(PendingOperation(operationId = newId(), recordId = historicalId, payload = "retained history", beforePayload = null, state = "reviewed"))
            assertFalse(backup.isEmpty())
            targetDb.openHelper.writableDatabase.execSQL("DELETE FROM pending_operations")
            file = NotebookBackup(context, source).create(password)
            file!!.inputStream().use { backup.inspect(it, password) }.use { prepared ->
                target.submitCustomer(newId(), 0, JSONObject().put("name", "Written after preview").put("archived", true))
                failure("backup_not_empty") { backup.restore(prepared) }
            }
            assertEquals("Written after preview", Customer.parse(JSONObject(target.dao.customers().single().payload)).name)
            assertEquals(1, target.dao.allOperations().size)
        } finally { file?.delete(); sourceDb.close(); targetDb.close() }
    }

    @Test fun wrongPasswordCorruptionAndMalformedLogicalPayloadLeaveDatabaseUntouchedAndCleanStaging() = runBlocking {
        val db = database(); var file: File? = null; var malformed: File? = null
        try {
            val repository = NotebookRepository(db); val backup = NotebookBackup(context, repository)
            file = backup.create(password)
            val folder = file!!.parentFile!!
            val stages = folder.listFiles()!!.filter { it.extension == "stage" }.map { it.name }.toSet()
            failure("backup_password") { file!!.inputStream().use { backup.inspect(it, "Wrong but long password".toCharArray()) } }
            val corrupt = file!!.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            failure("backup_password") { backup.inspect(corrupt.inputStream(), password) }
            malformed = File.createTempFile("malformed-", ".bkgbak", context.cacheDir)
            malformed!!.outputStream().use { output -> BackupCrypto.encrypt(output, password, BackupCrypto.newHeader("test")) { it.write(byteArrayOf(0, 0, 0, 1)) } }
            failure("backup_invalid") { malformed!!.inputStream().use { backup.inspect(it, password) } }
            assertEquals(stages, folder.listFiles()!!.filter { it.extension == "stage" }.map { it.name }.toSet())
            assertTrue(backup.isEmpty()); assertTrue(repository.dao.metadata().isEmpty())
        } finally { file?.delete(); malformed?.delete(); db.close() }
    }

    @Test fun interruptedRestoreRollsBackRecordsCustomersOutboxAndMetadataTogether() = runBlocking {
        val sourceDb = database(); val targetDb = database(); var file: File? = null
        try {
            val source = NotebookRepository(sourceDb); val target = NotebookRepository(targetDb)
            val customer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Rollback fixture"))
            source.submit("save_sale", newId(), 0, sale(customer.id))
            file = NotebookBackup(context, source).create(password)
            // Inject a storage failure after record insertion, inside the same owned test transaction.
            targetDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_restore_failure BEFORE INSERT ON customers BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
            val restore = NotebookBackup(context, target)
            file!!.inputStream().use { restore.inspect(it, password) }.use { prepared ->
                assertTrue(runCatching { restore.restore(prepared) }.isFailure)
                assertTrue(target.dao.records().isEmpty()); assertTrue(target.dao.customers().isEmpty())
                assertTrue(target.dao.allOperations().isEmpty()); assertTrue(target.dao.metadata().isEmpty())
                targetDb.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_restore_failure")
                restore.restore(prepared)
                assertEquals(1, target.dao.records().size); assertEquals(1, target.dao.customers().size)
            }
        } finally { file?.delete(); sourceDb.close(); targetDb.close() }
    }

    @Test fun incompatibleAndInvalidFilesCannotChangeAnExistingNotebook() = runBlocking {
        val db = database(); val emptyDb = database(); var file: File? = null; var malformed: File? = null
        try {
            val repository = NotebookRepository(db)
            repository.submitCustomer(newId(), 0, JSONObject().put("name", "Existing protected customer"))
            val beforeCustomers = repository.dao.customers(); val beforeOperations = repository.dao.allOperations()
            val backup = NotebookBackup(context, repository)
            file = NotebookBackup(context, NotebookRepository(emptyDb)).create(password)
            val unsupported = file!!.readBytes(); ByteBuffer.wrap(unsupported).putInt(16, 99)
            failure("backup_incompatible") { backup.inspect(unsupported.inputStream(), password) }
            malformed = File.createTempFile("invalid-", ".bkgbak", context.cacheDir)
            malformed!!.outputStream().use { out -> BackupCrypto.encrypt(out, password, BackupCrypto.newHeader("test")) { payload ->
                java.io.DataOutputStream(payload).apply { writeInt(1); writeInt(-1) }
            } }
            failure("backup_limit") { malformed!!.inputStream().use { backup.inspect(it, password) } }
            file!!.inputStream().use { backup.inspect(it, password) }.use { prepared -> failure("backup_not_empty") { backup.restore(prepared) } }
            assertEquals(beforeCustomers, repository.dao.customers()); assertEquals(beforeOperations, repository.dao.allOperations())
            assertTrue(repository.dao.records().isEmpty()); assertTrue(repository.dao.metadata().all { it.key == "customerDraft" && it.value.isEmpty() })
        } finally { file?.delete(); malformed?.delete(); db.close(); emptyDb.close() }
    }

    @Test fun backupBeforeUploadsReplaysAcknowledgedIdsThenPreservesLaterVersionConflict() = runBlocking {
        val sourceDb = database(); val targetDb = database(); var file: File? = null
        val client = NotebookHttpClient("http://127.0.0.1:3001", BuildConfig.SYNC_TOKEN)
        try {
            check(BuildConfig.SYNC_TOKEN.isNotEmpty())
            val source = NotebookRepository(sourceDb); val target = NotebookRepository(targetDb)
            val customer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Preupload backup customer"))
            val sale = source.submit("save_sale", newId(), 0, sale(customer.id).put("date", "2095-05-11"))
            source.submit("add_receipt", sale.id, 1, payment("20000").put("date", "2095-05-12"))
            source.submit("save_sale", sale.id, 2, JSONObject().put("date", "2095-05-11").put("channel", "langsung")
                .put("amount", "105000").put("customerId", customer.id).put("reason", "Offline correction before backup"))
            val original = source.dao.allOperations()
            file = NotebookBackup(context, source).create(password)
            assertTrue(source.synchronize(client))
            source.submit("save_sale", sale.id, 3, JSONObject().put("date", "2095-05-11").put("channel", "langsung")
                .put("amount", "110000").put("customerId", customer.id).put("reason", "Later source correction"))
            assertTrue(source.synchronize(client))
            val backup = NotebookBackup(context, target)
            file!!.inputStream().use { backup.inspect(it, password) }.use { backup.restore(it) }
            assertEquals(original, target.dao.allOperations())
            target.submit("save_sale", sale.id, 3, JSONObject().put("date", "2095-05-11").put("channel", "langsung")
                .put("amount", "120000").put("customerId", customer.id).put("reason", "Restored older device correction"))
            assertTrue(target.synchronize(client)); assertTrue(target.synchronize(client))
            assertTrue(original.all { old -> target.dao.allOperations().any { it.operationId == old.operationId && it.payload == old.payload && it.state == "acknowledged" } })
            assertEquals("version_conflict", target.dao.activeFor(sale.id).single().error)
            val local = target.dao.record(sale.id)!!
            assertEquals(120000L, BookRecord.parse(JSONObject(local.payload)).amount)
            assertEquals(110000L, BookRecord.parse(JSONObject(local.serverPayload!!)).amount)
            val remote = BookRecord.parse(client.get("/v1/records/${sale.id}").body)
            assertEquals(2, remote.receipts.size); assertEquals(60000L, remote.received); assertEquals(110000L, remote.amount)
            assertEquals(1, client.get("/v1/daily?date=2095-05-11").body.getJSONArray("records").length())
        } finally { file?.delete(); sourceDb.close(); targetDb.close() }
    }

    @Test fun restoredLostAcknowledgmentReplaysTheSamePaymentWithoutAnotherSaleOrReceipt() = runBlocking {
        val sourceDb = database(); val targetDb = database(); var file: File? = null
        val client = NotebookHttpClient("http://127.0.0.1:3001", BuildConfig.SYNC_TOKEN)
        var blockedId: String? = null
        try {
            check(BuildConfig.SYNC_TOKEN.isNotEmpty())
            val source = NotebookRepository(sourceDb); val target = NotebookRepository(targetDb)
            val customer = source.submitCustomer(newId(), 0, JSONObject().put("name", "Backup replay customer"))
            val record = source.submit("save_sale", newId(), 0, sale(customer.id).put("date", "2095-05-09"))
            assertTrue(source.synchronize(client))
            source.submit("add_receipt", record.id, 1, payment("20000").put("date", "2095-05-10"))
            val pending = source.dao.activeFor(record.id).single(); blockedId = record.id
            client.post("/test/acknowledgments", JSONObject().put("recordId", record.id).put("blocked", true))
            assertFalse(source.synchronize(client))
            file = NotebookBackup(context, source).create(password)
            val restore = NotebookBackup(context, target)
            file!!.inputStream().use { restore.inspect(it, password) }.use { restore.restore(it) }
            assertEquals(pending.payload, target.dao.activeFor(record.id).single().payload)
            assertEquals(pending.operationId, target.dao.activeFor(record.id).single().operationId)
            client.post("/test/acknowledgments", JSONObject().put("recordId", record.id).put("blocked", false)); blockedId = null
            assertTrue(target.synchronize(client)); assertTrue(target.dao.activeFor(record.id).isEmpty())
            val remote = BookRecord.parse(client.get("/v1/records/${record.id}").body)
            assertEquals(60000L, remote.received); assertEquals(2, remote.receipts.size)
            assertEquals(1, client.get("/v1/daily?date=2095-05-09").body.getJSONArray("records").length())
        } finally {
            blockedId?.let { client.post("/test/acknowledgments", JSONObject().put("recordId", it).put("blocked", false)) }
            file?.delete(); sourceDb.close(); targetDb.close()
        }
    }
}
