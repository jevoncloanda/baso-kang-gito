package id.basokanggito.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import id.basokanggito.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class HttpResult(val status: Int, val body: JSONObject)
class NotebookHttpClient(private val baseUrl: String, private val token: String) {
    fun upload(operation: JSONObject) = request("/v1/operations", operation.toString())
    fun download(cursor: String) = request("/v1/changes?after=$cursor&limit=100")
    fun get(path: String) = request(path)
    private fun request(path: String, body: String? = null): HttpResult {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { reader ->
                val value = reader.readText(); if (value.length > 2_000_000) throw IOException("response_too_large"); value
            } ?: throw IOException("empty_response")
            return HttpResult(status, JSONObject(text))
        } finally { connection.disconnect() }
    }
}

open class NotebookApplication : Application() {
    open val backgroundSyncEnabled: Boolean get() = syncConfigured()
    // A single explicit construction point; instrumentation overrides it with an isolated database.
    open val repository: NotebookRepository by lazy {
        NotebookRepository(Room.databaseBuilder(this, NotebookDatabase::class.java, "bookkeeping.db").build()) { scheduleSync(this) }
    }
    override fun onCreate() {
        super.onCreate()
        if (backgroundSyncEnabled) {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("notebook-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NotebookSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            scheduleSync(this)
        }
    }
}
fun syncConfigured() = BuildConfig.DEBUG && BuildConfig.SYNC_TOKEN.isNotEmpty() && BuildConfig.SYNC_URL == "http://127.0.0.1:3000"
fun scheduleSync(context: Context) {
    if (!syncConfigured()) return
    WorkManager.getInstance(context).enqueueUniqueWork("notebook-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<NotebookSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
}
class NotebookSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!syncConfigured()) return@withContext Result.success()
        val repository = (applicationContext as NotebookApplication).repository
        if (repository.synchronize(NotebookHttpClient(BuildConfig.SYNC_URL, BuildConfig.SYNC_TOKEN))) Result.success() else Result.retry()
    }
}
