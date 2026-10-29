package id.basokanggito.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.basokanggito.app.BuildConfig
import id.basokanggito.app.R
import id.basokanggito.app.data.AccessState

@Composable
internal fun ToolsScreen(access: AccessState, onBack: () -> Unit, onBackup: () -> Unit, onPin: () -> Unit, onDevices: () -> Unit) {
    val context = LocalContext.current
    val appearance = rememberAppAppearance()
    var showAppearance by remember { mutableStateOf(false) }
    if (showAppearance) AlertDialog(onDismissRequest = { showAppearance = false },
        title = { Text(stringResource(R.string.appearance)) },
        text = {
            Column {
                AppAppearance.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = appearance == option, role = Role.RadioButton,
                        onClick = { setAppAppearance(context, option); showAppearance = false }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = appearance == option, onClick = null)
                        Text(stringResource(option.label), Modifier.padding(start = Space.small))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showAppearance = false }) { Text(stringResource(R.string.cancel)) } })
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
            TextButton(onClick = { showAppearance = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.appearance), Modifier.weight(1f))
                Text(stringResource(appearance.label))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            UtilityAction(R.string.pin_change, R.string.tools_pin_description, onPin)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tools_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
