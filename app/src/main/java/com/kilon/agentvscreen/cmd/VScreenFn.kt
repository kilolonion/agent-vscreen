package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.AppFunctions
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.Guard
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.host.Privileged
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * `fn [<app>]` — read-only discovery of Android 16+ AppFunctions exposed by installed apps.
 *
 * Enforces [BackgroundOperation.requireNotOwnPackage] and [Guard.require] (`Act.READ`) so blocked apps
 * and Agent-VScreen itself are never inspected. All third-party function metadata is marked `untrusted: true`
 * and sanitized via [ScreenText.clean].
 */
internal fun VScreenOffloadHandler.fnCmd(@Suppress("UNUSED_PARAMETER") sid: String?, args: OffloadArgs): NativeOffloadResult {
    val query = args.positional.joinToString(" ").trim()
    val ownPkg = context.packageName

    if (query.isEmpty()) {
        return when (val res = AppFunctions.listAll(context)) {
            is AppFunctions.QueryResult.Unsupported -> throw ActionError(
                "FN_UNSUPPORTED",
                tr(
                    "当前系统不支持 AppFunctions 探测（需要 Android 16+ 且系统开放 app_function 服务）",
                    "AppFunctions discovery is not supported on this device (requires Android 16+ with the app_function shell service)",
                ),
            )
            is AppFunctions.QueryResult.NotConnected -> throw ActionError(
                "NOT_CONNECTED",
                tr("需要 ${Privileged.name()} 已连接", "${Privileged.name()} must be connected"),
            )
            is AppFunctions.QueryResult.Ok -> {
                val visible = res.items.filter { fn ->
                    fn.pkg != ownPkg && Guard.check(Guard.Act.READ, fn.pkg) !is Guard.Verdict.Deny
                }
                val byPkg = visible.groupBy { it.pkg }
                val appsArr = JSONArray()
                for ((pkg, list) in byPkg.entries.sortedBy { it.key }) {
                    val label = VScreenKnowledge.label(pkg) ?: pkg
                    appsArr.put(
                        JSONObject()
                            .put("package", pkg)
                            .put("name", ScreenText.clean(label, maxLen = 64))
                            .put("count", list.size)
                            .put("functions", JSONArray(list.map { it.json() })),
                    )
                }
                ok(
                    args,
                    JSONObject()
                        .put("supported", true)
                        .put("untrusted", true)
                        .put("apps", appsArr),
                )
            }
        }
    }

    val pkg = VScreenKnowledge.resolveApp(query)
        ?: throw ActionError("APP_NOT_FOUND", tr("没找到“$query”：apps $query 看看已安装的应用", "\"$query\" not found: apps $query lists installed apps"))
    requireNotOwnPackage(pkg)
    Guard.require(Guard.Act.READ, pkg)

    return when (val res = AppFunctions.listFor(context, pkg)) {
        is AppFunctions.QueryResult.Unsupported -> throw ActionError(
            "FN_UNSUPPORTED",
            tr(
                "当前系统不支持 AppFunctions 探测（需要 Android 16+ 且系统开放 app_function 服务）",
                "AppFunctions discovery is not supported on this device (requires Android 16+ with the app_function shell service)",
            ),
        )
        is AppFunctions.QueryResult.NotConnected -> throw ActionError(
            "NOT_CONNECTED",
            tr("需要 ${Privileged.name()} 已连接", "${Privileged.name()} must be connected"),
        )
        is AppFunctions.QueryResult.Ok -> {
            if (res.items.isEmpty()) {
                throw ActionError(
                    "FN_NOT_FOUND",
                    tr("应用 $pkg 未声明可用的 AppFunctions", "App $pkg does not expose any AppFunctions"),
                )
            }
            val label = VScreenKnowledge.label(pkg) ?: pkg
            ok(
                args,
                JSONObject()
                    .put("supported", true)
                    .put("untrusted", true)
                    .put("package", pkg)
                    .put("name", ScreenText.clean(label, maxLen = 64))
                    .put("functions", JSONArray(res.items.map { it.json() })),
            )
        }
    }
}
