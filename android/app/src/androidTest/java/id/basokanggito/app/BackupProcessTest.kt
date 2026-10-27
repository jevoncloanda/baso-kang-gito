package id.basokanggito.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Run stage=seed, force-stop only .validation, then stage=verify; default proves a normal DB reopen.
class BackupProcessTest {
    @Test fun restoredCustomerPaymentsDraftAndExactOutboxSurviveRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val name = "backup-process-proof.db"
        val stage = InstrumentationRegistry.getArguments().getString("stage", "roundtrip")
        require(stage in listOf("seed", "verify", "roundtrip"))
        fun database() = Room.databaseBuilder(context, NotebookDatabase::class.java, name).build()
        if (stage != "verify") {
            check(!context.getDatabasePath(name).exists()) { "Verify the previous owned backup fixture before repeating" }
            val source = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
            val target = database()
            try {
                val original = NotebookRepository(source)
                val customerId = newId()
                val saleId = newId()
                original.submitCustomer(customerId, 0, JSONObject().put("name", "Restored process customer").put("phone", "081200000123"))
                original.submit("save_sale", saleId, 0, JSONObject().put("date", "2096-02-01").put("channel", "langsung")
                    .put("amount", "100000").put("paid", false).put("customerId", customerId))
                original.submit("add_receipt", saleId, 1, JSONObject().put("id", newId()).put("date", "2096-02-02")
                    .put("amount", "40000").put("method", "cash").put("reason", "Synthetic partial payment"))
                val customerDraft = JSONObject().put("id", newId()).put("version", 0).put("name", "Unfinished restored customer")
                    .put("phone", "").put("address", "").put("notes", "").put("reason", "").put("archived", false).toString()
                original.saveCustomerDraft(customerDraft)
                val expected = original.dao.allOperations()
                val exported = NotebookBackup(context, original).create("Process recovery passphrase".toCharArray())
                try {
                    val restored = NotebookRepository(target)
                    val recovery = NotebookBackup(context, restored)
                    exported.inputStream().use { recovery.inspect(it, "Process recovery passphrase".toCharArray()) }.use { recovery.restore(it) }
                    assertEquals(expected, restored.dao.allOperations())
                    assertEquals(customerDraft, restored.dao.meta("customerDraft"))
                    // Store proof outside the backup allowlist only after restore, for the next process.
                    restored.dao.putMeta(NotebookMeta("proofBackupOperations", org.json.JSONArray(expected.map { operation ->
                        JSONObject().put("sequence", operation.sequence).put("id", operation.operationId).put("payload", operation.payload)
                            .put("before", operation.beforePayload ?: JSONObject.NULL).put("state", operation.state)
                    }).toString()))
                    restored.dao.putMeta(NotebookMeta("proofBackupCustomer", customerId))
                    restored.dao.putMeta(NotebookMeta("proofBackupSale", saleId))
                    restored.dao.putMeta(NotebookMeta("proofBackupDraft", customerDraft))
                } finally { exported.delete() }
            } finally { source.close(); target.close() }
        }
        if (stage != "seed") {
            val reopened = database()
            try {
                val repository = NotebookRepository(reopened)
                val operations = repository.dao.allOperations()
                val proof = org.json.JSONArray(repository.dao.meta("proofBackupOperations")!!)
                assertEquals(3, operations.size)
                operations.forEachIndexed { index, operation ->
                    val expected = proof.getJSONObject(index)
                    assertEquals(expected.getLong("sequence"), operation.sequence)
                    assertEquals(expected.getString("id"), operation.operationId)
                    assertEquals(expected.getString("payload"), operation.payload)
                    assertEquals(if (expected.isNull("before")) null else expected.getString("before"), operation.beforePayload)
                    assertEquals(expected.getString("state"), operation.state)
                }
                val customerId = repository.dao.meta("proofBackupCustomer")!!
                assertEquals("Restored process customer", Customer.parse(JSONObject(repository.dao.customer(customerId)!!.payload)).name)
                val sale = BookRecord.parse(JSONObject(repository.dao.record(repository.dao.meta("proofBackupSale")!!)!!.payload))
                assertEquals(customerId, sale.customerId)
                assertEquals(100000L, sale.amount)
                assertEquals(40000L, sale.received)
                assertEquals(1, sale.receipts.size)
                assertEquals(repository.dao.meta("proofBackupDraft"), repository.dao.meta("customerDraft"))
                assertEquals("0", repository.dao.meta("cursor"))
                assertEquals("", repository.dao.meta("lastSync"))
                assertFalse(repository.dao.meta("restoredAt").isNullOrBlank())
            } finally { reopened.close(); context.deleteDatabase(name) }
        }
    }
}
