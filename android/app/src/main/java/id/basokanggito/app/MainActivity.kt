package id.basokanggito.app

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import id.basokanggito.app.ui.BasoTheme
import id.basokanggito.app.ui.FunctionalNotebook
import id.basokanggito.app.ui.localizedAppContext
import id.basokanggito.app.ui.AccessGate
import id.basokanggito.app.data.AccessViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import id.basokanggito.app.data.scheduleSync
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val accessModel by lazy { ViewModelProvider(this)[AccessViewModel::class.java] }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedAppContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val presentation = getSharedPreferences("notebook_presentation", MODE_PRIVATE)
        // Old prototype saved UI state is never imported into real bookkeeping screens.
        super.onCreate(if (presentation.getBoolean("functional", false)) savedInstanceState else null)
        presentation.edit().putBoolean("functional", true).apply()
        enableEdgeToEdge()
        setContent {
            val journalReset by accessModel.journalReset.collectAsState()
            val navigationSession by accessModel.navigationSession.collectAsState()
            BasoTheme {
                AccessGate(accessModel) { FunctionalNotebook(journalReset, navigationSession) }
                id.basokanggito.app.ui.AppUpdateDownloadDialog()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (accessModel.unlocked.value && !accessModel.covered.value) scheduleSync(this@MainActivity, ExistingWorkPolicy.KEEP)
                    delay(60_000)
                }
            }
        }
    }
    override fun onStart() { super.onStart(); accessModel.foreground() }
    override fun onStop() { if (!isChangingConfigurations) accessModel.background(); super.onStop() }
}
