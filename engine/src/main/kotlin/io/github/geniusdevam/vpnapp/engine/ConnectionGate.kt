package io.github.geniusdevam.vpnapp.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A connection that has passed the encryption policy. [cipher] is the OBSERVED cipher; null with [cipherVerified] false means "not verified". */
data class Admitted(val server: Server, val cipher: String?, val cipherVerified: Boolean, val connectMs: Long)

sealed interface GateResult {
    data class Open(val admitted: Admitted) : GateResult
    data class Refused(val result: ConnectResult) : GateResult
}

/**
 * The one place that turns "the tunnel came up" into "the tunnel is acceptable". Quick Connect, Best Connect,
 * manual connections and the restore-previous-connection path all go through it, so the encryption policy
 * cannot be skipped by a particular code path. A connection that violates the policy is torn down before the
 * caller sees the result.
 */
class ConnectionGate(
    private val tunnel: TunnelController,
    private val mode: EncryptionMode,
    private val reputation: Reputation = NoReputation,
    private val recorder: AttemptRecorder = NoRecorder,
    private val fingerprint: String = "unknown",
    private val clock: Clock = MonotonicClock,
) {
    suspend fun connect(server: Server, timeoutMs: Long): GateResult {
        val t0 = clock.nowMs()
        val r = tunnel.connect(server, timeoutMs) // cancellation propagates; a cancelled attempt is nobody's fault
        val took = clock.nowMs() - t0
        if (!r.success) {
            if (r.kind != FailureKind.CANCELLED) {
                reputation.recordFailure(server.id, r.hard)
                recorder.connectFailed(server.id, fingerprint, r.kind ?: FailureKind.OTHER, took)
            }
            return GateResult.Refused(r)
        }
        return when (val v = CipherPolicy.evaluate(r.cipher, server.declaredCipher, mode)) {
            is CipherVerdict.Reject -> {
                withContext(NonCancellable) { tunnel.disconnect() }
                reputation.recordFailure(server.id, true)
                recorder.connectFailed(server.id, fingerprint, FailureKind.POLICY, took)
                GateResult.Refused(ConnectResult.failed(FailureKind.POLICY, v.reason))
            }
            is CipherVerdict.Accept -> {
                reputation.recordSuccess(server.id)
                recorder.connectSucceeded(server.id, fingerprint, took, v.cipher, v.verified)
                GateResult.Open(Admitted(server, v.cipher, v.verified, took))
            }
        }
    }

    /**
     * Re-checks a connection that is ALREADY up (for instance Best Connect's winner, which stays connected after
     * its speed test) against the current policy. Disconnects and returns false if it no longer qualifies.
     */
    suspend fun recheck(a: Admitted): Boolean {
        val ok = CipherPolicy.acceptsServer(a.server, mode) &&
            CipherPolicy.evaluate(a.cipher, a.server.declaredCipher, mode) is CipherVerdict.Accept &&
            (mode != EncryptionMode.STRONG || a.cipherVerified)
        if (!ok) withContext(NonCancellable) { tunnel.disconnect() }
        return ok
    }
}
