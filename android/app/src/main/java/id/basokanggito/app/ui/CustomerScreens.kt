package id.basokanggito.app.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import id.basokanggito.app.data.*
import org.json.JSONObject
import java.time.LocalDate

@Composable
internal fun CustomerSaleControl(draft: SaleDraft, onChange: (SaleDraft) -> Unit, onChoose: () -> Unit) {
    OutlinedButton(onClick = onChoose, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        AppIcon(R.drawable.ic_customers)
        Spacer(Modifier.width(Space.small))
        Text(if (draft.customerId == null) stringResource(R.string.general_customer) else draft.customer, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        AppIcon(R.drawable.ic_next)
    }
    if (draft.customerId == null) EntryInput(stringResource(R.string.customer_optional), draft.customer, { onChange(draft.copy(customer = it)) })
    else TextButton(onClick = { onChange(draft.copy(customerId = null, customer = "")) }) { Text(stringResource(R.string.general_customer)) }
}

@Composable
internal fun CustomersScreen(state: NotebookState, selectedId: String, picking: Boolean, error: String,
    onSelect: (Customer) -> Unit, onAdd: () -> Unit, onEdit: (Customer) -> Unit, onRecord: (BookRecord) -> Unit,
    onPayment: (BookRecord) -> Unit, onReview: (Customer) -> Unit, onReopen: (Customer) -> Unit) {
    var search by rememberSaveable(picking) { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    val customers = state.customers.filter {
        (if (picking) !it.archived else archived || !it.archived || customerOutstanding(it.id, state.records).signum() > 0) &&
            (it.name.contains(search.trim(), ignoreCase = true) || it.phone.contains(search.trim(), ignoreCase = true))
    }.sortedWith(compareByDescending<Customer> { customerOutstanding(it.id, state.records).signum() > 0 }.thenBy { it.name.lowercase() }.thenBy { it.id })
    val selectedCustomer = state.customers.find { it.id == selectedId }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchLabel = stringResource(R.string.customer_search)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 840.dp && !picking
        val list: @Composable (Modifier) -> Unit = { modifier ->
            LazyColumn(modifier.testTag("customer-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                OutlinedTextField(search, { search = it }, singleLine = true,
                    placeholder = { Text(searchLabel) }, leadingIcon = { AppIcon(R.drawable.ic_search) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = searchLabel })
                Button(onClick = onAdd, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    AppIcon(R.drawable.ic_add); Spacer(Modifier.width(Space.small)); Text(stringResource(R.string.add_customer))
                }
                if (!picking) FilterChip(selected = archived, onClick = { archived = !archived }, label = { Text(stringResource(R.string.show_archived)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.heightIn(min = 48.dp))
                SectionTitle(stringResource(R.string.customer_list))
                if (customers.isEmpty()) Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                        IconBadge(R.drawable.ic_customers)
                        Text(stringResource(if (search.isBlank()) R.string.customers_empty else R.string.customer_no_results))
                    }
                }
                }
                }
                    items(customers, key = { it.id }) { customer ->
                        val outstanding = customerOutstanding(customer.id, state.records)
                        val large = LocalDensity.current.fontScale > 1.3f
                        val active = selectedId == customer.id
                        Surface(onClick = { onSelect(customer) }, shape = MaterialTheme.shapes.large,
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth().semantics { selected = active }) {
                            Row(Modifier.padding(Space.medium), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                                CustomerAvatar(customer.name)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                                    Text(customer.name, style = MaterialTheme.typography.titleMedium)
                                    if (customer.phone.isNotEmpty()) Text(customer.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (customer.archived) Text(stringResource(R.string.customer_archived), style = MaterialTheme.typography.labelLarge)
                                    if (large) {
                                        AmountText(outstanding, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(if (outstanding.signum() > 0) R.string.bon_outstanding else R.string.bon_clear), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (state.pending.any { it.recordId == customer.id && it.state == "failed" }) Text(stringResource(R.string.sync_review), color = MaterialTheme.colorScheme.error)
                                }
                                if (!large) {
                                    Column(Modifier.widthIn(max = 140.dp), horizontalAlignment = Alignment.End) {
                                        AmountText(outstanding, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(if (outstanding.signum() > 0) R.string.bon_outstanding else R.string.bon_clear), style = MaterialTheme.typography.labelMedium)
                                    }
                                    AppIcon(R.drawable.ic_next)
                                }
                            }
                        }
                    }
            }
        }
        val detail: @Composable (Modifier) -> Unit = { modifier ->
            if (selectedCustomer != null) CustomerDetail(selectedCustomer, state, error, modifier, { onEdit(selectedCustomer) }, onRecord, onPayment,
                { onReview(selectedCustomer) }, { onReopen(selectedCustomer) })
            else Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.choose_customer_hint)) }
        }
        if (split) Row(Modifier.fillMaxSize().testTag("customer-split")) { list(Modifier.weight(0.42f).fillMaxHeight()); VerticalDivider(); detail(Modifier.weight(0.58f).fillMaxHeight()) }
        else if (selectedCustomer != null && !picking) detail(Modifier.fillMaxSize()) else list(Modifier.fillMaxSize())
    }
}

@Composable
private fun CustomerDetail(customer: Customer, state: NotebookState, error: String, modifier: Modifier,
    onEdit: () -> Unit, onRecord: (BookRecord) -> Unit, onPayment: (BookRecord) -> Unit, onReview: () -> Unit, onReopen: () -> Unit) {
    val history = customerSales(customer.id, state.records).sortedWith(compareByDescending<BookRecord> { !it.voided && it.received < it.amount }.thenByDescending { it.date }.thenByDescending { it.createdAt })
    val failed = state.pending.any { it.recordId == customer.id && it.state == "failed" }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp).testTag("customer-detail"), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            item {
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.medium), verticalAlignment = Alignment.CenterVertically) {
                            CustomerAvatar(customer.name)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                                SectionTitle(customer.name)
                                if (customer.phone.isNotBlank()) Text(customer.phone, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (customer.archived) Text(stringResource(R.string.customer_archived))
                            }
                        }
                        if (customer.address.isNotBlank()) Text(customer.address)
                        if (customer.notes.isNotBlank()) Text(customer.notes)
                        val active = history.filterNot { it.voided }
                        BonBalanceSummary(active.fold(java.math.BigInteger.ZERO) { sum, row -> sum + row.amount.toBigInteger() },
                            active.fold(java.math.BigInteger.ZERO) { sum, row -> sum + row.received.toBigInteger() },
                            customerOutstanding(customer.id, state.records), R.string.bon_linked_sales)
                    }
                }
            }
            if (failed) item {
                Text(stringResource(R.string.conflict_hint), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.local_preserved))
                state.serverCustomers[customer.id]?.let { server ->
                    Text(stringResource(R.string.server_customer, server.name))
                    TextButton(onClick = onReview) { Text(stringResource(R.string.use_server)) }
                }
                TextButton(onClick = onReopen) { Text(stringResource(R.string.reopen_local)) }
            }
            if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item { OutlinedButton(onClick = onEdit, enabled = !failed, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.edit_customer)) } }
            item { SectionTitle(stringResource(R.string.customer_history)) }
            if (history.isEmpty()) item { Text(stringResource(R.string.customer_no_history)) }
            items(history, key = { it.id }) { record ->
                val needsReview = state.pending.any { it.recordId == record.id && it.state == "failed" }
                Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(Space.medium), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onRecord(record) }.heightIn(min = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.customer_sale_date, dateLabel(LocalDate.parse(record.date))), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        AppIcon(R.drawable.ic_next)
                    }
                    MoneyRow(stringResource(R.string.sale_total), record.amount)
                    MoneyRow(stringResource(R.string.received), record.received)
                    MoneyRow(stringResource(R.string.unpaid), if (record.voided) 0L else record.amount - record.received)
                    Text(stringResource(when { record.voided -> R.string.voided; record.received == record.amount -> R.string.bon_paid; record.received > 0 -> R.string.bon_partial; else -> R.string.bon_unpaid }), style = MaterialTheme.typography.labelLarge)
                    if (needsReview) Text(stringResource(R.string.sync_review), color = MaterialTheme.colorScheme.error)
                    if (record.customer != customer.name) Text(stringResource(R.string.customer_snapshot, record.customer))
                    if (record.receipts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    record.receipts.sortedByDescending { it.date }.forEach { receipt ->
                        MoneyRow(stringResource(if (receipt.voided) R.string.customer_void_payment else R.string.customer_payment_date, dateLabel(LocalDate.parse(receipt.date))), receipt.amount)
                        Text(stringResource(if (receipt.method == "cash") R.string.cash else R.string.transfer))
                    }
                    if (!record.voided && record.received < record.amount && !needsReview) Button(onClick = { onPayment(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.add_receipt)) }
                }
                }
            }
        }
    }
}

@Composable
internal fun CustomerForm(draft: JSONObject, error: String, saving: Boolean, onChange: (JSONObject) -> Unit, onDiscard: () -> Unit, onSave: () -> Unit) {
    val name = draft.optString("name")
    var attempted by rememberSaveable(draft.getString("id")) { mutableStateOf(false) }
    fun change(key: String, value: Any) { onChange(JSONObject(draft.toString()).put(key, value)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                EntryInput(stringResource(R.string.customer_name), name, { change("name", it) }, error = if (attempted && name.isBlank()) stringResource(R.string.name_error) else null)
                EntryInput(stringResource(R.string.customer_phone), draft.optString("phone"), { change("phone", it) })
                EntryInput(stringResource(R.string.customer_address), draft.optString("address"), { change("address", it) })
                EntryInput(stringResource(R.string.customer_notes), draft.optString("notes"), { change("notes", it) }, imeAction = ImeAction.Done)
                if (draft.getInt("version") > 0) {
                    FilterChip(selected = draft.optBoolean("archived"), onClick = { change("archived", !draft.optBoolean("archived")) }, label = { Text(stringResource(R.string.archive_customer)) }, modifier = Modifier.heightIn(min = 48.dp))
                    Text(stringResource(R.string.archive_customer_hint))
                    EntryInput(stringResource(R.string.correction_reason), draft.optString("reason"), { change("reason", it) })
                }
                TextButton(onClick = onDiscard, enabled = !saving) { Text(stringResource(R.string.discard_draft)) }
            }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempted = true; if (name.isNotBlank()) onSave() }, enabled = !saving, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.save_record)) }
            }
        }
    }
}
