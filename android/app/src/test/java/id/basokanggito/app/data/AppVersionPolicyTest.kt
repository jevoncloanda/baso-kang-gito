package id.basokanggito.app.data

import org.junit.Assert.*
import org.junit.Test

class AppVersionPolicyTest {
    @Test fun updateAvailabilityRecommendationAndRequirementAreSeparate() {
        val policy = AppVersionPolicy(4, 2, 3, "0.0.4", "Update")
        assertTrue(policy.required(1)); assertFalse(policy.required(2))
        assertTrue(policy.updateRecommended(2)); assertFalse(policy.updateRecommended(3))
        assertTrue(policy.updateAvailable(3)); assertFalse(policy.updateAvailable(4))
        assertFalse(policy.required(4))
    }
    @Test fun existingPinTimeoutRemainsThirtySeconds() {
        assertFalse(pinSessionExpired(1000, 30999))
        assertTrue(pinSessionExpired(1000, 31000))
    }
}
