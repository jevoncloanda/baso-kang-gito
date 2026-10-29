package id.basokanggito.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import id.basokanggito.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class NavigationAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication
    @Before fun prepare() { app.resetRepository() }
    private fun awaitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun back() { compose.onNodeWithContentDescription("Back").performClick() }
    private fun draft(): JSONObject = runBlocking { JSONObject(app.repository.dao.meta("draft")!!) }

    @Test fun saleCreateAndEditStaySeparateAcrossRecreation() = checkForm("sale")
    @Test fun expenseCreateAndEditStaySeparateAcrossRecreation() = checkForm("expense")

    private fun checkForm(kind: String) {
        val sale = kind == "sale"
        val original = runBlocking {
            app.repository.submit(if (sale) "save_sale" else "save_expense", newId(), 0,
                if (sale) JSONObject().put("date", businessToday()).put("channel", "langsung").put("amount", "25000").put("paid", false).put("customer", "Original sale")
                else JSONObject().put("date", businessToday()).put("items", JSONArray().put(JSONObject().put("id", newId()).put("name", "Original shopping").put("amount", "25000"))))
        }
        val add = if (sale) "Add sale" else "Add shopping"
        val edit = if (sale) "Edit sale" else "Edit shopping"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.unlockNotebookFixture(); awaitText(add)
            compose.onNodeWithText(add).performScrollTo().performClick()
            if (sale) compose.onNodeWithText("Sale amount").performTextInput("37000")
            else {
                compose.onNodeWithContentDescription("Item 1 name").performTextInput("New shopping")
                compose.onNodeWithContentDescription("Item 1 amount").performTextInput("37000")
            }
            back(); awaitText(add)
            val createId = draft().getString("id")
            assertNotEquals(original.id, createId)
            compose.onNode(if (sale) hasText("Original sale") else hasText("Rp 25.000") and hasClickAction()).performScrollTo().performClick()
            awaitText("Edit entry"); compose.onNodeWithText("Edit entry").performScrollTo().performClick()
            awaitText(edit); assertEquals(original.id, draft().getString("id"))
            scenario.recreate(); awaitText(edit)
            back(); awaitText(add); compose.onNodeWithText(add).performScrollTo().performClick()
            compose.onNodeWithText("Reason for correction").assertDoesNotExist()
            assertEquals(createId, draft().getString("id")); assertEquals(0, draft().getInt("version"))
            compose.onNodeWithText("37000").assertExists()
            scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            compose.waitForIdle()
            compose.onNodeWithText("Save").performClick(); awaitText("Saved on this device")
            runBlocking {
                assertEquals(2, app.repository.dao.records().size)
                assertEquals(25000L, BookRecord.parse(JSONObject(app.repository.dao.record(original.id)!!.payload)).amount)
                assertEquals(37000L, BookRecord.parse(JSONObject(app.repository.dao.record(createId)!!.payload)).amount)
            }
            back(); awaitText(add)
            compose.onNode(if (sale) hasText("Original sale") else hasText("Rp 25.000") and hasClickAction()).performScrollTo().performClick()
            awaitText("Edit entry"); compose.onNodeWithText("Edit entry").performScrollTo().performClick()
            awaitText(edit); assertEquals(original.id, draft().getString("id"))
            val amount = if (sale) compose.onNodeWithText("Sale amount") else compose.onNodeWithContentDescription("Item 1 amount")
            amount.performTextClearance(); amount.performTextInput("28000")
            compose.onNodeWithText("Reason for correction").performScrollTo().performTextInput("Targeted correction")
            scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            compose.waitForIdle()
            compose.onNodeWithText("Save").performClick(); awaitText("Saved on this device")
            runBlocking {
                assertEquals(2, app.repository.dao.records().size)
                assertEquals(28000L, BookRecord.parse(JSONObject(app.repository.dao.record(original.id)!!.payload)).amount)
                assertEquals(37000L, BookRecord.parse(JSONObject(app.repository.dao.record(createId)!!.payload)).amount)
            }
            back(); awaitText(add); compose.onNodeWithText(add).performScrollTo().performClick()
            assertNotEquals(original.id, draft().getString("id")); assertNotEquals(createId, draft().getString("id"))
            compose.onNodeWithText("37000").assertDoesNotExist()
            compose.onNodeWithText("25000").assertDoesNotExist()
        }
    }

    @Test fun pinFeedbackKeepsIndicatorsAndKeysInPlace() {
        var busy by mutableStateOf(false)
        var message by mutableStateOf("")
        var seconds by mutableStateOf(0L)
        compose.setContent { BasoTheme(false) { PinUnlockScreen(busy, seconds, onUnlock = { busy = true }, onRecovery = {}, feedback = { Text(message) }) } }
        val indicators = compose.onNodeWithTag("pin_unlock").fetchSemanticsNode().boundsInRoot
        val key = compose.onNodeWithTag("pin-digit-1").fetchSemanticsNode().boundsInRoot
        compose.enterNotebookPin("123")
        assertEquals(indicators, compose.onNodeWithTag("pin_unlock").fetchSemanticsNode().boundsInRoot)
        compose.enterNotebookPin("456")
        compose.onNodeWithText(app.getString(R.string.pin_verifying)).assertExists()
        assertEquals(key, compose.onNodeWithTag("pin-digit-1").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { busy = false; message = app.getString(R.string.pin_incorrect) }
        assertEquals(indicators, compose.onNodeWithTag("pin_unlock").fetchSemanticsNode().boundsInRoot)
        assertEquals(key, compose.onNodeWithTag("pin-digit-1").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { seconds = 30; message = app.getString(R.string.pin_incorrect) + " 30" }
        assertEquals(key, compose.onNodeWithTag("pin-digit-1").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.pin_incorrect) + " 30").performScrollTo().assertIsDisplayed()
    }

    @Test fun toolsAppearancePersistsAndUpdatesWholeTheme() {
        val preferences = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val old = preferences.getString("theme", null)
        var systemDark by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        try {
            preferences.edit().remove("theme").commit()
            compose.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                key(generation) { CompositionLocalProvider(LocalConfiguration provides config) { BasoTheme {
                    Text(if (MaterialTheme.colorScheme.background == DarkColors.background) "Palette dark" else "Palette light")
                    ToolsScreen(app.access.state.value, {}, {}, {}, {})
                } } }
            }
            compose.onNodeWithText("Palette light").assertExists()
            compose.onNodeWithText("Appearance").performScrollTo().performClick()
            compose.onNodeWithText("Dark").performClick(); compose.onNodeWithText("Palette dark").assertExists()
            compose.runOnIdle { generation++ }
            compose.onNodeWithText("Palette dark").assertExists()
            assertEquals("DARK", preferences.getString("theme", null))
            compose.onNodeWithText("Appearance").performScrollTo().performClick()
            compose.onNodeWithText("Light").performClick()
            compose.runOnIdle { systemDark = true }
            compose.onNodeWithText("Palette light").assertExists()
            compose.onNodeWithText("Appearance").performScrollTo().performClick()
            compose.onNodeWithText("System default").performClick()
            compose.onNodeWithText("Palette dark").assertExists()
            compose.runOnIdle { systemDark = false; generation++ }
            compose.onNodeWithText("Palette light").assertExists()
            assertEquals("SYSTEM", preferences.getString("theme", null))
        } finally { preferences.edit().apply { if (old == null) remove("theme") else putString("theme", old) }.commit() }
    }
}
