package id.basokanggito.app

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import id.basokanggito.app.ui.BasoTheme
import id.basokanggito.app.ui.FunctionalNotebook
import id.basokanggito.app.ui.localizedAppContext
import id.basokanggito.app.ui.AccessGate
import id.basokanggito.app.data.AccessViewModel
import androidx.lifecycle.ViewModelProvider
import android.view.WindowManager

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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { BasoTheme { AccessGate(accessModel) { FunctionalNotebook() } } }
    }
    override fun onStart() { super.onStart(); accessModel.foreground() }
    override fun onStop() { if (!isChangingConfigurations) accessModel.background(); super.onStop() }
}
