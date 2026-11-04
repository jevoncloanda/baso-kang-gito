package id.basokanggito.app.ui

import id.basokanggito.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ActivityFeedTest {
    private val payment = Payment("payment", "2026-10-31", 41000, "cash", createdAt = "2026-10-31T00:00:00Z")
    private val sale = BookRecord("sale", "sale", payment.date, 1, 41000, "langsung", receipts = listOf(payment),
        createdAt = payment.createdAt, initialReceiptId = payment.id)
    private fun rows(record: BookRecord) = presentation(NotebookState(records = listOf(record)))

    @Test fun initialPaidSaleGroupsByCreationRelationshipWithoutChangingTotalsOrNavigation() {
        val before = totals(listOf(sale), "2026-10-31", "2026-11-01")
        val entries = rows(sale)
        assertEquals(1, entries.size)
        assertEquals(sale.id, entries.single().id)
        assertTrue(entries.single().immediatePayment)
        assertEquals(before, totals(listOf(sale), "2026-10-31", "2026-11-01"))
        assertEquals(3, presentation(NotebookState(records = listOf(sale, sale.copy(id = "other", initialReceiptId = null)))).size)
    }

    @Test fun laterSameAmountPartialPlatformVoidedAndCrossDateEventsStayVisible() {
        assertEquals(2, rows(sale.copy(initialReceiptId = null)).size)
        assertEquals(2, rows(sale.copy(amount = 100000)).size)
        assertEquals(2, rows(sale.copy(channel = "gofood_mamat")).size)
        assertEquals(2, rows(sale.copy(receipts = listOf(payment.copy(voided = true)))).size)
        val moved = sale.copy(date = "2026-11-01")
        val entries = rows(moved)
        assertEquals(listOf("2026-11-01", "2026-10-31"), entries.map { it.date })
        assertEquals(sale.id, entries.last().receiptFor)
        assertEquals("0", totals(listOf(moved), "2026-10-31", "2026-11-01").sales.toString())
        assertEquals("41000", totals(listOf(moved), "2026-10-31", "2026-11-01").received.toString())
    }

    @Test fun gofoodCodesAndDisplayKeysRemainDistinctAndLegacyStillParsesInPresentation() {
        assertEquals(ChannelCodes.size, SalesChannels.size)
        assertEquals("GoFood", SalesChannels[ChannelCodes.indexOf("gofood")])
        assertEquals("GoFood - Mamat", rows(sale.copy(channel = "gofood_mamat")).first().channel)
        assertEquals("GoFood - Gito", rows(sale.copy(channel = "gofood_gito")).first().channel)
    }
}
