package id.basokanggito.app.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.basokanggito.app.R
import id.basokanggito.app.data.BackupViewModel
import id.basokanggito.app.data.NotebookViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun BackupScreen(model: NotebookViewModel, onBack: () -> Unit) {
    val host = LocalContext.current
    val context = host.applicationContext
    val backup: BackupViewModel = viewModel(key = "backup", factory = remember(model) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = BackupViewModel(context, model) as T
    } })
    val busy by backup.busy.collectAsState()
    val password by backup.password.collectAsState()
    val confirmation by backup.confirmation.collectAsState()
    val message by backup.message.collectAsState()
    val cleanup by backup.cleanupNeeded.collectAsState()
    val selected by backup.selected.collectAsState()
    val prepared by backup.prepared.collectAsState()
    val empty by backup.empty.collectAsState()
    val chooseDestination by backup.chooseDestination.collectAsState()
    val destinationName by backup.destinationName.collectAsState()
    val notebook by model.state.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), backup::write)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), backup::select)
    DisposableEffect(backup, host) {
        backup.enter()
        onDispose {
            var activityContext = host
            while (activityContext is ContextWrapper && activityContext !is Activity) activityContext = activityContext.baseContext
            if ((activityContext as? Activity)?.isChangingConfigurations != true) backup.leave()
        }
    }
    LaunchedEffect(chooseDestination) {
        if (chooseDestination) {
            backup.chooseDestination.value = false
            runCatching { create.launch("Baso-Kang-Gito-Backup-${LocalDate.now()}.bkg") }.onFailure {
                backup.write(null); backup.message.value = "backup_io"
            }
        }
    }
    BackHandler { if (!busy) { backup.cancel(); onBack() } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { backup.cancel(); onBack() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_back)) }
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.backup_explanation))
            Text(stringResource(R.string.backup_password_warning))
            notebook.meta["lastBackupAt"]?.let { raw ->
                runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrNull()?.let { date ->
                    Text(stringResource(R.string.backup_last_saved, date))
                    Text(stringResource(R.string.backup_last_caveat), style = MaterialTheme.typography.bodySmall)
                }
            }
            prepared?.let { stage ->
                val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(stage.header.createdAt))
                Text(stringResource(R.string.backup_preview_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.backup_preview_version, date, stage.header.appVersion, stage.header.format, stage.header.schema))
                Text(stringResource(R.string.backup_preview_counts, stage.summary.records, stage.summary.customers, stage.summary.operations, stage.summary.pending, stage.summary.failed))
                Text(stringResource(if (stage.summary.hasDrafts) R.string.backup_drafts_yes else R.string.backup_drafts_no))
                Text(stringResource(if (empty) R.string.backup_empty_ready else R.string.backup_not_empty), color = if (empty) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Button(onClick = { confirmRestore = true }, enabled = !busy && empty, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("backup_confirm")) { Text(stringResource(R.string.backup_restore)) }
                OutlinedButton(onClick = backup::cancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.backup_cancel)) }
            } ?: run {
                if (selected != null) Text(stringResource(R.string.backup_file_selected))
                OutlinedTextField(value = password, onValueChange = { backup.password.value = it }, label = { Text(stringResource(R.string.backup_password_label)) }, singleLine = true, enabled = !busy,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { visible = !visible }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (visible) R.string.backup_hide else R.string.backup_show)) } },
                    modifier = Modifier.fillMaxWidth().testTag("backup_password"))
                if (selected == null) {
                    OutlinedTextField(value = confirmation, onValueChange = { backup.confirmation.value = it }, label = { Text(stringResource(R.string.backup_confirm_password)) }, singleLine = true, enabled = !busy,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("backup_password_confirmation"))
                    Button(onClick = backup::create, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("backup_create")) { Text(stringResource(R.string.backup_create)) }
                } else {
                    Button(onClick = backup::inspect, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("backup_inspect")) { Text(stringResource(R.string.backup_inspect)) }
                    TextButton(onClick = backup::cancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_cancel)) }
                }
                OutlinedButton(onClick = { runCatching { open.launch(arrayOf("*/*")) }.onFailure { backup.message.value = "backup_io" } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("backup_select")) { Text(stringResource(R.string.backup_select)) }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.backup_busy)) }
            if (message.isNotEmpty()) Text(stringResource(when (message) {
                "backup_exported" -> R.string.backup_exported
                "backup_restored" -> R.string.backup_restored
                "backup_password_rules" -> R.string.backup_password_rules
                "backup_password" -> R.string.backup_password_error
                "backup_incompatible" -> R.string.backup_incompatible
                "backup_limit" -> R.string.backup_limit
                "backup_not_empty" -> R.string.backup_not_empty
                "backup_invalid" -> R.string.backup_invalid
                else -> R.string.backup_io
            }), color = if (message in listOf("backup_exported", "backup_restored")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup_result"))
            if (message == "backup_exported" && destinationName.isNotEmpty()) Text(stringResource(R.string.backup_exported_location, destinationName))
            if (cleanup) Text(stringResource(R.string.backup_cleanup), color = MaterialTheme.colorScheme.error)
        }
    }
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false }, title = { Text(stringResource(R.string.backup_restore)) }, text = { Text(stringResource(R.string.backup_restore_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmRestore = false; backup.restore() }) { Text(stringResource(R.string.backup_restore)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.backup_cancel)) } })
}
