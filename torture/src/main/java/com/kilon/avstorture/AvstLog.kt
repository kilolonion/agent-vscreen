package com.kilon.avstorture

import android.util.Log
import org.json.JSONObject

object AvstLog {
    const val TAG = "AVST"

    fun emit(scene: String, ev: String, ok: Boolean? = null, data: JSONObject? = null) {
        val o = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("scene", scene)
            .put("ev", ev)
        if (ok != null) o.put("ok", ok)
        if (data != null) o.put("data", data)
        Log.i(TAG, o.toString())
    }

    fun touch(scene: String, action: String, x: Int, y: Int) {
        emit(
            scene = scene,
            ev = "touch",
            data = JSONObject().put("a", action).put("x", x).put("y", y),
        )
    }
}
