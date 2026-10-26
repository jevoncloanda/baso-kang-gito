package id.basokanggito.app.ui

import java.io.Serializable
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import id.basokanggito.app.R

val SampleDate: LocalDate = LocalDate.of(2026, 10, 26)
object SampleData {
    val entries = listOf(
        JournalEntry("langsung-26", "2026-10-26", "Langsung", 175_000, 175_000, "Bu Sari"),
        JournalEntry("gofood-26", "2026-10-26", "GoFood", 200_000),
        JournalEntry("belanja-26", "2026-10-26", "Belanja", 175_000,
            lines = listOf(JournalLine("", 150_000, R.string.sample_meat), JournalLine("", 25_000, R.string.sample_spices))),
        JournalEntry("grabfood-26", "2026-10-26", "GrabFood", 125_000),
        JournalEntry("langsung-25", "2026-10-25", "Langsung", 260_000, 260_000),
        JournalEntry("belanja-25", "2026-10-25", "Belanja", 60_000,
            lines = listOf(JournalLine("", 60_000, R.string.sample_vegetables))),
    )
}
