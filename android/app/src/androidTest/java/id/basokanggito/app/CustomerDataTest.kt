package id.basokanggito.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CustomerDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(name: String) = Room.databaseBuilder(context, NotebookDatabase::class.java, name).addMigrations(CustomerMigration).build()
    private fun customer(name: String) = JSONObject().put("name", name).put("phone", "08123456789")
    private fun sale(customerId: String, paid: Boolean = false) = JSONObject().put("date", "2094-03-01").put("channel", "langsung")
        .put("amount", "100000").put("customerId", customerId).put("paid", paid)
        .apply { if (paid) put("receiptId", newId()).put("method", "cash").put("paymentAmount", "40000") }
    private fun payment(value: String, date: String = "2094-03-02") = JSONObject().put("id", newId()).put("amount", value)
        .put("date", date).put("method", "transfer").put("reason", "Bon payment received")
    private suspend fun records(repository: NotebookRepository) = repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }

    @Test fun conflictingOfflineInstallmentsReopenOneAtATimeWithoutLosingLaterInput() = runBlocking {
        val name = "test-payment-recovery-${newId()}.db"; var db = database(name)
        val otherDb = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
        val client = enrolledTestClient()
        try {
            var first = NotebookRepository(db); val second = NotebookRepository(otherDb); val customerId = newId(); val id = newId()
            first.submitCustomer(customerId, 0, customer("Installment recovery"))
            first.submit("save_sale", id, 0, sale(customerId).put("date", "2094-04-02"))
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            first.submit("add_receipt", id, 1, payment("20000"))
            first.submit("add_receipt", id, 2, payment("10000", "2094-04-03"))
            second.submit("add_receipt", id, 1, payment("50000"))
            assertTrue(second.synchronize(client)); assertTrue(first.synchronize(client))
            assertEquals(listOf("failed", "pending"), first.dao.activeFor(id).map { it.state })
            var recovery = first.reopenPayment(id)
            assertEquals(1, recovery.getJSONArray("remaining").length())
            first.saveDraft(JSONObject().put("route", "receipt").put("id", id).put("version", 2)
                .put("receiptId", recovery.getJSONObject("data").getString("id")).put("receiptAmount", "15000")
                .put("receiptDate", "2094-04-04").put("receiptMethod", "cash").toString())
            db.close(); db = database(name); first = NotebookRepository(db)
            recovery = JSONObject(first.dao.meta("paymentRecovery")!!)
            assertEquals("15000", recovery.getJSONObject("data").getString("amount"))
            assertEquals("2094-04-04", recovery.getJSONObject("data").getString("date"))
            assertEquals("cash", recovery.getJSONObject("data").getString("method"))
            first.submit("add_receipt", id, 2, recovery.getJSONObject("data"))
            second.submit("add_receipt", id, 2, payment("5000"))
            assertTrue(second.synchronize(client)); assertTrue(first.synchronize(client))
            recovery = first.reopenPayment(id)
            assertEquals(1, recovery.getJSONArray("remaining").length())
            assertEquals("15000", recovery.getJSONObject("data").getString("amount"))
            first.submit("add_receipt", id, 3, recovery.getJSONObject("data"))
            val next = JSONObject(first.dao.meta("paymentRecovery")!!)
            assertEquals("10000", next.getJSONObject("data").getString("amount")); assertEquals("2094-04-03", next.getJSONObject("data").getString("date"))
            assertEquals(4, next.getInt("expectedVersion"))
            first.submit("add_receipt", id, 4, next.getJSONObject("data"))
            assertTrue(first.dao.meta("paymentRecovery").isNullOrEmpty())
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals(80000L, BookRecord.parse(JSONObject(second.dao.record(id)!!.payload)).received)
            assertEquals(3, first.dao.allOperations().count { it.recordId == id && it.state == "reviewed" })
        } finally { db.close(); otherDb.close(); context.deleteDatabase(name) }
    }

    @Test fun recoveredCustomerCreationUploadsBeforeItsEarlierDependentSale() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
        val client = enrolledTestClient()
        try {
            val repository = NotebookRepository(db); val id = newId(); val saleId = newId()
            repository.submitCustomer(id, 0, customer("Recovered dependency"))
            repository.submit("save_sale", saleId, 0, sale(id).put("date", "2094-04-01"))
            val creation = repository.dao.activeFor(id).single()
            repository.dao.updateOperation(creation.copy(state = "failed", error = "invalid_operation"))
            assertTrue(repository.synchronize(client))
            assertEquals("pending", repository.dao.activeFor(saleId).single().state)
            val recovered = repository.reopenCustomer(id)
            assertEquals(0, recovered.version)
            assertTrue(repository.synchronize(client))
            assertEquals(404, client.get("/v1/records/$saleId").status)
            repository.submitCustomer(id, 0, customer(recovered.name))
            repository.submitCustomer(id, 1, customer("Renamed before sale upload").put("reason", "Rename offline"))
            assertTrue(repository.synchronize(client))
            assertTrue(repository.dao.activeFor(id).isEmpty()); assertTrue(repository.dao.activeFor(saleId).isEmpty())
            assertEquals(id, client.get("/v1/records/$saleId").body.getString("customerId"))
            assertEquals("Recovered dependency", client.get("/v1/records/$saleId").body.getString("customer"))
        } finally { db.close() }
    }

    @Test fun offlineCustomerPartialSaleAndPaymentSurviveDatabaseReopenAtomically() = runBlocking {
        val name = "test-customer-durable-${newId()}.db"; var db = database(name)
        try {
            var repository = NotebookRepository(db); val id = newId(); val saleId = newId()
            repository.submitCustomer(id, 0, customer("Durable customer"))
            repository.submit("save_sale", saleId, 0, sale(id, paid = true))
            repository.submit("add_receipt", saleId, 1, payment("20000"))
            val operations = repository.dao.allOperations().map { it.operationId }
            assertTrue(runCatching { repository.submit("add_receipt", saleId, 2, payment("40001")) }.isFailure)
            assertEquals(operations, repository.dao.allOperations().map { it.operationId })
            db.close(); db = database(name); repository = NotebookRepository(db)
            assertEquals("Durable customer", Customer.parse(JSONObject(repository.dao.customer(id)!!.payload)).name)
            assertEquals(operations, repository.dao.allOperations().map { it.operationId })
            assertEquals("40000", customerOutstanding(id, records(repository)).toString())
            val report = totals(records(repository), "2094-03-01", "2094-03-03")
            assertEquals("100000", report.sales.toString()); assertEquals("60000", report.received.toString())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun roomOneToTwoMigrationPreservesLegacySnapshotsPendingBodiesAndDrafts() = runBlocking {
        val name = "test-customer-migration-${newId()}.db"; val id = newId(); val operationId = newId()
        val legacy = BookRecord(id, "sale", "2094-03-04", 1, 100000, channel = "langsung", customer = "Legacy name").json().apply { remove("customerId") }.toString()
        val body = JSONObject().put("id", operationId).put("recordId", id).put("expectedVersion", 0).put("action", "save_sale")
            .put("data", JSONObject().put("date", "2094-03-04").put("amount", "100000").put("channel", "langsung").put("paid", false)).toString()
        context.openOrCreateDatabase(name, 0, null).use { old ->
            old.execSQL("CREATE TABLE book_records (id TEXT NOT NULL PRIMARY KEY,date TEXT NOT NULL,payload TEXT NOT NULL,serverPayload TEXT)")
            old.execSQL("CREATE TABLE pending_operations (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,operationId TEXT NOT NULL,recordId TEXT NOT NULL,payload TEXT NOT NULL,beforePayload TEXT,state TEXT NOT NULL,error TEXT NOT NULL)")
            old.execSQL("CREATE INDEX index_pending_operations_recordId ON pending_operations(recordId)")
            old.execSQL("CREATE UNIQUE INDEX index_pending_operations_operationId ON pending_operations(operationId)")
            old.execSQL("CREATE TABLE notebook_meta (`key` TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")
            old.execSQL("INSERT INTO book_records VALUES(?,?,?,NULL)", arrayOf(id, "2094-03-04", legacy))
            old.execSQL("INSERT INTO pending_operations(operationId,recordId,payload,beforePayload,state,error) VALUES(?,?,?,NULL,'pending','')", arrayOf(operationId, id, body))
            old.execSQL("INSERT INTO notebook_meta VALUES('draft','unfinished legacy draft')")
            old.version = 1
        }
        val db = database(name)
        try {
            val repository = NotebookRepository(db)
            assertEquals(legacy, repository.dao.record(id)!!.payload); assertNull(records(repository).single().customerId)
            assertEquals(body, repository.dao.activeFor(id).single().payload)
            assertEquals("unfinished legacy draft", repository.dao.meta("draft")); assertTrue(repository.dao.customers().isEmpty())
            repository.submitCustomer(newId(), 0, customer("New after migration"))
            assertEquals(2, repository.dao.allOperations().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun twoRoomClientsReconcileCustomersBonLostAcknowledgementsAndRecoverableConflicts() = runBlocking {
        check(BuildConfig.SYNC_TOKEN.isNotEmpty())
        val firstName = "test-customer-a-${newId()}.db"; val secondName = "test-customer-b-${newId()}.db"
        var firstDb = database(firstName); val secondDb = database(secondName)
        val client = enrolledTestClient()
        try {
            var first = NotebookRepository(firstDb); val second = NotebookRepository(secondDb)
            val id = newId(); val independent = newId(); val saleId = newId()
            first.submitCustomer(id, 0, customer("Client A customer")); second.submitCustomer(independent, 0, customer("Client B customer"))
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client)); assertTrue(first.synchronize(client))
            assertNotNull(first.dao.customer(independent)); assertNotNull(second.dao.customer(id))
            first.submit("save_sale", saleId, 0, sale(id)); assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals("100000", customerOutstanding(id, records(second)).toString())
            second.submit("add_receipt", saleId, 1, payment("40000")); assertTrue(second.synchronize(client)); assertTrue(first.synchronize(client))
            assertEquals("60000", customerOutstanding(id, records(first)).toString())
            // Commit a payment while suppressing its acknowledgment, then reopen the durable client.
            first.submit("add_receipt", saleId, 2, payment("10000"))
            val lost = first.dao.activeFor(saleId).single()
            testControlClient().post("/test/acknowledgments", JSONObject().put("recordId", saleId).put("blocked", true))
            assertFalse(first.synchronize(client)); assertEquals(50000L, BookRecord.parse(client.get("/v1/records/$saleId").body).received)
            firstDb.close(); firstDb = database(firstName); first = NotebookRepository(firstDb)
            assertEquals(lost.operationId, first.dao.activeFor(saleId).single().operationId)
            assertEquals(lost.payload, first.dao.activeFor(saleId).single().payload)
            testControlClient().post("/test/acknowledgments", JSONObject().put("recordId", saleId).put("blocked", false))
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals(2, BookRecord.parse(JSONObject(second.dao.record(saleId)!!.payload)).receipts.size)
            // Concurrent customer edits preserve local input until explicit reopening.
            first.submitCustomer(id, 1, customer("Server customer name").put("reason", "Rename on A"))
            second.submitCustomer(id, 1, customer("Local customer name").put("reason", "Rename on B"))
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals("version_conflict", second.dao.activeFor(id).single().error)
            assertEquals("Local customer name", Customer.parse(JSONObject(second.dao.customer(id)!!.payload)).name)
            val reopened = second.reopenCustomer(id)
            assertEquals("Local customer name", reopened.name); assertEquals(2, reopened.version)
            assertEquals("Local customer name", JSONObject(second.dao.meta("customerDraft")!!).getString("name"))
            assertEquals("Server customer name", Customer.parse(JSONObject(second.dao.customer(id)!!.payload)).name)
            // Archiving on A while B is offline rejects only B's new linkage, preserving its sale.
            first.submitCustomer(id, 2, customer("Server customer name").put("archived", true).put("reason", "Archived on A"))
            assertTrue(first.synchronize(client))
            val rejectedId = newId(); second.submit("save_sale", rejectedId, 0, sale(id).put("date", "2094-03-05"))
            assertTrue(second.synchronize(client)); assertEquals("customer_unavailable", second.dao.activeFor(rejectedId).single().error)
            assertEquals(id, BookRecord.parse(JSONObject(second.dao.record(rejectedId)!!.payload)).customerId)
            // A settled debt on one device conflicts with the other device's stale payment.
            first.submit("add_receipt", saleId, 3, payment("50000", "2094-03-06"))
            second.submit("add_receipt", saleId, 3, payment("30000", "2094-03-07"))
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals("version_conflict", second.dao.activeFor(saleId).single().error)
            assertEquals(80000L, BookRecord.parse(JSONObject(second.dao.record(saleId)!!.payload)).received)
            val recovery = second.reopenPayment(saleId)
            assertEquals("30000", recovery.getJSONObject("data").getString("amount")); assertEquals("2094-03-07", recovery.getJSONObject("data").getString("date"))
            assertEquals(4, recovery.getInt("expectedVersion")); assertEquals(100000L, BookRecord.parse(JSONObject(second.dao.record(saleId)!!.payload)).received)
            assertTrue(runCatching { second.submit("add_receipt", saleId, 4, recovery.getJSONObject("data")) }.isFailure)
            assertEquals("30000", JSONObject(second.dao.meta("paymentRecovery")!!).getJSONObject("data").getString("amount"))
            assertEquals("0", customerOutstanding(id, records(first)).toString())
            val remote = client.get("/v1/daily?date=2094-03-01").body
            assertEquals("100000", remote.getJSONObject("totals").getString("sales")); assertEquals(1, remote.getJSONArray("records").length())
        } finally { firstDb.close(); secondDb.close(); context.deleteDatabase(firstName); context.deleteDatabase(secondName) }
    }
}
