package id.basokanggito.app

import android.view.WindowManager
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
    @Before fun isolate() { app.resetRepository() }
    private fun awaitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun screenshot(name: String) {
        Espresso.closeSoftKeyboard(); compose.waitForIdle()
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/baso-functional-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
    @Test fun paidAndUnpaidSalesShoppingAndCorrectionUseRealRoomRecords() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            awaitText("Add sale")
            compose.onNodeWithText("Add sale").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextInput("25000")
            compose.onNodeWithText("Customer (optional)").performScrollTo().performTextInput("Room UI test")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick()
            awaitText("Saved on this device")
            Assert.assertEquals(1, runBlocking { app.repository.dao.records().size })
            val paid = runBlocking { BookRecord.parse(JSONObject(app.repository.dao.records().single().payload)) }
            Assert.assertEquals(25000L, paid.received)
            screenshot("paid-detail")
            compose.onNodeWithText("Edit entry").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextClearance(); compose.onNodeWithText("Sale amount").performTextInput("30000")
            compose.onNodeWithText("Reason for correction").performScrollTo().performTextInput("Correct sale price")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick()
            awaitText("Edit entry")
            val corrected = runBlocking { BookRecord.parse(JSONObject(app.repository.dao.record(paid.id)!!.payload)) }
            Assert.assertEquals(30000L, corrected.amount); Assert.assertEquals(25000L, corrected.received)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Add sale").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextInput("40000")
            compose.onNodeWithText("Money received").performScrollTo().performClick()
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Save").performClick(); awaitText("Edit entry")
            Assert.assertTrue(runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.any { it.amount == 40000L && it.receipts.isEmpty() } })
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Add shopping").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Item 1 name").performTextInput("Daging")
            compose.onNodeWithContentDescription("Item 1 amount").performTextInput("150000")
            Espresso.closeSoftKeyboard(); compose.onNodeWithText("Add item").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Item 2 name").performTextInput("Bumbu")
            compose.onNodeWithContentDescription("Item 2 amount").performTextInput("25000")
            Espresso.closeSoftKeyboard(); screenshot("shopping")
            compose.onNodeWithText("Save").performClick(); awaitText("Edit entry")
            Assert.assertEquals(175000L, runBlocking { app.repository.dao.records().map { BookRecord.parse(JSONObject(it.payload)) }.single { it.kind == "expense" }.amount })
            val client = NotebookHttpClient("http://127.0.0.1:3001", BuildConfig.SYNC_TOKEN)
            // The real test API commits this expense, then drops its first response.
            Assert.assertFalse(runBlocking { app.repository.synchronize(client) })
            scenario.recreate(); awaitText("Edit entry")
            compose.onNodeWithContentDescription("Back").performClick(); awaitText("Add sale")
            compose.onNodeWithTag("sales-total").assertTextEquals("Rp 70.000")
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
}
