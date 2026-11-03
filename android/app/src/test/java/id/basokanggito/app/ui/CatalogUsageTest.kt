package id.basokanggito.app.ui

import id.basokanggito.app.data.BookRecord
import id.basokanggito.app.data.ShoppingItem
import org.junit.Assert.*
import org.junit.Test

class CatalogUsageTest {
    private fun expense(id: String, date: String, vararg lines: ShoppingItem) =
        BookRecord(id, "expense", date, 1, lines.sumOf { it.amount }, items = lines.toList())

    @Test fun countsMasterReferencesAndKeepsLatestBusinessDateRegardlessOfInputOrder() {
        val records = listOf(
            expense("newer", "2026-11-02", ShoppingItem("a", "Renamed label", 120000, "meat")),
            expense("older", "2026-10-31", ShoppingItem("b", "Original label", 100000, "meat"),
                ShoppingItem("c", "Bumbu", 25000, "spices")),
            expense("same-day", "2026-11-02", ShoppingItem("d", "Daging", 90000, "meat")),
        )

        assertEquals(mapOf("meat" to CatalogUsage(3, "2026-11-02"), "spices" to CatalogUsage(1, "2026-10-31")), catalogUsage(records))
        assertEquals(catalogUsage(records), catalogUsage(records.reversed()))
        assertEquals("Original label", records[1].items[0].name)
        assertEquals(100000L, records[1].items[0].amount)
    }

    @Test fun ignoresVoidedPurchasesUnlinkedLegacyLinesAndNonShoppingRecords() {
        val line = ShoppingItem("line", "Daging", 100000, "meat")
        val voided = expense("void", "2026-12-01", line).copy(voided = true)
        val legacy = expense("legacy", "2026-12-02", line.copy(masterId = null))
        val sale = expense("sale", "2026-12-03", line).copy(kind = "sale", channel = "langsung")

        assertTrue(catalogUsage(listOf(voided, legacy, sale)).isEmpty())
        assertTrue(catalogUsage(emptyList()).isEmpty())
    }
}
