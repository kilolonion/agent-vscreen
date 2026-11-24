package com.kilon.avstorture

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import org.json.JSONObject

/**
 * 2.0.0-beta8 (C2): lightweight secondary process (`:worker`) under the same UID as AVSTorture,
 * used to verify that UID-wide process enumeration and freeze verification cover every process of an app.
 */
class WorkerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % 4 == 1) {
                AvstLog.emit(
                    scene = "S14",
                    ev = "worker_tick",
                    ok = true,
                    data = JSONObject().put("pid", Process.myPid()).put("ticks", ticks),
                )
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
