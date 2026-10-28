package id.basokanggito.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import id.basokanggito.app.data.BookRecord
import id.basokanggito.app.data.BookTotals
import id.basokanggito.app.data.totals
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

internal data class RecapDay(val date: LocalDate, val figures: BookTotals, val hasRecords: Boolean)

internal fun recapDays(entries: List<JournalEntry>, month: YearMonth, records: List<BookRecord>? = null): List<RecapDay> =
    (1..month.lengthOfMonth()).map { number ->
        val date = month.atDay(number)
        val daily = entries.filter { it.date == date.toString() }
        val active = daily.filterNot { it.voided }
        val figures = records?.let { totals(it, date.toString(), date.plusDays(1).toString()) } ?: BookTotals(
            active.filter { !it.isExpense && !it.isReceipt }.fold(BigInteger.ZERO) { sum, row -> sum + row.amount.toBigInteger() },
            active.fold(BigInteger.ZERO) { sum, row -> sum + row.received.toBigInteger() },
            active.filter { it.isExpense }.fold(BigInteger.ZERO) { sum, row -> sum + row.amount.toBigInteger() })
        RecapDay(date, figures, daily.isNotEmpty() || records?.any { it.date == date.toString() || it.receipts.any { receipt -> receipt.date == date.toString() } } == true)
    }

@Composable
internal fun MonthlySummary(figures: BookTotals) {
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                Text(stringResource(R.string.sales_shopping_difference), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconBadge(R.drawable.ic_chart, accent = true)
            }
            AmountText(figures.sales - figures.expenses, style = MaterialTheme.typography.headlineLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MoneyRow(stringResource(R.string.net_sales), figures.sales)
            MoneyRow(stringResource(R.string.shopping), figures.expenses)
            MoneyRow(stringResource(R.string.received), figures.received)
            MoneyRow(stringResource(R.string.cash_difference), figures.cashDifference)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MonthlyChart(days: List<RecapDay>, month: YearMonth, modifier: Modifier = Modifier) {
    val peak = days.maxOf { maxOf(it.figures.sales, it.figures.expenses) }
    val salesColor = MaterialTheme.colorScheme.primary
    val shoppingColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", LocalConfiguration.current.locales[0]))
    val description = stringResource(R.string.chart_accessibility, monthLabel,
        rupiah(days.fold(BigInteger.ZERO) { sum, day -> sum + day.figures.sales }),
        rupiah(days.fold(BigInteger.ZERO) { sum, day -> sum + day.figures.expenses }))
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.medium), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            SectionTitle(stringResource(R.string.monthly_chart_title))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.medium), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawRect(salesColor) }
                    Text(stringResource(R.string.net_sales), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawRect(shoppingColor) }
                    Text(stringResource(R.string.shopping), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (peak == BigInteger.ZERO) Text(stringResource(R.string.chart_empty))
            else {
                Text(stringResource(R.string.chart_scale, rupiah(peak)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Canvas(Modifier.fillMaxWidth().height(156.dp).testTag("monthly-chart").clearAndSetSemantics { contentDescription = description }) {
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), strokeWidth = 1.dp.toPx())
                    }
                    val group = size.width / days.size
                    days.forEachIndexed { index, day ->
                        listOf(day.figures.sales to salesColor, day.figures.expenses to shoppingColor).forEachIndexed { series, (value, color) ->
                            // Only drawing uses a rounded ratio; displayed and stored rupiah stay exact.
                            val ratio = value.toBigDecimal().divide(peak.toBigDecimal(), 8, RoundingMode.HALF_UP).toFloat()
                            val height = size.height * ratio
                            drawRect(color, Offset(group * (index + 0.1f + series * 0.4f), size.height - height), Size(group * 0.32f, height))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(1, (month.lengthOfMonth() + 1) / 2, month.lengthOfMonth()).forEach { day ->
                        Text(month.atDay(day).format(DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
