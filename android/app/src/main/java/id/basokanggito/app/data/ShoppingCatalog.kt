package id.basokanggito.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun shoppingMasterId(name: String): String = UUID.nameUUIDFromBytes(("bkg-shopping-item-v1:" + name.trim()).toByteArray(Charsets.UTF_8)).toString()

data class CatalogItem(val id: String, val name: String, val unit: String = "", val archived: Boolean = false,
    val version: Int = 1, val aliases: List<String> = listOf(name)) {
    fun json() = JSONObject().put("id", id).put("kind", "shopping_item").put("name", name).put("unit", unit)
        .put("archived", archived).put("version", version).put("aliases", JSONArray(aliases))
    companion object {
        fun parse(json: JSONObject): CatalogItem {
            require(json.getString("kind") == "shopping_item")
            val names = json.getJSONArray("aliases").let { array -> (0 until array.length()).map { array.getString(it) } }
            return CatalogItem(validId(json.getString("id")), json.getString("name").also { require(it.trim().length in 1..200) },
                json.getString("unit").also { require(it.length <= 50) }, json.getBoolean("archived"),
                json.getInt("version").also { require(it > 0) }, names.also { require(it.isNotEmpty() && it.all { name -> name.length in 1..200 }) })
        }
    }
}

fun applyCatalogLocally(op: JSONObject, before: CatalogItem?): CatalogItem {
    val data = op.getJSONObject("data"); val version = op.getInt("expectedVersion")
    require((before?.version ?: 0) == version) { "version_conflict" }
    if (version > 0) require(data.optString("reason").trim().length in 1..500) { "reason_required" }
    val name = data.getString("name").trim().also { require(it.length in 1..200) }
    val unit = data.optString("unit").trim().also { require(it.length <= 50) }
    val id = validId(op.getString("recordId"))
    require(before != null || id == shoppingMasterId(name))
    return CatalogItem(id, name, unit, data.optBoolean("archived", before?.archived ?: false), version + 1,
        ((before?.aliases ?: emptyList()) + name).distinct())
}
