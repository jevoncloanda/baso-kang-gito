package id.basokanggito.app.ui

import org.junit.Assert.*
import org.junit.Test

class AppAppearanceTest {
    @Test fun defaultsAndOverrides() {
        assertEquals(AppAppearance.SYSTEM, AppAppearance.parse(null))
        assertEquals(AppAppearance.SYSTEM, AppAppearance.parse("unknown"))
        AppAppearance.entries.forEach { assertEquals(it, AppAppearance.parse(it.name)) }
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, AppAppearance.SYSTEM.isDark(systemDark))
            assertFalse(AppAppearance.LIGHT.isDark(systemDark))
            assertTrue(AppAppearance.DARK.isDark(systemDark))
        }
    }
}
