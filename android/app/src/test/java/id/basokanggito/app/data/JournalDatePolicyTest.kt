package id.basokanggito.app.data

import org.junit.Assert.*
import org.junit.Test

class JournalDatePolicyTest {
    @Test fun elapsedTimeAndWibCalendarControlReturnReset() {
        assertFalse(resetJournalOnReturn(10_000, 10_001, "2026-10-31", "2026-10-31"))
        assertFalse(resetJournalOnReturn(10_000, 909_999, "2026-10-31", "2026-10-31"))
        assertTrue(resetJournalOnReturn(10_000, 910_000, "2026-10-31", "2026-10-31"))
        assertTrue(resetJournalOnReturn(10_000, 10_001, "2026-12-31", "2027-01-01"))
        assertTrue(resetJournalOnReturn(10_000, 1, "2026-10-31", "2026-10-31"))
    }
}
