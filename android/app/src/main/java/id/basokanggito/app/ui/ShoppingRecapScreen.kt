package id.basokanggito.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import id.basokanggito.app.data.BookRecord
import id.basokanggito.app.data.CatalogItem
import id.basokanggito.app.data.shoppingRecap
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@Composable
internal fun ShoppingRecapScreen(
    records: List<BookRecord>,
    catalog: List<CatalogItem>,
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    selectedKey: String,
    onSelectItem: (String) -> Unit,
) {
    val recap = remember(records, catalog, month) { shoppingRecap(records, catalog, month) }
    val listState = key(month) { rememberLazyListState() }
    val selected = recap.items.firstOrNull { it.key == selectedKey }
    val locale = LocalConfiguration.current.locales[0]
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val content = Modifier.widthIn(max = 640.dp).fillMaxSize()
        if (selectedKey.isNotEmpty()) {
            LazyColumn(content, contentPadding = PaddingValues(Space.medium)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)))
                        if (selected != null) {
                            SectionTitle(selected.name ?: stringResource(R.string.shopping_recap_unattributed))
                            AmountText(selected.total, style = MaterialTheme.typography.headlineMedium)
                        }
                        SectionTitle(stringResource(R.string.shopping_recap_purchases))
                    }
                }
                if (selected == null) {
                    item { Text(stringResource(R.string.shopping_recap_no_purchases)) }
                } else {
                    items(selected.purchases) { purchase ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.fillMaxWidth().padding(vertical = Space.small)) {
                            MoneyRow(dateLabel(LocalDate.parse(purchase.date)), purchase.amount)
                        }
                    }
                }
            }
        } else {
            LazyColumn(content, state = listState, contentPadding = PaddingValues(Space.medium)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                        ShoppingPeriodSelector(month, onMonth)
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(Space.medium)) {
                                Text(
                                    stringResource(R.string.shopping_total),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                AmountText(recap.total, style = MaterialTheme.typography.headlineLarge)
                            }
                        }
                        if (recap.items.isEmpty()) {
                            Text(stringResource(R.string.shopping_recap_empty))
                        }
                    }
                }
                items(recap.items, key = { it.key }) { spend ->
                    ListItem(
                        headlineContent = {
                            Text(spend.name ?: stringResource(R.string.shopping_recap_unattributed))
                        },
                        supportingContent = {
                            AmountText(spend.total, style = MaterialTheme.typography.titleMedium)
                        },
                        trailingContent = { AppIcon(R.drawable.ic_next) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { onSelectItem(spend.key) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingPeriodSelector(month: YearMonth, onMonth: (YearMonth) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val months = Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale) }
    val years = 2000..2099
    var picker by rememberSaveable { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        OutlinedButton(onClick = { picker = "month" }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(months[month.monthValue - 1])
        }
        OutlinedButton(onClick = { picker = "year" }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(month.year.toString())
        }
    }
    if (picker.isNotEmpty()) {
        val pickingMonth = picker == "month"
        val choices = if (pickingMonth) months else years.map(Int::toString)
        val selectedIndex = if (pickingMonth) month.monthValue - 1 else month.year - years.first
        val firstVisibleIndex = (selectedIndex - 2).coerceAtLeast(0)
        val title = if (pickingMonth) R.string.shopping_recap_choose_month else R.string.shopping_recap_choose_year
        AlertDialog(
            onDismissRequest = { picker = "" },
            title = { Text(stringResource(title)) },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 300.dp),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = firstVisibleIndex),
                ) {
                    items(choices.size) { index ->
                        TextButton(
                            onClick = {
                                val selection =
                                    if (pickingMonth) month.withMonth(index + 1)
                                    else month.withYear(years.first + index)
                                onMonth(selection)
                                picker = ""
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(choices[index]) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picker = "" }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
