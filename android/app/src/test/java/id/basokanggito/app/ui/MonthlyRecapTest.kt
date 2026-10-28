package id.basokanggito.app.ui

import id.basokanggito.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.YearMonth

class MonthlyRecapTest {
    @Test fun monthsIncludeEveryDayAndZeroDays() {
        for ((month, count) in listOf("2024-02" to 29, "2026-02" to 28, "2026-04" to 30, "2026-10" to 31)) {
            val days = recapDays(emptyList(), YearMonth.parse(month), emptyList())
            assertEquals(count, days.size)
            assertEquals(1, days.first().date.dayOfMonth)
            assertEquals(count, days.last().date.dayOfMonth)
            assertTrue(days.all { it.figures == BookTotals(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO) && !it.hasRecords })
        }
    }

    @Test fun receiptDatesVoidsAndShoppingMatchAuthoritativeTotals() {
        val sale = BookRecord("sale", "sale", "2026-09-30", 1, 125000, channel = "langsung", receipts = listOf(
            Payment("previous", "2026-09-30", 40000, "cash"), Payment("new", "2026-10-02", 40000, "transfer"),
            Payment("void", "2026-10-03", 45000, "cash", voided = true)))
        val shopping = BookRecord("shopping", "expense", "2026-10-02", 1, 30000, items = listOf(ShoppingItem("item", "Daging", 30000)))
        val voided = BookRecord("voided", "sale", "2026-10-03", 1, 100000, channel = "langsung", voided = true,
            receipts = listOf(Payment("voided-sale-payment", "2026-10-03", 100000, "cash")))
        val records = listOf(sale, shopping, voided)
        val days = recapDays(emptyList(), YearMonth.of(2026, 10), records)
        assertEquals(BookTotals(BigInteger.ZERO, 40000.toBigInteger(), 30000.toBigInteger()), days[1].figures)
        assertTrue(days[1].hasRecords)
        assertEquals(BigInteger.ZERO, days[2].figures.sales)
        assertEquals(BigInteger.ZERO, days[2].figures.received)
        val monthly = totals(records, "2026-10-01", "2026-11-01")
        assertEquals(monthly.sales, days.fold(BigInteger.ZERO) { sum, day -> sum + day.figures.sales })
        assertEquals(monthly.received, days.fold(BigInteger.ZERO) { sum, day -> sum + day.figures.received })
        assertEquals(monthly.expenses, days.fold(BigInteger.ZERO) { sum, day -> sum + day.figures.expenses })
    }

    @Test fun largeAggregatesStayExactAndSamplesDoNotCountReceiptsAsSales() {
        val records = List(100) { BookRecord("$it", "sale", "2026-10-31", 1, MaxAmount, channel = "langsung") }
        val days = recapDays(emptyList(), YearMonth.of(2026, 10), records)
        assertEquals("99999999999900", days.last().figures.sales.toString())
        val entries = listOf(JournalEntry("sale", "2026-10-01", "Langsung", 125000),
            JournalEntry("receipt", "2026-10-01", "Langsung", 40000, receiptFor = "sale"))
        assertEquals(125000.toBigInteger(), recapDays(entries, YearMonth.of(2026, 10)).first().figures.sales)
    }
}
