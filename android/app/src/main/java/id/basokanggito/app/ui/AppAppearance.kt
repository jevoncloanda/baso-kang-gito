package id.basokanggito.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import id.basokanggito.app.R

internal enum class AppAppearance(val label: Int) {
    SYSTEM(R.string.appearance_system), LIGHT(R.string.appearance_light), DARK(R.string.appearance_dark);

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun parse(value: String?): AppAppearance = entries.find { it.name == value } ?: SYSTEM
    }
}

@Composable
internal fun rememberAppAppearance(): AppAppearance {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var appearance by remember(preferences) { mutableStateOf(AppAppearance.parse(preferences.getString("theme", null))) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "theme") appearance = AppAppearance.parse(prefs.getString("theme", null))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        appearance = AppAppearance.parse(preferences.getString("theme", null))
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return appearance
}

internal fun setAppAppearance(context: Context, appearance: AppAppearance) {
    context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", appearance.name).apply()
}
