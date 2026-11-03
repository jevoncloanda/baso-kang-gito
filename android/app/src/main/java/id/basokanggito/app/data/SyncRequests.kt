package id.basokanggito.app.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// Coverage is process-local; wall-clock corrections and process restarts must never suppress a refresh.
internal object SyncRequests {
    private val session = UUID.randomUUID().toString()
    private val generation = AtomicLong()
    fun next(): String = "$session:${generation.incrementAndGet()}"
    fun current(): Long = generation.get()
    fun covered(request: String, completed: Long): Boolean = request.substringBefore(':') == session &&
        request.substringAfter(':', "").toLongOrNull()?.let { it in 1..completed } == true
}
