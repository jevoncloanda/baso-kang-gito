package id.basokanggito.app.data

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** All installation authority lives outside Room and the portable backup allowlist. */
class DeviceAccessStore(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(context.noBackupFilesDir, "device-access"))
    private val alias = "notebook-device-access-v1"
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    @Synchronized fun update(change: (JSONObject) -> Unit): JSONObject = read().also { change(it); write(it) }
    // Explicit recovery replaces only inaccessible authorization; never the notebook database.
    @Synchronized fun recoverStorage() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        write(JSONObject())
    }
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    @Synchronized fun delaySeconds(): Long {
        val state = read(); if (state.optInt("failures") < 5) return 0
        if (state.optInt("boot", -2) != boot() || state.optLong("attemptUptime") > SystemClock.elapsedRealtime()) {
            state.put("boot", boot()).put("attemptUptime", SystemClock.elapsedRealtime())
                .put("until", SystemClock.elapsedRealtime() + PinProtection.delayMillis(state.optInt("failures")))
            write(state)
        }
        return ((state.optLong("until") - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
    }
    @Synchronized fun verify(pin: String): Boolean {
        require(PinProtection.valid(pin)) { "pin_format" }
        require(delaySeconds() == 0L) { "pin_delayed" }
        val state = read(); require(state.has("pinHash")) { "pin_missing" }
        // Persist the attempt before expensive verification, including interruption/process death.
        val failures = (state.optInt("failures") + 1).coerceAtMost(30)
        state.put("failures", failures).put("boot", boot()).put("attemptUptime", SystemClock.elapsedRealtime())
            .put("until", SystemClock.elapsedRealtime() + PinProtection.delayMillis(failures))
        write(state)
        val matches = MessageDigest.isEqual(decode(state.getString("pinHash")), PinProtection.derive(pin, decode(state.getString("pinSalt"))))
        if (matches) { state.put("failures", 0).put("until", 0); write(state) }
        return matches
    }
    @Synchronized fun setPin(pin: String, confirmation: String) {
        require(PinProtection.valid(pin)) { "pin_format" }; require(pin == confirmation) { "pin_confirmation" }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        update { it.put("pinSalt", encode(salt)).put("pinHash", encode(PinProtection.derive(pin, salt))).put("failures", 0).put("until", 0) }
    }
    companion object {
        fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
}

object PinProtection {
    fun valid(pin: String) = pin.length == 6 && pin.all { it in '0'..'9' }
    fun delayMillis(failures: Int): Long = if (failures < 5) 0 else (30_000L * (1L shl (failures - 5).coerceAtMost(5))).coerceAtMost(900_000L)
    fun derive(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray(); val spec = PBEKeySpec(chars, salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { chars.fill('\u0000'); spec.clearPassword() }
    }
}

data class AccessState(val enrolled: Boolean = false, val hasPin: Boolean = false, val ready: Boolean = false,
    val blocked: Boolean = false, val storageFailure: Boolean = false, val pending: Boolean = false, val code: String = "", val role: String = "", val installationId: String = "", val name: String = "")

fun HttpResult.accessBody(): JSONObject {
    check(status == 200) { body.optString("error", "access_failed") }
    return body
}

class DeviceAccess(private val context: Context) {
    val store = DeviceAccessStore(context)
    val state = MutableStateFlow(AccessState())
    init { refresh() }
    fun refresh() { state.value = try { store.read().let { AccessState(it.optBoolean("enrolled"), it.has("pinHash"), it.optBoolean("ready"),
        it.optBoolean("blocked"), false, it.optString("credential").isNotEmpty() && !it.optBoolean("enrolled"), it.optString("code"), it.optString("role"), it.optString("credential").substringBefore('.'), it.optString("name")) } }
        catch (_: Exception) { AccessState(storageFailure = true) } }
    fun usable() = state.value.let { it.enrolled && it.hasPin && it.ready && !it.blocked && !it.storageFailure }
    fun client(): NotebookHttpClient {
        val token = store.read().getString("credential")
        return NotebookHttpClient(id.basokanggito.app.BuildConfig.SYNC_URL, token) { status, result ->
            if (status == 401 || result.optString("error") == "access_revoked") {
                store.update { if (it.optString("credential") == token) it.put("blocked", true) }; refresh()
            }
        }
    }
    suspend fun begin(name: String, bootstrap: String, recovery: Boolean) = withContext(Dispatchers.IO) {
        if (state.value.storageFailure) store.recoverStorage()
        var data = store.read()
        if (!state.value.pending) {
            require(name.trim().length in 1..80) { "device_name" }
            // Persist proof before sending, so a lost response can be retried verbatim.
            val proof = "${UUID.randomUUID()}.${DeviceAccessStore.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })}"
            data = JSONObject().put("credential", proof).put("name", name.trim()).put("purpose", if (recovery) "pin_recovery" else "device")
            store.write(data)
            refresh()
        }
        val client = NotebookHttpClient(id.basokanggito.app.BuildConfig.SYNC_URL, data.getString("credential"))
        // Provisioning may have committed even if its response was lost days ago.
        val existing = client.get("/v1/access/me")
        if (existing.status == 200) { activate(existing.body); refresh(); return@withContext }
        val result = if (bootstrap.isNotEmpty()) client.post("/v1/access/bootstrap", JSONObject().put("name", data.getString("name")).put("token", bootstrap))
            else client.post("/v1/access/enrollments", JSONObject().put("name", data.getString("name")).put("purpose", data.getString("purpose")))
        require(result.status == 200) { if (result.status == 429) "access_rate" else "access_failed" }
        if (bootstrap.isNotEmpty()) activate(result.body) else store.update { it.put("code", result.body.optString("code")).put("enrollmentState", result.body.getString("state")) }
        refresh()
    }
    private fun activate(result: JSONObject) { store.update {
        require(result.getString("id") == it.getString("credential").substringBefore('.') && result.getString("role") in setOf("admin", "standard"))
        it.put("enrolled", true).put("role", result.getString("role")).put("blocked", false).put("ready", false)
    } }
    suspend fun poll() = withContext(Dispatchers.IO) {
        val client = NotebookHttpClient(id.basokanggito.app.BuildConfig.SYNC_URL, store.read().getString("credential"))
        val existing = client.get("/v1/access/me")
        if (existing.status == 200) { activate(existing.body); refresh(); return@withContext }
        val result = client.post("/v1/access/enrollment/status", JSONObject())
        require(result.status == 200) { "access_failed" }
        when (result.body.getString("state")) {
            "approved", "active" -> { val complete = client.post("/v1/access/enrollment/complete", JSONObject()); require(complete.status == 200) { "access_failed" }; activate(complete.body) }
            "expired", "rejected" -> throw IllegalStateException("access_expired")
        }
        refresh()
    }
    fun restartEnrollment() { store.write(JSONObject()); refresh() }
    fun ready() { store.update { it.put("ready", true) }; refresh(); scheduleSync(context) }
}

class AccessViewModel(application: Application) : AndroidViewModel(application) {
    val access = (application as NotebookApplication).access
    val unlocked = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val delay = MutableStateFlow(0L)
    private var backgroundAt: Long? = null
    val covered = MutableStateFlow(false)
    fun background() { if (backgroundAt == null) backgroundAt = SystemClock.elapsedRealtime(); covered.value = true }
    fun foreground() { if (backgroundAt?.let { SystemClock.elapsedRealtime() - it >= 30_000 } == true) unlocked.value = false; backgroundAt = null; covered.value = false }
    fun run(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = ""
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; access.refresh() }
            catch (_: java.io.IOException) { error.value = "access_connection" }
            catch (failure: Exception) { error.value = failure.message?.takeIf { it in setOf("pin_format", "pin_confirmation", "pin_incorrect", "pin_delayed", "access_expired", "access_rate", "device_name", "last_administrator", "incorrect_pairing_code", "enrollment_unavailable", "too_many_attempts") } ?: "access_failed" }
            finally { access.refresh(); delay.value = runCatching { access.store.delaySeconds() }.getOrDefault(0); busy.value = false }
        }
    }
    fun unlock(pin: String) = run { require(access.store.verify(pin)) { "pin_incorrect" }; unlocked.value = true }
    fun setup(pin: String, confirmation: String) = run { access.store.setPin(pin, confirmation); unlocked.value = true }
    fun change(old: String, pin: String, confirmation: String, complete: () -> Unit) = run {
        require(access.store.verify(old)) { "pin_incorrect" }; access.store.setPin(pin, confirmation); complete()
    }
}
