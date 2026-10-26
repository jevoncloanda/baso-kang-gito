package id.basokanggito.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.app.LocaleManager
import android.os.LocaleList
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.core.app.ActivityScenario
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import id.basokanggito.app.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestWindowAwake() {
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedNotebookApplication).resetRepository()
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun screenshot(name: String) {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        // The shell capture is produced by the physical device, not an emulator or browser.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/baso-prototype-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun app(density: Float? = null, fontScale: Float = 1f, dark: Boolean = false, language: String = "en") {
        compose.setContent {
            val native = LocalDensity.current
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = remember(language, configuration) { context.createConfigurationContext(Configuration(configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }) }
            CompositionLocalProvider(LocalDensity provides Density(density ?: native.density, fontScale),
                LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                BasoTheme(dark) { BukuHarianApp() }
            }
        }
    }

    @Test
    fun compactNavigationAndDraftSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { BasoTheme(false) { BukuHarianApp() } }
        compose.onNodeWithTag("navigation-bottom").assertIsDisplayed()
        compose.onNodeWithText("Sample mode · Not business records").assertIsDisplayed()
        screenshot("phone")
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("Sale amount").performTextInput("25000")
        compose.onNodeWithText("Customer (optional)").performScrollTo().performTextInput("Bu Ani")
        Espresso.closeSoftKeyboard()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("25000").assertExists()
        screenshot("sale")
        compose.onNodeWithText("Save sample").performClick()
        compose.onNodeWithText("Customer: Bu Ani").assertIsDisplayed()
        compose.onNodeWithText("Edit sample").assertIsDisplayed()
        screenshot("detail")
    }

    @Test fun shoppingRowsCalculateRemoveAndUndo() {
        app()
        compose.onNodeWithText("Add shopping").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 1 name").performTextInput("Daging")
        compose.onNodeWithContentDescription("Item 1 amount").performTextInput("150000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Add item").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 2 name").assertIsFocused()
        compose.onNodeWithContentDescription("Item 2 name").performScrollTo().performTextInput("Bumbu")
        compose.onNodeWithContentDescription("Item 2 amount").performScrollTo().performTextInput("25000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Rp 175.000").assertIsDisplayed()
        screenshot("expense")
        compose.onNodeWithContentDescription("Remove item 1").performScrollTo().performClick()
        compose.onNodeWithText("Rp 25.000").assertIsDisplayed()
        compose.onNodeWithText("Undo remove").performScrollTo().performClick()
        compose.onNodeWithText("Rp 175.000").assertIsDisplayed()
        compose.onNodeWithText("Save sample").performClick()
        compose.onNodeWithText("Daging").assertIsDisplayed()
        compose.onNodeWithText("Bumbu").assertIsDisplayed()
    }

    @Test fun expandedLayoutAndResizeKeepFormState() {
        var density by mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                BasoTheme(false) { BukuHarianApp() }
            }
        }
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("journal-expanded").assertIsDisplayed()
        screenshot("expanded")
        compose.onNodeWithText("Add sale").performClick()
        compose.onNodeWithText("Sale amount").performTextInput("35000")
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { density = 3f }
        compose.onNodeWithText("35000").assertIsDisplayed()
        compose.onNodeWithText("Save sample").assertIsDisplayed()
    }

    @Test fun shoppingKeyboardProgressionWorksWithLargeText() {
        app(fontScale = 2f, dark = true)
        compose.onNodeWithText("Add shopping").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 1 name").performScrollTo().performTextInput("Daging")
        compose.onNodeWithContentDescription("Item 1 name").performImeAction()
        compose.onNodeWithContentDescription("Item 1 amount").assertIsFocused().performTextInput("150000")
        compose.onNodeWithContentDescription("Item 1 amount").performImeAction()
        compose.onNodeWithContentDescription("Item 2 name").assertIsFocused().performTextInput("Bumbu")
        compose.onNodeWithContentDescription("Item 2 name").performImeAction()
        compose.onNodeWithContentDescription("Item 2 amount").assertIsFocused().performTextInput("25000")
        compose.onNodeWithText("Save sample").assertIsDisplayed()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Item 2 amount").performScrollTo()
        screenshot("large-expense")
        compose.onNodeWithContentDescription("Remove item 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 1 name").assertIsFocused()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Undo remove").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Item 1 name").assertIsFocused()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Rp 175.000").assertIsDisplayed()
        compose.onNodeWithText("Save sample").performClick()
        compose.onNodeWithText("Daging").assertExists()
        compose.onNodeWithText("Bumbu").assertExists()
    }

    @Test fun monthlyRecapDrillsIntoTheSameNotebook() {
        app()
        compose.onNodeWithText("Recap").performClick()
        compose.onNodeWithText("Rp 760.000").assertIsDisplayed()
        screenshot("recap")
        compose.onNodeWithText("Oct 25, 2026", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("sales-total").assertTextEquals("Rp 260.000")
    }

    @Test fun largeTextDarkThemeKeepsActionsReachable() {
        app(fontScale = 2f, dark = true)
        compose.onNodeWithText("Add sale").performScrollTo().assertIsDisplayed()
        val bounds = compose.onNodeWithText("Add sale").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        screenshot("large-dark")
        compose.onNodeWithText("Add sale").performClick()
        compose.onNodeWithText("Sale amount").performScrollTo().performTextInput("40000")
        compose.onNodeWithText("Save sample").assertIsDisplayed()
        Espresso.closeSoftKeyboard()
        screenshot("large-form")
    }

    @Test fun darkThemeRendersCompactForms() {
        app(dark = true)
        compose.onNodeWithTag("navigation-bottom").assertIsDisplayed()
        compose.onNodeWithTag("sales-total").assertTextEquals("Rp 500.000")
        screenshot("dark-phone")
        compose.onNodeWithText("Add sale").performScrollTo().performClick()
        compose.onNodeWithText("Save sample").performClick()
        compose.onNodeWithText("Enter a whole rupiah amount greater than zero.").performScrollTo().assertIsDisplayed()
        screenshot("dark-sale-error")
        compose.onNodeWithText("Sale amount").performTextInput("25000")
        screenshot("dark-sale")
    }

    @Test fun darkThemeRendersExpandedNavigation() {
        app(density = 1f, dark = true)
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("journal-expanded").assertIsDisplayed()
        screenshot("dark-expanded")
        compose.onNodeWithText("Recap").performClick()
        compose.onNodeWithText("Rp 760.000").assertIsDisplayed()
        screenshot("dark-recap-expanded")
    }

    @Test fun realActivityKeepsDraftThroughLandscapeRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Add sale").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add sale").performScrollTo().performClick()
            compose.onNodeWithText("Sale amount").performTextInput("65000")
            Espresso.closeSoftKeyboard()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.onNodeWithText("65000").assertExists()
            screenshot("landscape")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) {
                InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            compose.onNodeWithText("65000").assertExists()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun reusableContentStatesHaveRecoveryAndProgress() {
        var state by mutableStateOf(ContentStatus.Error)
        compose.setContent { BasoTheme(false) { Box(Modifier.fillMaxSize()) { ContentState(state) { state = ContentStatus.Empty } } } }
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("No samples on this date").assertIsDisplayed()
        compose.runOnIdle { state = ContentStatus.Loading }
        compose.onNodeWithText("Loading sample entries…").assertIsDisplayed()
    }

    @Test fun indonesianResourcesRenderSampleRecordsAndForms() {
        app(language = "id")
        compose.onNodeWithText("Mode contoh · Bukan catatan usaha").assertIsDisplayed()
        screenshot("phone-id")
        compose.onNodeWithText("Catat belanja").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Nama barang 1").performTextInput("Sayur")
        compose.onNodeWithContentDescription("Jumlah barang 1").performTextInput("35000")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Simpan contoh").assertIsDisplayed()
        screenshot("expense-id")
    }

    @Test @SdkSuppress(minSdkVersion = 33) fun nativeLanguageSettingKeepsDraftAndUpdatesAllLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(LocaleManager::class.java)
        val previousLocales = manager.applicationLocales
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val previousPreference = preferences.getString("language", null)
        try {
            manager.applicationLocales = LocaleList.forLanguageTags("en")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Add sale").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Add sale").performScrollTo().performClick()
                compose.onNodeWithText("Sale amount").performTextInput("55000")
                Espresso.closeSoftKeyboard()
                compose.onNodeWithContentDescription("App language").performClick()
                compose.onNodeWithText("Bahasa Indonesia").performClick()
                compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithText("Simpan").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                compose.onNodeWithText("55000").assertExists()
                compose.onNodeWithText("Simpan").assertIsDisplayed()
                screenshot("sale-id")
                compose.onNodeWithContentDescription("Bahasa aplikasi").performClick()
                compose.onNodeWithText("English").performClick()
                compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                compose.onNodeWithText("55000").assertExists()
            }
        } finally {
            manager.applicationLocales = previousLocales
            if (previousPreference == null) preferences.edit().remove("language").apply()
            else preferences.edit().putString("language", previousPreference).apply()
        }
    }
}
