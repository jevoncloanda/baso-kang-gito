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
import androidx.test.platform.app.InstrumentationRegistry
import id.basokanggito.app.data.*
import id.basokanggito.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Screenshots and mutations belong only to the isolated validation installation. */
class RunThreeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication

    @Before fun prepare() {
        app.resetRepository()
        app.access.store.update { it.put("name", "Isolated UI administrator") }; app.access.refresh()
    }
    private fun awaitText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        val output = File(app.externalCacheDir, "run3-$name.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/baso-run3-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
    }

    @Test fun pinNotebookAndToolsNavigationPreserveRecordsAndSecurity() {
        val customer = newId()
        runBlocking {
            app.repository.submitCustomer(customer, 0, JSONObject().put("name", "Run 3 synthetic customer"))
            app.repository.submit("save_sale", newId(), 0, JSONObject().put("date", businessToday().toString()).put("channel", "langsung")
                .put("amount", "125000").put("customerId", customer).put("paid", false))
        }
        val before = runBlocking { app.repository.dao.records() }
        val access = AccessViewModel(app)
        compose.setContent { BasoTheme(false) { AccessGate(access) { FunctionalNotebook() } } }
        compose.unlockNotebookFixture()
        compose.onNodeWithTag("sales-total").assertTextEquals("Rp 125.000")
        screenshot("journal")
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        awaitText("Sale amount")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add shopping").performScrollTo().performClick()
        awaitText("Shopping items")
        compose.onNodeWithContentDescription("Item 1 name").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Customers & Bon").performClick()
        awaitText("Run 3 synthetic customer")
        compose.onNodeWithText("Run 3 synthetic customer").performScrollTo().performClick()
        awaitText("Linked sales")
        screenshot("customer-detail")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Recap").performClick()
        awaitText("Sales minus shopping")
        compose.onNodeWithText("Tools").performClick()
        awaitText("Tools & settings")
        screenshot("tools")
        compose.onNodeWithText("Backup & restore").performScrollTo().performClick()
        awaitText("Back up notebook")
        compose.onNodeWithTag("backup_password").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        screenshot("backup")
        compose.onNodeWithTag("backup_select").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.backup_restore_guidance)).assertIsDisplayed()
        screenshot("restore")
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
        awaitText("Tools & settings")
        compose.onNodeWithText("Change PIN").performScrollTo().performClick()
        awaitText("Current PIN")
        compose.onNodeWithTag("pin_current").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
        compose.onNodeWithText("Device management").performScrollTo().performClick()
        awaitText("Trusted devices")
        compose.onAllNodesWithText("Installation ID").onFirst().assertExists()
        // Capture public installation identifiers only; no token or pairing input is filled.
        compose.onNodeWithText("Trusted devices").performScrollTo()
        screenshot("devices")
        val id = app.access.state.value.installationId
        compose.onNodeWithTag("revoke-$id").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(app.access.state.value.blocked)
        assertEquals(before, runBlocking { app.repository.dao.records() })
        assertTrue(app.access.store.verify("135790"))
    }

    @Test fun toolsRespectRoleAndLargeIndonesianDarkLayout() {
        var administrator by mutableStateOf(false)
        var expanded by mutableStateOf(true)
        var deviceClicks = 0
        compose.setContent {
            val base = LocalContext.current
            val density = LocalDensity.current
            val config = android.content.res.Configuration(LocalConfiguration.current).apply { setLocale(java.util.Locale("id")) }
            key(expanded) {
                CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                    LocalDensity provides Density(if (expanded) 1f else density.density, if (expanded) 1f else 2f)) {
                    BasoTheme(true) { ToolsScreen(AccessState(name = "Perangkat keluarga dengan nama yang panjang", role = if (administrator) "admin" else "standard"), {}, {}, {}, { deviceClicks++ }) }
                }
            }
        }
        compose.onNodeWithText("Kelola perangkat").assertDoesNotExist()
        compose.runOnIdle { administrator = true; expanded = false }
        compose.onNodeWithText("Kelola perangkat").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, deviceClicks)
        compose.onNodeWithText("Ubah PIN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cadangkan & pulihkan").performScrollTo().assertIsDisplayed()
    }
}
