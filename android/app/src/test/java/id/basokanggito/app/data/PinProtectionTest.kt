package id.basokanggito.app.data

import org.junit.Assert.*
import org.junit.Test

class PinProtectionTest {
    @Test fun pinRequiresExactlySixAsciiDigits() {
        assertTrue(PinProtection.valid("012345"))
        for (value in listOf("", "12345", "1234567", "１２３４５６", "12345a", "123 45")) assertFalse(PinProtection.valid(value))
    }
    @Test fun verifierSeparatesSaltsAndPinValues() {
        val salt = ByteArray(32) { it.toByte() }
        val hash = PinProtection.derive("012345", salt)
        assertArrayEquals(hash, PinProtection.derive("012345", salt))
        assertFalse(hash.contentEquals(PinProtection.derive("012346", salt)))
        assertFalse(hash.contentEquals(PinProtection.derive("012345", ByteArray(32))))
        assertEquals(32, hash.size)
    }
    @Test fun repeatedGuessingEscalatesWithoutDestroyingData() {
        assertEquals(0, PinProtection.delayMillis(4)); assertEquals(30_000, PinProtection.delayMillis(5))
        assertEquals(60_000, PinProtection.delayMillis(6)); assertEquals(900_000, PinProtection.delayMillis(30))
    }
}
