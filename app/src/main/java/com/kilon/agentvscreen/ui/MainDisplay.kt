package com.kilon.agentvscreen.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Display

/**
 * 1.4.0-beta6 (test: 去示范 opened the teach page ON the background screen, where the user could not see it, and the
 * floating window hid itself because the page was "open"). A page started from a service, a notification or the
 * floating window goes to the display that has focus, and while the user or the agent works the background screen
 * that is the background screen. Every page of this app is for the person holding the phone: it always opens on the
 * phone's own display.
 */
object MainDisplay {
    fun options(): Bundle = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()

    /**
     * 1.4.0-beta11 (rainbow: after Done the review page was pushed away to the floating window). Every page is its own
     * task, always: started from the full screen page (an Activity) without NEW_TASK, the review page landed INSIDE
     * the full screen page's task, and that page ends its whole task when it is covered (closeWhenHidden) — taking
     * the review page with it. Each page's taskAffinity only applies with NEW_TASK.
     */
    fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent, options()) }.onFailure { runCatching { ctx.startActivity(intent) } }
    }

    /** Call first in onCreate: true when the page came up on another display and was moved (the caller returns). */
    fun moved(a: Activity): Boolean {
        val id = runCatching { a.display?.displayId }.getOrNull()
            ?: return false
        if (id == Display.DEFAULT_DISPLAY) return false
        runCatching { a.startActivity(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options()) }
        a.finish()
        return true
    }
}
