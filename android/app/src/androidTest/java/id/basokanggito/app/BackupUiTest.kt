package id.basokanggito.app

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import id.basokanggito.app.ui.BackupScreen
import id.basokanggito.app.ui.BasoTheme
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication
    private lateinit var notebook: NotebookViewModel
    private lateinit var backup: BackupViewModel

    @Before fun isolate() {
        app.resetRepository()
        notebook = NotebookViewModel(app.repository)
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            backup = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>) = BackupViewModel(app, notebook) as T
            })["backup", BackupViewModel::class.java]
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/baso-backup-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun nativeNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return result
    }

    private fun nativeDump(): String = nativeNodes().take(100).joinToString("\n") { node ->
        // Folder names and unrelated document titles may be private; report only control structure.
        "${node.packageName}/${node.viewIdResourceName} ${node.className} clickable=${node.isClickable} editable=${node.isEditable}"
    }

    private fun awaitNative(description: String, timeout: Long = 20_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            nativeNodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(150)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Document picker did not expose $description:\n${nativeDump()}")
    }

    private fun nativeClick(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Document picker control was not clickable:\n${nativeDump()}" }
    }

    private fun chooseDownloads() {
        fun inRoots(node: AccessibilityNodeInfo): Boolean {
            var parent = node.parent
            while (parent != null) {
                if (parent.viewIdResourceName?.endsWith("/roots_list") == true) return true
                parent = parent.parent
            }
            return false
        }
        val navigation = nativeNodes().firstOrNull {
            it.contentDescription?.toString() in listOf("Show roots", "Open navigation drawer", "Show navigation drawer", "Tampilkan akar", "Buka panel navigasi")
        }
        if (navigation != null) {
            nativeClick(navigation)
            nativeClick(awaitNative("Downloads location") { it.text?.toString() in listOf("Downloads", "Download", "Unduhan") && it.isEnabled && inRoots(it) })
        }
    }

    @Test fun passwordSurvivesActivityRecreationWithoutBeingStoredAsNotebookData() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.unlockNotebookFixture()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Tools").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Tools").performClick()
            compose.onNodeWithText("Backup & restore").performClick()
            compose.onNodeWithTag("backup_password").performScrollTo().performTextInput("Recreation-only passphrase")
            Espresso.closeSoftKeyboard()
            scenario.recreate()
            compose.onNodeWithTag("backup_password").performScrollTo()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                .assertTextContains("Recreation-only passphrase", substring = true)
            assertFalse(runBlocking { app.repository.dao.metadata().any { it.value.contains("Recreation-only passphrase") } })
        }
    }

    @Test fun systemDocumentPickersExportVerifyOpenAndRestoreEncryptedBackup() {
        val filename = "Baso-Test-${UUID.randomUUID()}.bkg"
        val passphrase = "Samsung picker roundtrip passphrase"
        var selectedDocument: Uri? = null
        try {
            compose.setContent { BasoTheme(false) { BackupScreen(notebook) {} } }
            compose.onNodeWithTag("backup_password").performScrollTo().performTextInput(passphrase)
            compose.onNodeWithTag("backup_password_confirmation").performScrollTo().performTextInput(passphrase)
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("backup_create").performScrollTo().performClick()
            compose.waitUntil(60_000) { !backup.busy.value }
            compose.waitForIdle()
            assertEquals("", backup.message.value)
            awaitNative("create-document filename") { it.isEditable && it.text?.toString()?.startsWith("Baso-Kang-Gito-Backup-") == true }
            chooseDownloads()
            val filenameField = awaitNative("filename editor") { it.isEditable && it.className?.toString()?.endsWith("EditText") == true }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename) }
            check(filenameField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "Could not name owned test document:\n${nativeDump()}" }
            nativeClick(awaitNative("Save button") { it.isEnabled && it.text?.toString()?.lowercase(Locale.ROOT) in listOf("save", "simpan") })
            compose.waitUntil(30_000) { backup.message.value == "backup_exported" && !backup.busy.value }
            compose.onNodeWithTag("backup_result").performScrollTo().assertTextContains("saved and verified", substring = true)
            screenshot("saf-export-verified")
            compose.onNodeWithTag("backup_select").performScrollTo().performClick()
            awaitNative("open-document picker") { node ->
                node.packageName?.toString()?.let { it.contains("documentsui", ignoreCase = true) || it.contains("myfiles", ignoreCase = true) } == true
            }
            chooseDownloads()
            nativeClick(awaitNative("owned backup document $filename") { it.text?.toString() == filename })
            compose.waitUntil(15_000) { backup.selected.value != null }
            selectedDocument = backup.selected.value
            compose.onNodeWithTag("backup_password").performScrollTo().performTextInput(passphrase)
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("backup_inspect").performScrollTo().performClick()
            compose.waitUntil(30_000) { backup.prepared.value != null && !backup.busy.value }
            compose.onNodeWithTag("backup_confirm").performScrollTo().assertIsEnabled().performClick()
            compose.onAllNodesWithText("Restore backup").onLast().performClick()
            compose.waitUntil(30_000) { backup.message.value == "backup_restored" && !backup.busy.value }
            compose.onNodeWithTag("backup_result").performScrollTo().assertTextContains("Backup restored", substring = true)
            assertTrue(runBlocking { app.repository.dao.records().isEmpty() })
            assertTrue(runBlocking { app.repository.dao.allOperations().isEmpty() })
            screenshot("saf-restore-complete")
        } finally {
            selectedDocument?.let { uri ->
                check(DocumentsContract.deleteDocument(app.contentResolver, uri)) { "Could not delete owned test document $filename" }
            }
        }
    }

    @Test fun passwordsAreMaskedByDefaultAndInvalidCreatePreservesNotebook() {
        compose.setContent { BasoTheme(false) { BackupScreen(notebook) {} } }
        val masked = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        fun renderedPassword(): String {
            var text = ""
            compose.onNodeWithTag("backup_password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(action(results)); text = results.single().layoutInput.text.text
            }
            return text
        }
        compose.onNodeWithTag("backup_password").performScrollTo().assert(masked).performTextInput("short")
        assertEquals("•••••", renderedPassword())
        compose.onNodeWithText("Show").performClick()
        assertEquals("short", renderedPassword())
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithTag("backup_password").assert(masked)
        assertEquals("•••••", renderedPassword())
        compose.onNodeWithTag("backup_password_confirmation").performScrollTo().performTextInput("different")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("backup_create").performScrollTo().performClick()
        compose.onNodeWithTag("backup_result").performScrollTo().assertTextContains("matching passwords", substring = true)
        assertTrue(runBlocking { app.repository.dao.records().isEmpty() })
        assertTrue(runBlocking { app.repository.dao.allOperations().isEmpty() })
        assertFalse(backup.chooseDestination.value)
        screenshot("password-validation-phone")
    }

    @Test fun localizedDarkExpandedAndLargeTextFormsKeepPasswordInMemory() {
        var density by mutableFloatStateOf(1f)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = remember { context.createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.forLanguageTag("id")) }) }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides compose.activity, LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density, fontScale)) {
                BasoTheme(true) {
                    Box(Modifier.fillMaxSize().padding(end = if (density == 1f) 0.dp else 1.dp)) { BackupScreen(notebook) {} }
                }
            }
        }
        compose.onNodeWithText("Cadangkan & pulihkan").assertExists()
        compose.onNodeWithTag("backup_password").performScrollTo().performTextInput("Frasa rahasia keluarga")
        Espresso.closeSoftKeyboard()
        screenshot("indonesian-dark-expanded-simulation")
        compose.runOnIdle { density = 3f; fontScale = 2f }
        compose.onNodeWithTag("backup_password").performScrollTo().assertIsDisplayed()
        assertEquals("Frasa rahasia keluarga", backup.password.value)
        compose.onNodeWithTag("backup_select").performScrollTo().assertIsDisplayed()
        screenshot("indonesian-dark-large-text-phone")
        compose.runOnIdle { density = 1f; fontScale = 1f }
        compose.onNodeWithTag("backup_password").performScrollTo().assertIsDisplayed()
        assertEquals("Frasa rahasia keluarga", backup.password.value)
    }

    @Test fun authenticatedPreviewRequiresConfirmationAndRechecksWorkspaceBeforeRestore() {
        val source = Room.inMemoryDatabaseBuilder(app, NotebookDatabase::class.java).build()
        val exported = try {
            runBlocking {
                val repository = NotebookRepository(source)
                val customer = newId()
                repository.submitCustomer(customer, 0, JSONObject().put("name", "Preview synthetic customer"))
                repository.submit("save_sale", newId(), 0, JSONObject().put("date", "2096-01-01").put("channel", "langsung")
                    .put("amount", "100000").put("paid", false).put("customerId", customer))
                NotebookBackup(app, repository).create("A long preview passphrase".toCharArray())
            }
        } finally { source.close() }
        try {
            compose.setContent { BasoTheme(false) { BackupScreen(notebook) {} } }
            compose.runOnIdle {
                backup.select(Uri.fromFile(exported))
                backup.password.value = "A long preview passphrase"
                backup.inspect()
            }
            compose.waitUntil(30_000) { !backup.busy.value && backup.prepared.value != null }
            compose.onNodeWithText("Review backup").assertExists()
            compose.onNodeWithTag("backup_confirm").performScrollTo().assertIsEnabled()
            assertTrue(runBlocking { app.repository.dao.records().isEmpty() })
            screenshot("authenticated-preview")
            // Work created after preview must still block the transactional restore.
            runBlocking { app.repository.submitCustomer(newId(), 0, JSONObject().put("name", "Keep newer local work")) }
            compose.onNodeWithTag("backup_confirm").performClick()
            compose.onAllNodesWithText("Restore backup").onLast().performClick()
            compose.waitUntil(30_000) { backup.message.value == "backup_not_empty" && !backup.busy.value }
            assertEquals(1, runBlocking { app.repository.dao.customers().size })
            assertTrue(runBlocking { app.repository.dao.records().isEmpty() })
            assertEquals("Keep newer local work", runBlocking { Customer.parse(JSONObject(app.repository.dao.customers().single().payload)).name })
            assertNull(backup.prepared.value)
        } finally { exported.delete() }
    }
}
