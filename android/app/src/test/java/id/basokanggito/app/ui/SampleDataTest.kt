package id.basokanggito.app.ui

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import id.basokanggito.app.data.*

class SampleDataTest {
    @Test fun legacyReplayRetainsKnownChronologyWithoutReplacingIncomingFinancialFields() {
        val previous = BookRecord("root", "sale", "2026-10-26", 1, 100, channel = "langsung",
            receipts = listOf(Payment("payment", "2026-10-26", 50, "cash", createdAt = "2026-10-26T02:00:00Z")),
            createdAt = "2026-10-26T01:00:00Z")
        val incoming = previous.copy(date = "2026-10-27", version = 2, amount = 200, note = "Latest correction", voided = true,
            createdAt = java.time.Instant.EPOCH.toString(), receipts = listOf(
                Payment("payment", "2026-10-27", 75, "transfer", voided = true),
                Payment("new-payment", "2026-10-27", 20, "cash")))
        val expected = incoming.copy(createdAt = previous.createdAt,
            receipts = listOf(incoming.receipts[0].copy(createdAt = previous.receipts[0].createdAt), incoming.receipts[1]))
        assertEquals(expected, incoming.withKnownChronology(previous))
        assertEquals(incoming, incoming.withKnownChronology(null))
        assertEquals(incoming, incoming.withKnownChronology(previous.copy(id = "other-root")))
        val knownIncoming = incoming.copy(createdAt = "2026-10-27T03:00:00Z",
            receipts = incoming.receipts.map { it.copy(createdAt = "2026-10-27T04:00:00Z") })
        assertEquals(knownIncoming, knownIncoming.withKnownChronology(previous))
    }
    @Test fun historyUsesBusinessDateThenCreationTimeThenStableId() {
        val early = BookRecord("z", "sale", "2026-10-26", 1, 100, channel = "langsung", createdAt = "2026-10-26T01:00:00Z")
        val later = early.copy(id = "a", createdAt = "2026-10-26T02:00:00Z")
        val tied = later.copy(id = "b")
        val receipt = Payment("p", "2026-10-26", 50, "cash", createdAt = "2026-10-26T03:00:00Z")
        val nextDay = early.copy(id = "n", date = "2026-10-27")
        val entries = listOf(early.copy(receipts = listOf(receipt)), tied, nextDay, later)
        val expected = listOf("n", "p", "a", "b", "z")
        assertEquals(expected, presentation(NotebookState(records = entries)).map { it.id })
        assertEquals(expected, presentation(NotebookState(records = entries.reversed())).map { it.id })
        assertEquals(expected, presentation(NotebookState(records = entries.map { if (it.id == "a") it.copy(version = 2, amount = 200) else it })).map { it.id })
    }
    @Test fun groupedMoneyPreservesEveryCursorPosition() {
        listOf("1", "25000", "001000", "9223372036854775807").forEach { raw ->
            val result = RupiahGrouping.filter(AnnotatedString(raw))
            for (offset in 0..raw.length) {
                val formattedOffset = result.offsetMapping.originalToTransformed(offset)
                assertTrue(formattedOffset in 0..result.text.length)
                assertEquals(offset, result.offsetMapping.transformedToOriginal(formattedOffset))
            }
            for (offset in 0..result.text.length) assertTrue(result.offsetMapping.transformedToOriginal(offset) in 0..raw.length)
        }
        assertEquals("25.000", RupiahGrouping.filter(AnnotatedString("25000")).text.text)
        assertEquals("-1", RupiahGrouping.filter(AnnotatedString("-1")).text.text)
    }

    @Test fun indonesianResourcesCoverEnglishWithMatchingFormatArguments() {
        fun strings(folder: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val english = strings("values"); val indonesian = strings("values-in")
        assertEquals(english.keys, indonesian.keys)
        val argument = Regex("%[0-9]+\\\u0024[sd]")
        english.forEach { (key, text) ->
            assertFalse(indonesian.getValue(key).isBlank())
            assertEquals(key, argument.findAll(text).map { it.value }.toList(), argument.findAll(indonesian.getValue(key)).map { it.value }.toList())
        }
    }
    @Test fun textRolesMeetContrastThreshold() {
        listOf(LightColors, DarkColors).forEach { colors ->
            listOf(
                colors.onBackground to colors.background,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.background,
                colors.onSurfaceVariant to colors.surface,
                colors.onSurfaceVariant to colors.surfaceContainerHighest,
                colors.onPrimary to colors.primary,
                colors.primary to colors.background,
                colors.primary to colors.surface,
                colors.primary to colors.primaryContainer,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondary to colors.secondary,
                colors.secondary to colors.background,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiary to colors.tertiary,
                colors.onTertiaryContainer to colors.tertiaryContainer,
                colors.error to colors.background,
                colors.error to colors.surface,
                colors.onError to colors.error,
                colors.onErrorContainer to colors.errorContainer,
                colors.inverseOnSurface to colors.inverseSurface,
            ).forEach { (text, background) ->
                val first = text.luminance(); val second = background.luminance()
                assertTrue((maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05) >= 4.5)
            }
            listOf(colors.outline to colors.background, colors.outline to colors.surface,
                colors.primary to colors.surfaceContainerHighest).forEach { (control, background) ->
                val first = control.luminance(); val second = background.luminance()
                assertTrue((maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05) >= 3.0)
            }
        }
    }
    @Test fun amountMustBePositiveWholeRupiahWithoutOverflow() {
        assertEquals(25_000L, positiveAmount("25000"))
        listOf("", "0", "-1", "25.000", "2.5", " 25", "9223372036854775808").forEach { assertNull(positiveAmount(it)) }
    }

    @Test fun shoppingTotalsIgnoreBlankTrailingRows() {
        assertEquals(175_000L, expenseTotal(listOf(
            ExpenseDraftLine("1", "Daging", "150000"), ExpenseDraftLine("2", "Bumbu", "25000"), ExpenseDraftLine("3"),
        )))
    }

    @Test fun incompleteOrOverflowingShoppingCannotBeSaved() {
        assertNull(expenseTotal(listOf(ExpenseDraftLine("1"))))
        assertNull(expenseTotal(listOf(ExpenseDraftLine("1", "Daging"))))
        assertNull(expenseTotal(listOf(ExpenseDraftLine("1", amount = "25000"))))
        assertNull(expenseTotal(listOf(ExpenseDraftLine("1", "Daging", Long.MAX_VALUE.toString()), ExpenseDraftLine("2", "Bumbu", "1"))))
    }

    @Test fun samplePlatformSalesAreNotReceivedMoney() {
        val daily = SampleData.entries.filter { it.date == SampleDate.toString() }
        assertEquals(500_000L, daily.filterNot { it.isExpense }.sumOf { it.amount })
        assertEquals(175_000L, daily.sumOf { it.received })
        assertEquals(175_000L, daily.filter { it.isExpense }.sumOf { it.amount })
        assertEquals("Rp 25.000", rupiah(25_000))
    }
}
