package id.basokanggito.app.ui

import id.basokanggito.app.data.ChannelCodes
import org.junit.Assert.*
import org.junit.Test

class ChannelPresentationTest {
    @Test fun newSalesOfferBothAccountsAndEveryOtherChannelWithoutLegacy() {
        assertEquals(listOf("Langsung", "GoFood - Mamat", "GoFood - Gito", "GrabFood", "ShopeeFood"), SelectableSalesChannels)
        assertFalse("GoFood" in SelectableSalesChannels)
        assertEquals("GoFood", SalesChannels[ChannelCodes.indexOf("gofood")])
    }

    @Test fun emptyLegacyIsHiddenForDailyAndMonthlyBreakdownsWithoutHidingOtherChannels() {
        val entries = listOf(JournalEntry("sale", "2026-11-04", "GoFood - Mamat", 41000),
            JournalEntry("receipt", "2026-11-04", "GoFood", 41000, receiptFor = "old-sale"),
            JournalEntry("void", "2026-11-04", "GoFood", 9000, voided = true))
        for (period in listOf(entries.filter { it.date == "2026-11-04" }, entries.filter { it.date.startsWith("2026-11-") })) {
            val channels = channelSales(period).toMap()
            assertFalse(channels.containsKey("GoFood"))
            assertEquals(41000.toBigInteger(), channels["GoFood - Mamat"])
            assertEquals(SelectableSalesChannels.toSet(), channels.keys)
        }
        assertFalse(channelSales(emptyList()).any { it.first == "GoFood" })
    }

    @Test fun historicalSalesRemainVisibleAndCombinedAccountsCountSalesOnly() {
        val entries = listOf(JournalEntry("legacy", "2026-10-31", "GoFood", 7000),
            JournalEntry("mamat", "2026-10-31", "GoFood - Mamat", 41000),
            JournalEntry("gito", "2026-10-31", "GoFood - Gito", 12000),
            JournalEntry("settlement", "2026-11-01", "GoFood - Mamat", 41000, receiptFor = "mamat"))
        val channels = channelSales(entries).toMap()
        assertEquals(7000.toBigInteger(), channels["GoFood"])
        assertEquals(41000.toBigInteger(), channels["GoFood - Mamat"])
        assertEquals(12000.toBigInteger(), channels["GoFood - Gito"])
        assertEquals(60000.toBigInteger(), channels.values.fold(java.math.BigInteger.ZERO, java.math.BigInteger::add))
        assertTrue(channelSales(listOf(JournalEntry("zero", "2026-10-31", "GoFood", 0))).any { it.first == "GoFood" })
    }
}
