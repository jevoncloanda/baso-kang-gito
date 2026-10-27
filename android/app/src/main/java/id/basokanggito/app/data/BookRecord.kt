package id.basokanggito.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

const val MaxAmount = 999_999_999_999L
fun businessToday(): LocalDate = LocalDate.now(ZoneId.of("Asia/Jakarta"))
fun validDate(value: String): String = value.also { require(LocalDate.parse(it).year in 2000..2099) }
fun validId(value: String): String = value.also { require(UUID.fromString(it).toString() == it) }
fun amount(value: String): Long = value.also { require(it.matches(Regex("[1-9][0-9]{0,11}"))) }.toLong().also { require(it <= MaxAmount) }
fun newId(): String = UUID.randomUUID().toString()
val ChannelCodes = listOf("langsung", "gofood", "grabfood", "shopeefood")

data class Payment(val id: String, val date: String, val amount: Long, val method: String, val voided: Boolean = false, val createdAt: String = Instant.EPOCH.toString()) {
    fun json() = JSONObject().put("id", id).put("date", date).put("amount", amount.toString()).put("method", method).put("voided", voided).put("createdAt", createdAt)
}
data class ShoppingItem(val id: String, val name: String, val amount: Long) {
    fun json() = JSONObject().put("id", id).put("name", name).put("amount", amount.toString())
}
data class BookRecord(
    val id: String, val kind: String, val date: String, val version: Int, val amount: Long,
    val channel: String = "", val customer: String = "", val note: String = "", val voided: Boolean = false,
    val receipts: List<Payment> = emptyList(), val items: List<ShoppingItem> = emptyList(),
    val createdAt: String = Instant.EPOCH.toString(),
    val customerId: String? = null,
) {
    val received: Long get() = receipts.filterNot { it.voided }.fold(0L) { sum, p -> Math.addExact(sum, p.amount) }
    fun withKnownChronology(previous: BookRecord?): BookRecord {
        if (previous == null || previous.id != id) return this
        val unknown = Instant.EPOCH.toString()
        val knownReceipts = previous.receipts.associateBy { it.id }
        return copy(createdAt = if (createdAt == unknown) previous.createdAt else createdAt,
            receipts = receipts.map { payment ->
                val known = knownReceipts[payment.id]
                if (payment.createdAt == unknown && known != null) payment.copy(createdAt = known.createdAt) else payment
            })
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("date", date).put("version", version)
        .put("amount", amount.toString()).put("note", note).put("voided", voided).put("createdAt", createdAt).apply {
            if (kind == "sale") put("channel", channel).put("customer", customer).put("origin", "manual")
                .put("customerId", customerId ?: JSONObject.NULL)
                .put("entryKind", if (channel == "langsung") "individual" else "daily_aggregate")
                .put("receipts", JSONArray(receipts.map { it.json() }))
            else put("items", JSONArray(items.map { it.json() }))
        }
    companion object {
        fun parse(json: JSONObject): BookRecord {
            val kind = json.getString("kind"); require(kind in listOf("sale", "expense"))
            val receipts = json.optJSONArray("receipts").objects().map {
                Payment(validId(it.getString("id")), validDate(it.getString("date")), amount(it.getString("amount")),
                    it.getString("method").also { method -> require(method in listOf("cash", "transfer")) }, it.getBoolean("voided"), timestamp(it))
            }
            val items = json.optJSONArray("items").objects().map { ShoppingItem(validId(it.getString("id")), it.getString("name"), amount(it.getString("amount"))) }
            val total = json.getString("amount").toLong().also { require(it > 0) }
            if (kind == "expense") require(items.isNotEmpty() && items.fold(0L) { sum, item -> Math.addExact(sum, item.amount) } == total)
            else { amount(json.getString("amount")); require(json.getString("channel") in ChannelCodes) }
            return BookRecord(validId(json.getString("id")), kind, validDate(json.getString("date")), json.getInt("version").also { require(it > 0) }, total,
                json.optString("channel"), json.optString("customer"), json.getString("note"), json.getBoolean("voided"), receipts, items, timestamp(json),
                if (json.isNull("customerId")) null else validId(json.getString("customerId")))
        }
    }
}
private fun timestamp(json: JSONObject): String = Instant.parse(json.optString("createdAt", Instant.EPOCH.toString())).toString()
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
data class BookTotals(val sales: BigInteger, val received: BigInteger, val expenses: BigInteger) {
    val cashDifference: BigInteger get() = received - expenses
}
fun totals(records: List<BookRecord>, from: String, to: String): BookTotals {
    val active = records.filterNot { it.voided }
    fun List<Long>.sumExact() = fold(BigInteger.ZERO) { total, value -> total + value.toBigInteger() }
    return BookTotals(active.filter { it.kind == "sale" && it.date >= from && it.date < to }.map { it.amount }.sumExact(),
        active.flatMap { it.receipts }.filter { !it.voided && it.date >= from && it.date < to }.map { it.amount }.sumExact(),
        active.filter { it.kind == "expense" && it.date >= from && it.date < to }.flatMap { it.items }.map { it.amount }.sumExact())
}

// Offline validation mirrors the server; the server still owns financial acceptance.
fun applyLocally(op: JSONObject, before: BookRecord?): BookRecord {
    val now = Instant.now().toString()
    val data = op.getJSONObject("data"); val id = validId(op.getString("recordId")); val version = op.getInt("expectedVersion")
    require((before?.version ?: 0) == version && before?.voided != true) { "version_conflict" }
    if (version > 0) require(data.optString("reason").trim().length in 1..500) { "reason_required" }
    fun payment() = Payment(validId(data.getString("id")), validDate(data.getString("date")), amount(data.getString("amount")),
        data.getString("method").also { require(it in listOf("cash", "transfer")) }, createdAt = now)
    return when (op.getString("action")) {
        "save_sale" -> {
            require(before == null || before.kind == "sale")
            val channel = data.getString("channel").also { require(it in ChannelCodes) }
            val value = amount(data.getString("amount")); require(value >= (before?.received ?: 0)) { "below_received" }
            require(before == null || channel == before.channel || before.received == 0L) { "linked_receipts" }
            if (before != null) require(listOf("paymentAmount", "paid", "receiptId", "method").none(data::has))
            val customerId = if (!data.has("customerId")) before?.customerId else if (data.isNull("customerId")) null else validId(data.getString("customerId"))
            if (before == null && !data.getBoolean("paid")) require(!data.has("paymentAmount"))
            val paidAmount = if (data.has("paymentAmount")) amount(data.getString("paymentAmount")).also { require(it <= value) { "overpayment" } } else value
            val payments = before?.receipts ?: if (data.getBoolean("paid")) listOf(Payment(validId(data.getString("receiptId")), validDate(data.getString("date")), paidAmount,
                data.getString("method").also { require(it in listOf("cash", "transfer")) }, createdAt = now)) else emptyList()
            BookRecord(id, "sale", validDate(data.getString("date")), version + 1, value, channel,
                if (before != null && customerId != null && customerId == before.customerId) before.customer else data.optString("customer").trim().also { require(it.length <= 200) },
                data.optString("note").trim().also { require(it.length <= 1000) }, receipts = payments, createdAt = before?.createdAt ?: now, customerId = customerId)
        }
        "save_expense" -> {
            require(before == null || before.kind == "expense")
            val items = data.getJSONArray("items").objects().map { ShoppingItem(validId(it.getString("id")),
                it.getString("name").trim().also { name -> require(name.length in 1..200) }, amount(it.getString("amount"))) }
            require(items.size in 1..100 && items.map { it.id }.distinct().size == items.size)
            BookRecord(id, "expense", validDate(data.getString("date")), version + 1,
                items.fold(0L) { sum, item -> Math.addExact(sum, item.amount) }, note = data.optString("note"), items = items, createdAt = before?.createdAt ?: now)
        }
        "add_receipt" -> {
            require(before?.kind == "sale"); val receipt = payment()
            require(Math.addExact(before.received, receipt.amount) <= before.amount) { "overpayment" }
            before.copy(version = version + 1, receipts = before.receipts + receipt)
        }
        "void" -> {
            require(before != null)
            if (data.has("receiptId")) {
                val receiptId = validId(data.getString("receiptId")); require(before.receipts.any { it.id == receiptId && !it.voided })
                before.copy(version = version + 1, receipts = before.receipts.map { if (it.id == receiptId) it.copy(voided = true) else it })
            } else {
                require(before.received == 0L || data.optBoolean("includeReceipts")) { "linked_receipts" }
                before.copy(version = version + 1, voided = true, receipts = before.receipts.map { it.copy(voided = true) })
            }
        }
        else -> error("invalid_operation")
    }
}
