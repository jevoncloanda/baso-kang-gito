package id.basokanggito.app.data

import org.junit.Assert.*
import org.junit.Test

class ShoppingCatalogTest {
    @Test fun stableMasterIdMatchesIndependentUtf8Md5UuidVector() {
        // Python hashlib MD5 + uuid.UUID(version=3), independent of Java UUID construction.
        assertEquals("ff88d575-452f-3493-8ef2-bdae86327a2e", shoppingMasterId("Daging"))
        assertEquals(shoppingMasterId("Daging"), shoppingMasterId(" \tDaging\n "))
    }

    @Test fun meaningfulCaseAndInternalSpacingRemainSeparateIdentities() {
        val names = listOf("Daging", "daging", "Daging Sapi", "Daging  Sapi")
        assertEquals(names.size, names.map(::shoppingMasterId).distinct().size)
        assertEquals(shoppingMasterId("Daging Sapi"), shoppingMasterId("  Daging Sapi  "))
    }
}
