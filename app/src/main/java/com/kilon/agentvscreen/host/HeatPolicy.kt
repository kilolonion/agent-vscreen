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
 *  - [locked]: hard overheat shutdown latch (>= 45.0°C or SEVERE), requiring 3 min below 39.0°C + low status and
 *    manual resume from the Home power menu (unchanged).
 */
internal class HeatPolicy(initiallyLocked: Boolean) {
    @Volatile var locked = initiallyLocked; private set
    @Volatile var limited = initiallyLocked; private set
    @Volatile var ocrPaused = initiallyLocked; private set
    private var coolSince: Long? = null

    @Synchronized fun observe(temp: Int?, status: Int?, now: Long): Boolean {
        val was = locked
        if ((temp != null && temp >= LOCK_TEMP) || (status != null && status >= LOCK_STATUS)) locked = true
        val cool = temp != null && temp < UNLOCK_TEMP && status != null && status < MODERATE_STATUS
        coolSince = if (cool) coolSince ?: now else null

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
        return !was && locked
    }

    @Synchronized fun resume(now: Long): Boolean {
        if (!locked) return true
        val since = coolSince ?: return false
        if (now - since < UNLOCK_HOLD_MS) return false
        locked = false; limited = false; ocrPaused = false; return true
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
        const val UNLOCK_TEMP = 390
        const val UNLOCK_HOLD_MS = 180_000L
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
}
