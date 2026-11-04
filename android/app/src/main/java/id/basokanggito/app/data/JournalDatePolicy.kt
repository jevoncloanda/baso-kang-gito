package id.basokanggito.app.data

internal fun pinSessionExpired(backgroundAt: Long, now: Long): Boolean = now - backgroundAt >= 30_000L

internal fun resetJournalOnReturn(backgroundAt: Long, now: Long, backgroundDate: String, today: String): Boolean =
    now < backgroundAt || now - backgroundAt >= 15 * 60_000L || backgroundDate != today
