package com.kilon.agentvscreen.core

/**
 * Unified pure decision model routing actions across touch injection, accessibility node actions,
 * main-screen wake-then-touch, or explicit refusal when the system marks the virtual display non-interactive
 * while the phone screen is off.
 */
internal object InputRoute {
    enum class Kind {
        /** Element-targeted tap (by text or #N, without coordinate offset). */
        ELEMENT_TAP,
        /** Element-targeted long press (non-closed-loop). */
        ELEMENT_PRESS,
        /** Directional scroll. */
        SCROLL,
        /** Text entry into a focused input field. */
        TYPE,
        /** Coordinate tap/press, offset tap, swipe, drag, closed-loop hold/seek, or raw finger events. */
        POINT_OR_GESTURE,
    }

    enum class Route {
        TOUCH,
        A11Y,
        WAKE_THEN_TOUCH,
        REFUSE,
    }

    fun choose(
        reach: InputReach.Reach,
        kind: Kind,
        hasNode: Boolean,
        wakeEnabled: Boolean,
    ): Route {
        if (reach != InputReach.Reach.BLOCKED) return Route.TOUCH
        return when (kind) {
            Kind.ELEMENT_TAP, Kind.ELEMENT_PRESS, Kind.SCROLL, Kind.TYPE ->
                if (hasNode) Route.A11Y else if (wakeEnabled) Route.WAKE_THEN_TOUCH else Route.REFUSE
            Kind.POINT_OR_GESTURE ->
                if (wakeEnabled) Route.WAKE_THEN_TOUCH else Route.REFUSE
        }
    }
}
