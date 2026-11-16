package com.yg.appkiller

import android.content.Context
import android.util.Base64
import java.util.concurrent.TimeUnit

/** root 通道：KernelSU / Magisk 的 su。只在需要时调用，探测结果缓存 */
object Shell {
    @Volatile var lastError: String? = null

    @Volatile private var probed = false
    @Volatile private var rootOk = false

    fun sh(cmd: String, timeoutMs: Long = 8000): String {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                lastError = "timeout"
                return ""
            }
            if (p.exitValue() != 0) lastError = out.trim().take(200) else lastError = null
            out
        } catch (e: Exception) {
            lastError = e.message
            ""
        }
    }

    fun run(cmd: String, timeoutMs: Long = 8000): Boolean = sh(cmd, timeoutMs).let { lastError == null }

    /** 是否拿到 root（首次会触发 KernelSU 授权弹窗，结果缓存） */
    @Synchronized
    fun rootOk(): Boolean {
        if (!probed) {
            probed = true
            rootOk = sh("id -u", 15000).trim() == "0"
        }
        return rootOk
    }

    fun resetProbe() {
        probed = false
    }
}

object ModuleRepo {
    const val MODDIR = "/data/adb/modules/appkiller"

    private val FILE_NAMES = listOf("run/state.json", "config.conf", "protect.list", "blacklist.list", "system_apps.list")

    /** 一次 su 读完状态 + 配置 + 三份名单，避免多次 fork su */
    fun readBundle(): Map<String, String> {
        if (!Shell.rootOk()) return emptyMap()
        val sb = StringBuilder()
        sb.append("cd ").append(MODDIR).append(" 2>/dev/null || { echo '===MISSING==='; exit 0; }; ")
        for (n in FILE_NAMES) {
            sb.append("echo '===").append(n).append("==='; cat '").append(n).append("' 2>/dev/null; ")
        }
        val raw = Shell.sh(sb.toString(), 12000)
        if (raw.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        var cur: String? = null
        val acc = StringBuilder()
        for (line in raw.lines()) {
            if (line.startsWith("===") && line.endsWith("===") && line.length > 6) {
                if (cur != null) map[cur] = acc.toString()
                cur = line.substring(3, line.length - 3)
                acc.setLength(0)
            } else if (cur != null) {
                acc.append(line).append('\n')
            }
        }
        if (cur != null) map[cur] = acc.toString()
        return map
    }

    fun running(): Boolean {
        if (Shell.rootOk()) {
            val out = Shell.sh("kill -0 \$(cat $MODDIR/run/appkillerd.pid 2>/dev/null) 2>/dev/null && echo ok", 4000)
            return out.contains("ok")
        }
        return Ctrl.ping()
    }

    fun pid(): Int {
        val b = readBundle()
        return b["run/state.json"]?.let {
            try { org.json.JSONObject(it).optInt("pid", -1) } catch (e: Exception) { -1 }
        } ?: -1
    }

    fun statusRaw(): String? {
        if (Shell.rootOk()) return readBundle()["run/state.json"]
        return Ctrl.call("STATUS")
    }

    fun configMap(): Map<String, String> {
        val raw = if (Shell.rootOk()) readBundle()["config.conf"] else Ctrl.call("CFG")
        if (raw.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (line in raw.lines()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue
            val i = s.indexOf('=')
            map[s.substring(0, i).trim()] = s.substring(i + 1).trim().trim('"')
        }
        return map
    }

    /** 批量写配置：替换已有键并保留注释，一次 su 完成 */
    fun setConfigs(pairs: Map<String, String>) {
        if (Shell.rootOk()) {
            val lines = (readBundle()["config.conf"] ?: "").lines().toMutableList()
            val pending = LinkedHashMap(pairs)
            for (i in lines.indices) {
                val s = lines[i].trim()
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue
                val k = s.substringBefore('=').trim()
                if (pending.containsKey(k)) {
                    lines[i] = k + "=" + pending.remove(k)
                }
            }
            pending.forEach { (k, v) -> lines.add(k + "=" + v) }
            writeFileRaw("config.conf", lines.joinToString("\n") + "\n")
            reload()
        } else {
            for ((k, v) in pairs) Ctrl.call("SETKV " + k + " " + v)
            Ctrl.call("RELOAD")
        }
    }

    fun list(name: String): List<String> {
        val raw = if (Shell.rootOk()) readBundle()[name] else Ctrl.call("LIST " + ctrlName(name))
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBefore(" #").trim() }
            .filter { it.isNotEmpty() }
    }

    fun writeList(name: String, header: String, items: Collection<String>) {
        if (Shell.rootOk()) {
            val sb = StringBuilder(header).append('\n')
            items.sorted().forEach { sb.append(it).append('\n') }
            writeFileRaw(name, sb.toString())
            reload()
        } else {
            Ctrl.call("SETLIST " + ctrlName(name) + " " + items.sorted().joinToString(","))
        }
    }

    /** 用 base64 传输文件内容，绕开所有 shell 转义问题 */
    fun writeFileRaw(rel: String, content: String) {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Shell.sh("printf '%s' '" + b64 + "' | base64 -d > " + MODDIR + "/" + rel, 12000)
    }

    fun reload(): Boolean {
        if (Shell.rootOk()) {
            Shell.sh("kill -HUP \$(cat $MODDIR/run/appkillerd.pid 2>/dev/null) 2>/dev/null", 4000)
            return true
        }
        return Ctrl.call("RELOAD") != null
    }

    fun logTail(n: Int = 80): String {
        if (Shell.rootOk()) return Shell.sh("tail -n " + n + " " + MODDIR + "/logs/daemon.log 2>/dev/null", 8000)
        return Ctrl.call("LOG " + n, 5000) ?: ""
    }

    fun startDaemon(ctx: Context?) {
        if (Shell.rootOk()) {
            Shell.sh("mkdir -p " + MODDIR + "/logs " + MODDIR + "/run; nohup " + MODDIR + "/bin/appkillerd daemon -m " + MODDIR + " >> " + MODDIR + "/logs/stdout.log 2>&1 &", 5000)
        }
    }

    fun stopDaemon() {
        if (Shell.rootOk()) {
            Shell.sh("kill \$(cat " + MODDIR + "/run/appkillerd.pid 2>/dev/null) 2>/dev/null", 5000)
        }
    }

    private fun ctrlName(file: String): String = when (file) {
        "protect.list" -> "white"
        "blacklist.list" -> "black"
        "system_apps.list" -> "system"
        else -> file
    }
}

data class FrozenApp(
    val pkg: String,
    val frozenSec: Long,
    val remainSec: Long,
    val frozenProcs: Int,
    val totalProcs: Int,
    val killedTimes: Int,
    val managed: Boolean,
)

object StateRepo {
    fun parse(raw: String?): StatusSnapshot {
        if (raw.isNullOrBlank()) return StatusSnapshot()
        return try {
            val root = org.json.JSONObject(raw)
            val uptime = root.optLong("uptime_sec")
            val pid = root.optInt("pid", -1)
            val arr = root.optJSONArray("watching")
            val list = ArrayList<FrozenApp>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        FrozenApp(
                            pkg = o.optString("pkg"),
                            frozenSec = o.optLong("frozen_sec"),
                            remainSec = o.optLong("remain_sec"),
                            frozenProcs = o.optInt("frozen_procs"),
                            totalProcs = o.optInt("total_procs"),
                            killedTimes = o.optInt("killed_times"),
                            managed = !o.optBoolean("protected", false),
                        )
                    )
                }
            }
            StatusSnapshot(uptime, list.sortedByDescending { it.frozenSec }, pid)
        } catch (e: Exception) {
            StatusSnapshot()
        }
    }

    fun snapshot(): StatusSnapshot = parse(ModuleRepo.statusRaw())
}

data class AppEntry(
    val pkg: String,
    val label: String,
    val system: Boolean,
    /** android:persistent：被杀死后系统会立刻拉起，清理它没有意义 */
    val persistent: Boolean = false,
)

object AppRepo {
    fun installed(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()

        // HyperOS 4 上即使声明了 QUERY_ALL_PACKAGES，PackageManager 也只返回本应用，
        // 所以有 root 时以 `pm list packages` 的全量输出为准，标签再逐个补。
        if (Shell.rootOk()) {
            for (line in Shell.sh("pm list packages 2>/dev/null").lineSequence()) {
                val pkg = line.removePrefix("package:").trim()
                if (pkg.isEmpty() || !seen.add(pkg)) continue
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val label = ai?.let { a ->
                    runCatching { pm.getApplicationLabel(a).toString() }.getOrNull()
                } ?: pkg
                val flags = ai?.flags ?: 0
                val path = ai?.sourceDir ?: ""
                val sys = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (path.isNotEmpty() && !path.startsWith("/data/"))
                val persist = (flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0
                out.add(AppEntry(pkg, label, sys, persist))
            }
        }
        if (out.isNotEmpty()) {
            return out.sortedWith(compareBy({ !it.system }, { it.label }))
        }

        for (ai in pm.getInstalledApplications(0)) {
            if (!seen.add(ai.packageName)) continue
            // HyperOS 的预装应用分散在 /system /product /my_product 等分区，
            // 单看 FLAG_SYSTEM 会漏；凡是安装路径不在 /data/app 下的一律算预装。
            val path = ai.sourceDir ?: ""
            val sys = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                !path.startsWith("/data/")
            val persist = (ai.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0
            out.add(AppEntry(ai.packageName, pm.getApplicationLabel(ai).toString(), sys, persist))
        }
        return out.sortedWith(compareBy({ !it.system }, { it.label }))
    }

    fun labelMap(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val m = HashMap<String, String>()
        for (ai in pm.getInstalledApplications(0)) {
            m[ai.packageName] = pm.getApplicationLabel(ai).toString()
        }
        return m
    }

    /** 把系统应用清单同步给 daemon（daemon 只读这个文件判定「系统应用默认豁免」） */
    fun syncSystemList(ctx: Context) {
        val apps = installed(ctx)
        ModuleRepo.writeList(
            "system_apps.list",
            "# 由 App 自动生成：系统/预装应用清单",
            apps.filter { it.system }.map { it.pkg },
        )
        // persistent 应用单独一份：它们杀不掉，清理只会白白浪费一轮扫描
        ModuleRepo.writeList(
            "immortal.list",
            "# 由 App 自动生成：persistent 应用（系统会自拉起，不做清理）",
            apps.filter { it.persistent }.map { it.pkg },
        )
        // 让 daemon 立刻重载清单（控制通道，不需要 root）
        Ctrl.call("RELOAD", 1500)
    }
}
