package io.github.geniusdevam.vpnapp.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {
    private val dir = File.createTempFile("persist", "").let { it.delete(); it.mkdirs(); it }

    @AfterTest fun cleanup() { AtomicFiles.beforeCommit = null; dir.deleteRecursively() }

    @Test fun replaceWritesNewContentAndLeavesNoTemp() {
        val f = File(dir, "state.bin")
        AtomicFiles.replace(f, "one".toByteArray())
        AtomicFiles.replace(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertTrue(!File(dir, "state.bin.tmp").exists())
    }

    /** The old vault deleted the good file when the rename failed. A crash at the worst moment must leave the OLD content intact. */
    @Test fun crashBetweenTempWriteAndCommitKeepsOldContent() {
        val f = File(dir, "state.bin")
        AtomicFiles.replace(f, "old-good".toByteArray())
        AtomicFiles.beforeCommit = { throw IllegalStateException("process killed here") }
        runCatching { AtomicFiles.replace(f, "new".toByteArray()) }
        assertEquals("old-good", f.readText(), "the previous file must survive a failed replacement")
        AtomicFiles.beforeCommit = null
        AtomicFiles.replace(f, "recovered".toByteArray())       // a stray .tmp from the crash is simply overwritten
        assertEquals("recovered", f.readText())
    }

    @Test fun firstWriteCreatesParentDirectory() {
        val f = File(dir, "a/b/c.bin")
        AtomicFiles.replace(f, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), f.readBytes())
    }

    @Test fun lastSubmittedSnapshotAlwaysWinsAndWritesNeverGoBackwards() {
        val pool = Executors.newFixedThreadPool(8)
        val scope = CoroutineScope(pool.asCoroutineDispatcher() + SupervisorJob())
        try {
            repeat(30) { round ->
                val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
                val w = LatestWinsWriter(scope) { b -> Thread.sleep(1); seen += String(b).toInt() }
                val n = 400
                // Several producers race; each submission carries a strictly increasing global number.
                val counter = AtomicInteger()
                val start = CountDownLatch(1)
                val ts = (0 until 4).map {
                    Thread {
                        start.await()
                        repeat(n / 4) {
                            synchronized(counter) { val v = counter.incrementAndGet(); w.submit { v.toString().toByteArray() } }
                        }
                    }.also { it.start() }
                }
                start.countDown(); ts.forEach { it.join() }
                assertTrue(w.awaitIdle(), "writer drained (round $round)")
                assertEquals(n, seen.last(), "the newest snapshot must be the last one persisted (round $round)")
                assertEquals(seen.sorted(), seen.toList(), "an older snapshot must never be written after a newer one (round $round)")
                assertTrue(seen.size < n, "a burst is coalesced instead of one rewrite per change")
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun failedWriteIsRetriedAndErrorClearedOnSuccess() {
        val pool = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(pool.asCoroutineDispatcher() + SupervisorJob())
        try {
            val calls = AtomicInteger()
            val ok = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val w = LatestWinsWriter(scope, retries = 2) { b -> if (calls.incrementAndGet() < 3) error("disk busy") else ok.set(String(b)) }
            w.submit { "payload".toByteArray() }
            assertTrue(w.awaitIdle())
            assertEquals("payload", ok.get())
            assertNull(w.lastError)
            assertEquals(3, calls.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun permanentFailureIsReportedNotThrownAndLaterSubmitsStillWork() {
        val pool = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(pool.asCoroutineDispatcher() + SupervisorJob())
        try {
            var fail = true
            val got = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val w = LatestWinsWriter(scope, retries = 1) { b -> if (fail) error("no space") else got.set(String(b)) }
            w.submit { "a".toByteArray() }
            assertTrue(w.awaitIdle())
            assertTrue(w.lastError != null)
            fail = false
            w.submit { "b".toByteArray() }
            assertTrue(w.awaitIdle())
            assertEquals("b", got.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun writerComposedWithAtomicFilePersistsLatestOnly() {
        val pool = Executors.newFixedThreadPool(4)
        val scope = CoroutineScope(pool.asCoroutineDispatcher() + SupervisorJob())
        try {
            val f = File(dir, "ledger.bin")
            val w = LatestWinsWriter(scope) { AtomicFiles.replace(f, it) }
            repeat(200) { i -> w.submit { "v$i".toByteArray() } }
            assertTrue(w.awaitIdle())
            assertEquals("v199", f.readText())
        } finally { pool.shutdownNow() }
    }
}
