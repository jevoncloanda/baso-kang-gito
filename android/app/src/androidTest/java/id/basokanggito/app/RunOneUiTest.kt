package id.basokanggito.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import id.basokanggito.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** All writes and screenshots belong to the isolated validation app and synthetic Room records. */
class RunOneUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication
    private lateinit var previous: String

    @Before fun prepare() { previous = app.access.store.read().toString(); app.resetRepository() }
    @After fun finish() { app.access.store.write(JSONObject(previous)); app.access.refresh() }

    private fun screenshot(name: String) {
        saveImage(name, compose.onRoot().captureToImage().asAndroidBitmap())
    }
    private fun saveImage(name: String, bitmap: android.graphics.Bitmap) {
        val output = File(app.externalCacheDir, "run1-$name.png")
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/baso-run1-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
    }
    private fun assertNoKeyboard() {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        }
    }

    @Test fun focusedRefinementKeepsAmountEditingAndCustomerSearch() {
        runBlocking {
            app.repository.submitCustomer(newId(), 0, JSONObject().put("name", "Refinement Ayu"))
            app.repository.submitCustomer(newId(), 0, JSONObject().put("name", "Refinement Budi"))
            app.repository.submit("save_sale", newId(), 0, JSONObject().put("date", businessToday().toString()).put("channel", "langsung")
                .put("amount", "75000").put("paid", true).put("receiptId", newId()).put("method", "cash"))
        }
        compose.setContent { BasoTheme(false) { FunctionalNotebook() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add sale").fetchSemanticsNodes().isNotEmpty() }
        screenshot("refinement-journal")
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        val amount = compose.onNodeWithText("Sale amount")
        amount.performTextInput("123456")
        amount.performTextInputSelection(androidx.compose.ui.text.TextRange(3))
        amount.performTextInput("9")
        amount.assertTextContains("1239456")
        amount.performTextClearance(); amount.performTextInput("125000")
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle(); screenshot("refinement-sale")
        compose.onNodeWithText("GoFood").performScrollTo().performClick()
        compose.onNodeWithText("GoFood").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Customers & Bon").performClick()
        val search = compose.onNodeWithContentDescription(app.getString(R.string.customer_search))
        search.performTextInput("Ayu"); search.performImeAction()
        compose.onNodeWithText("Refinement Ayu").assertExists()
        compose.onNodeWithText("Refinement Budi").assertDoesNotExist()
        screenshot("refinement-search")
    }

    @Test fun keypadMasksInputBackspacesSubmitsOnceAndHonorsBusyDelayAndCover() {
        var busy by mutableStateOf(false)
        var seconds by mutableStateOf(0L)
        var covered by mutableStateOf(false)
        var recovered = false
        val submissions = mutableListOf<String>()
        compose.setContent { BasoTheme(false) { PinUnlockScreen(busy, seconds, covered,
            onUnlock = { submissions += it; busy = true }, onRecovery = { recovered = true }) } }
        compose.enterNotebookPin("12345")
        assertTrue(submissions.isEmpty())
        compose.onNodeWithContentDescription("5 of 6 digits entered").assertExists()
        compose.onNodeWithTag("pin-backspace").performScrollTo().performClick()
        compose.onNodeWithContentDescription("4 of 6 digits entered").assertExists()
        compose.enterNotebookPin("60")
        assertEquals(listOf("123460"), submissions)
        compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
        compose.onNodeWithText("123460").assertDoesNotExist()
        assertNoKeyboard()
        compose.runOnIdle { busy = false; seconds = 30 }
        compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
        compose.runOnIdle { seconds = 0 }
        compose.enterNotebookPin("12")
        compose.runOnIdle { covered = true }
        compose.onNodeWithContentDescription("0 of 6 digits entered").assertExists()
        compose.runOnIdle { covered = false }
        compose.onNodeWithText("Forgot PIN?").performScrollTo().performClick()
        assertTrue(recovered)
    }

    @Test fun actualVerifierRejectsIncorrectPinThrottlesAndKeepsRecovery() {
        val model = AccessViewModel(app)
        compose.setContent { BasoTheme(false) { AccessGate(model) {} } }
        compose.onNodeWithText(app.getString(R.string.pin_incorrect)).assertDoesNotExist()
        repeat(5) {
            compose.enterNotebookPin("000000")
            compose.waitUntil(10_000) { !model.busy.value && model.error.value == "pin_incorrect" }
        }
        assertFalse(model.unlocked.value)
        assertTrue(app.access.store.delaySeconds() > 0)
        compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.pin_incorrect)).assertIsDisplayed()
        assertNoKeyboard()
        compose.onNodeWithText("Forgot PIN?").performScrollTo().performClick()
        compose.onNodeWithTag("register_device").performScrollTo().assertIsDisplayed()
        assertTrue(app.access.state.value.hasPin)
    }

    @Test fun samsungScreensAndShoppingRowsUseRealIsolatedState() {
        val date = businessToday().toString()
        runBlocking {
            app.repository.submit("save_sale", newId(), 0, JSONObject().put("date", date).put("channel", "langsung").put("amount", "75000").put("paid", true).put("receiptId", newId()).put("method", "cash"))
            app.repository.submit("save_sale", newId(), 0, JSONObject().put("date", date).put("channel", "grabfood").put("amount", "180000").put("paid", false))
            app.repository.submit("save_expense", newId(), 0, JSONObject().put("date", date).put("items", JSONArray().put(JSONObject().put("id", newId()).put("name", "Daging").put("amount", "90000"))))
        }
        val model = AccessViewModel(app)
        compose.setContent { BasoTheme(false) { AccessGate(model) { FunctionalNotebook() } } }
        screenshot("pin")
        compose.unlockNotebookFixture()
        screenshot("journal")
        compose.onNodeWithText("Activity").performScrollTo()
        screenshot("journal-history")
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("Sale amount").performTextInput("200000")
        compose.onNodeWithText("Save").assertIsDisplayed()
        Espresso.closeSoftKeyboard()
        screenshot("sale")
        compose.onNodeWithText("GoFood").performScrollTo().performClick()
        compose.onNodeWithText("GoFood").assertIsSelected()
        compose.onNodeWithText("Direct").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("200000").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add shopping").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 1 name").performTextInput("Daging")
        compose.onNodeWithContentDescription("Item 1 amount").performTextInput("150000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Add item").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 2 name").performTextInput("Seledri")
        compose.onNodeWithContentDescription("Item 2 amount").performTextInput("20000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Add item").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 3 name").performTextInput("Bumbu")
        compose.onNodeWithContentDescription("Item 3 amount").performTextInput("30000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Remove item 2").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Undo remove").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Shopping total").performScrollTo()
        screenshot("expense")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { runBlocking { app.repository.dao.records().size == 4 } }
        val expense = runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.single { it.kind == "expense" && it.amount == 200000L } }
        assertEquals(listOf("Daging", "Seledri", "Bumbu"), expense.items.map { it.name })
        val icon = app.packageManager.getApplicationIcon(app.packageName)
        val bitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 192, 192)
        icon.draw(android.graphics.Canvas(bitmap))
        saveImage("launcher", bitmap)
    }

    @Test fun largeDarkIndonesianPinAndExpandedJournalRemainUsable() {
        var expanded by mutableStateOf(false)
        var largeForm by mutableStateOf(false)
        compose.setContent {
            val base = LocalContext.current
            val config = android.content.res.Configuration(LocalConfiguration.current).apply { setLocale(java.util.Locale("id")) }
            val current = LocalDensity.current
            // Density simulation needs a fresh layout scope; Room restores the sale draft.
            key(largeForm) {
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                LocalDensity provides Density(if (expanded && !largeForm) 1f else current.density, if (expanded && !largeForm) 1f else 2f)) {
                BasoTheme(true) {
                    if (!expanded) PinUnlockScreen(false, 0, onUnlock = {}, onRecovery = {})
                    else FunctionalNotebook()
                }
            }
            }
        }
        compose.onNodeWithTag("pin-digit-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lupa PIN?").performScrollTo().assertIsDisplayed()
        assertNoKeyboard()
        screenshot("pin-id-dark-large")
        compose.runOnIdle { expanded = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("journal-expanded").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        screenshot("journal-id-dark-expanded-simulation")
        compose.onNodeWithText("Catat penjualan").performClick()
        compose.onNodeWithText("Jumlah penjualan").performTextInput("65000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Simpan").assertIsDisplayed()
        screenshot("sale-id-dark-expanded-simulation")
        compose.runOnIdle { largeForm = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("65000").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("65000").assertExists()
        screenshot("sale-id-dark-large")
        compose.onNodeWithText("Simpan").assertIsDisplayed()
    }
}
