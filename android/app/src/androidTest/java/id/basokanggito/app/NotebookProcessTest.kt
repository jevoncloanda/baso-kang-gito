package id.basokanggito.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

// Run with stage=seed, force-stop only the validation package, then stage=verify.
// Without an argument the normal suite exercises the same proof through a DB reopen.
class NotebookProcessTest {
    @Test fun recordsRecoveryAndScheduledWorkSurviveRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val name = "reliability-process-test.db"
        val stage = InstrumentationRegistry.getArguments().getString("stage", "roundtrip")
        require(stage in listOf("seed", "verify", "roundtrip"))
        fun database() = Room.databaseBuilder(context, NotebookDatabase::class.java, name).build()
        fun sale(value: String) = JSONObject().put("date", "2093-01-01").put("channel", "langsung").put("amount", value).put("paid", false)
        if (stage != "verify") {
            check(!context.getDatabasePath(name).exists()) { "Verify the previous owned lifecycle fixture before repeating" }
            val db = database()
            try {
                val repository = NotebookRepository(db)
                val correctionId = newId()
                val original = repository.submit("save_sale", correctionId, 0, sale("100000"))
                repository.dao.allOperations().forEach { repository.dao.updateOperation(it.copy(state = "acknowledged")) }
                val corrected = repository.submit("save_sale", correctionId, 1, sale("120000").apply { remove("paid"); put("reason", "Keep after process death") })
                repository.dao.putRecord(StoredRecord(correctionId, corrected.date, corrected.json().toString(), original.copy(version = 2, amount = 110000).json().toString()))
                repository.dao.activeFor(correctionId).forEach { repository.dao.updateOperation(it.copy(state = "failed", error = "version_conflict")) }
                repository.submit("save_sale", newId(), 0, sale("25000"))
                val customerId = newId(); val bonId = newId()
                repository.submitCustomer(customerId, 0, JSONObject().put("name", "Process durable customer"))
                repository.submit("save_sale", bonId, 0, sale("100000").put("date", "2093-01-02").put("customerId", customerId)
                    .put("paid", true).put("receiptId", newId()).put("method", "cash").put("paymentAmount", "40000"))
                repository.submit("add_receipt", bonId, 1, JSONObject().put("id", newId()).put("date", "2093-01-03").put("amount", "20000").put("method", "transfer").put("reason", "Installment"))
                repository.saveCustomerDraft("unfinished customer draft")
                repository.dao.putMeta(NotebookMeta("proofCustomer", customerId))
                repository.dao.putMeta(NotebookMeta("proofBon", bonId))
                repository.reopenFailed(correctionId)
                val pending = repository.dao.nextOperation()!!
                repository.dao.putMeta(NotebookMeta("proofOperation", pending.payload))
                val work = OneTimeWorkRequestBuilder<NotebookSyncWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
                WorkManager.getInstance(context).enqueue(work).result.get()
                repository.dao.putMeta(NotebookMeta("proofWork", work.id.toString()))
            } finally { db.close() }
        }
        if (stage != "seed") {
            val db = database()
            try {
                val repository = NotebookRepository(db)
                val pending = repository.dao.nextOperation()!!
                assertEquals(repository.dao.meta("proofOperation"), pending.payload)
                val recovery = RecoveredEdit.parse(repository.dao.meta("recoveredEdit")!!)
                assertEquals(120000L, recovery.local.amount)
                assertEquals("Keep after process death", recovery.reason)
                assertEquals(110000L, BookRecord.parse(JSONObject(repository.dao.record(recovery.local.id)!!.payload)).amount)
                val customerId = repository.dao.meta("proofCustomer")!!
                assertEquals("Process durable customer", Customer.parse(JSONObject(repository.dao.customer(customerId)!!.payload)).name)
                val bon = BookRecord.parse(JSONObject(repository.dao.record(repository.dao.meta("proofBon")!!)!!.payload))
                assertEquals(customerId, bon.customerId); assertEquals(60000L, bon.received)
                assertEquals("unfinished customer draft", repository.dao.meta("customerDraft"))
                val workId = UUID.fromString(repository.dao.meta("proofWork"))
                assertEquals(WorkInfo.State.ENQUEUED, WorkManager.getInstance(context).getWorkInfoById(workId).get()!!.state)
                assertFalse(repository.synchronize(NotebookHttpClient("http://127.0.0.1:1", BuildConfig.SYNC_TOKEN)))
                assertEquals(pending.operationId, repository.dao.nextOperation()!!.operationId)
                assertTrue(repository.synchronize(enrolledTestClient()))
                assertTrue(repository.dao.activeFor(pending.recordId).isEmpty())
                assertEquals(120000L, RecoveredEdit.parse(repository.dao.meta("recoveredEdit")!!).local.amount)
                WorkManager.getInstance(context).cancelWorkById(workId).result.get()
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
