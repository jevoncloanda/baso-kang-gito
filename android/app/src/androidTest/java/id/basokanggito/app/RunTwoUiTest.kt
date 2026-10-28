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
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import id.basokanggito.app.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.YearMonth

/** Synthetic records stay in the validation application's in-memory Room database. */
class RunTwoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication
    private lateinit var customerId: String
    private lateinit var saleId: String
    private val date get() = YearMonth.from(businessToday()).atDay(1).toString()

    @Before fun prepare(): Unit = runBlocking {
        app.resetRepository()
        customerId = newId(); saleId = newId()
        app.repository.submitCustomer(customerId, 0, JSONObject().put("name", "Run 2 Ayu").put("phone", "080000000001"))
        app.repository.submitCustomer(newId(), 0, JSONObject().put("name", "Run 2 Budi"))
        app.repository.submit("save_sale", saleId, 0, JSONObject().put("date", date).put("channel", "langsung").put("amount", "125000")
            .put("customerId", customerId).put("paid", true).put("paymentAmount", "40000").put("receiptId", newId()).put("method", "cash"))
        for ((day, value) in listOf(10 to "180000", 15 to "200000")) {
            app.repository.submit("save_sale", newId(), 0, JSONObject().put("date", YearMonth.from(businessToday()).atDay(day).toString())
                .put("channel", "grabfood").put("amount", value).put("paid", false))
        }
        app.repository.submit("save_expense", newId(), 0, JSONObject().put("date", date).put("items",
            JSONArray().put(JSONObject().put("id", newId()).put("name", "Synthetic shopping").put("amount", "30000"))))
    }

    private fun awaitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun record() = runBlocking { BookRecord.parse(JSONObject(app.repository.dao.record(saleId)!!.payload)) }
    private fun screenshot(name: String) {
        val output = File(app.externalCacheDir, "run2-$name.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/baso-run2-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
    }

    @Test fun customerSearchInstallmentsRecapAndDayNavigationPreserveMoney() {
        val count = runBlocking { app.repository.dao.records().size }
        compose.setContent { BasoTheme(false) { FunctionalNotebook() } }
        awaitText("Add sale")
        compose.onNodeWithText("Customers & Bon").performClick()
        awaitText("Run 2 Ayu")
        compose.onNodeWithText("Search name or phone").performTextInput("missing-customer")
        compose.onNodeWithText("No matching customers.").assertIsDisplayed()
        compose.onNodeWithText("Search name or phone").performTextClearance()
        compose.onNodeWithText("Search name or phone").performTextInput("0000000001")
        compose.onNodeWithText("Run 2 Ayu").assertExists()
        compose.onNodeWithText("Run 2 Budi").assertDoesNotExist()
        compose.onNodeWithText("Search name or phone").performTextClearance()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("No outstanding Bon").assertIsDisplayed()
        screenshot("customers")
        compose.onNodeWithText("Run 2 Ayu").performClick()
        awaitText("Edit customer")
        compose.onNodeWithText("Linked sales").assertExists()
        compose.onNodeWithText("Partially paid").assertExists()
        screenshot("customer-detail")
        compose.onNodeWithText("Record payment").performScrollTo().performClick()
        awaitText("Save payment")
        compose.onNodeWithText("Original sale").assertExists()
        compose.onNodeWithText("Previously received").assertExists()
        compose.onNodeWithText("Amount received").performTextClearance()
        compose.onNodeWithText("Amount received").performTextInput("40000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Run 2 Ayu").performScrollTo()
        screenshot("payment")
        compose.onNodeWithText("Remaining after payment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rp 45.000").assertExists()
        compose.onNodeWithText("Amount received").performScrollTo().performTextClearance()
        compose.onNodeWithText("Amount received").performTextInput("86000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Save payment").performClick()
        awaitText(app.getString(R.string.payment_constraint))
        assertEquals(40000L, record().received)
        compose.onNodeWithText("Amount received").performScrollTo().performTextClearance()
        compose.onNodeWithText("Amount received").performTextInput("40000")
        compose.onNodeWithText("Transfer").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Save payment").performClick()
        awaitText(app.getString(R.string.saved_local))
        assertEquals(80000L, record().received)
        assertEquals("transfer", record().receipts.last().method)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Record payment").performScrollTo().performClick()
        awaitText("Save payment")
        compose.onNodeWithText("Save payment").performClick()
        awaitText(app.getString(R.string.saved_local))
        assertEquals(125000L, record().received)
        assertEquals(125000L, record().amount)
        assertEquals(3, record().receipts.map { it.id }.distinct().size)
        assertEquals(count, runBlocking { app.repository.dao.records().size })
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Recap").performClick()
        awaitText("Sales minus shopping")
        compose.onNodeWithTag("monthly-chart").performScrollTo().assertIsDisplayed()
        screenshot("monthly-recap")
        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithText("No sales or shopping in this month.").assertExists()
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithTag("recap-day-$date").performScrollTo().performClick()
        awaitText("Add sale")
        compose.onNodeWithTag("sales-total").assertTextEquals("Rp 125.000")
    }

    @Test fun expandedCustomerDetailAndLargeIndonesianTextKeepActionsReachable() {
        val snapshot = runBlocking { app.repository.state.first { it.loaded } }
        var phone by mutableStateOf(false)
        compose.setContent {
            val base = LocalContext.current
            val current = LocalDensity.current
            val config = android.content.res.Configuration(LocalConfiguration.current).apply { setLocale(java.util.Locale("id")) }
            key(phone) {
                CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                    LocalDensity provides Density(if (phone) current.density else 1f, if (phone) 2f else 1f)) {
                    BasoTheme(true) { CustomersScreen(snapshot, customerId, false, "", {}, {}, {}, {}, {}, {}, {}) }
                }
            }
        }
        compose.onNodeWithTag("customer-split").assertIsDisplayed()
        compose.onAllNodesWithText("Run 2 Ayu").assertCountEquals(2)
        compose.runOnIdle { phone = true }
        compose.onNodeWithTag("customer-detail").performScrollToNode(hasText("Ubah pelanggan"))
        compose.onNodeWithText("Ubah pelanggan").assertIsDisplayed()
        compose.onNodeWithTag("customer-detail").performScrollToNode(hasText("Catat uang diterima"))
        compose.onNodeWithText("Catat uang diterima").assertIsDisplayed()
    }
}
