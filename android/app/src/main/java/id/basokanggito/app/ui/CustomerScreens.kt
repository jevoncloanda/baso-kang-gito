package id.basokanggito.app.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import id.basokanggito.app.data.*
import org.json.JSONObject
import java.time.LocalDate

@Composable
internal fun CustomerSaleControl(draft: SaleDraft, onChange: (SaleDraft) -> Unit, onChoose: () -> Unit) {
    OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(if (draft.customerId == null) stringResource(R.string.general_customer) else draft.customer, Modifier.weight(1f))
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
    val selected = state.customers.find { it.id == selectedId }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 840.dp && !picking
        val list: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                EntryInput(stringResource(R.string.customer_search), search, { search = it }, imeAction = ImeAction.Done)
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.add_customer)) }
                if (!picking) FilterChip(selected = archived, onClick = { archived = !archived }, label = { Text(stringResource(R.string.show_archived)) }, modifier = Modifier.heightIn(min = 48.dp))
                if (customers.isEmpty()) Text(stringResource(if (search.isBlank()) R.string.customers_empty else R.string.customer_no_results))
                LazyColumn(Modifier.weight(1f).testTag("customer-list")) {
                    items(customers, key = { it.id }) { customer ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(customer) }.heightIn(min = 64.dp).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(customer.name, style = MaterialTheme.typography.titleMedium)
                            if (customer.phone.isNotEmpty()) Text(customer.phone)
                            if (customer.archived) Text(stringResource(R.string.customer_archived), style = MaterialTheme.typography.labelLarge)
                            val outstanding = customerOutstanding(customer.id, state.records)
                            if (outstanding.signum() > 0) {
                                Text(stringResource(R.string.bon_outstanding), style = MaterialTheme.typography.labelLarge)
                                AmountText(outstanding)
                            }
                            if (state.pending.any { it.recordId == customer.id && it.state == "failed" }) Text(stringResource(R.string.sync_review), color = MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        val detail: @Composable (Modifier) -> Unit = { modifier ->
            if (selected != null) CustomerDetail(selected, state, error, modifier, { onEdit(selected) }, onRecord, onPayment,
                { onReview(selected) }, { onReopen(selected) })
            else Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.choose_customer_hint)) }
        }
        if (split) Row(Modifier.fillMaxSize().testTag("customer-split")) { list(Modifier.weight(0.42f).fillMaxHeight()); VerticalDivider(); detail(Modifier.weight(0.58f).fillMaxHeight()) }
        else if (selected != null && !picking) detail(Modifier.fillMaxSize()) else list(Modifier.fillMaxSize())
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
                SectionTitle(customer.name)
                if (customer.archived) Text(stringResource(R.string.customer_archived))
                if (customer.phone.isNotBlank()) Text(customer.phone)
                if (customer.address.isNotBlank()) Text(customer.address)
                if (customer.notes.isNotBlank()) Text(customer.notes)
                Text(stringResource(R.string.bon_outstanding), Modifier.padding(top = 16.dp))
                AmountText(customerOutstanding(customer.id, state.records), style = MaterialTheme.typography.headlineLarge)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRecord(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.customer_sale_date, dateLabel(LocalDate.parse(record.date))), Modifier.weight(1f))
                    }
                    MoneyRow(stringResource(R.string.sale_total), record.amount)
                    MoneyRow(stringResource(R.string.received), record.received)
                    MoneyRow(stringResource(R.string.unpaid), if (record.voided) 0L else record.amount - record.received)
                    Text(stringResource(when { record.voided -> R.string.voided; record.received == record.amount -> R.string.bon_paid; else -> R.string.bon_unpaid }))
                    if (needsReview) Text(stringResource(R.string.sync_review), color = MaterialTheme.colorScheme.error)
                    if (record.customer != customer.name) Text(stringResource(R.string.customer_snapshot, record.customer))
                    record.receipts.sortedByDescending { it.date }.forEach { receipt ->
                        MoneyRow(stringResource(if (receipt.voided) R.string.customer_void_payment else R.string.customer_payment_date, dateLabel(LocalDate.parse(receipt.date))), receipt.amount)
                        Text(stringResource(if (receipt.method == "cash") R.string.cash else R.string.transfer))
                    }
                    if (!record.voided && record.received < record.amount && !needsReview) Button(onClick = { onPayment(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.add_receipt)) }
                    HorizontalDivider()
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
                Button(onClick = { attempted = true; if (name.isNotBlank()) onSave() }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.save_record)) }
            }
        }
    }
}
