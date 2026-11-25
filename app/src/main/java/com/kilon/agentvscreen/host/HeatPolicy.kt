package com.kilon.agentvscreen.host

/** Battery in tenths of a degree, status uses the Android 0..6 scale. Elapsed time, never wall time. */
internal class HeatPolicy(initiallyLocked: Boolean) {
    @Volatile var locked = initiallyLocked; private set
    @Volatile var limited = initiallyLocked; private set
    private var coolSince: Long? = null
    @Synchronized fun observe(temp: Int?, status: Int?, now: Long): Boolean {
        val was = locked
        if ((temp != null && temp >= 450) || (status != null && status >= 3)) locked = true
        val cool = temp != null && temp < 390 && status != null && status < 2
        coolSince = if (cool) coolSince ?: now else null
        val warm = (temp != null && temp >= 420) || (status != null && status >= 2)
        limited = locked || warm || (limited && !cool)
        return !was && locked
    }
    @Synchronized fun resume(now: Long): Boolean {
        if (!locked) return true
        val since = coolSince ?: return false
        if (now - since < 180_000L) return false
        locked = false; limited = false; return true
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
