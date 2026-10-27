package id.basokanggito.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotebookDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(name: String) = Room.databaseBuilder(context, NotebookDatabase::class.java, name).build()
    private fun sale(date: String = "2091-01-31", paid: Boolean = false, channel: String = "langsung") = JSONObject()
        .put("date", date).put("channel", channel).put("amount", "100000").put("paid", paid)
        .apply { if (paid) put("receiptId", newId()).put("method", "cash") }
    private fun expense() = JSONObject().put("date", "2091-01-31").put("items", JSONArray(listOf(
        ShoppingItem(newId(), "Daging", 150000).json(), ShoppingItem(newId(), "Bumbu", 25000).json())))

    @Test fun roomRecordsDraftsOutboxAndTotalsSurviveDatabaseReopen() = runBlocking {
        val name = "test-durable-${newId()}.db"
        var db = database(name)
        try {
            var repository = NotebookRepository(db)
            repository.submit("save_sale", newId(), 0, sale(paid = true))
            repository.submit("save_sale", newId(), 0, sale(channel = "gofood"))
            repository.submit("save_expense", newId(), 0, expense())
            repository.saveDraft("durable unfinished draft")
            db.close(); db = database(name); repository = NotebookRepository(db)
            val records = repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }
            assertEquals(3, records.size); assertEquals(3, repository.dao.allOperations().size)
            assertEquals("durable unfinished draft", repository.dao.meta("draft"))
            val sum = totals(records, "2091-01-31", "2091-02-01")
            assertEquals("200000", sum.sales.toString()); assertEquals("100000", sum.received.toString()); assertEquals("175000", sum.expenses.toString())
            assertEquals("-75000", sum.cashDifference.toString())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun localValidationAndPaymentCorrectionKeepPriorOperationHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
        try {
            val repository = NotebookRepository(db); val id = newId()
            val first = repository.submit("save_sale", id, 0, sale(paid = true))
            assertTrue(runCatching { repository.submit("save_sale", id, 1, sale().apply { remove("paid");put("amount","50000");put("reason","Correction") }) }.isFailure)
            assertEquals(1, repository.dao.allOperations().size)
            val corrected = repository.submit("void", id, 1, JSONObject().put("receiptId", first.receipts[0].id).put("reason", "Wrong payment"))
            assertEquals(0L, corrected.received)
            assertEquals(2, repository.dao.allOperations().size)
            assertTrue(repository.dao.allOperations().last().beforePayload!!.contains(first.receipts[0].id))
        } finally { db.close() }
    }

    @Test fun twoRoomClientsSynchronizeWithPostgresAcrossOfflineLostAcknowledgementAndConflicts() = runBlocking {
        check(BuildConfig.SYNC_TOKEN.isNotEmpty()) { "Private integration API token is required" }
        val firstName = "test-client-a-${newId()}.db"; val secondName = "test-client-b-${newId()}.db"
        var firstDb = database(firstName); val secondDb = database(secondName)
        val client = enrolledTestClient()
        try {
            var first = NotebookRepository(firstDb); val second = NotebookRepository(secondDb)
            val expenseId = newId(); val paidId = newId(); val unpaidId = newId()
            first.submit("save_expense", expenseId, 0, expense())
            first.submit("save_sale", paidId, 0, sale(paid = true))
            first.submit("save_sale", unpaidId, 0, sale())
            assertEquals(3, first.dao.allOperations().size)
            assertFalse(first.synchronize(NotebookHttpClient("http://127.0.0.1:1", BuildConfig.SYNC_TOKEN)))
            assertEquals(3, first.dao.allOperations().count { it.state == "pending" })
            testControlClient().post("/test/acknowledgments", JSONObject().put("recordId", expenseId).put("blocked", true))
            // All automatic transport retries commit/replay but cannot acknowledge yet.
            assertFalse(first.synchronize(client))
            val pendingId = first.dao.nextOperation()!!.operationId
            assertEquals(175000L, BookRecord.parse(client.get("/v1/records/$expenseId").body).amount)
            firstDb.close(); firstDb = database(firstName); first = NotebookRepository(firstDb)
            assertEquals(pendingId, first.dao.nextOperation()!!.operationId)
            assertTrue(testControlClient().post("/test/acknowledgments", JSONObject().put("recordId", expenseId).put("blocked", false)).body.getInt("attempts") > 0)
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals(3, second.dao.records().count { it.date == "2091-01-31" })
            val postgres = client.get("/v1/daily?date=2091-01-31").body
            assertEquals("200000", postgres.getJSONObject("totals").getString("sales"))
            assertEquals("100000", postgres.getJSONObject("totals").getString("received"))
            assertEquals("175000", postgres.getJSONObject("totals").getString("expenses"))
            assertEquals(3, postgres.getJSONArray("records").length())
            assertTrue(first.synchronize(client)); assertEquals(3, client.get("/v1/daily?date=2091-01-31").body.getJSONArray("records").length())
            // Independent offline writes in both directions; uploads must not skip other clients' changes.
            val secondExpense = newId(); val firstSale = newId()
            second.submit("save_expense", secondExpense, 0, expense().put("date", "2091-02-02"))
            first.submit("save_sale", firstSale, 0, sale(date = "2091-02-02"))
            val unavailable = NotebookHttpClient("http://127.0.0.1:1", BuildConfig.SYNC_TOKEN)
            assertFalse(first.synchronize(unavailable)); assertFalse(second.synchronize(unavailable))
            assertTrue(second.synchronize(client)); assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertNotNull(first.dao.record(secondExpense)); assertNotNull(second.dao.record(firstSale))
            assertTrue(first.dao.activeFor(firstSale).isEmpty()); assertTrue(second.dao.activeFor(secondExpense).isEmpty())
            fun history(repository: NotebookRepository) = runBlocking {
                id.basokanggito.app.ui.presentation(NotebookState(records = repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) })).map { it.id }
            }
            assertEquals(history(first), history(second))
            assertEquals(2, client.get("/v1/daily?date=2091-02-02").body.getJSONArray("records").length())
            // An existing cache/cursor must also acquire timestamps without a new financial write.
            val known = first.dao.record(firstSale)!!
            val legacy = JSONObject(known.payload).apply { remove("createdAt") }.toString()
            first.dao.putRecord(known.copy(payload = legacy, serverPayload = legacy))
            val cursorBefore = first.dao.meta("cursor")!!.toBigInteger()
            first.dao.putMeta(NotebookMeta("chronologyVersion", ""))
            assertTrue(first.synchronize(client))
            assertTrue(first.dao.meta("cursor")!!.toBigInteger() >= cursorBefore)
            assertNotEquals(java.time.Instant.EPOCH.toString(), BookRecord.parse(JSONObject(first.dao.record(firstSale)!!.payload)).createdAt)
            assertEquals(history(first), history(second))
            val a = BookRecord.parse(JSONObject(first.dao.record(unpaidId)!!.payload))
            val b = BookRecord.parse(JSONObject(second.dao.record(unpaidId)!!.payload))
            fun correction(value: String) = sale().apply { remove("paid");put("amount",value);put("reason","Corrected on another device") }
            first.submit("save_sale", unpaidId, a.version, correction("110000"))
            second.submit("save_sale", unpaidId, b.version, correction("120000"))
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            val conflict = second.state.first { it.loaded }
            assertEquals(120000L, conflict.records.first { it.id == unpaidId }.amount)
            assertEquals(110000L, conflict.serverRecords.getValue(unpaidId).amount)
            assertTrue(conflict.pending.any { it.error == "version_conflict" })
            second.useServerVersion(unpaidId)
            assertEquals(110000L, BookRecord.parse(JSONObject(second.dao.record(unpaidId)!!.payload)).amount)
            assertTrue(second.dao.allOperations().any { it.state == "reviewed" })
            val badAuthId = newId(); second.submit("save_sale", badAuthId, 0, sale(date = "2091-02-01"))
            assertFalse(second.synchronize(NotebookHttpClient("http://127.0.0.1:3001", "wrong-token")))
            assertTrue(second.dao.activeFor(badAuthId).all { it.state == "pending" }); assertEquals("unauthorized", second.dao.meta("syncError"))
            second.retryFailed(); assertTrue(second.synchronize(client))
            assertTrue(second.dao.activeFor(badAuthId).isEmpty())
            // Server validation failure is retained for review, then corrected explicitly.
            val invalidExpenseId = newId()
            val invalidExpense = expense().put("date", "2091-02-03").put("note", "x".repeat(1001))
            second.submit("save_expense", invalidExpenseId, 0, invalidExpense)
            assertTrue(second.synchronize(client))
            assertEquals("invalid_operation", second.dao.activeFor(invalidExpenseId).single().error)
            val review = second.reopenFailed(invalidExpenseId)
            assertEquals(175000L, review.local.amount)
            second.submit("save_expense", invalidExpenseId, 0, invalidExpense.put("note", "Corrected note"))
            assertTrue(second.synchronize(client))
            assertTrue(second.dao.activeFor(invalidExpenseId).isEmpty())
            assertEquals(175000L, BookRecord.parse(client.get("/v1/records/$invalidExpenseId").body).amount)
        } finally { firstDb.close(); secondDb.close(); context.deleteDatabase(firstName); context.deleteDatabase(secondName) }
    }

    @Test fun failedCreateRecoveryPreservesPaymentsOrKeepsComplexChainsForReview() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
        try {
            val repository = NotebookRepository(db)
            val id = newId(); val paymentId = newId()
            repository.submit("save_sale", id, 0, sale(paid = true).put("method", "transfer").put("receiptId", paymentId))
            repository.dao.activeFor(id).forEach { repository.dao.updateOperation(it.copy(state = "failed", error = "invalid_operation")) }
            val edit = repository.reopenFailed(id)
            repository.saveDraft(JSONObject().put("id", id).put("recoveredEdit", edit.json()).toString())
            assertTrue(runCatching { repository.submit("save_sale", id, 0, sale(paid = true).put("amount", "120000")) }.isFailure)
            val retried = repository.submit("save_sale", id, 0, sale(paid = true).put("method", "transfer"))
            assertEquals(paymentId, retried.receipts.single().id)
            assertEquals("transfer", retried.receipts.single().method)
            val complexId = newId()
            repository.submit("save_sale", complexId, 0, sale())
            repository.submit("add_receipt", complexId, 1, JSONObject().put("id", newId()).put("date", "2091-02-01").put("amount", "40000").put("method", "transfer").put("reason", "Partial payment"))
            repository.dao.activeFor(complexId).forEach { repository.dao.updateOperation(it.copy(state = "failed", error = "invalid_operation")) }
            assertTrue(runCatching { repository.reopenFailed(complexId) }.isFailure)
            assertEquals(40000L, BookRecord.parse(JSONObject(repository.dao.record(complexId)!!.payload)).received)
            assertEquals(2, repository.dao.activeFor(complexId).size)
            val voidId = newId()
            val original = repository.submit("save_sale", voidId, 0, sale())
            repository.submit("save_sale", voidId, 1, sale().apply { remove("paid"); put("amount", "120000"); put("reason", "Correction") })
            val voided = repository.submit("void", voidId, 2, JSONObject().put("reason", "Mistaken entry"))
            repository.dao.putRecord(StoredRecord(voidId, voided.date, voided.json().toString(), original.copy(version = 2).json().toString()))
            repository.dao.activeFor(voidId).forEach { repository.dao.updateOperation(it.copy(state = "failed", error = "version_conflict")) }
            assertTrue(runCatching { repository.reopenFailed(voidId) }.isFailure)
            assertTrue(BookRecord.parse(JSONObject(repository.dao.record(voidId)!!.payload)).voided)
            assertEquals(3, repository.dao.activeFor(voidId).size)
        } finally { db.close() }
    }

    @Test fun failedEditRecoverySurvivesReopenBeforeUiDraftPersistence() = runBlocking {
        val name = "test-recovery-${newId()}.db"
        var db = database(name)
        try {
            var repository = NotebookRepository(db); val id = newId()
            val original = repository.submit("save_sale", id, 0, sale())
            repository.dao.allOperations().forEach { repository.dao.updateOperation(it.copy(state = "acknowledged")) }
            val local = repository.submit("save_sale", id, 1, sale().apply { remove("paid"); put("amount", "120000"); put("reason", "Keep my correction") })
            val server = original.copy(version = 2, amount = 110000)
            repository.dao.putRecord(StoredRecord(id, local.date, local.json().toString(), server.json().toString()))
            repository.dao.activeFor(id).forEach { repository.dao.updateOperation(it.copy(state = "failed", error = "version_conflict")) }
            assertTrue(runCatching { db.withTransaction { repository.reopenFailed(id); error("Interrupted before commit") } }.isFailure)
            assertEquals(120000L, BookRecord.parse(JSONObject(repository.dao.record(id)!!.payload)).amount)
            assertTrue(repository.dao.activeFor(id).any { it.state == "failed" })
            assertTrue(repository.dao.meta("recoveredEdit").isNullOrEmpty())
            repository.reopenFailed(id)
            // Stop at the former loss window: no UI callback/draft write has occurred.
            db.close(); db = database(name); repository = NotebookRepository(db)
            val recovered = RecoveredEdit.parse(repository.dao.meta("recoveredEdit")!!)
            assertEquals(120000L, recovered.local.amount); assertEquals(110000L, recovered.server!!.amount)
            assertEquals("Keep my correction", recovered.reason)
            assertEquals(110000L, BookRecord.parse(JSONObject(repository.dao.record(id)!!.payload)).amount)
            assertTrue(repository.dao.activeFor(id).isEmpty())
            assertTrue(repository.dao.allOperations().any { it.state == "reviewed" })
            repository.saveDraft(JSONObject().put("id", id).put("amount", "120000").toString())
            assertEquals("", repository.dao.meta("recoveredEdit"))
            assertEquals("120000", JSONObject(repository.dao.meta("draft")!!).getString("amount"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
