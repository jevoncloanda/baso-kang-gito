package id.basokanggito.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.basokanggito.app.R
import id.basokanggito.app.data.AppDownloadPage
import id.basokanggito.app.data.AppVersionPolicy
import id.basokanggito.app.data.ApkUpdaterViewModel
import id.basokanggito.app.data.ApkPhase
import id.basokanggito.app.data.ApkError

internal fun openAppDownload(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppDownloadPage))) }
}

@Composable
internal fun AppUpdateButton(policy: AppVersionPolicy, prominent: Boolean = false) {
    val updater: ApkUpdaterViewModel = viewModel()
    if (prominent) Button(onClick = { updater.begin(policy) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.app_update_action)) }
    else TextButton(onClick = { updater.begin(policy) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.app_update_action)) }
}

@Composable
internal fun AppUpdateDownloadDialog() {
    val updater: ApkUpdaterViewModel = viewModel()
    val state by updater.state.collectAsState()
    val context = LocalContext.current
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { updater.installed(it.resultCode) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { updater.permissionReturned() }
    if (state.phase == ApkPhase.IDLE) return
    val busy = state.phase in listOf(ApkPhase.DOWNLOADING, ApkPhase.VERIFYING)
    AlertDialog(onDismissRequest = updater::dismiss,
        title = { Text(stringResource(if (state.phase == ApkPhase.READY) R.string.apk_update_ready else R.string.app_update_action)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            Text(stringResource(when (state.phase) {
                ApkPhase.DOWNLOADING -> R.string.apk_update_downloading
                ApkPhase.VERIFYING -> R.string.apk_update_verifying
                ApkPhase.READY -> R.string.apk_update_verified
                ApkPhase.PERMISSION -> R.string.apk_update_permission
                ApkPhase.INSTALLING -> R.string.apk_update_installing
                ApkPhase.CANCELLED -> R.string.apk_update_cancelled
                ApkPhase.DISABLED -> R.string.apk_update_disabled
                else -> when (state.error) {
                    ApkError.STORAGE -> R.string.apk_update_storage
                    ApkError.METADATA -> R.string.apk_update_metadata
                    ApkError.INTEGRITY, ApkError.SIGNATURE, ApkError.PACKAGE, ApkError.VERSION -> R.string.apk_update_untrusted
                    ApkError.INSTALLER -> R.string.apk_update_installer_error
                    else -> R.string.apk_update_network
                }
            }))
            if (state.phase == ApkPhase.DOWNLOADING && state.percent != null) {
                Text(stringResource(R.string.apk_update_progress, state.percent!!))
                LinearProgressIndicator(progress = { state.percent!! / 100f }, modifier = Modifier.fillMaxWidth())
            } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.app_update_preserves_data))
            if (state.phase != ApkPhase.INSTALLING) TextButton(onClick = { openAppDownload(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.apk_update_browser)) }
        } },
        confirmButton = {
            when (state.phase) {
                ApkPhase.READY -> Button(onClick = { updater.requestInstall { intent -> installer.launch(intent) } }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.apk_update_install)) }
                ApkPhase.PERMISSION -> Button(onClick = {
                    runCatching { updater.permissionIntent()?.let { permission.launch(it) } }.onFailure { updater.failInstaller() }
                }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.apk_update_settings)) }
                ApkPhase.ERROR, ApkPhase.CANCELLED -> if (updater.enabled && state.error != ApkError.METADATA) {
                    Button(onClick = updater::retry, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.apk_update_retry)) }
                } else Unit
                else -> Unit
            }
        },
        dismissButton = {
            if (state.phase != ApkPhase.INSTALLING) TextButton(onClick = updater::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        })
}

@Composable
internal fun AppUpdateRequiredDialog(policy: AppVersionPolicy, checking: Boolean, onRetry: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(Space.large)) {
                    LanguageMenu()
                    AppIcon(R.drawable.ic_device)
                    Text(stringResource(R.string.app_update_required_title), style = MaterialTheme.typography.headlineMedium)
                    Text(policy.message.ifBlank { stringResource(R.string.app_update_required_body) })
                    Text(stringResource(R.string.app_update_preserves_data))
                    AppUpdateButton(policy, prominent = true)
                    OutlinedButton(onClick = onRetry, enabled = !checking, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.app_update_retry)) }
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
