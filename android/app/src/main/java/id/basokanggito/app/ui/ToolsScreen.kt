package id.basokanggito.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.basokanggito.app.BuildConfig
import id.basokanggito.app.R
import id.basokanggito.app.data.AccessState

@Composable
internal fun ToolsScreen(access: AccessState, onBack: () -> Unit, onBackup: () -> Unit, onPin: () -> Unit, onDevices: () -> Unit) {
    BackHandler(onBack = onBack)
    UtilityPage(R.string.tools_title, onBack) {
        UtilitySection(R.string.tools_device, R.drawable.ic_device) {
            Text(access.name, style = MaterialTheme.typography.titleMedium)
            Text("${Build.MANUFACTURER} ${Build.MODEL}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (access.role == "admin") R.string.access_administrator else R.string.access_standard), style = MaterialTheme.typography.labelLarge)
            if (access.role == "admin") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UtilityAction(R.string.access_devices, R.string.tools_devices_description, onDevices)
            }
        }
        UtilitySection(R.string.tools_data, R.drawable.ic_backup) {
            UtilityAction(R.string.backup_title, R.string.tools_backup_description, onBackup)
        }
        UtilitySection(R.string.tools_application, R.drawable.ic_lock) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.language), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                LanguageMenu()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            UtilityAction(R.string.pin_change, R.string.tools_pin_description, onPin)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tools_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
