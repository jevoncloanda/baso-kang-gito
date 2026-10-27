package id.basokanggito.app

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeviceAccessProcessTest {
    @Test fun credentialsPinAndPendingMoneySurviveProcessRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".access.validation"))
        val stage = InstrumentationRegistry.getArguments().getString("stage", "roundtrip")
        require(stage in listOf("seed", "verify", "roundtrip"))
        val folder = File(context.noBackupFilesDir, "access-process-fixture")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = folder
        }
        val databaseName = "access-process-fixture.db"
        if (stage != "verify") {
            check(!folder.exists() && !context.getDatabasePath(databaseName).exists()) { "Verify the previous owned access fixture first" }
            check(folder.mkdirs())
            val access = DeviceAccess(isolated)
            val token = testControlClient().post("/test/bootstrap", JSONObject()).body.getString("token")
            access.begin("Process-restart fixture", token, false)
            access.store.setPin("246810", "246810")
            val db = Room.databaseBuilder(context, NotebookDatabase::class.java, databaseName).build()
            try {
                val repository = NotebookRepository(db)
                repository.submit("save_sale", newId(), 0, JSONObject().put("date", "2098-12-23").put("channel", "langsung").put("amount", "35000")
                    .put("paid", true).put("receiptId", newId()).put("method", "cash"))
                repository.dao.putMeta(NotebookMeta("fixtureOperation", repository.dao.nextOperation()!!.payload))
                repository.dao.putMeta(NotebookMeta("fixtureDevice", access.state.value.installationId))
            } finally { db.close() }
        }
        if (stage != "seed") {
            val access = DeviceAccess(isolated)
            assertTrue(access.state.value.enrolled); assertTrue(access.store.verify("246810"))
            assertEquals(200, access.client().get("/v1/access/me").status)
            val db = Room.databaseBuilder(context, NotebookDatabase::class.java, databaseName).build()
            try {
                val repository = NotebookRepository(db); val pending = repository.dao.nextOperation()!!
                assertEquals(repository.dao.meta("fixtureOperation"), pending.payload)
                assertEquals(repository.dao.meta("fixtureDevice"), access.state.value.installationId)
                assertFalse(repository.synchronize(NotebookHttpClient("http://127.0.0.1:1", "offline")))
                assertEquals(pending, repository.dao.nextOperation())
                assertTrue(repository.synchronize(access.client())); assertTrue(repository.synchronize(access.client()))
                assertNull(repository.dao.nextOperation())
                assertEquals(35000L, BookRecord.parse(access.client().get("/v1/records/${pending.recordId}").body).received)
            } finally { db.close(); context.deleteDatabase(databaseName); folder.deleteRecursively() }
        }
    }
}
