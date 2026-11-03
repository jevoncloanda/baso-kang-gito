package id.basokanggito.app.data

import org.junit.Assert.*
import org.junit.Test

class SyncRequestsTest {
    @Test fun onlyRequestsCoveredInThisProcessAreSuppressed() {
        val earlier = SyncRequests.next()
        val started = SyncRequests.current()
        val later = SyncRequests.next()
        assertTrue(SyncRequests.covered(earlier, started))
        assertFalse(SyncRequests.covered(later, started))
        assertFalse(SyncRequests.covered("another-process:1", Long.MAX_VALUE))
        assertFalse(SyncRequests.covered("2099-12-31T00:00:00Z", Long.MAX_VALUE))
        assertFalse(SyncRequests.covered(earlier, 0))
    }
}
