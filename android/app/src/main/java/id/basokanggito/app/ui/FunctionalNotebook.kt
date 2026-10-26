package id.basokanggito.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.basokanggito.app.R
import id.basokanggito.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

private fun displayChannel(code: String) = SalesChannels[ChannelCodes.indexOf(code).coerceAtLeast(0)]
private fun channelCode(label: String) = ChannelCodes[SalesChannels.indexOf(label).coerceAtLeast(0)]
internal fun presentation(state: NotebookState): List<JournalEntry> = state.records.flatMap { record ->
    val sync = state.pending.filter { it.recordId == record.id }.let { if (it.any { p -> p.state == "failed" }) "failed" else if (it.isNotEmpty()) "pending" else "" }
    val root = JournalEntry(record.id, record.date, if (record.kind == "expense") "Belanja" else displayChannel(record.channel),
        record.amount, record.received, record.customer, record.note, record.items.map { JournalLine(it.name, it.amount) }, voided = record.voided, syncState = sync, createdAt = record.createdAt)
    listOf(root) + record.receipts.map { payment -> JournalEntry(payment.id, payment.date, displayChannel(record.channel), payment.amount,
        customer = record.customer, receiptFor = record.id, voided = record.voided || payment.voided, syncState = sync, createdAt = payment.createdAt) }
}.sortedWith(compareByDescending<JournalEntry> { it.date }.thenByDescending { Instant.parse(it.createdAt) }.thenBy { it.id })

@Composable
fun FunctionalNotebook() {
    val context = LocalContext.current
    val repository = (context.applicationContext as NotebookApplication).repository
    val model: NotebookViewModel = viewModel(factory = remember(repository) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = NotebookViewModel(repository) as T
    } })
    val state by model.state.collectAsState()
    val saving by model.saving.collectAsState()
    val saved by model.saved.collectAsState()
    val error by model.error.collectAsState()
    var route by rememberSaveable { mutableStateOf("journal") }
    var dateText by rememberSaveable { mutableStateOf(businessToday().toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(businessToday()).toString()) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var draftKind by rememberSaveable { mutableStateOf("") }
    var recordId by rememberSaveable { mutableStateOf(newId()) }
    var baseVersion by rememberSaveable { mutableIntStateOf(0) }
    var sale by rememberSaveable { mutableStateOf(SaleDraft()) }
    var expense by rememberSaveable { mutableStateOf<List<ExpenseDraftLine>>(arrayListOf(ExpenseDraftLine(newId()))) }
    var reason by rememberSaveable { mutableStateOf("") }
    var receiptAmount by rememberSaveable { mutableStateOf("") }
    var receiptDate by rememberSaveable { mutableStateOf(businessToday().toString()) }
    var receiptMethod by rememberSaveable { mutableStateOf("cash") }
    var receiptId by rememberSaveable { mutableStateOf(newId()) }
    var draftLoaded by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf(false) }
    var recoveredEdit by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun persist() {
        model.draft(JSONObject().put("route", route).put("date", dateText).put("kind", draftKind).put("id", recordId).put("version", baseVersion).put("reason", reason)
            .put("sale", JSONObject().put("amount", sale.amount).put("customer", sale.customer).put("channel", sale.channel).put("paid", sale.paid).put("note", sale.note).put("method", sale.method))
            .put("expense", JSONArray(expense.map { JSONObject().put("id", it.id).put("name", it.name).put("amount", it.amount) }))
            .put("receiptAmount", receiptAmount).put("receiptDate", receiptDate).put("receiptMethod", receiptMethod).put("receiptId", receiptId)
            .apply { if (recoveredEdit.isNotEmpty()) put("recoveredEdit", JSONObject(recoveredEdit)) }.toString())
    }
    fun restoreEdit(edit: RecoveredEdit) {
        val local = edit.local
        recoveredEdit = edit.json().toString()
        recordId = local.id; baseVersion = edit.server?.version ?: 0; dateText = local.date
        reason = edit.reason; draftKind = local.kind; route = local.kind
        if (local.kind == "sale") sale = SaleDraft(local.amount.toString(), local.customer, displayChannel(local.channel), local.received > 0, local.note, local.receipts.firstOrNull { !it.voided }?.method ?: "cash")
        else expense = ArrayList(local.items.map { ExpenseDraftLine(it.id, it.name, it.amount.toString()) })
        persist()
    }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !draftLoaded) {
            val recovered = state.meta["recoveredEdit"]?.takeIf { it.isNotEmpty() }
            if (recovered != null) {
                runCatching { restoreEdit(RecoveredEdit.parse(recovered)) }.onFailure { model.error.value = "draft_unreadable" }
            } else state.meta["draft"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching {
                    val draft = JSONObject(raw); val s = draft.getJSONObject("sale")
                    recoveredEdit = draft.optJSONObject("recoveredEdit")?.toString().orEmpty()
                    dateText = validDate(draft.getString("date")); recordId = validId(draft.getString("id")); baseVersion = draft.getInt("version")
                    draftKind = draft.getString("kind"); reason = draft.getString("reason")
                    sale = SaleDraft(s.getString("amount"), s.getString("customer"), s.getString("channel"), s.getBoolean("paid"), s.getString("note"), s.getString("method"))
                    expense = ArrayList(draft.getJSONArray("expense").objects().map { ExpenseDraftLine(it.getString("id"), it.getString("name"), it.getString("amount")) })
                    receiptAmount = draft.getString("receiptAmount"); receiptDate = draft.getString("receiptDate"); receiptMethod = draft.getString("receiptMethod"); receiptId = draft.getString("receiptId")
                    route = draft.getString("route").takeIf { it in listOf("sale", "expense", "receipt") } ?: "journal"
                    if (baseVersion == 0 && state.records.any { it.id == recordId }) { selectedId = recordId; route = "detail" }
                }.onFailure { model.error.value = "draft_unreadable" }
            }
            draftLoaded = true
        }
    }
    LaunchedEffect(saved) {
        saved?.let {
            selectedId = it.id; dateText = it.date; route = "detail"; notice = true
            sale = SaleDraft(); expense = arrayListOf(ExpenseDraftLine(newId())); draftKind = ""; reason = ""; baseVersion = 0; recordId = newId()
            recoveredEdit = ""
            model.draft(""); keyboard?.hide(); model.saved.value = null
        }
    }
    fun back() { keyboard?.hide(); route = "journal"; model.error.value = ""; persist() }
    fun begin(kind: String, record: BookRecord? = null) {
        model.error.value = ""; notice = false
        if (record != null) {
            recoveredEdit = ""
            recordId = record.id; baseVersion = record.version; dateText = record.date; reason = ""
            if (kind == "sale") sale = SaleDraft(record.amount.toString(), record.customer, displayChannel(record.channel), false, record.note)
            if (kind == "expense") expense = ArrayList(record.items.map { ExpenseDraftLine(it.id, it.name, it.amount.toString()) })
        } else if (draftKind != kind) { recordId = newId(); baseVersion = 0; reason = ""; recoveredEdit = "" }
        draftKind = kind; route = kind; persist()
    }
    BackHandler(route != "journal" && !saving, ::back)
    if (!state.loaded || !draftLoaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (state.unreadable || error == "draft_unreadable") { Text(stringResource(if (state.unreadable) R.string.storage_unreadable else R.string.draft_unreadable), Modifier.padding(24.dp)); return }
    val entries = presentation(state)
    val selected = state.records.find { it.id == selectedId }
    val date = LocalDate.parse(dateText)
    val nested = route in listOf("sale", "expense", "receipt", "detail")
    val formError = financialError(error)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) NavigationRail(Modifier.fillMaxHeight().testTag("navigation-rail"), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(64.dp))
                listOf("journal" to R.string.daily_book, "recap" to R.string.recap).forEach { (destination, label) ->
                    NavigationRailItem(selected = route == destination || (nested && destination == "journal"), onClick = { route = destination; persist() },
                        icon = { AppIcon(if (destination == "journal") R.drawable.ic_book else R.drawable.ic_chart) }, label = { Text(stringResource(label)) },
                        colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
            Scaffold(Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background, topBar = {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.medium, vertical = Space.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                        if (nested) IconButton(onClick = ::back, enabled = !saving, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_back, description = stringResource(R.string.back)) }
                        Text(stringResource(when (route) { "sale" -> R.string.add_sale; "expense" -> R.string.add_shopping; "receipt" -> R.string.add_receipt; "detail" -> R.string.details; "recap" -> R.string.monthly_recap; else -> R.string.daily_book }), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        LanguageMenu()
                    }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(when { state.pending.any { it.state == "failed" } -> R.string.sync_review
                                state.meta["syncError"].orEmpty().isNotEmpty() -> R.string.sync_connection
                                !syncConfigured() -> R.string.sync_not_configured
                                state.pending.isNotEmpty() -> R.string.sync_pending
                                state.meta["lastSync"].orEmpty().isNotEmpty() -> R.string.sync_complete
                                else -> R.string.local_development }), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                            if (syncConfigured()) TextButton(onClick = { model.retry(); scheduleSync(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sync_now)) }
                        }
                    }
                }
            }, bottomBar = {
                if (!rail && !nested) NavigationBar(Modifier.testTag("navigation-bottom"), containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("journal" to R.string.daily_book, "recap" to R.string.recap).forEach { (destination, label) ->
                        NavigationBarItem(selected = route == destination, onClick = { route = destination; persist() }, icon = { AppIcon(if (destination == "journal") R.drawable.ic_book else R.drawable.ic_chart) }, label = { Text(stringResource(label)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    when (route) {
                        "sale" -> SaleForm(date, sale, { changed ->
                            val aggregate = if (baseVersion == 0 && changed.channel != "Langsung") state.records.find { !it.voided && it.kind == "sale" && it.date == dateText && it.channel == channelCode(changed.channel) } else null
                            if (aggregate != null) begin("sale", aggregate) else { sale = changed; persist() }
                        }, formError, real = true, editing = baseVersion > 0, onDate = { dateText = it.toString(); persist() }, extra = {
                            if (baseVersion > 0) EntryInput(stringResource(R.string.correction_reason), reason, { reason = it; persist() })
                            TextButton(onClick = { sale = SaleDraft(); draftKind = ""; recoveredEdit = ""; route = "journal"; model.draft("") }) { Text(stringResource(R.string.discard_draft)) }
                        }) {
                            val data = JSONObject().put("date", dateText).put("amount", sale.amount).put("channel", channelCode(sale.channel)).put("customer", sale.customer).put("note", sale.note)
                            if (baseVersion == 0) data.put("paid", sale.paid).apply { if (sale.paid) put("receiptId", newId()).put("method", sale.method) }
                            else data.put("reason", reason)
                            model.submit("save_sale", recordId, baseVersion, data)
                        }
                        "expense" -> ExpenseForm(date, expense, { expense = ArrayList(it); persist() }, formError, real = true, onDate = { dateText = it.toString(); persist() }, extra = {
                            if (baseVersion > 0) EntryInput(stringResource(R.string.correction_reason), reason, { reason = it; persist() })
                            TextButton(onClick = { expense = arrayListOf(ExpenseDraftLine(newId())); draftKind = ""; recoveredEdit = ""; route = "journal"; model.draft("") }) { Text(stringResource(R.string.discard_draft)) }
                        }) {
                            val data = JSONObject().put("date", dateText).put("reason", reason).put("items", JSONArray(expense.filter { it.name.isNotBlank() || it.amount.isNotBlank() }.map {
                                JSONObject().put("id", it.id).put("name", it.name).put("amount", it.amount)
                            }))
                            model.submit("save_expense", recordId, baseVersion, data)
                        }
                        "receipt" -> ReceiptForm(LocalDate.parse(receiptDate), receiptAmount, receiptMethod, formError,
                            onDate = { receiptDate = it.toString(); persist() }, onAmount = { receiptAmount = it; persist() }, onMethod = { receiptMethod = it; persist() }) {
                            model.submit("add_receipt", recordId, baseVersion, JSONObject().put("id", receiptId).put("date", receiptDate).put("amount", receiptAmount).put("method", receiptMethod).put("reason", "Payment received"))
                        }
                        "detail" -> selected?.let { record ->
                            RecordDetail(record, state, notice, formError, onEdit = { begin(record.kind, record) }, onReceipt = {
                                recordId = record.id; baseVersion = record.version; receiptId = newId(); receiptAmount = (record.amount - record.received).toString()
                                receiptDate = businessToday().toString(); receiptMethod = if (record.channel == "langsung") "cash" else "transfer"; route = "receipt"; draftKind = "receipt"; persist()
                            }, onVoid = { target, why, expected ->
                                val data = JSONObject().put("reason", why).put("includeReceipts", true)
                                if (target.isNotEmpty()) data.put("receiptId", target)
                                model.submit("void", record.id, expected, data)
                            }, onUseServer = { model.review(record.id) { success -> if (success) { route = "journal"; notice = false } else model.error.value = "server_version_unavailable" } },
                                onReopen = { model.reopen(record.id, ::restoreEdit) })
                        }
                        "recap" -> MonthlyRecap(entries, YearMonth.parse(monthText), { monthText = it.toString() }, realRecords = state.records) { dateText = it; route = "journal" }
                        else -> Journal(entries.filter { it.date == dateText }, date, { dateText = it.toString() }, { begin("sale") }, { begin("expense") },
                            { selectedId = it.receiptFor.ifEmpty { it.id }; route = "detail"; notice = false }, realTotals = totals(state.records, dateText, date.plusDays(1).toString()))
                    }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}

@Composable
internal fun financialError(code: String): String = if (code.isEmpty()) "" else stringResource(when (code) {
    "reason_required" -> R.string.reason_required
    "below_received", "overpayment", "linked_receipts" -> R.string.payment_constraint
    "version_conflict", "review_required" -> R.string.conflict_hint
    "daily_aggregate_exists" -> R.string.aggregate_exists
    "server_version_unavailable" -> R.string.server_unavailable
    "draft_unreadable" -> R.string.draft_unreadable
    else -> R.string.invalid_record
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BusinessDateControl(date: LocalDate, onDate: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DateSelector(date, onDate) { open = true }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 2000..2099)
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text(stringResource(R.string.choose_date)) }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(state = picker) }
    }
}

@Composable
internal fun PaymentMethod(method: String, onMethod: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("cash" to R.string.cash, "transfer" to R.string.transfer).forEach { (id, label) ->
            FilterChip(selected = method == id, onClick = { onMethod(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun ReceiptForm(date: LocalDate, amount: String, method: String, error: String, onDate: (LocalDate) -> Unit, onAmount: (String) -> Unit, onMethod: (String) -> Unit, onSave: () -> Unit) {
    FormFrame(total = positiveAmount(amount), error = error, real = true, onSave = onSave) {
        BusinessDateControl(date, onDate)
        EntryInput(stringResource(R.string.receipt_amount), amount, onAmount, money = true, prominent = true)
        PaymentMethod(method, onMethod)
    }
}

@Composable
private fun RecordDetail(record: BookRecord, state: NotebookState, notice: Boolean, error: String, onEdit: () -> Unit, onReceipt: () -> Unit, onVoid: (String, String, Int) -> Unit, onUseServer: () -> Unit, onReopen: () -> Unit) {
    var voidTarget by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var voidVersion by rememberSaveable(record.id) { mutableIntStateOf(record.version) }
    var reason by rememberSaveable(record.id) { mutableStateOf("") }
    val failed = state.pending.firstOrNull { it.recordId == record.id && it.state == "failed" }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (notice) Text(stringResource(R.string.saved_local), color = MaterialTheme.colorScheme.primary)
            SectionTitle(if (record.kind == "expense") stringResource(R.string.shopping) else stringResource(R.string.sale_title, channelLabel(displayChannel(record.channel))))
            Text(dateLabel(LocalDate.parse(record.date)))
            AmountText(record.amount, style = MaterialTheme.typography.headlineLarge)
            if (record.voided) Text(stringResource(R.string.voided))
            if (failed != null) {
                Text(stringResource(R.string.conflict_hint), color = MaterialTheme.colorScheme.error)
                val remote = state.meta["conflict:${record.id}"]?.takeIf { it.isNotEmpty() }
                Text(stringResource(R.string.local_preserved))
                val server = remote?.let { BookRecord.parse(JSONObject(it)) } ?: state.serverRecords[record.id]
                if (server != null) {
                    MoneyRow(stringResource(R.string.server_amount), server.amount)
                    MoneyRow(stringResource(R.string.received), server.received)
                    Text(dateLabel(LocalDate.parse(server.date)))
                    TextButton(onClick = onUseServer) { Text(stringResource(R.string.use_server)) }
                }
                if (remote == null && JSONObject(failed.payload).getString("action") in listOf("save_sale", "save_expense")) TextButton(onClick = onReopen) { Text(stringResource(R.string.reopen_local)) }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (record.customer.isNotBlank()) Text(stringResource(R.string.customer_value, record.customer))
            if (record.note.isNotBlank()) Text(record.note)
            if (record.kind == "expense") record.items.forEach { MoneyRow(it.name, it.amount) }
            else {
                MoneyRow(stringResource(R.string.received), record.received)
                MoneyRow(stringResource(R.string.unpaid), record.amount - record.received)
                record.receipts.sortedWith(compareByDescending<Payment> { it.date }.thenByDescending { Instant.parse(it.createdAt) }.thenBy { it.id }).forEach { payment ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MoneyRow(dateLabel(LocalDate.parse(payment.date)), payment.amount)
                        Text(stringResource(if (payment.method == "cash") R.string.cash else R.string.transfer))
                        if (payment.voided) Text(stringResource(R.string.voided))
                        else if (!record.voided) TextButton(onClick = { voidTarget = payment.id; voidVersion = record.version }) { Text(stringResource(R.string.void_payment)) }
                    }
                }
                if (!record.voided && record.received < record.amount) Button(onClick = onReceipt, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.add_receipt)) }
            }
            if (!record.voided) {
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.edit_record)) }
                TextButton(onClick = { voidTarget = ""; voidVersion = record.version }) { Text(stringResource(R.string.void_record)) }
            }
            if (voidTarget != null) {
                Text(stringResource(if (voidTarget!!.isEmpty()) R.string.void_effect else R.string.void_payment_effect, rupiah(if (voidTarget!!.isEmpty()) record.received else record.receipts.first { it.id == voidTarget }.amount)))
                EntryInput(stringResource(R.string.correction_reason), reason, { reason = it })
                Button(onClick = { onVoid(voidTarget!!, reason, voidVersion); voidTarget = null }, enabled = reason.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.confirm_void)) }
                TextButton(onClick = { voidTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
