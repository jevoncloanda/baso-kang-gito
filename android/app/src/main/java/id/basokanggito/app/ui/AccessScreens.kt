package id.basokanggito.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import id.basokanggito.app.R
import id.basokanggito.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
private fun AccessPage(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    LanguageMenu()
                }
                content()
            }
        }
    }
}

@Composable
private fun PinField(value: String, change: (String) -> Unit, label: Int, tag: String) {
    OutlinedTextField(value, { if (it.length <= 6 && it.all { char -> char in '0'..'9' }) change(it) },
        label = { Text(stringResource(label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun AccessFeedback(model: AccessViewModel) {
    val busy by model.busy.collectAsState(); val error by model.error.collectAsState(); val seconds by model.delay.collectAsState()
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotEmpty()) Text(stringResource(when (error) {
        "pin_format" -> R.string.pin_format; "pin_confirmation" -> R.string.pin_confirmation_error
        "pin_incorrect" -> R.string.pin_incorrect; "pin_delayed" -> R.string.pin_delayed
        "access_expired", "enrollment_unavailable" -> R.string.access_expired; "access_rate", "too_many_attempts" -> R.string.access_rate
        "last_administrator" -> R.string.access_last_admin; "incorrect_pairing_code" -> R.string.access_wrong_code
        "device_name" -> R.string.access_name_error; "access_connection" -> R.string.access_connection
        else -> R.string.access_failed
    }), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (seconds > 0) Text(stringResource(R.string.pin_wait, seconds))
}

/** No PIN/token is saveable: configuration changes intentionally clear secret input. */
@Composable
fun AccessGate(model: AccessViewModel = viewModel(), content: @Composable () -> Unit) {
    val access = model.access
    val state by access.state.collectAsState(); val unlocked by model.unlocked.collectAsState(); val covered by model.covered.collectAsState()
    val busy by model.busy.collectAsState()
    val savedContent = rememberSaveableStateHolder()
    var restore by rememberSaveable { mutableStateOf(false) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var bootstrap by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    val context = LocalContext.current
    val notebook: NotebookViewModel = viewModel(factory = remember { object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = NotebookViewModel((context.applicationContext as NotebookApplication).repository) as T
    } })
    LaunchedEffect(unlocked, covered) { if (!unlocked || covered) { pin = ""; confirmation = ""; bootstrap = "" } }
    LaunchedEffect(state.hasPin, unlocked) {
        if (state.hasPin && !unlocked) while (true) {
            model.delay.value = withContext(Dispatchers.IO) { runCatching { access.store.delaySeconds() }.getOrDefault(0) }
            delay(1000)
        }
    }
    Box(Modifier.fillMaxSize()) {
    if (restore && (unlocked || (!state.enrolled && !state.hasPin && !state.pending && !state.storageFailure && !recovery)))
        savedContent.SaveableStateProvider("initial-restore") { BackupScreen(notebook, restoreOnly = !unlocked) { restore = false } }
    else when {
        state.storageFailure || recovery || !state.enrolled -> AccessPage(R.string.access_register) {
            Text(stringResource(if (state.storageFailure || recovery) R.string.access_recovery else R.string.access_introduction))
            Text(stringResource(R.string.access_restore_first))
            if (!state.enrolled && !state.storageFailure && !recovery && !state.pending) {
                OutlinedButton(onClick = { restore = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.backup_restore)) }
            }
            if (!state.pending) OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.access_device_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("device_name"))
            if (state.pending) {
                Text(stringResource(R.string.access_waiting))
                Text(state.name, style = MaterialTheme.typography.titleMedium)
                Text(state.installationId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("installation_id"))
                if (state.code.isNotEmpty()) Text(state.code, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("pairing_code"))
                Text(stringResource(R.string.access_pairing_warning))
                Button(onClick = { model.run { access.poll(); if (access.state.value.enrolled) recovery = false } }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("check_approval")) { Text(stringResource(R.string.access_check)) }
                OutlinedButton(onClick = { model.run { access.restartEnrollment() } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.access_new_request)) }
            }
            OutlinedTextField(bootstrap, { bootstrap = it.trim() }, label = { Text(stringResource(R.string.access_bootstrap_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("bootstrap_token"))
            Button(onClick = {
                val token = bootstrap; bootstrap = ""
                model.run { access.begin(name, token, recovery || state.storageFailure); if (access.state.value.enrolled) recovery = false }
            }, enabled = !busy && (state.pending || name.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("register_device")) { Text(stringResource(if (bootstrap.isNotEmpty()) R.string.access_bootstrap else R.string.access_register)) }
            AccessFeedback(model)
        }
        !state.hasPin -> AccessPage(R.string.pin_setup) {
            Text(stringResource(R.string.access_approved))
            PinField(pin, { pin = it }, R.string.pin_new, "pin_new"); PinField(confirmation, { confirmation = it }, R.string.pin_confirm, "pin_confirm")
            Button(onClick = { val value = pin; val repeat = confirmation; pin = ""; confirmation = ""; model.setup(value, repeat) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("pin_save")) { Text(stringResource(R.string.pin_save)) }
            AccessFeedback(model)
        }
        !unlocked -> AccessPage(R.string.pin_enter) {
            PinField(pin, { pin = it }, R.string.pin_enter, "pin_unlock")
            Button(onClick = { val value = pin; pin = ""; model.unlock(value) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("unlock")) { Text(stringResource(R.string.pin_unlock)) }
            TextButton(onClick = { recovery = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pin_forgot)) }
            AccessFeedback(model)
        }
        state.blocked -> AccessPage(R.string.access_revoked) {
            Text(stringResource(R.string.access_revoked_detail))
            Button(onClick = { recovery = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.access_contact)) }
            OutlinedButton(onClick = { restore = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.backup_title)) }
        }
        !state.ready -> AccessPage(R.string.access_ready_title) {
            Text(stringResource(R.string.access_restore_first))
            OutlinedButton(onClick = { restore = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.backup_restore)) }
            Button(onClick = { model.run { access.ready() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.access_open)) }
        }
        else -> savedContent.SaveableStateProvider("notebook") { content() }
    }
    // Keep launchers and drafts composed during the background grace period (including SAF).
    if (covered) Surface(Modifier.matchParentSize()) {}
    }
}

@Composable
fun ChangePinScreen(onBack: () -> Unit, model: AccessViewModel = viewModel()) {
    var old by remember { mutableStateOf("") }; var pin by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    val busy by model.busy.collectAsState(); var submitted by remember { mutableStateOf(false) }; var saved by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    AccessPage(R.string.pin_change) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
        PinField(old, { old = it }, R.string.pin_current, "pin_current"); PinField(pin, { pin = it }, R.string.pin_new, "pin_new"); PinField(confirmation, { confirmation = it }, R.string.pin_confirm, "pin_confirm")
        Button(onClick = { val previous = old; val next = pin; val repeat = confirmation; old = ""; pin = ""; confirmation = ""; submitted = true; saved = false
            model.change(previous, next, repeat) { saved = true }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.pin_save)) }
        if (submitted && saved) Text(stringResource(R.string.pin_changed))
        AccessFeedback(model)
    }
}

@Composable
fun DeviceManagementScreen(onBack: () -> Unit, model: AccessViewModel = viewModel()) {
    var listing by remember { mutableStateOf<JSONObject?>(null) }; var codes by remember { mutableStateOf(mapOf<String, String>()) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    val busy by model.busy.collectAsState()
    fun refresh() { model.run { listing = model.access.client().get("/v1/access/devices").accessBody() } }
    LaunchedEffect(Unit) { refresh() }
    BackHandler(onBack = onBack)
    AccessPage(R.string.access_devices) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.access_admin_warning))
        OutlinedButton(onClick = ::refresh, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.access_refresh)) }
        listing?.let { data ->
            Text(stringResource(R.string.access_pending), style = MaterialTheme.typography.titleLarge)
            val pending = data.getJSONArray("pending")
            if (pending.length() == 0) Text(stringResource(R.string.access_no_pending))
            for (index in 0 until pending.length()) {
                val row = pending.getJSONObject(index); val id = row.getString("id")
                HorizontalDivider(); Text(row.getString("name"), style = MaterialTheme.typography.titleMedium)
                Text(id, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (row.getString("purpose") == "pin_recovery") R.string.access_pin_recovery else R.string.access_new_device))
                Text(stringResource(R.string.access_expires, row.getString("expiresAt")))
                if (row.getString("state") == "approved") { Text(stringResource(R.string.access_finish_wait)); continue }
                OutlinedTextField(codes[id].orEmpty(), { value -> if (value.length <= 6 && value.all { it in '0'..'9' }) codes = codes + (id to value) }, label = { Text(stringResource(R.string.access_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pairing-input-$id"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { model.run { model.access.client().post("/v1/access/enrollments/$id/decision", JSONObject().put("approve", true).put("code", codes[id].orEmpty())).accessBody(); listing = model.access.client().get("/v1/access/devices").accessBody() } },
                        enabled = !busy && codes[id]?.length == 6, modifier = Modifier.heightIn(min = 56.dp).weight(1f).testTag("approve-$id")) { Text(stringResource(R.string.access_approve)) }
                    OutlinedButton(onClick = { model.run { model.access.client().post("/v1/access/enrollments/$id/decision", JSONObject().put("approve", false)).accessBody(); listing = model.access.client().get("/v1/access/devices").accessBody() } },
                        enabled = !busy, modifier = Modifier.heightIn(min = 56.dp).weight(1f)) { Text(stringResource(R.string.access_reject)) }
                }
            }
            Text(stringResource(R.string.access_trusted), style = MaterialTheme.typography.titleLarge)
            val devices = data.getJSONArray("devices")
            for (index in 0 until devices.length()) {
                val row = devices.getJSONObject(index)
                HorizontalDivider(); Text(row.getString("name"), style = MaterialTheme.typography.titleMedium)
                Text(row.getString("id"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (!row.isNull("revokedAt")) R.string.access_revoked else if (row.getString("role") == "admin") R.string.access_administrator else R.string.access_standard))
                if (row.isNull("revokedAt")) OutlinedButton(onClick = { revoke = row }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp).testTag("revoke-${row.getString("id")}")) { Text(stringResource(R.string.access_revoke)) }
            }
        }
        AccessFeedback(model)
    }
    revoke?.let { row -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text(stringResource(R.string.access_revoke)) },
        text = { Text(stringResource(R.string.access_revoke_confirm, row.getString("name"))) }, confirmButton = {
            TextButton(onClick = { revoke = null; model.run { model.access.client().post("/v1/access/devices/${row.getString("id")}/revoke", JSONObject()).accessBody(); listing = model.access.client().get("/v1/access/devices").accessBody() } }) { Text(stringResource(R.string.access_revoke)) }
        }, dismissButton = { TextButton(onClick = { revoke = null }) { Text(stringResource(R.string.backup_cancel)) } }) }
}
