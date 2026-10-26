package id.basokanggito.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID
import id.basokanggito.app.data.BookRecord
import id.basokanggito.app.data.BookTotals
import id.basokanggito.app.data.totals

@Composable
internal fun Journal(entries: List<JournalEntry>, date: LocalDate, onDate: (LocalDate) -> Unit, onSale: () -> Unit, onExpense: () -> Unit, onEntry: (JournalEntry) -> Unit, realTotals: BookTotals? = null) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val split = maxWidth >= 840.dp
        Column(Modifier.widthIn(max = 1200.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (realTotals == null) DateSelector(date, onDate) else BusinessDateControl(date, onDate)
            if (split) {
                Row(Modifier.fillMaxWidth().testTag("journal-expanded"), horizontalArrangement = Arrangement.spacedBy(Space.section), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(Space.large)) {
                        Summary(entries, realTotals)
                        EntryActions(onSale, onExpense)
                        if (realTotals != null) ChannelSummary(entries)
                    }
                    History(entries, Modifier.weight(1f), onEntry, real = realTotals != null)
                }
            } else {
                Column(Modifier.testTag("journal-compact"), verticalArrangement = Arrangement.spacedBy(Space.large)) {
                    Summary(entries, realTotals)
                    EntryActions(onSale, onExpense)
                    if (realTotals != null) ChannelSummary(entries)
                    History(entries, onEntry = onEntry, real = realTotals != null)
                }
            }
            if (realTotals == null) Text(stringResource(R.string.sample_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DateSelector(date: LocalDate, onDate: (LocalDate) -> Unit, onPick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onDate(date.minusDays(1)) }, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_back, description = stringResource(R.string.previous_date)) }
        Box(Modifier.weight(1f).then(if (onPick != null) Modifier.clickable(onClick = onPick).heightIn(min = 48.dp) else Modifier).padding(horizontal = Space.small), contentAlignment = Alignment.CenterStart) {
            Text(dateLabel(date), style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = { onDate(date.plusDays(1)) }, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_next, description = stringResource(R.string.next_date)) }
    }
}

@Composable
internal fun Summary(entries: List<JournalEntry>, realTotals: BookTotals? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.net_sales), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AmountText(realTotals?.sales ?: entries.filter { !it.isExpense && !it.isReceipt && !it.voided }.sumOf { it.amount }, style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.testTag("sales-total"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (LocalDensity.current.fontScale > 1.3f) {
                MoneyRow(stringResource(R.string.received), realTotals?.received ?: entries.sumOf { it.received })
                MoneyRow(stringResource(R.string.shopping), realTotals?.expenses ?: entries.filter { it.isExpense }.sumOf { it.amount })
            } else Row(horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.received), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AmountText(realTotals?.received ?: entries.sumOf { it.received }, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.shopping), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AmountText(realTotals?.expenses ?: entries.filter { it.isExpense }.sumOf { it.amount }, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (realTotals != null) MoneyRow(stringResource(R.string.cash_difference), realTotals.cashDifference)
        }
    }
}

@Composable
internal fun MoneyRow(label: String, amount: Number) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    if (largeText) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label); AmountText(amount) }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AmountText(amount, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun EntryActions(onSale: () -> Unit, onExpense: () -> Unit) {
    val sale: @Composable (Modifier) -> Unit = { modifier ->
        Button(onClick = onSale, shape = MaterialTheme.shapes.medium, modifier = modifier.heightIn(min = 56.dp)) {
            AppIcon(R.drawable.ic_add); Spacer(Modifier.width(Space.small)); Text(stringResource(R.string.add_sale))
        }
    }
    val shopping: @Composable (Modifier) -> Unit = { modifier ->
        FilledTonalButton(onClick = onExpense, shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            modifier = modifier.heightIn(min = 56.dp)) {
            AppIcon(R.drawable.ic_shopping); Spacer(Modifier.width(Space.small)); Text(stringResource(R.string.add_shopping))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) { sale(Modifier.weight(1f)); shopping(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(Space.small)) { sale(Modifier.fillMaxWidth()); shopping(Modifier.fillMaxWidth()) }
    }
}

@Composable
internal fun History(entries: List<JournalEntry>, modifier: Modifier = Modifier, onEntry: (JournalEntry) -> Unit, real: Boolean = false) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column {
            SectionTitle(stringResource(R.string.history), Modifier.padding(horizontal = Space.medium, vertical = 12.dp))
            if (entries.isEmpty()) {
                if (real) Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.empty_records)); Text(stringResource(R.string.empty_records_body), style = MaterialTheme.typography.bodyMedium) }
                else ContentState(ContentStatus.Empty)
            }
            entries.forEachIndexed { index, entry ->
                TransactionRow(entry, onClick = { onEntry(entry) })
                if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = Space.medium), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun TransactionRow(entry: JournalEntry, onClick: () -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.view_details, entryTitle(entry)), onClick = onClick)
        .semantics(mergeDescendants = true) {}.padding(horizontal = Space.medium, vertical = 12.dp).heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        AppIcon(if (entry.isExpense) R.drawable.ic_shopping else R.drawable.ic_sale, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entryTitle(entry), style = MaterialTheme.typography.labelLarge)
            Text(if (entry.isExpense) pluralStringResource(R.plurals.item_count, entry.lines.size, entry.lines.size) else entry.customer.ifEmpty { if (entry.channel == "Langsung") stringResource(R.string.no_customer) else stringResource(R.string.daily_total) },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.voided) Text(stringResource(R.string.voided), style = MaterialTheme.typography.bodyMedium)
            else if (!entry.isExpense && !entry.isReceipt) Text(stringResource(if (entry.received == entry.amount) R.string.paid else R.string.unpaid), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.syncState.isNotEmpty()) Text(stringResource(if (entry.syncState == "failed") R.string.sync_review else R.string.sync_pending), style = MaterialTheme.typography.labelMedium)
            if (largeText) AmountText(entry.amount, style = MaterialTheme.typography.titleMedium)
        }
        if (!largeText) AmountText(entry.amount, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun Detail(entry: JournalEntry, notice: String, onEdit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (notice.isNotEmpty()) Text(notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary)
        SectionTitle(entryTitle(entry))
        Text(dateLabel(LocalDate.parse(entry.date)), color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(entry.amount, style = MaterialTheme.typography.headlineLarge)
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                if (entry.isExpense) entry.lines.forEach { MoneyRow(lineLabel(it), it.amount) }
                else {
                    Text(stringResource(R.string.channel_value, channelLabel(entry.channel)))
                    if (entry.customer.isNotEmpty()) Text(stringResource(R.string.customer_value, entry.customer))
                    MoneyRow(stringResource(R.string.received), entry.received)
                    MoneyRow(stringResource(R.string.unpaid), entry.amount - entry.received)
                    Text(stringResource(R.string.receipt_date, if (entry.received > 0) dateLabel(LocalDate.parse(entry.date)) else stringResource(R.string.none_yet)), style = MaterialTheme.typography.bodyMedium)
                    if (entry.channel != "Langsung") Text(stringResource(R.string.sales_after_fees), style = MaterialTheme.typography.bodyMedium)
                }
                if (entry.note.isNotEmpty()) Text(entry.note)
            }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.edit_sample)) }
        Text(stringResource(R.string.sample_edit_note), style = MaterialTheme.typography.bodyMedium)
    }
    }
}

@Composable
internal fun MonthlyRecap(entries: List<JournalEntry>, month: YearMonth, onMonth: (YearMonth) -> Unit, realRecords: List<BookRecord>? = null, onDay: (String) -> Unit) {
    val monthly = entries.filter { YearMonth.from(LocalDate.parse(it.date)) == month }
    val realTotals = realRecords?.let { totals(it, month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_back, description = stringResource(R.string.previous_month)) }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = Space.small))
            IconButton(onClick = { onMonth(month.plusMonths(1)) }, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_next, description = stringResource(R.string.next_month)) }
        }
        BoxWithConstraints {
            if (maxWidth >= 840.dp) Row(horizontalArrangement = Arrangement.spacedBy(Space.section), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(320.dp)) { Summary(monthly, realTotals) }
                ChannelSummary(monthly, Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(Space.large)) { Summary(monthly, realTotals); ChannelSummary(monthly) }
        }
        SectionTitle(stringResource(R.string.by_day))
        if (monthly.isEmpty()) Text(stringResource(if (realRecords == null) R.string.month_empty else R.string.month_empty_records))
        monthly.groupBy { it.date }.toSortedMap(compareByDescending { it }).forEach { (day, daily) ->
            Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { onDay(day) }) {
                Column(Modifier.padding(Space.medium), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    Text(dateLabel(LocalDate.parse(day)), style = MaterialTheme.typography.titleMedium)
                    MoneyRow(stringResource(R.string.net_sales), daily.filter { !it.isExpense && !it.isReceipt && !it.voided }.fold(java.math.BigInteger.ZERO) { sum, entry -> sum + entry.amount.toBigInteger() })
                }
            }
        }
    }
}

@Composable
internal fun ChannelSummary(entries: List<JournalEntry>, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            SectionTitle(stringResource(R.string.by_channel))
            SalesChannels.forEach { channel -> MoneyRow(channelLabel(channel), entries.filter { !it.isExpense && !it.isReceipt && !it.voided && it.channel == channel }.fold(java.math.BigInteger.ZERO) { sum, entry -> sum + entry.amount.toBigInteger() }) }
        }
    }
}
