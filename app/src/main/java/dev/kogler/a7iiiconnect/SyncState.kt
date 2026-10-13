package dev.kogler.a7iiiconnect

import android.os.Handler
import android.os.Looper

enum class Phase { IDLE, SEARCHING, READING, COPYING, DONE, FAILED }

/** What the sync is doing right now, shared between the service and the screen. */
data class SyncStatus(
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val copied: Int = 0,
    val message: String = "Ready",
    val done: Int = 0,
    val total: Int = 0,
    val currentFile: String? = null,
)

object SyncState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(SyncStatus) -> Unit>()
    private val log = StringBuilder()

    @Volatile var status = SyncStatus()
        private set

    fun update(transform: (SyncStatus) -> SyncStatus) {
        val next = synchronized(this) { transform(status).also { status = it } }
        main.post { listeners.toList().forEach { it(next) } }
    }

    fun observe(listener: (SyncStatus) -> Unit) {
        listeners += listener
        listener(status)
    }

    fun remove(listener: (SyncStatus) -> Unit) {
        listeners -= listener
    }

    fun log(msg: String) {
        android.util.Log.i("A7IIIConnect", msg)
        synchronized(log) {
            log.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                .append(' ').append(msg).append('\n')
            if (log.length > 200_000) log.delete(0, log.length - 150_000)
        }
    }

    fun logText(): String = synchronized(log) { log.toString() }
}
