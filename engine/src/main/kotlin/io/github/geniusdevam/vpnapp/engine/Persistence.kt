package io.github.geniusdevam.vpnapp.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe file replacement (finding C7).
 *
 * The previous vault wrote a temp file and, when the rename failed, *deleted the existing file* before retrying, so one failed
 * rename could destroy the only good copy. Here the existing file is never touched until the new content is complete and durable:
 * write the temp file, fsync it, then atomically rename it over the target. At every instant the target is either the complete
 * old content or the complete new content; a crash in between leaves at worst a stray `.tmp` that the next write overwrites.
 */
object AtomicFiles {
    /** Test hook: runs after the temp file is durable and before it replaces the target (simulates a crash/kill at that point). */
    @Volatile internal var beforeCommit: (() -> Unit)? = null

    fun replace(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: error("no parent directory")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        beforeCommit?.invoke()
        // File.renameTo is rename(2) on Android and Linux: it atomically replaces an existing target on the same filesystem, and works
        // on every Android version (java.nio.file needs Android 8.0). If it fails, the old file is still intact: never delete first.
        if (!tmp.renameTo(target)) throw java.io.IOException("could not replace ${target.name}; the previous version is kept")
    }
}

/**
 * Ordered, coalescing background writer for one piece of persisted state.
 *
 * The previous design launched one coroutine per save and serialised them with a Mutex. A Mutex is not FIFO across coroutines
 * on a multi-threaded dispatcher, so an older snapshot could be persisted *after* a newer one and silently roll the state back.
 * Here there is exactly one drain loop per writer; `submit` only replaces the pending snapshot. Consequences:
 *  - writes are strictly in submission order, and the last submitted snapshot is always the last one written;
 *  - a burst of changes (or a multi-thousand-record directory refresh followed by ledger updates) collapses into one write
 *    instead of one full encrypted rewrite per change.
 *
 * `snapshot` is invoked on the writer's thread, so it must capture an immutable view taken at submit time (build the view in the
 * caller, encode inside the lambda).
 */
class LatestWinsWriter(
    private val scope: CoroutineScope,
    private val retries: Int = 2,
    private val sink: (ByteArray) -> Unit,
) {
    private val lock = Object()
    private var pending: (() -> ByteArray)? = null
    private var draining = false

    @Volatile var lastError: Throwable? = null
        private set
    @Volatile var writes: Int = 0
        private set

    fun submit(snapshot: () -> ByteArray) {
        synchronized(lock) {
            pending = snapshot
            if (draining) return
            draining = true
        }
        scope.launch { drain() }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val p = pending
                pending = null
                if (p == null) { draining = false; lock.notifyAll() }
                p
            } ?: return
            var attempt = 0
            while (true) {
                try {
                    sink(job())
                    writes++
                    lastError = null
                    break
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt++ >= retries) break
                    // Re-queue only if nothing newer arrived while we failed: a newer snapshot supersedes this one anyway.
                }
            }
        }
    }

    /** Blocks until everything submitted so far has been written (or given up on). Used on shutdown paths and in tests. */
    fun awaitIdle(timeoutMs: Long = 10_000): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (draining || pending != null) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                lock.wait(left)
            }
        }
        return true
    }
}
