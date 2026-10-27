package id.basokanggito.app.ui

import java.io.Serializable
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import id.basokanggito.app.R

// Presentation values; durable records are owned by data/NotebookRepository.
data class JournalLine(val name: String, val amount: Long, val sampleNameRes: Int = 0) : Serializable

data class JournalEntry(
    val id: String,
    val date: String,
    val channel: String,
    val amount: Long,
    val received: Long = 0,
    val customer: String = "",
    val note: String = "",
    val lines: List<JournalLine> = emptyList(),
    val receiptFor: String = "", val voided: Boolean = false, val syncState: String = "",
    val createdAt: String = java.time.Instant.EPOCH.toString(),
) : Serializable {
    val isExpense: Boolean get() = lines.isNotEmpty()
    val isReceipt: Boolean get() = receiptFor.isNotEmpty()
}

data class SaleDraft(
    val amount: String = "", val customer: String = "", val channel: String = "Langsung",
    val paid: Boolean = true, val note: String = "", val method: String = "cash",
    val customerId: String? = null, val paymentAmount: String = "",
) : Serializable

data class ExpenseDraftLine(val id: String, val name: String = "", val amount: String = "") : Serializable

val Indonesian: Locale = Locale.forLanguageTag("id-ID")
val SalesChannels = listOf("Langsung", "GoFood", "GrabFood", "ShopeeFood")


fun rupiah(amount: Number): String = "Rp ${NumberFormat.getIntegerInstance(Indonesian).format(amount)}"

fun positiveAmount(text: String): Long? = text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    ?.toLongOrNull()?.takeIf { it in 1..id.basokanggito.app.data.MaxAmount }

fun expenseTotal(lines: List<ExpenseDraftLine>): Long? {
    val filled = lines.filter { it.name.isNotBlank() || it.amount.isNotBlank() }
    if (filled.isEmpty()) return null
    return try {
        filled.fold(0L) { total, line ->
            val amount = positiveAmount(line.amount) ?: return null
            if (line.name.isBlank()) return null
            Math.addExact(total, amount)
        }
    } catch (_: ArithmeticException) { null }
}
