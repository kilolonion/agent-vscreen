package com.kilon.agentvscreen.core

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.File

/**
 * 1.4.2-beta3 [root way in]: the screen service's own process when the phone is rooted and the user picked root
 * instead of Shizuku. It is the same [VScreenDisplayService] that Shizuku hosts, so everything after the start (screens,
 * input, tasks, sound, commands through EXEC) is one code path for both ways in.
 *
 * Started by [com.kilon.agentvscreen.host.RootBackend] through `su`, as the shell user (`su 2000`) when the root
 * manager supports it, so the service has exactly the identity it has under Shizuku in ADB mode (Android 13 and older
 * refuse a virtual display to uid 0, which has no package); as root itself otherwise.
 *
 *   CLASSPATH=<apk> app_process /system/bin --nice-name=agent-vscreen:root com.kilon.agentvscreen.core.RootMain
 *       <package> <versionCode> <apk path> <android user>
 *
 * It hands its binder to the app through the app's [com.kilon.agentvscreen.host.RootLink] provider, which accepts it
 * from the shell or root only. It stays up when the app process dies (as the Shizuku daemon does), so the next app
 * process adopts its screens: that process asks for the binder again by writing [REQ] (watched here). It exits when
 * the app sends [VScreenDisplayService.DESTROY_CODE] (user switched to Shizuku, newer build) or the app is uninstalled.
 */
class RootMain {
    companion object {
        const val DIR = "/data/local/tmp"
        /** "<pid> <versionCode> <uid>", written at start: the launcher adopts this process when the version matches. */
        const val PID = "$DIR/agent-vscreen-root.pid"
        /** Any write here makes the service hand its binder to the app again (a new app process). */
        const val REQ = "$DIR/agent-vscreen-root.req"
        const val NICE_NAME = "agent-vscreen:root"
        const val METHOD = "binder"
        const val KEY_BINDER = "binder"
        const val KEY_UID = "uid"
        const val KEY_PROTOCOL = "protocol"

        @Volatile private var keep: Any? = null

        @JvmStatic
        fun main(args: Array<String>) {
            val pkg = args.getOrNull(0) ?: return fail("missing package")
            val version = args.getOrNull(1) ?: "0"
            val apk = args.getOrNull(2).orEmpty()
            val user = args.getOrNull(3)?.toIntOrNull() ?: 0
            runCatching { if (Looper.getMainLooper() == null) Looper.prepareMainLooper() }
            // The system context is made here, on the main thread (ActivityThread needs a looper), as Shizuku's own
            // service starter does; the service builds its DisplayManager on it.
            val ctx = runCatching { systemContext() }.onFailure { log("system context: ${it.message}") }.getOrNull()
            val service = (if (ctx != null) VScreenDisplayService(ctx) else VScreenDisplayService()).apply {
                targetUserId = user
                ownSourceDirHint = apk.takeIf { it.isNotBlank() }
            }
            runCatching {
                File(PID).writeText("${android.os.Process.myPid()} $version ${android.os.Process.myUid()}\n")
                File(PID).setReadable(true, false)
            }.onFailure { log("pid file: ${it.message}") }
            val send = { reason: String ->
                Thread({
                    val r = runCatching { deliver(pkg, user, service) }
                    log("binder to $pkg ($reason): " + (r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok"))
                }, "vscreen-root-send").apply { isDaemon = true }.start()
            }
            // A new app process asks by writing REQ (the launcher does it when it finds this service running).
            val watcher = object : android.os.FileObserver(File(DIR), CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) { if (path == File(REQ).name) send("asked") }
            }
            watcher.startWatching()
            keep = watcher
            send("start")
            // Uninstalled: nobody will ever send DESTROY, so give everything back and go.
            LoopWatchdog.process.startLoop("vscreen-root-watch", critical = true) { ctx ->
                while (ctx.active) {
                    Thread.sleep(30_000)
                    ctx.beat()
                    if (apk.isNotEmpty() && !File(apk).exists() && !installed(pkg)) {
                        log("$pkg is gone: stopping")
                        ctx.stopCleanly()
                        val d = Parcel.obtain(); val r = Parcel.obtain()
                        runCatching { service.transact(VScreenDisplayService.DESTROY_CODE, d, r, 0) }
                        System.exit(0)
                    }
                }
            }
            log("up: uid=${android.os.Process.myUid()} sdk=${Build.VERSION.SDK_INT} pkg=$pkg v$version")
            Looper.loop()
        }

        private fun systemContext(): android.content.Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            return at.getMethod("getSystemContext").invoke(thread) as android.content.Context
        }

        private fun installed(pkg: String): Boolean = runCatching {
            val p = ProcessBuilder("cmd", "package", "path", pkg).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("package:")
        }.getOrDefault(true)

        private fun fail(msg: String) { log(msg); System.exit(1) }

        private fun log(msg: String) = System.err.println("agent-vscreen-root: $msg")

        /**
         * Hands [service] to the app: a call on its RootLink provider, the way Shizuku's own server hands its binder to
         * apps (the system's external provider access, which the shell and root hold). The call's signature changed in
         * Android 11 and 12; both are covered by [callArgs].
         */
        internal fun deliver(pkg: String, user: Int, service: IBinder) {
            val am = activityManager()
            val authority = "$pkg.root"
            val token = Binder()
            val get = am.javaClass.methods.first { it.name == "getContentProviderExternal" }
            val holder = when (get.parameterTypes.size) {
                4 -> get.invoke(am, authority, user, token, "agent-vscreen")
                else -> get.invoke(am, authority, user, token)
            } ?: error("provider $authority not found (app not installed for user $user?)")
            try {
                val provider = holder.javaClass.getField("provider").get(holder) ?: error("provider $authority has no binder")
                val extras = Bundle().apply {
                    putBinder(KEY_BINDER, service)
                    putInt(KEY_UID, android.os.Process.myUid())
                    putInt(KEY_PROTOCOL, VScreenDisplayService.PROTOCOL)
                }
                val call = provider.javaClass.methods.filter { it.name == "call" }
                    .firstNotNullOfOrNull { m -> callArgs(m.parameterTypes, authority, extras)?.let { m to it } }
                    ?: error("no known IContentProvider.call signature")
                call.first.invoke(provider, *call.second)
            } finally {
                runCatching {
                    val rm = am.javaClass.methods.firstOrNull { it.name == "removeContentProviderExternalAsUser" }
                    if (rm != null) rm.invoke(am, authority, token, user)
                    else am.javaClass.methods.first { it.name == "removeContentProviderExternal" }.invoke(am, authority, token)
                }
            }
        }

        /** Arguments for one IContentProvider.call overload, or null when it is not one we know. */
        internal fun callArgs(types: Array<Class<*>>, authority: String, extras: Bundle): Array<Any?>? {
            val uid = android.os.Process.myUid()
            val caller = if (uid == 0) "root" else "com.android.shell"
            val s = String::class.java
            val b = Bundle::class.java
            if (types.size >= 5 && types[0].name == "android.content.AttributionSource") {
                val bundleIdx = types.indexOfFirst { it == b }
                // Android 12+ standard: call(AttributionSource, authority, method, arg, extras[, ...optional trailing])
                if (bundleIdx == 4 && types[1] == s && types[2] == s && types[3] == s) {
                    return Array<Any?>(types.size) { i ->
                        when (i) {
                            0 -> attribution(if (uid == 0) null else caller)
                            1 -> authority
                            2 -> METHOD
                            3 -> null
                            4 -> extras
                            else -> defaultArg(types[i])
                        }
                    }
                }
                // Forward-compatible: call(AttributionSource, feature/tag, authority, method, arg, extras[, ...])
                if (bundleIdx == 5 && types[1] == s && types[2] == s && types[3] == s && types[4] == s) {
                    return Array<Any?>(types.size) { i ->
                        when (i) {
                            0 -> attribution(if (uid == 0) null else caller)
                            1 -> null
                            2 -> authority
                            3 -> METHOD
                            4 -> null
                            5 -> extras
                            else -> defaultArg(types[i])
                        }
                    }
                }
            }
            return when {
                // Android 11: call(callingPkg, featureId, authority, method, arg, extras)
                types.size == 6 && types.take(5).all { it == s } && types[5] == b ->
                    arrayOf(caller, null, authority, METHOD, null, extras)
                // Android 10: call(callingPkg, authority, method, arg, extras)
                types.size == 5 && types.take(4).all { it == s } && types[4] == b ->
                    arrayOf(caller, authority, METHOD, null, extras)
                else -> null
            }
        }

        private fun defaultArg(t: Class<*>): Any? = when (t) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }

        private fun attribution(pkg: String?): Any {
            val b = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(android.os.Process.myUid())
            if (pkg != null) {
                b.javaClass.getMethod("setPackageName", String::class.java).invoke(b, pkg)
            }
            return b.javaClass.getMethod("build").invoke(b)!!
        }

        private fun activityManager(): Any {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity") as IBinder
            return Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
        }
    }
}
