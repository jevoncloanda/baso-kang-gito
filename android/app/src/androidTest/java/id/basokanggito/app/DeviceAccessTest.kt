package id.basokanggito.app

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import id.basokanggito.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class DeviceAccessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication
    private lateinit var previous: String
    private lateinit var folder: File
    private lateinit var isolated: android.content.Context
    @Before fun prepare() {
        previous = app.access.store.read().toString()
        folder = File(app.cacheDir, "access-test-${newId()}").apply { mkdirs() }
        isolated = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = folder
        }
        app.resetRepository()
    }
    @After fun finish() { app.access.store.write(JSONObject(previous)); app.access.refresh(); folder.deleteRecursively() }
    private fun bootstrap(access: DeviceAccess): String = runBlocking {
        val token = testControlClient().post("/test/bootstrap", JSONObject()).body.getString("token")
        access.begin("Samsung access test", token, false)
        assertTrue(access.state.value.enrolled)
        token
    }
    @Test fun pinStorageConfirmationChangesAndPersistentGuessDelay() {
        val store = DeviceAccessStore(isolated)
        assertThrows(IllegalArgumentException::class.java) { store.setPin("123456", "123455") }
        assertFalse(store.read().has("pinHash"))
        store.setPin("246810", "246810"); assertTrue(store.verify("246810"))
        assertFalse(String(File(folder, "device-access").readBytes()).contains("246810"))
        assertTrue(DeviceAccessStore(isolated).verify("246810"))
        store.setPin("135791", "135791"); assertFalse(store.verify("246810")); assertTrue(store.verify("135791"))
        repeat(5) { assertFalse(store.verify("000000")) }
        assertTrue(DeviceAccessStore(isolated).delaySeconds() in 1..30)
        assertThrows(IllegalArgumentException::class.java) { DeviceAccessStore(isolated).verify("135791") }
    }
    @Test fun bootstrapProvisioningAndSecretsSurviveReopenWithoutCloningIntoRoom() = runBlocking {
        val access = DeviceAccess(isolated); bootstrap(access)
        access.store.setPin("012345", "012345"); access.refresh()
        val restored = DeviceAccess(isolated)
        assertTrue(restored.state.value.enrolled); assertTrue(restored.store.verify("012345"))
        assertEquals(200, restored.client().get("/v1/access/me").status)
        assertTrue(app.repository.dao.metadata().none { it.value.contains(access.store.read().getString("credential")) })
        assertFalse(String(File(folder, "device-access").readBytes()).contains(access.store.read().getString("credential")))
    }
    @Test fun pairingBindsProofAndInterruptedCompletionRecovers() = runBlocking {
        val access = DeviceAccess(isolated)
        access.begin("Samsung second installation", "", false)
        val pending = access.state.value
        assertFalse(pending.enrolled); assertEquals(6, pending.code.length)
        val administrator = AccessViewModel(app)
        compose.setContent { BasoTheme(false) { DeviceManagementScreen({}, administrator) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pairing-input-${pending.installationId}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pairing-input-${pending.installationId}").performScrollTo().performTextInput(pending.code)
        compose.onNodeWithTag("approve-${pending.installationId}").performScrollTo().performClick()
        compose.waitUntil(10_000) { !administrator.busy.value }
        assertEquals("", administrator.error.value)
        // Server commits completion, client loses it before writing its local active marker.
        assertEquals(200, access.client().post("/v1/access/enrollment/complete", JSONObject()).status)
        assertFalse(access.state.value.enrolled)
        val reopened = DeviceAccess(isolated); reopened.poll()
        assertTrue(reopened.state.value.enrolled)
        assertEquals(pending.installationId, reopened.state.value.installationId)
        assertEquals("standard", reopened.state.value.role)
    }
    @Test fun pendingInitiationRetryUsesPersistedNameAfterProcessBoundary() = runBlocking {
        val access = DeviceAccess(isolated); access.begin("Persistent request name", "", false)
        val id = access.state.value.installationId; val code = access.state.value.code
        val reopened = DeviceAccess(isolated); reopened.begin("", "", false)
        assertEquals(id, reopened.state.value.installationId); assertEquals(code, reopened.state.value.code)
        assertFalse(reopened.state.value.enrolled)
    }
    @Test fun portableBackupRestoresMoneyWithoutCloningAuthorizationAndReplaysOnce() = runBlocking {
        val source = app.repository
        val saleId = newId()
        source.submit("save_sale", saleId, 0, JSONObject().put("date", "2098-11-23").put("channel", "langsung").put("amount", "25000")
            .put("paid", true).put("receiptId", newId()).put("method", "cash"))
        assertTrue(source.synchronize(app.access.client()))
        val original = source.dao.allOperations().single()
        val password = "Portable access recovery test".toCharArray()
        val exported = NotebookBackup(app, source).create(password)
        val targetDb = androidx.room.Room.inMemoryDatabaseBuilder(app, NotebookDatabase::class.java).build()
        try {
            val decoded = java.io.ByteArrayOutputStream()
            exported.inputStream().use { BackupCrypto.decrypt(it, decoded, password) }
            val plaintext = decoded.toString("UTF-8")
            assertFalse(plaintext.contains(app.access.store.read().getString("credential")))
            assertFalse(plaintext.contains("pinHash")); assertFalse(plaintext.contains("pinSalt"))
            val replacement = DeviceAccess(isolated); assertFalse(replacement.state.value.enrolled)
            val target = NotebookRepository(targetDb); val backup = NotebookBackup(app, target)
            val prepared = exported.inputStream().use { backup.inspect(it, password) }; backup.restore(prepared)
            assertEquals(original.operationId, target.dao.allOperations().single().operationId)
            assertEquals(original.payload, target.dao.allOperations().single().payload)
            assertFalse(replacement.state.value.enrolled)
            replacement.begin("Restored replacement", "", false)
            val pending = replacement.state.value
            assertEquals(200, app.access.client().post("/v1/access/enrollments/${pending.installationId}/decision", JSONObject().put("approve", true).put("code", pending.code)).status)
            replacement.poll(); replacement.store.setPin("246810", "246810"); replacement.refresh()
            assertTrue(target.synchronize(replacement.client()))
            assertEquals(25000L, BookRecord.parse(JSONObject(target.dao.record(saleId)!!.payload)).received)
            val totals = replacement.client().get("/v1/daily?date=2098-11-23").body.getJSONObject("totals")
            assertEquals("25000", totals.getString("sales")); assertEquals("25000", totals.getString("received"))
        } finally { exported.delete(); password.fill('\u0000'); targetDb.close() }
    }
    @Test fun revocationStopsSyncAndKeepsPendingFinancialOperations() = runBlocking {
        val access = DeviceAccess(isolated); access.begin("Revoke synthetic client", "", false)
        val state = access.state.value
        assertEquals(200, app.access.client().post("/v1/access/enrollments/${state.installationId}/decision", JSONObject().put("approve", true).put("code", state.code)).status)
        access.poll(); access.store.setPin("012345", "012345"); access.store.update { it.put("ready", true) }; access.refresh()
        val repository = app.repository
        val record = repository.submit("save_sale", newId(), 0, JSONObject().put("date", "2098-02-01").put("channel", "langsung").put("amount", "25000").put("paid", true).put("receiptId", newId()).put("method", "cash"))
        val before = repository.dao.nextOperation()!!
        assertEquals(200, app.access.client().post("/v1/access/devices/${state.installationId}/revoke", JSONObject()).status)
        assertFalse(repository.synchronize(access.client())); assertTrue(access.state.value.blocked); assertFalse(access.usable())
        assertEquals(before, repository.dao.nextOperation()); assertNotNull(repository.dao.record(record.id))
        assertEquals("access_revoked", repository.dao.meta("syncError")); assertTrue(access.store.verify("012345"))
    }
    @Test fun pinSetupUnlockAndChangeScreensValidateActualVerifier() {
        app.access.store.update { it.remove("pinHash"); it.remove("pinSalt"); it.put("ready", false) }; app.access.refresh()
        val model = AccessViewModel(app)
        val changing = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { BasoTheme(false) { if (changing.value) ChangePinScreen({}, model) else AccessGate(model) { androidx.compose.material3.Text("Unlocked fixture") } } }
        compose.onNodeWithTag("pin_new").performTextInput("123456"); compose.onNodeWithTag("pin_confirm").performTextInput("123455")
        compose.onNodeWithTag("pin_save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("The PINs do not match.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pin_new").performTextInput("123456"); compose.onNodeWithTag("pin_confirm").performTextInput("123456")
        compose.onNodeWithTag("pin_save").performScrollTo().performClick()
        compose.waitUntil(10_000) { app.access.state.value.hasPin }
        assertTrue(app.access.store.verify("123456"))
        model.unlocked.value = false
        compose.onNodeWithTag("pin_unlock").performTextInput("123456"); compose.onNodeWithTag("unlock").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.unlocked.value }
        compose.runOnIdle { changing.value = true }
        compose.onNodeWithTag("pin_current").performTextInput("123456")
        compose.onNodeWithTag("pin_new").performTextInput("246810"); compose.onNodeWithTag("pin_confirm").performTextInput("246810")
        compose.onNodeWithText("Save PIN").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("PIN changed.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.access.store.verify("246810")); assertFalse(app.access.store.verify("123456"))
    }
    @Test fun backgroundGraceRotationAndRealLockKeepDraftAndSecureWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.unlockNotebookFixture()
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.onNodeWithText("Add sale").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextInput("65000")
            scenario.recreate()
            scenario.onActivity { assertTrue("Rotation must retain the unlocked session", androidx.lifecycle.ViewModelProvider(it)[AccessViewModel::class.java].unlocked.value) }
            assertTrue("Committed draft must survive rotation", runBlocking { app.repository.dao.meta("draft").orEmpty().contains("65000") })
            compose.waitUntil(10_000) { compose.onAllNodesWithText("65000").fetchSemanticsNodes().isNotEmpty() }
            scenario.moveToState(Lifecycle.State.CREATED); SystemClock.sleep(300); scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("65000").assertExists()
            scenario.moveToState(Lifecycle.State.CREATED); SystemClock.sleep(31_000); scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pin_unlock").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pin_unlock").performTextInput("135790"); compose.onNodeWithTag("unlock").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("65000").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    @Test fun pinScreensRenderInBothLanguagesAndLargeText() {
        app.access.store.update { it.remove("pinHash"); it.remove("pinSalt") }; app.access.refresh()
        val model = AccessViewModel(app)
        val large = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val config = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply { setLocale(java.util.Locale(if (large.value) "id" else "en")) }
            val current = androidx.compose.ui.platform.LocalContext.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides current.createConfigurationContext(config),
                androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, if (large.value) 2f else 1f)) {
                BasoTheme(large.value) { AccessGate(model) {} }
            }
        }
        compose.onNodeWithText("Create your PIN").assertIsDisplayed()
        saveImage("access-pin-en")
        compose.runOnIdle { large.value = true }
        compose.onNodeWithText("Buat PIN Anda").assertIsDisplayed()
        compose.onNodeWithTag("pin_save").performScrollTo().assertIsDisplayed()
        saveImage("access-pin-id-large")
    }
    private fun saveImage(name: String) {
        val bitmap = compose.onRoot().captureToImage()
        val output = File(app.externalCacheDir, "$name.png")
        output.outputStream().use { bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
