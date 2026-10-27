package id.basokanggito.app.data

import org.json.JSONObject
import java.math.BigInteger
import java.time.Instant

data class Customer(
    val id: String, val name: String, val phone: String = "", val address: String = "", val notes: String = "",
    val archived: Boolean = false, val version: Int = 1, val createdAt: String = Instant.EPOCH.toString(),
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", "customer").put("name", name).put("phone", phone)
        .put("address", address).put("notes", notes).put("archived", archived).put("version", version).put("createdAt", createdAt)

    companion object {
        fun parse(json: JSONObject): Customer {
            require(json.getString("kind") == "customer")
            return Customer(validId(json.getString("id")), customerText(json, "name", 200, required = true),
                customerText(json, "phone", 50), customerText(json, "address", 500), customerText(json, "notes", 1000),
                json.getBoolean("archived"), json.getInt("version").also { require(it > 0) },
                Instant.parse(json.optString("createdAt", Instant.EPOCH.toString())).toString())
        }
    }
}

private fun customerText(data: JSONObject, key: String, maximum: Int, required: Boolean = false): String {
    require(!data.has(key) || data.opt(key) is String)
    return data.optString(key).trim().also { require(it.length in (if (required) 1 else 0)..maximum) }
}

fun applyCustomerLocally(op: JSONObject, before: Customer?): Customer {
    require(op.getString("action") == "save_customer")
    val id = validId(op.getString("recordId")); val version = op.getInt("expectedVersion")
    require((before?.version ?: 0) == version && (before == null || before.id == id)) { "version_conflict" }
    val data = op.getJSONObject("data")
    require(data.keys().asSequence().all { it in listOf("name", "phone", "address", "notes", "archived", "reason") })
    if (version > 0) require(data.optString("reason").trim().length in 1..500) { "reason_required" }
    listOf("name", "phone", "address", "notes").forEach { require(!data.has(it) || data.opt(it) is String) }
    return Customer.parse(JSONObject().put("id", id).put("kind", "customer").put("version", version + 1)
        .put("name", data.getString("name")).put("phone", data.optString("phone")).put("address", data.optString("address"))
        .put("notes", data.optString("notes")).put("archived", if (data.has("archived")) data.getBoolean("archived") else before?.archived ?: false)
        .put("createdAt", before?.createdAt ?: Instant.now().toString()))
}

fun customerSales(customerId: String, records: List<BookRecord>): List<BookRecord> =
    records.filter { it.kind == "sale" && it.channel == "langsung" && it.customerId == customerId }

fun customerOutstanding(customerId: String, records: List<BookRecord>): BigInteger =
    customerSales(customerId, records).filterNot { it.voided }.fold(BigInteger.ZERO) { total, sale ->
        total + (sale.amount - sale.received).toBigInteger()
    }
