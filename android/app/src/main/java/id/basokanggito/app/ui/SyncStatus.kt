package id.basokanggito.app.ui

import id.basokanggito.app.R

internal fun notebookSyncStatus(failed: Boolean, connectionError: Boolean, configured: Boolean,
    pending: Boolean, completed: Boolean, running: Boolean): Int = when {
    failed -> R.string.sync_review
    connectionError -> R.string.sync_connection
    !configured -> R.string.sync_not_configured
    running -> R.string.sync_syncing
    pending -> R.string.sync_pending
    completed -> R.string.sync_complete
    else -> R.string.local_development
}
