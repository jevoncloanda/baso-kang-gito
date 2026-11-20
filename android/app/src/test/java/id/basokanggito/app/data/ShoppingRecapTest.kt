package id.basokanggito.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.YearMonth

class ShoppingRecapTest {
    private val month = YearMonth.of(2026, 11)

    @Test
    fun addsRepeatedItemLinesWithinBusinessDatesAndReconcilesWithBookTotals() {
        val beef = ShoppingItem("beef", "Daging Sapi", 150_000, "catalog-beef")
        val noodles = ShoppingItem("noodles", "Mie", 35_000, "catalog-noodles")
        val corrected = expense("late", "2026-10-31", beef.copy(amount = 90_000))
            .copy(date = "2026-11-30", version = 2)
        val records = listOf(
            expense("first", "2026-11-01", beef, noodles),
            expense("previous", "2026-10-31", noodles),
            expense("next", "2026-12-01", noodles),
            expense("deleted", "2026-11-02", noodles).copy(voided = true),
            BookRecord("sale", "sale", "2026-11-02", 1, 350_000),
            corrected,
        )

        val recap = shoppingRecap(records, emptyList(), month)

        assertEquals(listOf("Daging Sapi", "Mie"), recap.items.map { it.name })
        assertEquals(240_000.toBigInteger(), recap.items.first().total)
        assertEquals(
            listOf(ShoppingPurchase("2026-11-30", 90_000), ShoppingPurchase("2026-11-01", 150_000)),
            recap.items.first().purchases,
        )
        assertEquals(275_000.toBigInteger(), recap.total)
        assertEquals(totals(records, "2026-11-01", "2026-12-01").expenses, recap.total)
    }

    @Test
    fun linksExactHistoricalAliasesButKeepsDistinctCatalogIdsAndSimilarNamesSeparate() {
        val catalog = listOf(
            CatalogItem("first", "Cabai", archived = true, aliases = listOf("Cabe")),
            CatalogItem("second", "Cabai", aliases = listOf("Cabai merah")),
        )
        val records = listOf(
            expense(
                "shopping", "2026-11-01",
                ShoppingItem("first", "Original label", 30_000, "first"),
                ShoppingItem("alias", " Cabe ", 20_000),
                ShoppingItem("second", "Cabai", 40_000, "second"),
                ShoppingItem("case", "cabe", 10_000),
                ShoppingItem("same-display", "Cabai", 5_000),
            ),
        )

        val items = shoppingRecap(records, catalog, month).items

        assertEquals(listOf(50_000, 40_000, 10_000, 5_000).map(Int::toBigInteger), items.map { it.total })
        assertEquals(listOf("Cabai", "Cabai", "cabe", "Cabai"), items.map { it.name })
        assertNotEquals(items[0].key, items[1].key)
    }

    @Test
    fun usesDeterministicHistoricalIdentityAndKeepsUnnamedSpendingInTheTotal() {
        val records = listOf(
            expense(
                "first", "2026-11-02",
                ShoppingItem("old", "Mie", 50_000),
                ShoppingItem("unnamed", " ", 25_000),
            ),
            expense("second", "2026-11-20", ShoppingItem("new", "Mie", 75_000, shoppingMasterId("Mie"))),
        )

        val recap = shoppingRecap(records, emptyList(), month)

        assertEquals(2, recap.items.size)
        assertEquals(125_000.toBigInteger(), recap.items.first().total)
        assertNull(recap.items.last().name)
        assertEquals(listOf(ShoppingPurchase("2026-11-02", 25_000)), recap.items.last().purchases)
        assertEquals(totals(records, "2026-11-01", "2026-12-01").expenses, recap.total)
        val empty = shoppingRecap(records, emptyList(), month.plusMonths(1))
        assertTrue(empty.items.isEmpty())
        assertEquals(BigInteger.ZERO, empty.total)
    }

    private fun expense(id: String, date: String, vararg items: ShoppingItem) = BookRecord(
        id = id,
        kind = "expense",
        date = date,
        version = 1,
        amount = items.sumOf { it.amount },
        items = items.toList(),
        createdAt = "2026-12-05T00:00:00Z",
    )
}
