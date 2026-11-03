package id.basokanggito.app.ui

import id.basokanggito.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance

class SyncStatusTest {
    @Test fun statusTextHasAccessibleContrastInBothThemes() {
        for ((scheme, success) in listOf(LightColors to LightSyncSuccess, DarkColors to DarkSyncSuccess)) {
            for (color in listOf(success, scheme.secondary, scheme.error, scheme.onSurfaceVariant)) {
                val foreground = color.luminance()
                val background = scheme.surface.luminance()
                assertTrue((maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f) >= 4.5f)
            }
        }
    }

    @Test fun successRequiresCompletionWithoutOutstandingWork() {
        for (failed in listOf(false, true)) for (error in listOf(false, true))
            for (configured in listOf(false, true)) for (pending in listOf(false, true))
                for (completed in listOf(false, true)) for (running in listOf(false, true)) {
                    assertEquals(!failed && !error && configured && !pending && completed && !running,
                        notebookSyncStatus(failed, error, configured, pending, completed, running) == R.string.sync_complete)
                }
    }

    @Test fun problemsAndActiveWorkHaveExplicitLabels() {
        assertEquals(R.string.sync_review, notebookSyncStatus(true, true, true, true, true, true))
        assertEquals(R.string.sync_connection, notebookSyncStatus(false, true, true, true, true, false))
        assertEquals(R.string.sync_syncing, notebookSyncStatus(false, false, true, false, true, true))
        assertEquals(R.string.sync_pending, notebookSyncStatus(false, false, true, true, true, false))
    }
}
