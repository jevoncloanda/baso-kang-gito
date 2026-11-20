package id.basokanggito.app.data

import java.math.BigInteger
import java.time.YearMonth

internal data class ShoppingPurchase(val date: String, val amount: Long)

internal data class ShoppingSpend(
    val key: String,
    val name: String?,
    val purchases: List<ShoppingPurchase>,
) {
    val total: BigInteger = purchases.fold(BigInteger.ZERO) { sum, purchase ->
        sum + purchase.amount.toBigInteger()
    }
}

internal data class ShoppingRecap(val items: List<ShoppingSpend>) {
    val total: BigInteger = items.fold(BigInteger.ZERO) { sum, item -> sum + item.total }
}

internal fun shoppingRecap(
    records: List<BookRecord>,
    catalog: List<CatalogItem>,
    month: YearMonth,
): ShoppingRecap {
    val from = month.atDay(1).toString()
    val until = month.plusMonths(1).atDay(1).toString()
    val catalogById = catalog.associateBy { it.id }
    val idsByAlias = catalog.flatMap { item ->
        (item.aliases + item.name).distinct().map { it to item.id }
    }.groupBy({ it.first }, { it.second })

    data class Contribution(val key: String, val name: String?, val purchase: ShoppingPurchase)

    val contributions = records.asSequence()
        .filter { it.kind == "expense" && !it.voided && it.date >= from && it.date < until }
        .flatMap { record ->
            record.items.asSequence().map { line ->
                val name = line.name.trim()
                val matches = idsByAlias[name].orEmpty()
                val id = line.masterId ?: when {
                    name.isEmpty() -> null
                    matches.isEmpty() -> shoppingMasterId(name)
                    else -> matches.singleOrNull()
                }
                val key = when {
                    id != null -> "catalog:$id"
                    name.isNotEmpty() -> "name:$name"
                    else -> "unattributed"
                }
                val label = catalogById[id]?.name ?: name.takeIf { it.isNotEmpty() }
                Contribution(key, label, ShoppingPurchase(record.date, line.amount))
            }
        }
        .sortedByDescending { it.purchase.date }
        .groupBy { it.key }

    return ShoppingRecap(
        contributions.map { (key, rows) ->
            ShoppingSpend(key, rows.firstNotNullOfOrNull { it.name }, rows.map { it.purchase })
        }.sortedWith(compareByDescending<ShoppingSpend> { it.total }.thenBy { it.name }.thenBy { it.key }),
    )
}
