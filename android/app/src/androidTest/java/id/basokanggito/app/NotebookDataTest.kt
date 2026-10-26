package id.basokanggito.app

import androidx.room.Room
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
        val client = NotebookHttpClient("http://127.0.0.1:3001", BuildConfig.SYNC_TOKEN)
        try {
            var first = NotebookRepository(firstDb); val second = NotebookRepository(secondDb)
            val expenseId = newId(); val paidId = newId(); val unpaidId = newId()
            first.submit("save_expense", expenseId, 0, expense())
            first.submit("save_sale", paidId, 0, sale(paid = true))
            first.submit("save_sale", unpaidId, 0, sale())
            assertEquals(3, first.dao.allOperations().size)
            assertFalse(first.synchronize(NotebookHttpClient("http://127.0.0.1:1", BuildConfig.SYNC_TOKEN)))
            assertEquals(3, first.dao.allOperations().count { it.state == "pending" })
            // The test server commits the first expense and closes its response socket once.
            assertFalse(first.synchronize(client))
            val pendingId = first.dao.nextOperation()!!.operationId
            firstDb.close(); firstDb = database(firstName); first = NotebookRepository(firstDb)
            assertEquals(pendingId, first.dao.nextOperation()!!.operationId)
            assertTrue(first.synchronize(client)); assertTrue(second.synchronize(client))
            assertEquals(3, second.dao.records().count { it.date == "2091-01-31" })
            val postgres = client.get("/v1/daily?date=2091-01-31").body
            assertEquals("200000", postgres.getJSONObject("totals").getString("sales"))
            assertEquals("100000", postgres.getJSONObject("totals").getString("received"))
            assertEquals("175000", postgres.getJSONObject("totals").getString("expenses"))
            assertEquals(3, postgres.getJSONArray("records").length())
            assertTrue(first.synchronize(client)); assertEquals(3, client.get("/v1/daily?date=2091-01-31").body.getJSONArray("records").length())
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
            assertTrue(second.dao.activeFor(badAuthId).any { it.error == "unauthorized" })
            second.retryFailed(); assertTrue(second.synchronize(client))
            assertTrue(second.dao.activeFor(badAuthId).isEmpty())
        } finally { firstDb.close(); secondDb.close(); context.deleteDatabase(firstName); context.deleteDatabase(secondName) }
    }
}
