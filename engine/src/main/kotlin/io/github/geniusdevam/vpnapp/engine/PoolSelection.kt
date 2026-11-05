package io.github.geniusdevam.vpnapp.engine

/**
 * Turns the directory contents into the candidate pool for one run, and when that pool is empty says WHY (finding C9).
 *
 * Three different situations used to collapse into "VPN Gate has no usable servers in Japan right now", two of which that
 * sentence misreports:
 *  - the directory itself could not be loaded (offline, blocked, rejected download): nothing is known about any country;
 *  - the directory loaded but has no relay in the chosen country;
 *  - relays exist in the country, but every one is excluded by the user's own settings (e.g. "Strong encryption only", when
 *    the service currently offers only AES-128-CBC) or is temporarily avoided after failures.
 * The scope is never widened: an empty pool is reported, not silently replaced by another country.
 */
sealed interface PoolResult {
    data class Ready(val pool: List<Server>) : PoolResult
    data class Empty(val reason: FailReason, val inScope: Int, val excludedBySettings: Int, val avoided: Int) : PoolResult
}

object PoolSelection {
    fun select(
        all: List<Server>,
        scope: Scope,
        mode: EncryptionMode,
        reputation: Reputation,
    ): PoolResult {
        if (all.isEmpty()) return PoolResult.Empty(FailReason.DIRECTORY_UNAVAILABLE, 0, 0, 0)
        val inScope = all.filter { scope.matches(it) }
        if (inScope.isEmpty()) return PoolResult.Empty(FailReason.NO_SERVERS_IN_SCOPE, 0, 0, 0)
        val allowed = inScope.filter { CipherPolicy.acceptsServer(it, mode) }
        val usable = allowed.filter { !reputation.isBlocked(it.id) }
        if (usable.isNotEmpty()) return PoolResult.Ready(usable)
        return PoolResult.Empty(
            FailReason.NO_SERVERS_MATCH_SETTINGS, inScope.size,
            excludedBySettings = inScope.size - allowed.size, avoided = allowed.size,
        )
    }
}
