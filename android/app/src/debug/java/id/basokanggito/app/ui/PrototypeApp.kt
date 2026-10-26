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

private val Destinations = listOf(Triple("journal", R.string.daily_book, R.drawable.ic_book), Triple("recap", R.string.recap, R.drawable.ic_chart))

@Composable
fun BukuHarianApp() {
    var destination by rememberSaveable { mutableStateOf("journal") }
    var dateText by rememberSaveable { mutableStateOf(SampleDate.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(SampleDate).toString()) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf("") }
    var entries by rememberSaveable { mutableStateOf<List<JournalEntry>>(ArrayList(SampleData.entries)) }
    var sale by rememberSaveable { mutableStateOf(SaleDraft()) }
    var expense by rememberSaveable { mutableStateOf<List<ExpenseDraftLine>>(arrayListOf(ExpenseDraftLine(UUID.randomUUID().toString()))) }
    var notice by rememberSaveable { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val date = LocalDate.parse(dateText)
    val selected = entries.find { it.id == selectedId }
    val errorText = when (saveError) {
        R.string.aggregate_error -> stringResource(saveError, channelLabel(sale.channel))
        0 -> ""
        else -> stringResource(saveError)
    }
    val resources = LocalResources.current
    val form = destination == "sale" || destination == "expense"
    val nested = form || destination == "detail"
    fun back() { keyboard?.hide(); destination = "journal"; saveError = 0 }
    BackHandler(enabled = destination != "journal", onBack = ::back)

    fun save(entry: JournalEntry) {
        val others = entries.filter { it.id != entry.id }
        try { Math.addExact(others.sumOf { it.amount }, entry.amount) } catch (_: ArithmeticException) {
            saveError = R.string.overflow_error
            return
        }
        entries = ArrayList(others + entry)
        selectedId = entry.id
        editingId = ""
        notice = true
        saveError = 0
        keyboard?.hide()
        destination = "detail"
        sale = SaleDraft()
        expense = arrayListOf(ExpenseDraftLine(UUID.randomUUID().toString()))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                NavigationRail(modifier = Modifier.fillMaxHeight().testTag("navigation-rail"), containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(64.dp))
                    Destinations.forEach { (id, label, icon) ->
                        NavigationRailItem(selected = destination == id || (nested && id == "journal"), onClick = { destination = id; notice = false }, icon = { AppIcon(icon) }, label = { Text(stringResource(label)) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column(Modifier.statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.medium, vertical = Space.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.small),
                        ) {
                            if (nested) IconButton(onClick = ::back, modifier = Modifier.size(48.dp)) { AppIcon(R.drawable.ic_back, description = stringResource(R.string.back)) }

                            Text(
                                stringResource(when (destination) { "sale" -> R.string.add_sale; "expense" -> R.string.add_shopping; "detail" -> R.string.details; "recap" -> R.string.monthly_recap; else -> R.string.daily_book }),
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                            )
                            LanguageMenu()
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(R.string.sample_notice), color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
                        }
                    }
                },
                bottomBar = {
                    if (!rail && !nested) NavigationBar(modifier = Modifier.testTag("navigation-bottom"), containerColor = MaterialTheme.colorScheme.surface) {
                        Destinations.forEach { (id, label, icon) ->
                            NavigationBarItem(selected = destination == id || (nested && id == "journal"), onClick = { destination = id; notice = false }, icon = { AppIcon(icon) }, label = { Text(stringResource(label)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    when (destination) {
                        "sale" -> SaleForm(date, sale, { sale = it; saveError = 0 }, errorText) {
                            val amount = positiveAmount(sale.amount) ?: return@SaleForm
                            // One daily platform total per channel/date, including edits in this example session.
                            val existing = entries.find { !it.isExpense && it.channel == sale.channel && it.date == dateText && sale.channel != "Langsung" }
                            if (editingId.isNotEmpty() && existing != null && existing.id != editingId) {
                                saveError = R.string.aggregate_error
                                return@SaleForm
                            }
                            save(JournalEntry(editingId.ifEmpty { existing?.id ?: UUID.randomUUID().toString() }, dateText,
                                sale.channel, amount, if (sale.paid) amount else 0, sale.customer.trim(), sale.note.trim()))
                        }
                        "expense" -> ExpenseForm(date, expense, { expense = ArrayList(it); saveError = 0 }, errorText) {
                            val total = expenseTotal(expense) ?: return@ExpenseForm
                            save(JournalEntry(editingId.ifEmpty { UUID.randomUUID().toString() }, dateText, "Belanja", total,
                                lines = expense.filter { it.name.isNotBlank() }.map { JournalLine(it.name.trim(), positiveAmount(it.amount)!!) }))
                        }
                        "detail" -> selected?.let { entry ->
                            Detail(entry, if (notice) stringResource(R.string.sample_updated) else "", onEdit = {
                                editingId = entry.id; dateText = entry.date; notice = false; saveError = 0
                                if (entry.isExpense) {
                                    expense = ArrayList(entry.lines.map { ExpenseDraftLine(UUID.randomUUID().toString(), if (it.sampleNameRes != 0) resources.getString(it.sampleNameRes) else it.name, it.amount.toString()) })
                                    destination = "expense"
                                } else {
                                    sale = SaleDraft(entry.amount.toString(), entry.customer, entry.channel, entry.received > 0, entry.note)
                                    destination = "sale"
                                }
                            })
                        }
                        "recap" -> MonthlyRecap(entries, YearMonth.parse(monthText), { monthText = it.toString() }) {
                            dateText = it; destination = "journal"
                        }
                        else -> Journal(entries.filter { it.date == dateText }, date,
                            onDate = { dateText = it.toString() },
                            onSale = { if (editingId.isNotEmpty()) sale = SaleDraft(); editingId = ""; saveError = 0; destination = "sale" },
                            onExpense = {
                                if (editingId.isNotEmpty()) expense = arrayListOf(ExpenseDraftLine(UUID.randomUUID().toString()))
                                editingId = ""; saveError = 0; destination = "expense"
                            },
                            onEntry = { selectedId = it.id; notice = false; destination = "detail" },
                        )
                    }
                }
            }
        }
    }
}
