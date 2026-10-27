package id.basokanggito.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun SaleForm(date: LocalDate, draft: SaleDraft, onChange: (SaleDraft) -> Unit, saveError: String, real: Boolean = false, editing: Boolean = false, onDate: ((LocalDate) -> Unit)? = null, customerControl: (@Composable () -> Unit)? = null, extra: @Composable ColumnScope.() -> Unit = {}, onSave: () -> Unit) {
    var attempted by rememberSaveable { mutableStateOf(false) }
    var channelsOpen by remember { mutableStateOf(false) }
    val amount = positiveAmount(draft.amount)
    FormFrame(total = amount, error = saveError, real = real, onSave = { attempted = true; if (amount != null) onSave() }) {
        if (onDate == null) Text(dateLabel(date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else BusinessDateControl(date, onDate)
        EntryInput(stringResource(R.string.amount_label), draft.amount, { onChange(draft.copy(amount = it)) }, money = true, prominent = true,
            error = if (attempted && amount == null) stringResource(R.string.amount_error) else null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.channel_label), style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { channelsOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.small) {
                    Text(channelLabel(draft.channel), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    AppIcon(R.drawable.ic_next)
                }
                DropdownMenu(expanded = channelsOpen, onDismissRequest = { channelsOpen = false }) {
                    SalesChannels.forEach { channel ->
                        DropdownMenuItem(text = { Text(channelLabel(channel)) }, onClick = { onChange(draft.copy(channel = channel, paid = channel == "Langsung", customerId = if (channel == "Langsung") draft.customerId else null, paymentAmount = "")); channelsOpen = false })
                    }
                }
            }
        }
        if (draft.channel != "Langsung") Text(stringResource(if (real) R.string.platform_real_hint else R.string.platform_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (customerControl != null && draft.channel == "Langsung") customerControl()
        else EntryInput(stringResource(R.string.customer_optional), draft.customer, { onChange(draft.copy(customer = it)) })
        if (!editing) Row(Modifier.fillMaxWidth().toggleable(value = draft.paid, role = Role.Checkbox, onValueChange = { onChange(draft.copy(paid = it)) }).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.paid, onCheckedChange = null)
            Spacer(Modifier.width(Space.small))
            Text(stringResource(R.string.money_received), modifier = Modifier.weight(1f))
        }
        if (!editing && !draft.paid) Text(stringResource(if (real && draft.channel == "Langsung") R.string.bon_customer_hint else R.string.unpaid_hint), style = MaterialTheme.typography.bodyMedium)
        if (real && draft.paid && !editing) {
            EntryInput(stringResource(R.string.initial_payment), draft.paymentAmount, { onChange(draft.copy(paymentAmount = it)) }, money = true)
            Text(stringResource(R.string.initial_payment_hint), style = MaterialTheme.typography.bodyMedium)
            val received = if (draft.paymentAmount.isEmpty()) amount else positiveAmount(draft.paymentAmount)
            if (amount != null && received != null && received <= amount) MoneyRow(stringResource(R.string.unpaid), amount - received)
        }
        if (real && draft.paid && !editing) PaymentMethod(draft.method) { onChange(draft.copy(method = it)) }
        if (editing) Text(stringResource(R.string.edit_payment_hint), style = MaterialTheme.typography.bodyMedium)
        EntryInput(stringResource(R.string.note_optional), draft.note, { onChange(draft.copy(note = it)) }, imeAction = ImeAction.Done)
        extra()
    }
}

@Composable
fun ExpenseForm(date: LocalDate, lines: List<ExpenseDraftLine>, onChange: (List<ExpenseDraftLine>) -> Unit, saveError: String, real: Boolean = false, onDate: ((LocalDate) -> Unit)? = null, extra: @Composable ColumnScope.() -> Unit = {}, onSave: () -> Unit) {
    var attempted by rememberSaveable { mutableStateOf(false) }
    var removed by rememberSaveable { mutableStateOf<ExpenseDraftLine?>(null) }
    var removedIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusId by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val total = expenseTotal(lines)
    val stacked = LocalDensity.current.fontScale > 1.3f
    FormFrame(total, saveError.ifEmpty { if (attempted && total == null) stringResource(R.string.shopping_error) else "" },
        real = real,
        onSave = { attempted = true; if (total != null) onSave() }) {
        if (onDate == null) Text(dateLabel(date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else BusinessDateControl(date, onDate)
        Text(stringResource(R.string.shopping_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEachIndexed { index, line ->
            key(line.id) {
                val nameFocus = remember { FocusRequester() }
                val amountFocus = remember { FocusRequester() }
                LaunchedEffect(focusId) {
                    if (focusId == line.id) { nameFocus.requestFocus(); keyboard?.show(); focusId = "" }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wrap = stacked || maxWidth < 340.dp
                    val remove: @Composable () -> Unit = {
                        IconButton(onClick = {
                            removed = line; removedIndex = index
                            val remaining = lines.filterNot { it.id == line.id }.ifEmpty { listOf(ExpenseDraftLine(UUID.randomUUID().toString())) }
                            onChange(remaining); focusId = remaining[index.coerceAtMost(remaining.lastIndex)].id
                        }, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_close, description = stringResource(R.string.remove_item, index + 1)) }
                    }
                    val filled = line.name.isNotBlank() || line.amount.isNotBlank()
                    val updateName: (String) -> Unit = { value -> onChange(lines.map { if (it.id == line.id) it.copy(name = value) else it }) }
                    val updateAmount: (String) -> Unit = { value -> onChange(lines.map { if (it.id == line.id) it.copy(amount = value) else it }) }
                    val nameError = if (attempted && filled && line.name.isBlank()) stringResource(R.string.name_error) else null
                    val amountError = if (attempted && filled && positiveAmount(line.amount) == null) stringResource(R.string.amount_error) else null
                    val nameField: @Composable (Modifier) -> Unit = { modifier ->
                        EntryInput(stringResource(R.string.item_name), line.name, updateName, modifier.focusRequester(nameFocus), error = nameError,
                            description = stringResource(R.string.item_name_accessibility, (index + 1).toString()), onNext = { amountFocus.requestFocus() })
                    }
                    val amountField: @Composable (Modifier) -> Unit = { modifier ->
                        EntryInput(stringResource(R.string.item_amount), line.amount, updateAmount, modifier.focusRequester(amountFocus), money = true, error = amountError,
                            description = stringResource(R.string.item_amount_accessibility, (index + 1).toString()), onNext = {
                                if (index < lines.lastIndex) focusId = lines[index + 1].id
                                else { val next = ExpenseDraftLine(UUID.randomUUID().toString()); onChange(lines + next); focusId = next.id }
                            })
                    }
                    if (wrap) Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { nameField(Modifier.weight(1f)); remove() }
                        amountField(Modifier.fillMaxWidth())
                    } else Row(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
                        nameField(Modifier.weight(1.15f)); amountField(Modifier.weight(1f)); remove()
                    }
                }
            }
        }
        TextButton(onClick = { val next = ExpenseDraftLine(UUID.randomUUID().toString()); onChange(lines + next); focusId = next.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            AppIcon(R.drawable.ic_add); Spacer(Modifier.width(Space.small)); Text(stringResource(R.string.add_item))
        }
        removed?.let { line ->
            TextButton(onClick = {
                val restored = lines.toMutableList()
                if (restored.size == 1 && restored[0].name.isEmpty() && restored[0].amount.isEmpty()) restored.clear()
                restored.add(removedIndex.coerceAtMost(restored.size), line)
                onChange(restored); focusId = line.id; removed = null
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.undo_remove)) }
        }
        extra()
    }
}

@Composable
internal fun FormFrame(total: Long?, error: String, real: Boolean = false, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.medium), content = content)
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.large, vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (real) R.string.total_record else R.string.total_sample), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        AmountText(total ?: 0, style = MaterialTheme.typography.titleLarge)
                    }
                    Button(onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) { Text(stringResource(if (real) R.string.save_record else R.string.save_sample)) }
                }
            }
        }
    }
}
