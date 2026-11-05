package io.github.geniusdevam.vpnapp.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serialises the user's commands (connect runs, cancel, disconnect) so an older operation's cleanup can never
 * undo a newer decision.
 *
 * The bug this prevents: `disconnect()` cancelled the active run and, separately, started `tunnel.disconnect()`.
 * The cancelled run's cleanup ("restore the previous connection") runs under NonCancellable and could reconnect
 * the old server AFTER the user had asked to disconnect.
 *
 * Rules:
 *  - Every [start] and [disconnect] takes a new epoch. A run may restore the previous connection only while its
 *    own epoch is still current ([Handle.mayRestore]); a plain [cancel] does not change the epoch, so cancelling a
 *    connection attempt still brings back the previous connection, as before.
 *  - [disconnect] waits for the cancelled run (and its cleanup) to finish, then disconnects, as one job. Disconnects
 *    queue behind each other.
 *  - [start] waits for any pending disconnect and runs the new body afterwards, so the body sees the true state.
 *  - Only one run at a time: [start] returns false while a run (including its cleanup) is still active.
 */
class RunCoordinator(private val scope: CoroutineScope, private val tunnel: TunnelController) {
    private val lock = Any()
    private var epoch = 0L
    private var job: Job? = null
    private var tail: Job? = null // the latest queued disconnect

    class Handle internal constructor(private val c: RunCoordinator, val epoch: Long) {
        /** True only while no explicit disconnect or newer run has superseded this one. */
        fun mayRestore(): Boolean = c.currentEpoch() == epoch
    }

    internal fun currentEpoch(): Long = synchronized(lock) { epoch }

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts [body] unless a run is already active. [body] must call [Handle.mayRestore] before restoring anything. */
    fun start(body: suspend (Handle) -> Unit): Boolean = synchronized(lock) {
        if (job?.isActive == true) return false
        val h = Handle(this, ++epoch)
        val wait = tail
        job = scope.launch {
            wait?.join() // a pending disconnect finishes first, so `body` reads the real state
            if (h.mayRestore()) body(h)
        }
        true
    }

    /** User cancelled the current attempt. The previous connection (if any) is restored by the run's own cleanup. */
    fun cancel() {
        synchronized(lock) { job }?.cancel(CancellationException("cancelled by user"))
    }

    /** Explicit disconnect. Nothing queued or running before this call can reconnect afterwards. Returns the job that completes when the tunnel is down. */
    fun disconnect(after: suspend () -> Unit = {}): Job = synchronized(lock) {
        epoch++
        val older = job
        val previousTail = tail
        older?.cancel(CancellationException("disconnect requested"))
        val d = scope.launch {
            previousTail?.join()
            older?.join() // let the old run finish its cleanup; it is no longer allowed to restore
            tunnel.disconnect()
            after()
        }
        tail = d
        d
    }
}
