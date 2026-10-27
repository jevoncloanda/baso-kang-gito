package id.basokanggito.app.ui

import id.basokanggito.app.data.*
import org.junit.Assert.*
import org.junit.Test

class CustomerTotalsTest {
    private fun sale(id: String, customerId: String? = "customer", amount: Long = 100000, payments: List<Payment> = emptyList()) =
        BookRecord(id, "sale", "2026-10-31", 1, amount, channel = "langsung", customer = "Original name", receipts = payments, customerId = customerId)
    private fun payment(id: String, amount: Long, date: String = "2026-11-01") = Payment(id, date, amount, "cash")

    @Test fun installmentsChangeOutstandingAndReceiptDateWithoutAnotherSale() {
        val partial = sale("sale", payments = listOf(payment("first", 40000, "2026-10-31")))
        val settled = partial.copy(receipts = partial.receipts + payment("later", 60000))
        assertEquals("60000", customerOutstanding("customer", listOf(partial)).toString())
        assertEquals("0", customerOutstanding("customer", listOf(settled)).toString())
        assertEquals("100000", totals(listOf(settled), "2026-10-01", "2026-11-01").sales.toString())
        assertEquals("40000", totals(listOf(settled), "2026-10-01", "2026-11-01").received.toString())
        assertEquals("0", totals(listOf(settled), "2026-11-01", "2026-12-01").sales.toString())
        assertEquals("60000", totals(listOf(settled), "2026-11-01", "2026-12-01").received.toString())
    }

    @Test fun namesNeverIdentifyDebtAndPlatformsAndVoidsNeverContribute() {
        val linked = sale("linked")
        val legacy = sale("legacy", customerId = null)
        val other = sale("other", customerId = "different")
        val platform = sale("platform").copy(channel = "gofood")
        val voided = sale("voided").copy(voided = true)
        val records = listOf(linked, legacy, other, platform, voided)
        assertEquals("100000", customerOutstanding("customer", records).toString())
        assertEquals(listOf("linked", "voided"), customerSales("customer", records).map { it.id })
        assertEquals("Original name", linked.customer)
    }

    @Test fun voidedPaymentReopensBalanceAndLargeCustomerTotalsRemainExact() {
        val reopened = sale("sale", payments = listOf(payment("void", 40000).copy(voided = true)))
        assertEquals("100000", customerOutstanding("customer", listOf(reopened)).toString())
        val many = List(10000) { sale(it.toString(), amount = MaxAmount) }
        assertEquals("9999999999990000", customerOutstanding("customer", many).toString())
    }
}
