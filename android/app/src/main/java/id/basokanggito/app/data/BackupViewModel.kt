package id.basokanggito.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

class BackupViewModel(context: Context, private val notebook: NotebookViewModel) : ViewModel() {
    private val backup = NotebookBackup(context, notebook.repository)
    private val resolver = context.applicationContext.contentResolver
    val password = MutableStateFlow("")
    val confirmation = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val cleanupNeeded = MutableStateFlow(false)
    val selected = MutableStateFlow<Uri?>(null)
    val prepared = MutableStateFlow<PreparedBackup?>(null)
    val empty = MutableStateFlow(false)
    val chooseDestination = MutableStateFlow(false)
    val destinationName = MutableStateFlow("")
    private var export: File? = null
    @Volatile private var activePassword: CharArray? = null
    @Volatile private var cleared = false
    @Volatile private var abandoned = false

    private fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; message.value = ""; cleanupNeeded.value = false
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { message.value = (failure as? BackupException)?.code ?: "backup_io" }
            finally { busy.value = false; if (cleared || abandoned) cancel() }
        }
    }

    fun create() {
        if (busy.value) return
        if (password.value.length !in BackupCrypto.MinPasswordLength..512 || password.value != confirmation.value) {
            message.value = "backup_password_rules"; return
        }
        val secret = password.value.toCharArray()
        activePassword = secret
        password.value = ""; confirmation.value = ""
        work {
            try {
                export?.delete()
                export = notebook.withDrafts { backup.create(secret) }
                chooseDestination.value = true
            } finally { secret.fill('\u0000'); activePassword = null }
        }
    }

    fun write(destination: Uri?) {
        if (destination == null) { export?.delete(); export = null; return }
        work {
            val file = export ?: throw IOException()
            var verified = false
            try {
                val expected = MessageDigest.getInstance("SHA-256")
                resolver.openOutputStream(destination, "wt")?.use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            expected.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                } ?: throw IOException()
                val actual = MessageDigest.getInstance("SHA-256")
                var length = 0L
                resolver.openInputStream(destination)?.use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        length += count
                        if (length > file.length()) throw IOException()
                        actual.update(buffer, 0, count)
                    }
                } ?: throw IOException()
                if (length != file.length() || !MessageDigest.isEqual(expected.digest(), actual.digest())) throw IOException()
                verified = true
                destinationName.value = runCatching {
                    resolver.query(destination, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).orEmpty().take(200) else ""
                    }.orEmpty()
                }.getOrDefault("")
                notebook.repository.writeLock.withLock { notebook.repository.dao.putMeta(NotebookMeta("lastBackupAt", Instant.now().toString())) }
                message.value = "backup_exported"
            } finally {
                if (!verified) cleanupNeeded.value = !runCatching { DocumentsContract.deleteDocument(resolver, destination) }.getOrDefault(false)
                file.delete(); export = null
            }
        }
    }

    fun select(uri: Uri?) {
        if (uri == null) return
        prepared.value?.close(); prepared.value = null
        selected.value = uri; message.value = ""
        password.value = ""; confirmation.value = ""
    }

    fun inspect() {
        if (busy.value) return
        val uri = selected.value ?: return
        if (password.value.isEmpty() || password.value.length > 512) { message.value = "backup_password_rules"; return }
        val secret = password.value.toCharArray()
        activePassword = secret
        password.value = ""; confirmation.value = ""
        work {
            try {
                prepared.value?.close(); prepared.value = null
                val result = resolver.openInputStream(uri)?.use { backup.inspect(it, secret) } ?: throw IOException()
                try { empty.value = notebook.withDrafts { backup.isEmpty() }; prepared.value = result }
                catch (failure: Exception) { result.close(); throw failure }
            } finally { secret.fill('\u0000'); activePassword = null }
        }
    }

    fun restore() {
        val stage = prepared.value ?: return
        work {
            try {
                notebook.withDrafts { backup.restore(stage) }
                selected.value = null; message.value = "backup_restored"
            } finally { stage.close(); prepared.value = null }
        }
    }

    fun cancel() {
        if (busy.value) return
        password.value = ""; confirmation.value = ""
        prepared.value?.close(); prepared.value = null
        export?.delete(); export = null
        selected.value = null; chooseDestination.value = false; message.value = ""; cleanupNeeded.value = false
        destinationName.value = ""
    }

    fun enter() { abandoned = false }
    fun leave() { abandoned = true; if (!busy.value) cancel() }

    override fun onCleared() {
        cleared = true
        activePassword?.fill('\u0000'); activePassword = null
        password.value = ""; confirmation.value = ""
        prepared.value?.close(); export?.delete()
    }
}
