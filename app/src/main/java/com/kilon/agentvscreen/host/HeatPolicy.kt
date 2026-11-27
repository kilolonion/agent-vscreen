package com.kilon.agentvscreen.host

/**
 * Battery in tenths of a degree, status uses the Android 0..6 scale. Elapsed time, never wall time.
 *
 * Three tiers:
 *  - [limited]: pauses continuous background work (perception, icon detector, self-test, idle frame pump, wake keys)
 *    and downgrades on-demand OCR from heavy PP-OCRv6 to lightweight ML Kit. Enters at >= 43.0°C, or at MODERATE (2)
 *    when battery is >= 40.0°C (or temp unavailable), so mild charging warmth does not throttle; clears below 41.0°C.
 *  - [ocrPaused]: pauses all OCR (including fast ML Kit) only when genuinely near overheat (>= 44.0°C or SEVERE /
 *    locked), clearing automatically below 42.0°C once unlocked.
 *  - [locked]: two-stage hard overheat shutdown latch (>= 45.0°C or SEVERE), optional via [hardStopEnabled]:
 *      * [Phase.LOCKED_HOT]: still in danger zone (>= 45.0°C or status >= SEVERE); cannot resume.
 *      * [Phase.COOLING]: left danger zone; normal recovery requires battery < 43.0°C and status < SEVERE for 60 s.
 *        If re-triggered within 1 hour of the previous resume, recovery tightens to < 39.0°C and status < MODERATE
 *        for 180 s.
 *      * [Phase.READY]: recovery criteria met; manual resume enters [limited] and clears automatically by normal rules.
 */
internal class HeatPolicy(
    initiallyLocked: Boolean,
    hardStopEnabled: Boolean = true,
    private var lastResumedAt: Long? = null,
    initiallyStrictUnlock: Boolean = false,
) {
    enum class Phase(val key: String) {
        NORMAL("normal"),
        LOCKED_HOT("locked_hot"),
        COOLING("cooling"),
        READY("ready"),
    }

    data class ToggleResult(val unlocked: Boolean, val tripped: Boolean)

    data class Snapshot(
        val locked: Boolean,
        val limited: Boolean,
        val ocrPaused: Boolean,
        val hardStopEnabled: Boolean,
        val phase: Phase,
        val strictUnlock: Boolean,
        val tempTenths: Int?,
        val status: Int?,
        val unlockTempTenths: Int,
        val unlockMaxStatusExclusive: Int,
        val holdMs: Long,
        val retryAfterS: Int?,
    )

    @Volatile var hardStopEnabled: Boolean = hardStopEnabled; private set
    @Volatile var locked: Boolean = initiallyLocked && hardStopEnabled; private set
    @Volatile var limited: Boolean = this.locked; private set
    @Volatile var ocrPaused: Boolean = this.locked; private set
    @Volatile var strictUnlock: Boolean = this.locked && initiallyStrictUnlock; private set
    private var coolSince: Long? = null
    private var lastTemp: Int? = null
    private var lastStatus: Int? = null
    private var inDangerWhenDisabled: Boolean = false
    private var pendingDangerAlert: Boolean = false

    private fun isDanger(temp: Int?, status: Int?): Boolean =
        (temp != null && temp >= LOCK_TEMP) || (status != null && status >= LOCK_STATUS)

    private fun shouldUseStrict(now: Long): Boolean {
        val prev = lastResumedAt ?: return false
        return now >= prev && (now - prev) < RECURRENCE_WINDOW_MS
    }

    private fun unlockTemp(): Int = if (strictUnlock) STRICT_UNLOCK_TEMP else COOL_UNLOCK_TEMP
    private fun unlockMaxStatusExclusive(): Int = if (strictUnlock) MODERATE_STATUS else LOCK_STATUS
    private fun unlockHoldMs(): Long = if (strictUnlock) STRICT_UNLOCK_HOLD_MS else COOL_UNLOCK_HOLD_MS

    private fun meetsCool(temp: Int?, status: Int?): Boolean =
        temp != null && temp < unlockTemp() && status != null && status < unlockMaxStatusExclusive()

    private fun updateSoftTiers(temp: Int?, status: Int?) {
        val hasSignal = temp != null || status != null
        val ocrHot = locked || (temp != null && temp >= OCR_PAUSE_TEMP) || (status != null && status >= LOCK_STATUS)
        val ocrCool = !locked && !ocrHot && hasSignal &&
            (temp == null || temp < OCR_RESUME_TEMP) &&
            (status == null || status < LOCK_STATUS)
        ocrPaused = locked || ocrHot || (ocrPaused && !ocrCool)

        val warm = ocrPaused ||
            (temp != null && temp >= LIMIT_TEMP) ||
            (status != null && status >= MODERATE_STATUS && (temp == null || temp >= MODERATE_MIN_TEMP))
        val warmCool = !locked && !warm && hasSignal &&
            (temp == null || temp < LIMIT_RESUME_TEMP) &&
            (status == null || status < MODERATE_STATUS || (temp != null && temp < MODERATE_RESUME_TEMP))
        limited = locked || warm || (limited && !warmCool)
    }

    @Synchronized fun observe(temp: Int?, status: Int?, now: Long): Boolean {
        lastTemp = temp
        lastStatus = status
        val was = locked
        val danger = isDanger(temp, status)
        if (hardStopEnabled) {
            inDangerWhenDisabled = false
            pendingDangerAlert = false
            if (danger) {
                if (!locked) strictUnlock = shouldUseStrict(now)
                locked = true
                coolSince = null
            } else if (locked) {
                coolSince = if (meetsCool(temp, status)) coolSince ?: now else null
            } else {
                coolSince = null
            }
        } else {
            coolSince = null
            if (danger) {
                if (!inDangerWhenDisabled) {
                    inDangerWhenDisabled = true
                    pendingDangerAlert = true
                }
            } else if (temp != null || status != null) {
                inDangerWhenDisabled = false
            }
        }
        updateSoftTiers(temp, status)
        return !was && locked
    }

    /** Returns true once each time the device enters the danger zone while [hardStopEnabled] is false. */
    @Synchronized fun consumeDangerAlert(): Boolean {
        val hit = pendingDangerAlert
        pendingDangerAlert = false
        return hit
    }

    @Synchronized fun setHardStopEnabled(enabled: Boolean, now: Long): ToggleResult {
        if (hardStopEnabled == enabled) return ToggleResult(unlocked = false, tripped = false)
        hardStopEnabled = enabled
        if (!enabled) {
            val wasLocked = locked
            locked = false
            strictUnlock = false
            coolSince = null
            inDangerWhenDisabled = isDanger(lastTemp, lastStatus)
            pendingDangerAlert = false
            // Recalculate soft tiers cleanly from current readings without forcing lock carryover.
            ocrPaused = false
            limited = false
            updateSoftTiers(lastTemp, lastStatus)
            return ToggleResult(unlocked = wasLocked, tripped = false)
        }
        inDangerWhenDisabled = false
        pendingDangerAlert = false
        if (isDanger(lastTemp, lastStatus)) {
            strictUnlock = shouldUseStrict(now)
            locked = true
            coolSince = null
            updateSoftTiers(lastTemp, lastStatus)
            return ToggleResult(unlocked = false, tripped = true)
        }
        updateSoftTiers(lastTemp, lastStatus)
        return ToggleResult(unlocked = false, tripped = false)
    }

    @Synchronized fun resume(now: Long): Boolean {
        if (!locked) return true
        if (isDanger(lastTemp, lastStatus)) return false
        val since = coolSince ?: return false
        if (now - since < unlockHoldMs()) return false
        locked = false
        strictUnlock = false
        coolSince = null
        lastResumedAt = now
        // Enter `limited` immediately on resume; subsequent observations clear it via normal thresholds.
        ocrPaused = false
        limited = true
        return true
    }

    @Synchronized fun snapshot(now: Long): Snapshot {
        val uTemp = unlockTemp()
        val uStatus = unlockMaxStatusExclusive()
        val hold = unlockHoldMs()
        val phase: Phase
        val retryAfterS: Int?
        if (!locked) {
            phase = Phase.NORMAL
            retryAfterS = null
        } else if (isDanger(lastTemp, lastStatus)) {
            phase = Phase.LOCKED_HOT
            retryAfterS = null
        } else {
            val since = coolSince
            if (since == null) {
                phase = Phase.COOLING
                retryAfterS = null
            } else {
                val remMs = (hold - (now - since)).coerceAtLeast(0L)
                if (remMs == 0L) {
                    phase = Phase.READY
                    retryAfterS = 0
                } else {
                    phase = Phase.COOLING
                    retryAfterS = ((remMs + 999L) / 1000L).toInt()
                }
            }
        }
        return Snapshot(
            locked = locked,
            limited = limited,
            ocrPaused = ocrPaused,
            hardStopEnabled = hardStopEnabled,
            phase = phase,
            strictUnlock = strictUnlock,
            tempTenths = lastTemp,
            status = lastStatus,
            unlockTempTenths = uTemp,
            unlockMaxStatusExclusive = uStatus,
            holdMs = hold,
            retryAfterS = retryAfterS,
        )
    }

    companion object {
        const val MODERATE_STATUS = 2
        const val LOCK_STATUS = 3
        const val MODERATE_MIN_TEMP = 400
        const val MODERATE_RESUME_TEMP = 390
        const val LIMIT_TEMP = 430
        const val LIMIT_RESUME_TEMP = 410
        const val OCR_PAUSE_TEMP = 440
        const val OCR_RESUME_TEMP = 420
        const val LOCK_TEMP = 450
        const val COOL_UNLOCK_TEMP = 430
        const val COOL_UNLOCK_HOLD_MS = 60_000L
        const val STRICT_UNLOCK_TEMP = 390
        const val STRICT_UNLOCK_HOLD_MS = 180_000L
        const val RECURRENCE_WINDOW_MS = 60 * 60_000L
        // Legacy aliases kept for compatibility.
        const val UNLOCK_TEMP = STRICT_UNLOCK_TEMP
        const val UNLOCK_HOLD_MS = STRICT_UNLOCK_HOLD_MS
    }
}

/** Budget actual injected wake keys across display instances and screen-on broadcasts. */
internal class WakeBudget {
    private val keys = ArrayDeque<Long>()
    @Synchronized fun allow(now: Long): Boolean {
        while (keys.isNotEmpty() && now - keys.first() >= 120_000L) keys.removeFirst()
        if (keys.size >= 2 || keys.lastOrNull()?.let { now - it < 15_000L } == true) return false
        keys.addLast(now); return true
    }
    @Synchronized fun reset() { keys.clear() }
}
