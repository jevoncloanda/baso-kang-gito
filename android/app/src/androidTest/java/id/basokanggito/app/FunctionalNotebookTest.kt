package id.basokanggito.app

import android.view.WindowManager
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import id.basokanggito.app.ui.*
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import id.basokanggito.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.json.JSONObject

class FunctionalNotebookTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication
    @Before fun isolate() {
        app.resetRepository()
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private fun awaitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun assertSaleFieldOrder() {
        val customer = compose.onNodeWithText("Customer (optional)").fetchSemanticsNode().boundsInRoot
        val amount = compose.onNodeWithText("Sale amount").fetchSemanticsNode().boundsInRoot
        val channel = compose.onNodeWithText("Sales channel").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(customer.bottom <= amount.top)
        Assert.assertTrue(amount.bottom <= channel.top)
    }
    private fun screenshot(name: String) {
        // Keyboard actions belong to the form interactions, not a capture-only helper.
        compose.waitForIdle()
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/baso-functional-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
    @Test fun journalSelectionOverridesParkedFormDatesAndResetPreservesActiveDraft() {
        var reset by mutableStateOf("first-launch")
        compose.setContent { BasoTheme { FunctionalNotebook(reset) } }
        awaitText("Add sale")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.previous_date)).performScrollTo().performClick()
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("Sale amount").performTextInput("41000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.previous_date)).performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.repository.dao.meta("draft")?.let { JSONObject(it).getString("date") == businessToday().minusDays(2).toString() } == true } }
        compose.runOnIdle { reset = "return-after-midnight" }
        compose.waitForIdle()
        compose.waitUntil(5_000) { runBlocking { app.repository.dao.meta("draft")?.let { JSONObject(it).getString("date") == businessToday().minusDays(2).toString() } == true } }
        compose.onNodeWithText("41000").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("41000").assertExists()
        compose.waitUntil(5_000) { runBlocking { app.repository.dao.meta("draft")?.let { JSONObject(it).getString("date") == businessToday().toString() } == true } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.previous_date)).performScrollTo().performClick()
        compose.onNodeWithText("Add shopping").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 1 amount").performTextInput("25000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.next_date)).performScrollTo().performClick()
        compose.onNodeWithText("Add shopping").performScrollTo().performClick()
        compose.onNodeWithText("25000").assertExists()
        compose.waitUntil(5_000) { runBlocking { app.repository.dao.meta("draft")?.let { JSONObject(it).getString("date") == businessToday().toString() } == true } }
    }

    @Test fun authenticationSessionReturnsHomeButRecoversTheOriginalDatedDraft() {
        var session by mutableStateOf("authenticated-1")
        compose.setContent { BasoTheme { FunctionalNotebook(navigationSession = session) } }
        awaitText("Add sale")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.previous_date)).performClick()
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("Sale amount").performTextInput("41000")
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { runBlocking { app.repository.dao.meta("draft")?.let { JSONObject(it).getJSONObject("sale").getString("amount") == "41000" } == true } }
        val draft = runBlocking { JSONObject(app.repository.dao.meta("draft")!!) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Customers & Bon").performClick(); awaitText("Search name or phone")
        compose.runOnIdle { session = "authenticated-1" }
        compose.onNodeWithText("Search name or phone").assertExists()
        compose.runOnIdle { session = "authenticated-2" }
        awaitText("Add sale")
        compose.onAllNodesWithText("Search name or phone").assertCountEquals(0)
        compose.onNodeWithText("Continue unfinished entry").performClick()
        awaitText("Sale amount"); compose.onNodeWithText("41000").assertExists()
        val restored = runBlocking { JSONObject(app.repository.dao.meta("draft")!!) }
        Assert.assertEquals(draft.getString("id"), restored.getString("id"))
        Assert.assertEquals(draft.getString("date"), restored.getString("date"))
        Assert.assertTrue(runBlocking { app.repository.dao.records().isEmpty() })
    }

    @Test fun paidAndUnpaidSalesShoppingAndCorrectionUseRealRoomRecords() {
        runBlocking { app.repository.submitCustomer(newId(), 0, JSONObject().put("name", "Room Bon customer")) }
        runBlocking {
            for (name in listOf("Daging", "Bumbu")) app.repository.submitCatalog(shoppingMasterId(name), 0, JSONObject().put("name", name))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.unlockNotebookFixture()
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            awaitText("Add sale")
            compose.onNodeWithText("Add sale").performScrollTo().performClick()
            assertSaleFieldOrder()
            compose.onNodeWithText("Sale amount").performTextInput("25000")
            compose.onNodeWithText("Customer (optional)").performScrollTo().performTextInput("Room UI test")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick()
            awaitText("Saved on this device")
            Assert.assertEquals(1, runBlocking { app.repository.dao.records().size })
            val paid = runBlocking { BookRecord.parse(JSONObject(app.repository.dao.records().single().payload)) }
            Assert.assertEquals(25000L, paid.received)
            screenshot("paid-detail")
            compose.onNodeWithText("Edit entry").performScrollTo().performClick()
            assertSaleFieldOrder()
            compose.onNodeWithText("Sale amount").performTextClearance(); compose.onNodeWithText("Sale amount").performTextInput("30000")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("General customer").performScrollTo().performClick()
            awaitText("Room Bon customer"); compose.onNodeWithText("Room Bon customer").performClick()
            compose.onNodeWithText("Reason for correction").performScrollTo().performTextInput("Correct sale price")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick()
            awaitText("Edit entry")
            val corrected = runBlocking { BookRecord.parse(JSONObject(app.repository.dao.record(paid.id)!!.payload)) }
            Assert.assertEquals(30000L, corrected.amount); Assert.assertEquals(25000L, corrected.received)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Add sale").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextInput("40000")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("General customer").performScrollTo().performClick()
            awaitText("Room Bon customer"); compose.onNodeWithText("Room Bon customer").performClick()
            compose.onNodeWithText("Money received").performScrollTo().performClick()
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick(); awaitText("Edit entry")
            Assert.assertTrue(runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.any { it.amount == 40000L && it.receipts.isEmpty() } })
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Add shopping").performScrollTo().performClick()
            compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.catalog_select_item)).performScrollTo().performClick()
            awaitText("Daging"); compose.onNodeWithText("Daging").performClick()
            compose.onNodeWithContentDescription("Item 1 amount").performTextInput("150000")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Add item").performScrollTo().performClick()
            compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.catalog_select_item)).performScrollTo().performClick()
            awaitText("Bumbu"); compose.onNodeWithText("Bumbu").performClick()
            compose.onNodeWithContentDescription("Item 2 amount").performTextInput("25000")
            Espresso.closeSoftKeyboard(); screenshot("shopping")
            compose.onNodeWithText("Save").performClick(); awaitText("Edit entry")
            Assert.assertEquals(175000L, runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.single { it.kind == "expense" }.amount })
            val client = enrolledTestClient()
            val expenseId = runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.single { it.kind == "expense" }.id }
            testControlClient().post("/test/acknowledgments", JSONObject().put("recordId", expenseId).put("blocked", true))
            // Keep acknowledgment blocked even if HttpURLConnection retries internally.
            Assert.assertFalse(runBlocking { app.repository.synchronize(client) })
            scenario.recreate(); awaitText("Edit entry")
            compose.onNodeWithContentDescription("Back").performClick(); awaitText("Add sale")
            compose.onNodeWithTag("sales-total").assertTextEquals("Rp 70.000")
            Assert.assertTrue(testControlClient().post("/test/acknowledgments", JSONObject().put("recordId", expenseId).put("blocked", false)).body.getInt("attempts") > 0)
            Assert.assertTrue(runBlocking { app.repository.synchronize(client) })
            val persisted = client.get("/v1/daily?date=${businessToday()}").body
            Assert.assertEquals("70000", persisted.getJSONObject("totals").getString("sales"))
            Assert.assertEquals("25000", persisted.getJSONObject("totals").getString("received"))
            Assert.assertEquals("175000", persisted.getJSONObject("totals").getString("expenses"))
            Assert.assertEquals(3, persisted.getJSONArray("records").length())
            Assert.assertTrue(runBlocking { app.repository.synchronize(client) })
            Assert.assertEquals(3, client.get("/v1/daily?date=${businessToday()}").body.getJSONArray("records").length())
            screenshot("journal")
        }
    }

    @Test fun customerCreationFromSalePreservesPartialDraftAndCustomerSearchEditArchiveWorks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.unlockNotebookFixture()
            awaitText("Add sale"); compose.onNodeWithText("Add sale").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextInput("100000")
            compose.onNodeWithText("Amount received now (optional)").performScrollTo().performTextInput("40000")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("General customer").performScrollTo().performClick()
            compose.onNodeWithText("Add customer").performClick()
            compose.onNodeWithText("Customer name").performTextInput("UI Bon customer")
            compose.onNodeWithText("Phone (optional)").performTextInput("081911223344")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick(); awaitText("Amount received now (optional)")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitForIdle(); scenario.recreate(); awaitText("UI Bon customer")
            compose.onNodeWithText("UI Bon customer").assertExists()
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick(); awaitText("Edit entry")
            val sale = runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.single() }
            Assert.assertEquals(100000L, sale.amount); Assert.assertEquals(40000L, sale.received); Assert.assertNotNull(sale.customerId)
            screenshot("customer-partial-sale")
            compose.onNodeWithContentDescription("Back").performClick(); awaitText("Add sale")
            compose.onNodeWithText("Customers & Bon").performClick(); awaitText("Search name or phone")
            compose.onNodeWithText("Search name or phone").performTextInput("9112233")
            Espresso.closeSoftKeyboard(); awaitText("UI Bon customer"); compose.onNodeWithText("UI Bon customer").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("customer-detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("customer-detail").performScrollToIndex(1)
            awaitText("Edit customer"); compose.onNodeWithText("Edit customer").performScrollTo().performClick()
            compose.onNodeWithText("Customer name").performTextClearance(); compose.onNodeWithText("Customer name").performTextInput("UI renamed customer")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Archive customer").performScrollTo().performClick()
            compose.onNodeWithText("Archive customer").assertIsSelected()
            compose.onNodeWithText("Reason for correction").performScrollTo().performTextInput("Correct and archive test customer")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick(); awaitText("UI renamed customer")
            compose.onNodeWithTag("customer-detail").performScrollToIndex(1); awaitText("Edit customer")
            val customer = runBlocking { Customer.parse(JSONObject(app.repository.dao.customer(sale.customerId!!)!!.payload)) }
            Assert.assertTrue("Synthetic customer operation: " + runBlocking { app.repository.dao.activeFor(customer.id).last().payload }, customer.archived); Assert.assertEquals("UI renamed customer", customer.name)
            Assert.assertEquals("UI Bon customer", runBlocking { BookRecord.parse(JSONObject(app.repository.dao.record(sale.id)!!.payload)).customer })
            compose.onNodeWithTag("customer-detail").performScrollToIndex(3)
            compose.onNodeWithText("Record payment").performScrollTo().performClick(); awaitText("Amount received")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save payment").performClick(); awaitText("Edit entry")
            Assert.assertEquals(100000L, runBlocking { BookRecord.parse(JSONObject(app.repository.dao.record(sale.id)!!.payload)).received })
            Assert.assertEquals(1, runBlocking { app.repository.dao.records().size })
            screenshot("customer-settled-sale")
        }
    }

    @Test fun functionalCustomersAdaptFromExpandedToLargeTextDarkPhoneWithoutLosingDraft() {
        runBlocking { app.repository.submitCustomer(newId(), 0, JSONObject().put("name", "Expanded customer").put("phone", "0812345678")) }
        var density by mutableFloatStateOf(1f)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                BasoTheme(true) {
                    // A density simulation also needs changed constraints to remeasure BoxWithConstraints.
                    Box(Modifier.fillMaxSize().padding(end = if (density == 1f) 0.dp else 1.dp)) { FunctionalNotebook() }
                }
            }
        }
        awaitText("Add sale")
        compose.onNodeWithText("Customers & Bon").performClick()
        awaitText("Expanded customer"); compose.onNodeWithText("Expanded customer").performClick()
        awaitText("Edit customer")
        compose.onNodeWithTag("customer-split").assertIsDisplayed()
        screenshot("customers-expanded-dark")
        compose.runOnIdle { density = 3f; fontScale = 2f }
        compose.waitForIdle()
        compose.onNodeWithTag("customer-detail").performScrollToIndex(1)
        compose.onNodeWithText("Edit customer").performClick()
        compose.onNodeWithText("Customer name").performScrollTo().performTextClearance()
        compose.onNodeWithText("Customer name").performTextInput("Large text draft")
        Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").assertIsDisplayed()
        screenshot("customer-large-dark")
        compose.runOnIdle { density = 1f; fontScale = 1f }
        compose.onNodeWithText("Large text draft").assertExists()
    }

    @Test fun functionalCustomerSearchAndFormsHaveIndonesianLabels() {
        compose.setContent {
            val context = LocalContext.current; val configuration = LocalConfiguration.current
            val localized = remember { context.createConfigurationContext(Configuration(configuration).apply { setLocale(java.util.Locale.forLanguageTag("id")) }) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) { BasoTheme(false) { FunctionalNotebook() } }
        }
        awaitText("Catat penjualan")
        compose.onNodeWithText("Pelanggan & Bon").performClick()
        awaitText("Cari nama atau nomor telepon")
        compose.onNodeWithText("Tambah pelanggan").performClick()
        compose.onNodeWithText("Nama pelanggan").performTextInput("Pelanggan uji")
        compose.onNodeWithText("Nomor telepon (opsional)").performTextInput("08123456")
        Espresso.closeSoftKeyboard(); compose.onNodeWithText("Simpan").assertIsDisplayed()
        screenshot("customer-form-id")
    }
}
