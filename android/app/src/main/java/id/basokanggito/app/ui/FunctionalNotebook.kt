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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.basokanggito.app.R
import id.basokanggito.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import kotlinx.coroutines.launch

private fun displayChannel(code: String) = SalesChannels[ChannelCodes.indexOf(code).coerceAtLeast(0)]
private fun channelCode(label: String) = ChannelCodes[SalesChannels.indexOf(label).coerceAtLeast(0)]
internal fun presentation(state: NotebookState): List<JournalEntry> = state.records.flatMap { record ->
    val immediate = record.receipts.firstOrNull { it.id == record.initialReceiptId && !it.voided && !record.voided &&
        record.kind == "sale" && record.channel == "langsung" && it.date == record.date && it.amount == record.amount }
    val sync = state.pending.filter { it.recordId == record.id }.let { if (it.any { p -> p.state == "failed" }) "failed" else if (it.isNotEmpty()) "pending" else "" }
    val root = JournalEntry(record.id, record.date, if (record.kind == "expense") "Belanja" else displayChannel(record.channel),
        record.amount, record.received, record.customer, record.note, record.items.map { JournalLine(it.name, it.amount) }, voided = record.voided, syncState = sync, createdAt = record.createdAt, immediatePayment = immediate != null)
    listOf(root) + record.receipts.filterNot { it.id == immediate?.id }.map { payment -> JournalEntry(payment.id, payment.date, displayChannel(record.channel), payment.amount,
        customer = record.customer, receiptFor = record.id, voided = record.voided || payment.voided, syncState = sync, createdAt = payment.createdAt) }
}.sortedWith(compareByDescending<JournalEntry> { it.date }.thenByDescending { Instant.parse(it.createdAt) }.thenBy { it.id })

@Composable
fun FunctionalNotebook(journalReset: String = "", navigationSession: String = "") {
    val context = LocalContext.current
    val accessState by (context.applicationContext as NotebookApplication).access.state.collectAsState()
    val repository = (context.applicationContext as NotebookApplication).repository
    val model: NotebookViewModel = viewModel(factory = remember(repository) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = NotebookViewModel(repository) as T
    } })
    val state by model.state.collectAsState()
    val syncing by repository.syncing.collectAsState()
    val saving by model.saving.collectAsState()
    val saved by model.saved.collectAsState()
    val savedCustomer by model.savedCustomer.collectAsState()
    val savedCatalog by model.savedCatalog.collectAsState()
    val error by model.error.collectAsState()
    var route by rememberSaveable { mutableStateOf("journal") }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var securityScreen by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(businessToday().toString()) }
    var journalDateText by rememberSaveable { mutableStateOf(businessToday().toString()) }
    var appliedJournalReset by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(journalReset) {
        if (journalReset != appliedJournalReset) {
            journalDateText = businessToday().toString(); appliedJournalReset = journalReset
        }
    }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(businessToday()).toString()) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedCustomerId by rememberSaveable { mutableStateOf("") }
    var customerDraft by rememberSaveable { mutableStateOf("") }
    var detailReturn by rememberSaveable { mutableStateOf("journal") }
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
    var draftLoaded by remember(state.meta["restoredAt"]) { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf(false) }
    var recoveredEdit by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    var createSale by rememberSaveable { mutableStateOf("") }
    var createExpense by rememberSaveable { mutableStateOf("") }
    var catalogLineId by rememberSaveable { mutableStateOf("") }
    var selectCreatedCatalog by rememberSaveable { mutableStateOf(false) }
    var appliedNavigationSession by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun protectDraft(): Boolean {
        val protected = draftKind == "receipt" || (draftKind in listOf("sale", "expense") && (baseVersion > 0 || recoveredEdit.isNotEmpty()))
        if (protected && route !in listOf("sale", "expense", "receipt")) { model.error.value = "finish_draft_first"; return true }
        return false
    }
    fun draftJson(): JSONObject = JSONObject().put("route", route).put("date", dateText).put("kind", draftKind).put("id", recordId).put("version", baseVersion).put("reason", reason)
            .put("sale", JSONObject().put("amount", sale.amount).put("customer", sale.customer).put("customerId", sale.customerId ?: JSONObject.NULL).put("paymentAmount", sale.paymentAmount).put("channel", sale.channel).put("paid", sale.paid).put("note", sale.note).put("method", sale.method))
            .put("expense", JSONArray(expense.map { JSONObject().put("id", it.id).put("name", it.name).put("amount", it.amount).apply { it.masterId?.let { id -> put("masterId", id) } } }))
            .put("receiptAmount", receiptAmount).put("receiptDate", receiptDate).put("receiptMethod", receiptMethod).put("receiptId", receiptId)
            .apply { if (recoveredEdit.isNotEmpty()) put("recoveredEdit", JSONObject(recoveredEdit)) }
    fun persist() {
        val draft = draftJson()
        if (baseVersion == 0 && recoveredEdit.isEmpty()) {
            if (draftKind == "sale") createSale = draft.toString()
            if (draftKind == "expense") createExpense = draft.toString()
        }
        model.draft(draft.apply {
            if (createSale.isNotEmpty()) put("createSale", JSONObject(createSale))
            if (createExpense.isNotEmpty()) put("createExpense", JSONObject(createExpense))
        }.toString())
    }
    fun restoreDraft(draft: JSONObject) {
        val s = draft.getJSONObject("sale")
        recoveredEdit = draft.optJSONObject("recoveredEdit")?.toString().orEmpty()
        dateText = validDate(draft.getString("date")); recordId = validId(draft.getString("id")); baseVersion = draft.getInt("version")
        draftKind = draft.getString("kind"); reason = draft.getString("reason")
        sale = SaleDraft(s.getString("amount"), s.getString("customer"), s.getString("channel"), s.getBoolean("paid"), s.getString("note"), s.getString("method"),
            s.optString("customerId").takeUnless { it.isEmpty() || it == "null" }, s.optString("paymentAmount"))
        expense = ArrayList(draft.getJSONArray("expense").objects().map { ExpenseDraftLine(it.getString("id"), it.getString("name"), it.getString("amount"), it.optString("masterId").takeIf { id -> id.isNotEmpty() }) })
        receiptAmount = draft.getString("receiptAmount"); receiptDate = draft.getString("receiptDate"); receiptMethod = draft.getString("receiptMethod"); receiptId = draft.getString("receiptId")
    }
    fun discardForm() {
        if (baseVersion == 0 && recoveredEdit.isEmpty()) {
            if (draftKind == "sale") createSale = "" else createExpense = ""
        }
        sale = SaleDraft(); expense = arrayListOf(ExpenseDraftLine(newId()))
        draftKind = ""; recoveredEdit = ""; reason = ""; baseVersion = 0; recordId = newId(); route = "journal"
        model.draft("")
        if (createSale.isNotEmpty() || createExpense.isNotEmpty()) persist()
    }
    fun changeCustomerDraft(draft: JSONObject) { customerDraft = draft.toString(); model.customerDraft(customerDraft) }
    fun beginCustomer(customer: Customer? = null) {
        val returnTo = if (route in listOf("sale", "customer_picker")) "sale" else "customers"
        if (customerDraft.isEmpty() || customer != null) changeCustomerDraft((customer?.json() ?: JSONObject().put("id", newId()).put("version", 0)).put("returnTo", returnTo))
        else changeCustomerDraft(JSONObject(customerDraft).put("returnTo", returnTo))
        model.error.value = ""; route = "customer_form"
    }
    fun receive(record: BookRecord) {
        if (protectDraft()) return
        selectedId = record.id; recordId = record.id; baseVersion = record.version; receiptId = newId(); receiptAmount = (record.amount - record.received).toString()
        receiptDate = businessToday().toString(); receiptMethod = if (record.channel == "langsung") "cash" else "transfer"; route = "receipt"; draftKind = "receipt"; persist()
    }
    fun restorePayment(op: JSONObject) {
        val data = op.getJSONObject("data")
        recordId = op.getString("recordId"); selectedId = recordId; baseVersion = op.getInt("expectedVersion")
        receiptId = data.getString("id"); receiptAmount = data.getString("amount"); receiptDate = data.getString("date"); receiptMethod = data.getString("method")
        route = "receipt"; draftKind = "receipt"; persist()
    }
    fun restoreEdit(edit: RecoveredEdit) {
        val local = edit.local
        recoveredEdit = edit.json().toString()
        recordId = local.id; baseVersion = edit.server?.version ?: 0; dateText = local.date
        reason = edit.reason; draftKind = local.kind; route = local.kind
        if (local.kind == "sale") sale = SaleDraft(local.amount.toString(), local.customer, displayChannel(local.channel), local.received > 0, local.note, local.receipts.firstOrNull { !it.voided }?.method ?: "cash", local.customerId,
            if (local.received in 1 until local.amount) local.received.toString() else "")
        else expense = ArrayList(local.items.map { ExpenseDraftLine(it.id, it.name, it.amount.toString(), it.masterId) })
        persist()
    }
    LaunchedEffect(state.loaded, state.meta["restoredAt"]) {
        if (state.loaded && !draftLoaded) {
            // A retained StateFlow can still contain the previous draft immediately after recreation.
            // Wait for queued draft writes and read Room before initializing the restored form.
            val metadata = model.withDrafts { repository.dao.metadata().associate { it.key to it.value } }
            if (metadata["restoredAt"] != null) {
                route = "journal"; selectedId = ""; selectedCustomerId = ""; customerDraft = ""
                sale = SaleDraft(); expense = arrayListOf(ExpenseDraftLine(newId()))
                draftKind = ""; recoveredEdit = ""; reason = ""; baseVersion = 0; recordId = newId()
                receiptAmount = ""; receiptId = newId(); createSale = ""; createExpense = ""
            }
            metadata["draft"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching {
                    val draft = JSONObject(raw)
                    createSale = draft.optJSONObject("createSale")?.toString().orEmpty()
                    createExpense = draft.optJSONObject("createExpense")?.toString().orEmpty()
                    // Older installations kept the active create draft at the root only.
                    if (draft.getInt("version") == 0 && !draft.has("recoveredEdit")) {
                        if (draft.getString("kind") == "sale") createSale = JSONObject(raw).apply { remove("createSale"); remove("createExpense") }.toString()
                        if (draft.getString("kind") == "expense") createExpense = JSONObject(raw).apply { remove("createSale"); remove("createExpense") }.toString()
                    }
                }.onFailure { model.error.value = "draft_unreadable" }
            }
            val recovered = metadata["recoveredEdit"]?.takeIf { it.isNotEmpty() }
            if (recovered != null) {
                runCatching { restoreEdit(RecoveredEdit.parse(recovered)) }.onFailure { model.error.value = "draft_unreadable" }
            } else metadata["draft"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching {
                    val draft = JSONObject(raw)
                    restoreDraft(draft)
                    route = draft.getString("route").takeIf { it in listOf("sale", "expense", "receipt") } ?: "journal"
                    if (baseVersion == 0 && state.records.any { it.id == recordId }) { selectedId = recordId; route = "detail" }
                }.onFailure { model.error.value = "draft_unreadable" }
            }
            metadata["paymentRecovery"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching { restorePayment(JSONObject(raw)) }.onFailure { model.error.value = "draft_unreadable" }
            }
            metadata["customerDraft"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching { JSONObject(raw).getString("id"); customerDraft = raw; route = "customer_form" }.onFailure { model.error.value = "draft_unreadable" }
            }
            draftLoaded = true
        }
    }
    LaunchedEffect(navigationSession, draftLoaded) {
        if (draftLoaded && navigationSession.isNotEmpty() && navigationSession != appliedNavigationSession) {
            model.withDrafts { Unit }
            route = "journal"; selectedId = ""; selectedCustomerId = ""; detailReturn = "journal"
            showBackup = false; securityScreen = ""; notice = false; keyboard?.hide()
            journalDateText = businessToday().toString(); appliedNavigationSession = navigationSession
        }
    }
    fun resumeDraft() { scope.launch {
        val metadata = model.withDrafts { repository.dao.metadata().associate { it.key to it.value } }
        runCatching {
            when {
                !metadata["customerDraft"].isNullOrEmpty() -> { customerDraft = metadata.getValue("customerDraft"); route = "customer_form" }
                !metadata["paymentRecovery"].isNullOrEmpty() -> restorePayment(JSONObject(metadata.getValue("paymentRecovery")))
                !metadata["recoveredEdit"].isNullOrEmpty() -> restoreEdit(RecoveredEdit.parse(metadata.getValue("recoveredEdit")))
                else -> metadata["draft"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                    val root = JSONObject(raw)
                    val draft = if (root.optString("kind") in listOf("sale", "expense", "receipt")) root else root.optJSONObject("createSale") ?: root.optJSONObject("createExpense")
                    if (draft != null) { restoreDraft(draft); route = draft.getString("kind") }
                }
            }
            model.error.value = ""
        }.onFailure { model.error.value = "draft_unreadable" }
    } }
    LaunchedEffect(savedCustomer) {
        savedCustomer?.let { customer ->
            val returnTo = customerDraft.takeIf { it.isNotEmpty() }?.let { JSONObject(it).optString("returnTo", "customers") } ?: "customers"
            if (returnTo == "sale") { sale = sale.copy(customerId = customer.id, customer = customer.name); route = "sale"; persist() }
            else { selectedCustomerId = customer.id; route = "customers" }
            customerDraft = ""; keyboard?.hide(); model.savedCustomer.value = null
        }
    }
    fun selectCatalog(item: CatalogItem) {
        expense = ArrayList(expense.map { if (it.id == catalogLineId) it.copy(name = item.name, masterId = item.id) else it })
        route = "expense"; model.error.value = ""; persist()
    }
    LaunchedEffect(savedCatalog) {
        savedCatalog?.let { item ->
            if (selectCreatedCatalog && !item.archived) selectCatalog(item)
            selectCreatedCatalog = false; model.savedCatalog.value = null
        }
    }
    LaunchedEffect(saved) {
        saved?.let {
            if (createSale.isNotEmpty() && JSONObject(createSale).getString("id") == it.id) createSale = ""
            if (createExpense.isNotEmpty() && JSONObject(createExpense).getString("id") == it.id) createExpense = ""
            selectedId = it.id; dateText = it.date; route = "detail"; notice = true
            sale = SaleDraft(); expense = arrayListOf(ExpenseDraftLine(newId())); draftKind = ""; reason = ""; baseVersion = 0; recordId = newId()
            recoveredEdit = ""
            val recovery = repository.dao.meta("paymentRecovery")?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
            if (recovery != null && recovery.getString("recordId") == it.id) restorePayment(recovery) else if (createSale.isNotEmpty() || createExpense.isNotEmpty()) persist() else model.draft("")
            keyboard?.hide(); model.saved.value = null
        }
    }
    fun back() {
        keyboard?.hide()
        route = when (route) {
            "customer_form" -> JSONObject(customerDraft).optString("returnTo", "customers")
            "customer_picker" -> "sale"
            "catalog_picker" -> "expense"
            "customers" -> { selectedCustomerId = ""; "customers" }
            "receipt" -> "detail"
            "detail" -> detailReturn
            else -> "journal"
        }
        model.error.value = ""; persist()
    }
    fun begin(kind: String, record: BookRecord? = null) {
        if (protectDraft()) return
        model.error.value = ""; notice = false
        if (record != null) {
            recoveredEdit = ""
            recordId = record.id; baseVersion = record.version; dateText = record.date; reason = ""
            if (kind == "sale") sale = SaleDraft(record.amount.toString(), record.customer, displayChannel(record.channel), false, record.note, customerId = record.customerId)
            if (kind == "expense") expense = ArrayList(record.items.map { ExpenseDraftLine(it.id, it.name, it.amount.toString(), it.masterId) })
        } else {
            val parked = (if (kind == "sale") createSale else createExpense).takeIf { it.isNotEmpty() }?.let(::JSONObject)
                ?.takeUnless { draft -> state.records.any { it.id == draft.getString("id") } }
            if (parked != null) restoreDraft(parked)
            else {
                recordId = newId(); baseVersion = 0; reason = ""; recoveredEdit = ""
                sale = SaleDraft(); expense = arrayListOf(ExpenseDraftLine(newId()))
            }
            if (route == "journal") dateText = journalDateText
        }
        draftKind = kind; route = kind; persist()
    }
    BackHandler(!showBackup && securityScreen.isEmpty() && (route !in listOf("journal", "recap", "customers") || (route == "customers" && selectedCustomerId.isNotEmpty())) && !saving, ::back)
    if (securityScreen == "tools") {
        ToolsScreen(accessState, { securityScreen = "" }, { securityScreen = ""; showBackup = true }, { securityScreen = "pin" }, { securityScreen = "devices" })
        return
    }
    if (securityScreen == "pin") { ChangePinScreen({ securityScreen = "tools" }); return }
    if (securityScreen == "devices") { DeviceManagementScreen({ securityScreen = "tools" }); return }
    if (showBackup) { BackupScreen(model) { showBackup = false; securityScreen = "tools" }; return }
    if (!state.loaded || !draftLoaded || (navigationSession.isNotEmpty() && navigationSession != appliedNavigationSession)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (state.unreadable || error == "draft_unreadable") { Text(stringResource(if (state.unreadable) R.string.storage_unreadable else R.string.draft_unreadable), Modifier.padding(24.dp)); return }
    val entries = presentation(state)
    val selected = state.records.find { it.id == selectedId }
    val date = LocalDate.parse(if (route == "journal") journalDateText else dateText)
    val nested = route in listOf("sale", "expense", "receipt", "detail", "customer_form", "customer_picker", "catalog_picker") || (route == "customers" && selectedCustomerId.isNotEmpty())
    val customerNavigation = route in listOf("customers", "customer_form", "customer_picker") || (route in listOf("detail", "receipt") && detailReturn == "customers")
    fun navigationIcon(destination: String) = when (destination) { "journal" -> R.drawable.ic_book; "customers" -> R.drawable.ic_customers; else -> R.drawable.ic_chart }
    val formError = financialError(error)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) NavigationRail(Modifier.fillMaxHeight().testTag("navigation-rail"), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(64.dp))
                listOf("journal" to R.string.daily_book, "customers" to R.string.customers_bon, "recap" to R.string.recap).forEach { (destination, label) ->
                    NavigationRailItem(selected = route == destination || (nested && destination == if (customerNavigation) "customers" else "journal"), onClick = { route = destination; selectedCustomerId = ""; persist() },
                        icon = { AppIcon(navigationIcon(destination)) }, label = { Text(stringResource(label)) },
                        colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
            Scaffold(Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background, topBar = {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.medium, vertical = Space.tiny), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                        if (nested) IconButton(onClick = ::back, enabled = !saving, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_back, description = stringResource(R.string.back)) }
                        Text(stringResource(when (route) { "sale" -> if (baseVersion > 0 || recoveredEdit.isNotEmpty()) R.string.edit_sale else R.string.add_sale; "expense" -> if (baseVersion > 0 || recoveredEdit.isNotEmpty()) R.string.edit_shopping else R.string.add_shopping; "receipt" -> R.string.add_receipt; "detail" -> R.string.details; "recap" -> R.string.monthly_recap; "customers" -> R.string.customers_bon; "customer_picker" -> R.string.select_customer; "customer_form" -> R.string.customer_details; else -> R.string.daily_book }), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        LanguageMenu()
                        TextButton(onClick = { keyboard?.hide(); securityScreen = "tools" }) { Text(stringResource(R.string.backup_menu)) }
                    }
                    val syncStatus = notebookSyncStatus(state.pending.any { it.state == "failed" }, state.meta["syncError"].orEmpty().isNotEmpty(),
                        syncConfigured(), state.pending.isNotEmpty(), state.meta["lastSync"].orEmpty().isNotEmpty(), syncing)
                    Surface(color = MaterialTheme.colorScheme.surface,
                        contentColor = when (syncStatus) {
                            R.string.sync_complete -> LocalSyncSuccess.current
                            R.string.sync_syncing, R.string.sync_pending -> MaterialTheme.colorScheme.secondary
                            R.string.sync_review -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(stringResource(syncStatus), style = MaterialTheme.typography.labelLarge)
                                state.meta["lastSync"]?.takeIf { it.isNotBlank() }?.let {
                                    val time = Instant.parse(it).atZone(ZoneId.of("Asia/Jakarta"))
                                        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.getDefault()))
                                    Text(stringResource(R.string.sync_last_success, time), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (syncConfigured()) TextButton(onClick = { model.retry(); scheduleSync(context, forceCompatibility = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sync_now)) }
                        }
                    }
                    AppVersionPolicy.cached(state.meta)?.takeIf { it.updateAvailable() && !it.required() }?.let { policy ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (policy.updateRecommended()) R.string.app_update_recommended else R.string.app_update_available, policy.versionName), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            AppUpdateButton(policy)
                        }
                    }
                    if (state.meta["compatibilityError"] == "unknown") Text(stringResource(R.string.app_compatibility_unknown), modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                }
            }, bottomBar = {
                if (!rail && !nested) NavigationBar(Modifier.testTag("navigation-bottom"), containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("journal" to R.string.daily_book, "customers" to R.string.customers_bon, "recap" to R.string.recap).forEach { (destination, label) ->
                        NavigationBarItem(selected = route == destination, onClick = { route = destination; selectedCustomerId = ""; persist() }, icon = { AppIcon(navigationIcon(destination)) }, label = { Text(stringResource(label)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    when (route) {
                        "sale" -> SaleForm(date, sale, { changed ->
                            val aggregate = if (baseVersion == 0 && changed.channel != "Langsung") state.records.find { !it.voided && it.kind == "sale" && it.date == dateText && it.channel == channelCode(changed.channel) } else null
                            if (aggregate != null) begin("sale", aggregate) else { sale = changed; persist() }
                        }, formError, real = true, editing = baseVersion > 0, saving = saving, onDate = { dateText = it.toString(); persist() }, customerControl = {
                            CustomerSaleControl(sale, { sale = it; persist() }, { route = "customer_picker"; persist() })
                        }, extra = {
                            if (baseVersion > 0) EntryInput(stringResource(R.string.correction_reason), reason, { reason = it; persist() })
                            TextButton(onClick = ::discardForm, enabled = !saving) { Text(stringResource(R.string.discard_draft)) }
                        }) {
                            val data = JSONObject().put("date", dateText).put("amount", sale.amount).put("channel", channelCode(sale.channel)).put("customer", sale.customer).put("customerId", sale.customerId ?: JSONObject.NULL).put("note", sale.note)
                            if (baseVersion == 0) data.put("paid", sale.paid).apply { if (sale.paid) { put("receiptId", newId()).put("method", sale.method); if (sale.paymentAmount.isNotEmpty()) put("paymentAmount", sale.paymentAmount) } }
                            else data.put("reason", reason)
                            model.submit("save_sale", recordId, baseVersion, data)
                        }
                        "expense" -> ExpenseForm(date, expense, { expense = ArrayList(it); persist() }, formError, real = true, saving = saving, onDate = { dateText = it.toString(); persist() },
                            onSelectItem = { catalogLineId = it; route = "catalog_picker"; persist() }, extra = {
                            if (baseVersion > 0) EntryInput(stringResource(R.string.correction_reason), reason, { reason = it; persist() })
                            TextButton(onClick = ::discardForm, enabled = !saving) { Text(stringResource(R.string.discard_draft)) }
                        }) {
                            val data = JSONObject().put("date", dateText).put("reason", reason).put("catalogVersion", 1).put("items", JSONArray(expense.filter { it.name.isNotBlank() || it.amount.isNotBlank() }.map {
                                val masterId = it.masterId ?: state.catalog.find { master -> it.name.trim() in master.aliases }?.id
                                JSONObject().put("id", it.id).put("name", it.name).put("amount", it.amount).apply { masterId?.let { id -> put("masterId", id) } }
                            }))
                            model.submit("save_expense", recordId, baseVersion, data)
                        }
                        "catalog_picker" -> Column(Modifier.fillMaxSize()) {
                            state.pending.filter { it.state == "failed" && state.catalog.any { item -> item.id == it.recordId } }.forEach { operation ->
                                Text(stringResource(R.string.conflict_hint), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
                                TextButton(onClick = { model.review(operation.recordId) { success -> if (!success) model.error.value = "server_version_unavailable" } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.use_server)) }
                            }
                            Box(Modifier.weight(1f)) {
                                ShoppingCatalogPicker(state.catalog, state.records, ::selectCatalog,
                                    onCreate = { name, unit ->
                                        selectCreatedCatalog = true
                                        model.submitCatalog(shoppingMasterId(name), 0, JSONObject().put("name", name).put("unit", unit))
                                    }, onManage = { item, name, unit, archived, why ->
                                        selectCreatedCatalog = false
                                        model.submitCatalog(item.id, item.version, JSONObject().put("name", name).put("unit", unit).put("archived", archived).put("reason", why))
                                    }, onBack = ::back, error = formError, saving = saving)
                            }
                        }
                        "receipt" -> ReceiptForm(LocalDate.parse(receiptDate), receiptAmount, receiptMethod, formError, state.records.find { it.id == recordId },
                            customer = state.customers.find { it.id == state.records.find { row -> row.id == recordId }?.customerId }, saving = saving,
                            recoveryCount = state.meta["paymentRecovery"]?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).optJSONArray("remaining").objects().size + 1 } ?: 0,
                            onDiscard = { model.discardPaymentDraft { route = "detail"; draftKind = ""; model.error.value = "" } },
                            onDate = { receiptDate = it.toString(); persist() }, onAmount = { receiptAmount = it; persist() }, onMethod = { receiptMethod = it; persist() }) {
                            model.submit("add_receipt", recordId, baseVersion, JSONObject().put("id", receiptId).put("date", receiptDate).put("amount", receiptAmount).put("method", receiptMethod).put("reason", "Payment received"))
                        }
                        "detail" -> selected?.let { record ->
                            RecordDetail(record, state, notice, formError, saving, onCorrectDate = { target, date, why, expected ->
                                val data = JSONObject().put("date", date).put("reason", why)
                                if (target.isNotEmpty()) data.put("receiptId", target)
                                model.submit("correct_date", record.id, expected, data)
                            }, onEdit = { begin(record.kind, record) }, onReceipt = { receive(record) }, onVoid = { target, why, expected ->
                                val data = JSONObject().put("reason", why).put("includeReceipts", true)
                                if (target.isNotEmpty()) data.put("receiptId", target)
                                model.submit("void", record.id, expected, data)
                            }, onUseServer = { model.review(record.id) { success -> if (success) { route = "journal"; notice = false } else model.error.value = "server_version_unavailable" } },
                                onReopen = { model.reopen(record.id, ::restoreEdit) }, onReopenPayment = { model.reopenPayment(record.id, ::restorePayment) })
                        }
                        "customers", "customer_picker" -> CustomersScreen(state, if (route == "customer_picker") "" else selectedCustomerId, route == "customer_picker", formError,
                            onSelect = { customer ->
                                if (route == "customer_picker") { sale = sale.copy(customerId = customer.id, customer = customer.name); route = "sale"; persist() }
                                else selectedCustomerId = customer.id
                            }, onAdd = { beginCustomer() }, onEdit = { beginCustomer(it) }, onRecord = { selectedId = it.id; detailReturn = "customers"; route = "detail"; notice = false },
                            onPayment = { detailReturn = "customers"; receive(it) }, onReview = { customer -> model.review(customer.id) { success -> if (!success) model.error.value = "server_version_unavailable" } },
                            onReopen = { customer -> model.reopenCustomer(customer.id) { edit -> changeCustomerDraft(edit.json().put("returnTo", "customers")); route = "customer_form" } })
                        "customer_form" -> if (customerDraft.isNotEmpty()) CustomerForm(JSONObject(customerDraft), formError, saving, ::changeCustomerDraft,
                            onDiscard = { val target = JSONObject(customerDraft).optString("returnTo", "customers"); customerDraft = ""; model.customerDraft(""); route = target; model.error.value = "" },
                            onSave = {
                                val draft = JSONObject(customerDraft)
                                val data = JSONObject().put("name", draft.optString("name")).put("phone", draft.optString("phone")).put("address", draft.optString("address"))
                                    .put("notes", draft.optString("notes")).put("archived", draft.optBoolean("archived")).put("reason", draft.optString("reason"))
                                model.submitCustomer(draft.getString("id"), draft.getInt("version"), data)
                            })
                        "recap" -> MonthlyRecap(entries, YearMonth.parse(monthText), { monthText = it.toString() }, realRecords = state.records) { journalDateText = it; route = "journal" }
                        else -> Column(Modifier.fillMaxSize()) {
                            if (draftKind.isNotEmpty() || createSale.isNotEmpty() || createExpense.isNotEmpty() || customerDraft.isNotEmpty()) {
                                TextButton(onClick = ::resumeDraft, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.resume_notebook_draft)) }
                            }
                            if (formError.isNotEmpty()) Text(formError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
                            Box(Modifier.weight(1f)) { Journal(entries.filter { it.date == journalDateText }, LocalDate.parse(journalDateText), { journalDateText = it.toString() }, { begin("sale") }, { begin("expense") },
                                { selectedId = it.receiptFor.ifEmpty { it.id }; detailReturn = "journal"; route = "detail"; notice = false }, realTotals = totals(state.records, journalDateText, LocalDate.parse(journalDateText).plusDays(1).toString())) }
                        }
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
    "customer_required" -> R.string.bon_customer_hint
    "customer_unavailable" -> R.string.customer_unavailable
    "catalog_name_exists" -> R.string.catalog_name_exists
    "catalog_item_unavailable" -> R.string.catalog_unavailable
    "finish_draft_first" -> R.string.finish_draft_first
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
            FilterChip(selected = method == id, onClick = { onMethod(id) }, label = { Text(stringResource(label)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun ReceiptForm(date: LocalDate, amount: String, method: String, error: String, record: BookRecord?, customer: Customer?, saving: Boolean, recoveryCount: Int, onDiscard: () -> Unit, onDate: (LocalDate) -> Unit, onAmount: (String) -> Unit, onMethod: (String) -> Unit, onSave: () -> Unit) {
    val remaining = record?.let { it.amount - it.received }
    val entered = positiveAmount(amount)
    FormFrame(total = entered, error = error, real = true, saving = saving, totalLabel = R.string.bon_new_payment, saveLabel = R.string.save_payment, onSave = onSave) {
        if (record != null) Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
                    CustomerAvatar(record.customer.ifEmpty { channelLabel(displayChannel(record.channel)) })
                    Column(Modifier.weight(1f)) {
                        SectionTitle(record.customer.ifEmpty { channelLabel(displayChannel(record.channel)) })
                        customer?.phone?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text(stringResource(R.string.customer_sale_date, dateLabel(LocalDate.parse(record.date))), style = MaterialTheme.typography.bodyMedium)
                BonBalanceSummary(record.amount, record.received, remaining ?: 0, R.string.bon_original, R.string.bon_before_payment)
            }
        }
        EntryInput(stringResource(R.string.receipt_amount), amount, onAmount, money = true, prominent = true,
            error = if (remaining != null && entered != null && entered > remaining) stringResource(R.string.payment_constraint) else null,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        SectionTitle(stringResource(R.string.payment_date))
        BusinessDateControl(date, onDate)
        SectionTitle(stringResource(R.string.payment_method))
        PaymentMethod(method, onMethod)
        if (recoveryCount > 0) Text(stringResource(R.string.payment_drafts_review, recoveryCount))
        if (remaining != null) {
            MoneyRow(stringResource(R.string.bon_before_payment), remaining)
            entered?.takeIf { it <= remaining }?.let { MoneyRow(stringResource(R.string.balance_after_payment), remaining - it) }
        }
        TextButton(onClick = onDiscard, enabled = !saving) { Text(stringResource(if (recoveryCount > 1) R.string.discard_payment_drafts else R.string.discard_draft)) }
    }
}

@Composable
private fun RecordDetail(record: BookRecord, state: NotebookState, notice: Boolean, error: String, saving: Boolean, onCorrectDate: (String, String, String, Int) -> Unit, onEdit: () -> Unit, onReceipt: () -> Unit, onVoid: (String, String, Int) -> Unit, onUseServer: () -> Unit, onReopen: () -> Unit, onReopenPayment: () -> Unit) {
    var voidTarget by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var voidVersion by rememberSaveable(record.id) { mutableIntStateOf(record.version) }
    var reason by rememberSaveable(record.id) { mutableStateOf("") }
    var dateTarget by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var correctedDate by rememberSaveable(record.id) { mutableStateOf(record.date) }
    var dateVersion by rememberSaveable(record.id) { mutableIntStateOf(record.version) }
    var dateReason by rememberSaveable(record.id) { mutableStateOf("") }
    fun correctDate(target: String, date: String) {
        dateTarget = target; correctedDate = date; dateVersion = record.version; dateReason = ""; voidTarget = null
    }
    val failed = state.pending.firstOrNull { it.recordId == record.id && it.state == "failed" }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (notice) Text(stringResource(R.string.saved_local), color = MaterialTheme.colorScheme.primary)
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    IconBadge(if (record.kind == "expense") R.drawable.ic_shopping else channelIcon(displayChannel(record.channel)))
                    SectionTitle(if (record.kind == "expense") stringResource(R.string.shopping) else stringResource(R.string.sale_title, channelLabel(displayChannel(record.channel))))
                    Text(dateLabel(LocalDate.parse(record.date)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AmountText(record.amount, style = MaterialTheme.typography.headlineLarge)
                    if (record.voided) Text(stringResource(R.string.voided))
                }
            }
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
                val attempted = JSONObject(failed.payload)
                if (attempted.getString("action") == "correct_date") Text(stringResource(R.string.date_correction_attempt, attempted.getJSONObject("data").getString("date")))
                if (attempted.getString("action") == "add_receipt") {
                    val input = attempted.getJSONObject("data")
                    Text(stringResource(R.string.attempted_payment, rupiah(input.getString("amount").toLong()), input.getString("date")))
                    TextButton(onClick = onReopenPayment) { Text(stringResource(R.string.review_payment)) }
                }
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
                        else if (!record.voided) {
                            TextButton(onClick = { correctDate(payment.id, payment.date) }, enabled = failed == null && !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.correct_payment_date)) }
                            TextButton(onClick = { voidTarget = payment.id; voidVersion = record.version }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.void_payment)) }
                        }
                    }
                }
                if (!record.voided && record.received < record.amount) Button(onClick = onReceipt, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.add_receipt)) }
            }
            if (!record.voided) {
                TextButton(onClick = { correctDate("", record.date) }, enabled = failed == null && !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.correct_business_date)) }
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.edit_record)) }
                TextButton(onClick = { voidTarget = ""; voidVersion = record.version }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.void_record)) }
            }
            if (dateTarget != null) {
                SectionTitle(stringResource(if (dateTarget!!.isEmpty()) R.string.correct_business_date else R.string.correct_payment_date))
                Text(stringResource(R.string.date_correction_hint))
                BusinessDateControl(LocalDate.parse(correctedDate)) { correctedDate = it.toString() }
                EntryInput(stringResource(R.string.correction_reason), dateReason, { dateReason = it })
                Button(onClick = { onCorrectDate(dateTarget!!, correctedDate, dateReason, dateVersion); dateTarget = null },
                    enabled = dateReason.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.save_date_correction)) }
                TextButton(onClick = { dateTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            }
            if (voidTarget != null) {
                Text(stringResource(if (voidTarget!!.isEmpty()) R.string.void_effect else R.string.void_payment_effect, rupiah(if (voidTarget!!.isEmpty()) record.received else record.receipts.first { it.id == voidTarget }.amount)))
                EntryInput(stringResource(R.string.correction_reason), reason, { reason = it })
                Button(onClick = { onVoid(voidTarget!!, reason, voidVersion); voidTarget = null }, enabled = reason.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.confirm_void)) }
                TextButton(onClick = { voidTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
