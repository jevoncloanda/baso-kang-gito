package id.basokanggito.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppCompatibilityTest {
    @Test fun requiredUpdateKeepsExactPendingOperationsAndCompatibleRetryAcknowledgesOnce() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
        val executor = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        try {
            val repository = NotebookRepository(db)
            val id = newId()
            val saved = repository.submit("save_sale", id, 0, JSONObject().put("date", "2026-11-04").put("channel", "gofood_mamat").put("amount", "41000").put("paid", false))
            val original = repository.dao.activeFor(id).single()
            fun policy(minimum: Int) = JSONObject().put("latestVersionCode", minimum).put("minimumSupportedVersionCode", minimum)
                .put("recommendedVersionCode", minimum).put("latestVersionName", "0.0.$minimum").put("downloadPageUrl", AppDownloadPage)
                .put("mandatoryUpdateMessage", "Perbarui aplikasi")
            val paths = listOf("/v1/app-version", "/v1/operations", "/v1/app-version", "/v1/operations", "/v1/shopping-items", "/v1/changes")
            val replies = listOf(policy(BuildConfig.VERSION_CODE), JSONObject().put("error", "APP_UPDATE_REQUIRED").put("policy", policy(BuildConfig.VERSION_CODE + 1)), policy(BuildConfig.VERSION_CODE),
                JSONObject().put("record", saved.json()).put("cursor", "1"),
                JSONObject().put("items", JSONArray()).put("revision", "0").put("next", JSONObject.NULL),
                JSONObject().put("changes", JSONArray()).put("cursor", "1").put("hasMore", false))
            val transport = executor.submit {
                paths.zip(replies).forEach { (path, body) -> server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream().bufferedReader()
                    assertTrue(input.readLine().split(" ")[1].startsWith(path))
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    assertTrue(headers.any { it.equals("X-App-Version-Code: ${BuildConfig.VERSION_CODE}", true) })
                    assertTrue(headers.any { it.equals("X-App-Variant: validation", true) })
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    val status = if (body.optString("error") == "APP_UPDATE_REQUIRED") "426 Upgrade Required" else "200 OK"
                    socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                } }
            }
            val client = NotebookHttpClient("http://127.0.0.1:${server.localPort}", "isolated-test")
            assertFalse(repository.synchronize(client))
            assertTrue(repository.updateRequired()); assertEquals("app_update_required", repository.dao.meta("syncError"))
            assertEquals(original, repository.dao.activeFor(id).single())
            assertEquals(saved, BookRecord.parse(JSONObject(repository.dao.record(id)!!.payload)))
            assertTrue(repository.dao.meta("lastSync").isNullOrEmpty())
            assertTrue(repository.synchronize(client, forceCompatibility = true))
            assertFalse(repository.updateRequired()); assertTrue(repository.dao.activeFor(id).isEmpty())
            assertEquals(original.operationId, repository.dao.allOperations().single().operationId)
            assertEquals("acknowledged", repository.dao.allOperations().single().state)
            transport.get(10, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow(); db.close() }
    }
}
