package com.kilon.agentvscreen.api

import java.security.SecureRandom

/**
 * Short-lived, single-use onboarding codes (`avc_...`) for local and Tailscale agents (P1-2).
 *
 * Keeps permanent `avs_...` tokens out of chat logs, shell command histories, and `/proc/<pid>/cmdline`:
 * the user shares a 10-minute code, and the agent exchanges it once via `POST /v2/pair/exchange`
 * (mounted ONLY on [ApiServer], never on [PublicServer]) to receive and persist its permanent token locally.
 *
 * Security invariants:
 * - Stored strictly in memory (never written to disk); process restart invalidates pending codes.
 * - Only SHA-256 hashes of codes are retained in memory (no plaintext map).
 * - Single-use atomic removal on [consume]; expired or replayed codes are rejected.
 * - Bounded to [MAX_ACTIVE] concurrent codes (FIFO eviction) and 1 code per [tokenId].
 */
object OnboardingCodes {
    const val PREFIX = "avc_"
    const val TTL_MS = 10 * 60_000L
    const val MAX_ACTIVE = 16
    private const val RANDOM_LEN = 24
    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private data class Entry(
        val tokenId: String,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private val rng = SecureRandom()
    private val active = LinkedHashMap<String, Entry>()

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** True when [raw] matches the `avc_<alphanumeric>` onboarding code shape. */
    fun looksLikeCode(raw: String?): Boolean {
        val t = raw?.trim() ?: return false
        if (!t.startsWith(PREFIX)) return false
        val body = t.removePrefix(PREFIX)
        return body.length in 16..64 && body.all { it.isLetterOrDigit() }
    }

    /** Issue a fresh 10-minute single-use code for [tokenId], replacing any earlier active code for that token. */
    @Synchronized
    fun issue(tokenId: String): String {
        val now = clock()
        pruneExpiredLocked(now)
        active.entries.removeAll { it.value.tokenId == tokenId }
        while (active.size >= MAX_ACTIVE) {
            val oldest = active.entries.iterator().next().key
            active.remove(oldest)
        }
        val raw = PREFIX + randomBody()
        val hash = Tokens.sha256(raw)
        active[hash] = Entry(tokenId = tokenId, createdAt = now, expiresAt = now + TTL_MS)
        return raw
    }

    /**
     * Atomically validate and consume [rawCode]. Returns the bound `tokenId` on success,
     * or `null` if malformed, unknown, expired, or already redeemed.
     */
    @Synchronized
    fun consume(rawCode: String?): String? {
        val now = clock()
        pruneExpiredLocked(now)
        val trimmed = rawCode?.trim() ?: return null
        if (!looksLikeCode(trimmed)) return null
        val wantHash = Tokens.sha256(trimmed)
        var matchedKey: String? = null
        for (k in active.keys) {
            if (ApiServer.constTimeEq(k, wantHash)) {
                matchedKey = k
            }
        }
        val key = matchedKey ?: return null
        val entry = active.remove(key) ?: return null
        if (entry.expiresAt <= now) return null
        return entry.tokenId
    }

    /** Revoke any active onboarding code for [tokenId]. */
    @Synchronized
    fun revokeForToken(tokenId: String) {
        active.entries.removeAll { it.value.tokenId == tokenId }
    }

    /** Whether [tokenId] currently has an unexpired onboarding code. */
    @Synchronized
    fun hasActive(tokenId: String): Boolean {
        pruneExpiredLocked(clock())
        return active.values.any { it.tokenId == tokenId }
    }

    /** Expiration timestamp (epoch ms) of the active onboarding code for [tokenId], if any. */
    @Synchronized
    fun expiresAt(tokenId: String): Long? {
        pruneExpiredLocked(clock())
        return active.values.firstOrNull { it.tokenId == tokenId }?.expiresAt
    }

    @Synchronized
    internal fun activeCount(): Int {
        pruneExpiredLocked(clock())
        return active.size
    }

    @Synchronized
    internal fun resetForTests() {
        active.clear()
        clock = { System.currentTimeMillis() }
    }

    private fun pruneExpiredLocked(now: Long) {
        active.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun randomBody(): String =
        (1..RANDOM_LEN).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")
}
