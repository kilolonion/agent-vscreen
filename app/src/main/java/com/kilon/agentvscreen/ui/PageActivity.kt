package com.kilon.agentvscreen.ui

import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import androidx.activity.ComponentActivity

/**
 * 1.3.0 (rainbow, beta19) + 2.0.0-beta8 (G.3): one way back from our own full pages (the full screen page, an answer
 * page, the lesson review) to the floating window.
 *
 * Before, each page called `finish()` on its own paths and some paths did not call it at all: "Done" on the full
 * screen page opened the review in another task and left the full screen page stopped behind it. When the review
 * closed, the system brought that stale page back, on MagicOS shrunk into a small window, instead of the floating
 * window. Now:
 * - every way out goes through [back], which brings the floating window back FIRST, suppresses the task-close
 *   animation (`overrideActivityTransition` / `overridePendingTransition`), and ends the page AND its task;
 * - a page put into a small or split window by the system goes back to the floating window (these pages are
 *   only meant to fill the screen; the floating window is our small view);
 * - a page with [closeWhenHidden] ends as soon as another page or app covers it (the full screen page: what is in
 *   front of it decides where the user goes next, and coming back means the floating window).
 * While a page is in front the floating window steps aside ([Overlay.fullscreenShown]).
 */
abstract class PageActivity : ComponentActivity() {
    protected open val closeWhenHidden: Boolean = false
    private var overlayHeld = false

    override fun onStart() {
        super.onStart()
        if (!overlayHeld) {
            overlayHeld = true
            Overlay.fullscreenShown(true)
        }
        if (isInMultiWindowMode) back()
    }

    override fun onStop() {
        if (overlayHeld) {
            overlayHeld = false
            Overlay.fullscreenShown(false)
        }
        super.onStop()
        // Not when the screen just went off (the page is still where the user left it) or on a rotation.
        val screenOn = runCatching { getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        if (closeWhenHidden && !isChangingConfigurations && screenOn) back()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) back()
    }

    /** 2.0.0-beta8 (G.3): suppress close transition when returning from full-screen task to floating overlay. */
    protected fun suppressCloseTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Leave this page for the floating window: restore floating window first, then finish task with zero animation. */
    fun back() {
        if (!isFinishing && !isDestroyed) {
            if (overlayHeld) {
                overlayHeld = false
                Overlay.fullscreenShown(false)
                Overlay.showNowIfEligible()
            }
            finishAndRemoveTask()
            suppressCloseTransition()
        }
    }
}
