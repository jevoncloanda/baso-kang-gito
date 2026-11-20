package id.basokanggito.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleDraftTest {
    @Test
    fun newDraftsStartWithMoneyReceivedAcrossChannels() {
        for (channel in SalesChannels + "New platform") {
            assertTrue(channel, SaleDraft(channel = channel).paid)
            assertTrue(channel, SaleDraft().withChannel(channel).paid)
        }
    }

    @Test
    fun manualChoiceSurvivesChannelChangesInBothDirections() {
        val unpaidBon = SaleDraft(customerId = "customer").copy(paid = false)
        val unpaidPlatformSale = unpaidBon.withChannel("GoFood - Mamat")
        assertFalse(unpaidPlatformSale.paid)
        assertNull(unpaidPlatformSale.customerId)
        assertFalse(unpaidPlatformSale.withChannel("Langsung").paid)

        val paidPlatformSale = unpaidPlatformSale.copy(paid = true, paymentAmount = "20000")
        val nextChannel = paidPlatformSale.withChannel("GrabFood")
        assertTrue(nextChannel.paid)
        assertEquals("", nextChannel.paymentAmount)
    }
}
