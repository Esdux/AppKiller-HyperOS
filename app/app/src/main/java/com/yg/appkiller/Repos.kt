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

/**
 * A0 配置解析结果。四段按「条目粒度」拆开，而不是按关键字拆：
 *   apps / procs           KILL 的整包与子进程
 *   white / whiteProcs     WHITE 的整包与子进程
 * 这样落盘时每段对应一个明确的去处，不需要再问用户「白名单怎么存」。
 */
data class A0Config(
    val apps: List<String>,
    val procs: List<String>,
    val white: List<String>,
    val whiteProcs: List<String>,
) {
    val isEmpty: Boolean get() = apps.isEmpty() && procs.isEmpty() && white.isEmpty() && whiteProcs.isEmpty()
}

object ModuleRepo {
    const val MODDIR = "/data/adb/modules/appkiller"

    private val FILE_NAMES = listOf("run/state.json", "config.conf", "protect.list", "blacklist.list", "system_apps.list")

    /** bundle 短缓存：同一轮界面刷新里 running/configMap/list 多次调用只 fork 一次 su */
    private const val BUNDLE_TTL_MS = 2000L
    @Volatile private var bundleCache: Pair<Long, Map<String, String>>? = null

    fun invalidateBundle() { bundleCache = null }

    /** 一次 su 读完状态 + 配置 + 三份名单，避免多次 fork su */
    fun readBundle(): Map<String, String> {
        if (!Shell.rootOk()) return emptyMap()
        val c = bundleCache
        if (c != null && System.currentTimeMillis() - c.first < BUNDLE_TTL_MS) return c.second
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
        bundleCache = System.currentTimeMillis() to map
        return map
    }

    /** 存活判定优先走本地控制端口：毫秒级，且不 fork su */
    fun running(): Boolean {
        if (Ctrl.ping()) return true
        if (!Shell.rootOk()) return false
        val out = Shell.sh("kill -0 \$(cat $MODDIR/run/appkillerd.pid 2>/dev/null) 2>/dev/null && echo ok", 4000)
        return out.contains("ok")
    }

    fun pid(): Int {
        val b = readBundle()
        return b["run/state.json"]?.let {
            try { org.json.JSONObject(it).optInt("pid", -1) } catch (e: Exception) { -1 }
        } ?: -1
    }

    /** 状态快照优先走本地控制端口（daemon 内存直出），root 文件读取只作兜底 */
    fun statusRaw(): String? {
        Ctrl.call("STATUS", 1200)?.let { if (it.contains("\"watching\"")) return it }
        if (Shell.rootOk()) return readBundle()["run/state.json"]
        return null
    }

    fun configMap(): Map<String, String> {
        val raw = Ctrl.call("CFG", 1200) ?: if (Shell.rootOk()) readBundle()["config.conf"] else null
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
        val raw = Ctrl.call("LIST " + ctrlName(name), 1200)
            ?: if (Shell.rootOk()) readBundle()[name] else null
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

    /**
     * 直接读模块目录下的某个文本文件（逐行，已去注释与空行）。
     *
     * 为什么不复用 [list]：它走 Ctrl 的 LIST 命令或 readBundle 的白名单文件表，
     * 而 perapp.conf / perproc.conf 不在那两份名单里 —— 拿它读这两个文件会静默返回空表，
     * 于是「合并导入」变成「整份重写成只剩表头」，把用户原有配置抹掉。
     */
    fun readFileRaw(rel: String): List<String> {
        if (!Shell.rootOk()) return emptyList()
        val raw = Shell.sh("cat " + MODDIR + "/" + rel + " 2>/dev/null", 8000)
        if (raw.isBlank()) return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** 用 base64 传输文件内容，绕开所有 shell 转义问题 */
    fun writeFileRaw(rel: String, content: String) {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Shell.sh("printf '%s' '" + b64 + "' | base64 -d > " + MODDIR + "/" + rel, 12000)
        invalidateBundle()
    }

    /** 备份用：一次 su 读全部配置与名单文件 */
    fun exportAll(): Map<String, String> {
        if (!Shell.rootOk()) return emptyMap()
        val names = listOf("config.conf", "protect.list", "blacklist.list", "system_apps.list", "immortal.list")
        val sb = StringBuilder("cd ").append(MODDIR).append(" 2>/dev/null || { echo '===MISSING==='; exit 0; }; ")
        for (n in names) sb.append("echo '===").append(n).append("==='; cat '").append(n).append("' 2>/dev/null; ")
        val raw = Shell.sh(sb.toString(), 12000)
        if (raw.isBlank() || raw.contains("===MISSING===")) return emptyMap()
        val map = LinkedHashMap<String, String>()
        var cur: String? = null
        val acc = StringBuilder()
        for (line in raw.lines()) {
            if (line.startsWith("===") && line.endsWith("===") && line.length > 6) {
                if (cur != null) map[cur] = acc.toString()
                cur = line.substring(3, line.length - 3)
                acc.setLength(0)
            } else if (cur != null) acc.append(line).append('\n')
        }
        if (cur != null) map[cur] = acc.toString()
        return map
    }

    /** 备份文本格式：与 exportAll 相同的 ===文件名=== 分节 */
    fun toBackupText(map: Map<String, String>): String {
        val sb = StringBuilder("# AppKiller 备份 v1\n")
        for ((k, v) in map) sb.append("===").append(k).append("===\n").append(v)
        return sb.toString()
    }

    fun parseBackupText(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var cur: String? = null
        val acc = StringBuilder()
        for (line in text.lines()) {
            if (line.startsWith("===") && line.endsWith("===") && line.length > 6) {
                if (cur != null) map[cur] = acc.toString()
                cur = line.substring(3, line.length - 3)
                acc.setLength(0)
            } else if (cur != null) acc.append(line).append('\n')
        }
        if (cur != null) map[cur] = acc.toString()
        return map
    }

    /** 还原备份：逐文件写回并重载；返回写入的文件数 */
    fun importAll(text: String): Int {
        val map = parseBackupText(text)
        if (map.isEmpty() || !Shell.rootOk()) return 0
        var n = 0
        for ((k, v) in map) {
            if (k !in listOf("config.conf", "protect.list", "blacklist.list", "system_apps.list", "immortal.list")) continue
            writeFileRaw(k, v)
            n++
        }
        if (n > 0) reload()
        return n
    }

    /**
     * 热刷入模块 zip：解压到模块目录、覆盖程序本体，保留用户名单与配置。
     *
     * 为什么不走 Magisk/KernelSU 的安装流程：那要重启，而模块只有「二进制 + APK +
     * 几个脚本」三类文件，替换完重启守护进程就生效了 —— 重启整机纯属浪费。
     *
     * 四条必须遵守的顺序：
     * 1. **先停守护进程**。它是被替换的那个文件，运行中替换会让它拿着旧 inode 继续跑，
     *    看起来刷了新版其实没换（旧进程还占着端口，App 连上的是旧逻辑）。
     * 2. **解压时排除用户数据**。config.conf / protect.list / blacklist.list /
     *    system_apps.list / immortal.list / perapp.conf / perproc.conf 一律不覆盖，
     *    否则一次热刷入就把用户辛苦配的名单清空。
     * 3. **解压完补回可执行位**。zip 里存的是 Unix 权限，但 unzip 落盘时会被 umask 削掉，
     *    bin/appkillerd 不可执行就等于刷完起不来。
     * 4. **临时目录不能放在 /data/adb/modules 下**。那里每个带 module.prop 的子目录都会被
     *    Magisk/KernelSU 当成一个模块；曾用 appkiller.hot，结果管理器里出现两个 id 都是
     *    appkiller 的模块，它的列表 LazyColumn 直接因为重复 key 崩溃。改成放 App 自己的
     *    cacheDir（root 可读，且不在模块扫描范围）；启用守护时还会再扫一遍清残留。
     *
     * 另外 pm install -r 会**杀掉 App 自己的进程**，所以它必须放在最后、并脱离本进程在
     * 后台跑；否则装 APK 之后的清临时目录、起守护进程全都不执行，留下垃圾目录和没起的 daemon。
     *
     * 返回 (成功?, 说明文字)。
     */
    fun hotInstall(zipPath: String, cacheDir: String): Pair<Boolean, String> {
        if (!Shell.rootOk()) return false to "没有 root 权限"
        val tmp = cacheDir + "/hot"
        // 保留用户数据的文件清单：解压时逐个跳过
        val keep = listOf("config.conf", "protect.list", "blacklist.list", "system_apps.list",
                          "immortal.list", "perapp.conf", "perproc.conf")
        val keepArgs = keep.joinToString(" ") { "-x '$it'" }
        val cmd = StringBuilder()
        cmd.append("set -e; ")
        cmd.append("rm -rf ").append(tmp).append("; mkdir -p ").append(tmp).append("; ")
        cmd.append("unzip -o -q '").append(zipPath).append("' -d ").append(tmp).append(" ").append(keepArgs).append("; ")
        // 校验：必须有这两个文件，否则不是本模块的 zip
        cmd.append("[ -f ").append(tmp).append("/bin/appkillerd ] || { echo 'ERR no daemon'; rm -rf ").append(tmp).append("; exit 1; }; ")
        cmd.append("[ -f ").append(tmp).append("/module.prop ] || { echo 'ERR no prop'; rm -rf ").append(tmp).append("; exit 1; }; ")
        // 停守护进程（它是被替换的文件）
        cmd.append("kill \$(cat ").append(MODDIR).append("/run/appkillerd.pid 2>/dev/null) 2>/dev/null || true; sleep 1; ")
        // 覆盖：bin/ 整目录替换，其余文件逐个拷（不碰保留清单）
        cmd.append("rm -rf ").append(MODDIR).append("/bin; cp -a ").append(tmp).append("/bin ").append(MODDIR).append("/; ")
        cmd.append("for f in module.prop service.sh customize.sh uninstall.sh; do ")
        cmd.append("[ -f ").append(tmp).append("/\$f ] && cp -f ").append(tmp).append("/\$f ").append(MODDIR).append("/\$f; done; ")
        cmd.append("chmod 755 ").append(MODDIR).append("/bin/appkillerd ").append(MODDIR).append("/service.sh ").append(MODDIR).append("/customize.sh ").append(MODDIR).append("/uninstall.sh 2>/dev/null || true; ")
        // 装新 APK（-g 保留权限；失败不影响 daemon）。
        // pm install -r 会杀掉本 App 自己的进程，所以这之后的清临时目录、起 daemon 可能执行不到，
        // 属于预期：装完重新打开 App，在设置页手动点「启动守护进程」即可。
        cmd.append("pm install -r -g ").append(MODDIR).append("/bin/appkiller.apk 2>&1 | tail -1; ")
        cmd.append("rm -rf ").append(tmp).append("; ")
        // 起新 daemon
        cmd.append("mkdir -p ").append(MODDIR).append("/logs ").append(MODDIR).append("/run; ")
        cmd.append("nohup ").append(MODDIR).append("/bin/appkillerd daemon -m ").append(MODDIR).append(" >> ").append(MODDIR).append("/logs/stdout.log 2>&1 & ")
        cmd.append("sleep 2; ")
        cmd.append("echo \"PID=\$(cat ").append(MODDIR).append("/run/appkillerd.pid 2>/dev/null)\"")
        val out = Shell.sh(cmd.toString(), 120000)
        invalidateBundle()
        val err = out.lineSequence().firstOrNull { it.startsWith("ERR ") }
        if (err != null) return false to err.removePrefix("ERR ")
        val pid = Regex("PID=(\\d+)").find(out)?.groupValues?.get(1) ?: ""
        return if (pid.isNotEmpty()) true to "已热刷入，守护进程 pid " + pid else false to "刷入后守护进程没起来"
    }

    /** 直接读设备上的 A0 配置；未安装 / 无配置返回 null。位置固定：/sdcard/Android/A0_Memory/config.conf */
    fun readA0Config(): String? {
        if (!Shell.rootOk()) return null
        val raw = Shell.sh("cat /sdcard/Android/A0_Memory/config.conf 2>/dev/null", 10000)
        return raw.ifBlank { null }
    }

    /**
     * 解析 A0 配置里的乖巧名单（KILL）与白名单（WHITE）。配置文本来自默认路径或用户选中的文件。
     *
     * 格式是「行首关键字 + 空格 + 条目」，`#` 开头是注释。**条目带不带冒号决定落地方式**：
     *   KILL  com.tencent.mm          → 整包清理（per-app force_stop）
     *   KILL  com.tencent.mm:tools    → 只压那个子进程（per-proc force_stop）
     *   WHITE com.miui.securitycenter → 整包白名单（protect.list）
     *   WHITE com.tencent.mm:push     → 只把那个子进程设为跳过不清理（per-proc skip）
     *
     * 冒号在这里是「粒度」标记：不带 = 针对整个包，带 = 只针对冒号后面那个进程。
     * 所以 WHITE 的两种形态不是二选一，而是各自对应不同的作用范围，不需要用户再选。
     */
    fun parseA0Config(text: String): A0Config {
        val apps = LinkedHashSet<String>()
        val procs = LinkedHashSet<String>()
        val white = LinkedHashSet<String>()
        val whiteProcs = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val key = line.substring(0, sp).uppercase()
            val val0 = line.substring(sp + 1).trim()
            if (val0.isEmpty()) continue
            val hasProc = val0.contains(':')
            when (key) {
                "KILL" -> if (hasProc) procs.add(val0) else apps.add(val0)
                "WHITE" -> if (hasProc) whiteProcs.add(val0) else white.add(val0)
            }
        }
        return A0Config(apps.sorted(), procs.sorted(), white.sorted(), whiteProcs.sorted())
    }

    /**
     * 应用 A0 名单。
     *
     * 映射规则（用户指定，按条目粒度走，不让用户再选）：
     *   KILL  整包 → perapp.conf 的 force_stop
     *   KILL  子进程 → perproc.conf 的 force_stop
     *   WHITE 整包 → protect.list（白名单）
     *   WHITE 子进程 → perproc.conf 的 skip（不动它，但也不占清理名额）
     *
     * merge=true 与现有配置取并集；false 则用导入内容整体替换（白名单永远保留管理器自身）。
     * 返回 (per-app 数, per-proc 数, 白名单数)。
     */
    fun importA0Selection(
        apps: List<String>,
        procs: List<String>,
        white: List<String>,
        whiteProcs: List<String>,
        merge: Boolean,
    ): Triple<Int, Int, Int> {
        if (!Shell.rootOk()) return Triple(0, 0, 0)

        // per-app：KILL 的不带冒号条目 → force_stop
        val perapp = LinkedHashMap<String, Triple<String, String, Int>>()
        if (merge) {
            for (e in parsePerApp(readFileRaw("perapp.conf"))) perapp[e.first] = e.second
        }
        for (p in apps) perapp[p] = Triple("force_stop", "pkg", 0)
        val sbA = StringBuilder("# 每应用覆盖配置：<包名> mode=<sigkill|am_kill|force_stop> scope=<pkg|frozen> timeout=<秒>\n")
        for ((p, v) in perapp) {
            sbA.append(p)
            if (v.first.isNotEmpty()) sbA.append(" mode=").append(v.first)
            if (v.second.isNotEmpty()) sbA.append(" scope=").append(v.second)
            if (v.third > 0) sbA.append(" timeout=").append(v.third)
            sbA.append('\n')
        }
        writeFileRaw("perapp.conf", sbA.toString())

        // per-proc：KILL 的带冒号条目 → force_stop；WHITE 的带冒号条目 → skip。
        // 顺序有意义：同一个进程两边都出现时按白名单算（用户明确写了 WHITE 就是不想动它）。
        val perproc = LinkedHashMap<String, String>()
        if (merge) {
            for (e in parsePerProc(readFileRaw("perproc.conf"))) perproc[e.first] = e.second
        }
        for (p in procs) perproc[p] = "force_stop"
        for (p in whiteProcs) perproc[p] = "skip"
        val sbP = StringBuilder("# 每进程覆盖配置：<进程名> mode=<sigkill|am_kill|force_stop|skip>\n")
        for ((p, m) in perproc) sbP.append(p).append(" mode=").append(m).append('\n')
        writeFileRaw("perproc.conf", sbP.toString())

        // 白名单：只放 WHITE 的整包条目。带冒号的那些只压单个进程，包本身照常受管，
        // 不能一并塞进 protect.list —— 那会把整个包都豁免掉，比用户要的范围大。
        val wSet = if (merge) list("protect.list").toMutableSet() else mutableSetOf("com.yg.appkiller")
        wSet.addAll(white)
        writeListNoReload("protect.list", "# 白名单（含 A0 导入）", wSet)

        reload()
        return Triple(apps.size, perproc.size, wSet.size)
    }

    /** 解析 perapp.conf 的原始行 -> (包名, (mode, scope, timeout))。
     *  注意 list() 已经把注释与空行过滤掉了，但每行仍是「包名 key=value ...」的原始形态。 */
    private fun parsePerApp(lines: List<String>): List<Pair<String, Triple<String, String, Int>>> {
        val out = ArrayList<Pair<String, Triple<String, String, Int>>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            var mode = ""; var scope = ""; var tmo = 0
            for (i in 1 until parts.size) {
                val t = parts[i]
                when {
                    t.startsWith("mode=") -> mode = t.removePrefix("mode=")
                    t.startsWith("scope=") -> scope = t.removePrefix("scope=")
                    t.startsWith("timeout=") -> tmo = t.removePrefix("timeout=").toIntOrNull() ?: 0
                }
            }
            out.add(parts[0] to Triple(mode, scope, tmo))
        }
        return out
    }

    /** 解析 perproc.conf 的原始行 -> (进程名, mode) */
    private fun parsePerProc(lines: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val m = parts.firstOrNull { it.startsWith("mode=") }?.removePrefix("mode=") ?: continue
            out.add(parts[0] to m)
        }
        return out
    }

    /** 直接读设备上的 Cirno 配置；未安装 / 无配置返回 null */
    fun readCirnoConfig(): String? {
        if (!Shell.rootOk()) return null
        val raw = Shell.sh("cat /data/system/Cirno/ApplicationSettings.json 2>/dev/null", 8000)
        return raw.ifBlank { null }
    }

    /** 解析 Cirno 配置的 whiteApps / blackApps */
    fun parseCirnoApps(json: String): Pair<List<String>, List<String>> {
        val obj = try { org.json.JSONObject(json) } catch (e: Exception) { return emptyList<String>() to emptyList<String>() }
        fun arr(name: String): List<String> {
            val ja = obj.optJSONArray(name) ?: return emptyList()
            val out = ArrayList<String>(ja.length())
            for (i in 0 until ja.length()) {
                // Cirno 的条目格式是 "包名#userId"，只取包名部分
                val s = ja.optString(i, "").trim().substringBefore('#')
                if (s.isNotEmpty()) out.add(s)
            }
            return out.sorted().distinct()
        }
        return arr("whiteApps") to arr("blackApps")
    }

    /**
     * 导入勾选的 Cirno 条目。merge=true 与现有名单取并集；
     * merge=false 用勾选项整体覆盖现有名单（白名单永远保留 com.yg.appkiller）。
     * 返回写入后的 "白名单总数 / 黑名单总数"。
     */
    fun importCirnoSelection(white: List<String>, black: List<String>, merge: Boolean): Pair<Int, Int> {
        if (!Shell.rootOk()) return 0 to 0
        val wSet = if (merge) list("protect.list").toMutableSet() else mutableSetOf("com.yg.appkiller")
        wSet.addAll(white)
        val bSet = if (merge) list("blacklist.list").toMutableSet() else mutableSetOf()
        bSet.addAll(black)
        writeListNoReload("protect.list", "# 白名单（含 Cirno 导入）", wSet)
        writeListNoReload("blacklist.list", "# 黑名单（含 Cirno 导入）", bSet)
        reload()
        return wSet.size to bSet.size
    }

    private fun writeListNoReload(name: String, header: String, items: Collection<String>) {
        val sb = StringBuilder(header).append('\n')
        items.sorted().forEach { sb.append(it).append('\n') }
        writeFileRaw(name, sb.toString())
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
            // 先清热刷残留：临时解压目录带 module.prop，只要落在 /data/adb/modules 下就会被
            // Magisk/KernelSU 当成第二个模块，管理器列表因重复 id 直接崩。热刷用的临时目录现在
            // 放在 App 缓存里，但 pm install 会杀掉本进程、清理那一步常常跑不到，所以启用守护时
            // 顺手把两处可能的残留都清一遍。
            val tmp = if (ctx != null) ctx.cacheDir.absolutePath + "/hot" else ""
            val clean = "rm -rf /data/adb/modules/appkiller.hot " + (if (tmp.isNotEmpty()) tmp else "") + "; "
            Shell.sh(clean + "mkdir -p " + MODDIR + "/logs " + MODDIR + "/run; nohup " + MODDIR + "/bin/appkillerd daemon -m " + MODDIR + " >> " + MODDIR + "/logs/stdout.log 2>&1 &", 6000)
        }
    }

    fun restartDaemon() {
        if (Shell.rootOk()) {
            Shell.sh("kill \$(cat " + MODDIR + "/run/appkillerd.pid 2>/dev/null) 2>/dev/null; sleep 1; mkdir -p " + MODDIR + "/logs " + MODDIR + "/run; nohup " + MODDIR + "/bin/appkillerd daemon -m " + MODDIR + " >> " + MODDIR + "/logs/stdout.log 2>&1 &", 8000)
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

/**
 * 一个后台应用（含未冻结的）。
 *
 * 与 [FrozenApp] 的区别：FrozenApp 只表示「正在计时的」，本类覆盖全部后台包。
 * 分段不只看 [frozen]：白名单应用即使被系统冻结也不会被清理，要和未冻结的一起归入「不处理」，
 * 所以 [protected] 与 [frozen] 必须一起判断。
 */
data class BgApp(
    val pkg: String,
    val frozen: Boolean,
    val counting: Boolean,
    val frozenSec: Long,
    val remainSec: Long,
    val frozenProcs: Int,
    val totalProcs: Int,
    /** 命中显式白名单（protect.list），与「系统应用」分开：两者都受保护，但原因不同 */
    val protected: Boolean,
    /** 系统应用（uid < 10000 或命中 system_apps.list） */
    val sysapp: Boolean = false,
    /** 本轮会不会被清理，分段就按它切 */
    val managed: Boolean = true,
    /** 硬保护（桌面 / SystemUI / 管理器自身）：列出来只为看清它占了多少，永不处理 */
    val core: Boolean = false,
    /** 整包常驻（全部进程 adj < 0）：系统不冻它，我们也不计时 */
    val always: Boolean = false,
    /** 包内有前台可见进程 */
    val visible: Boolean = false,
)

/** 守护进程 STATUS 报文的一次快照。 */
data class StatusSnapshot(
    val uptimeSec: Long = 0,
    val apps: List<FrozenApp> = emptyList(),
    val background: List<BgApp> = emptyList(),
    val pid: Int = 0,
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
            // background：daemon 已按「冻结进程多的在前」排好，这里只做映射
            val bgArr = root.optJSONArray("background")
            val bg = ArrayList<BgApp>()
            if (bgArr != null) {
                // pkg 一定要归一化成包名（截掉 :子进程 后缀）。
                // 偶尔会出现「只有子进程在跑、主进程没起」的包，daemon 那边的 pkg
                // 若带了冒号，getApplicationInfo / getApplicationIcon 会抛
                // NameNotFoundException —— 表现就是图标空白、名称退回成「包名:子进程」。
                // 这里做一道归一化，比只在 daemon 侧修更保险：两端各归一一次，
                // 任一端漏了都不会把带冒号的名字漏到 UI 上。
                val seenPkg = HashSet<String>(bgArr.length())
                for (i in 0 until bgArr.length()) {
                    val o = bgArr.getJSONObject(i)
                    val basePkg = o.optString("pkg").substringBefore(':')
                    if (basePkg.isEmpty() || !seenPkg.add(basePkg)) continue
                    bg.add(
                        BgApp(
                            pkg = basePkg,
                            frozen = o.optBoolean("frozen"),
                            counting = o.optBoolean("counting"),
                            frozenSec = o.optLong("frozen_sec"),
                            remainSec = o.optLong("remain_sec"),
                            frozenProcs = o.optInt("frozen_procs"),
                            totalProcs = o.optInt("total_procs"),
                            protected = o.optBoolean("protected"),
                            sysapp = o.optBoolean("sysapp"),
                            managed = o.optBoolean("managed", true),
                            core = o.optBoolean("core"),
                            always = o.optBoolean("always"),
                            visible = o.optBoolean("visible"),
                        )
                    )
                }
            }
            StatusSnapshot(uptime, list.sortedByDescending { it.frozenSec }, bg, pid)
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
    /** 应用列表缓存：切页不再重跑 pm list packages + 逐包取标签 */
    private const val INSTALLED_TTL_MS = 120_000L
    @Volatile private var installedCache: Pair<Long, List<AppEntry>>? = null

    fun invalidateInstalled() { installedCache = null }

    fun installed(ctx: Context): List<AppEntry> {
        val c = installedCache
        if (c != null && System.currentTimeMillis() - c.first < INSTALLED_TTL_MS) return c.second
        val pm = ctx.packageManager
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()

        // HyperOS 4 上即使声明了 QUERY_ALL_PACKAGES，PackageManager 也只返回本应用，
        // 所以有 root 时以 `pm list packages` 的全量输出为准，标签再逐个补。
        //
        // 系统应用的判定不能只看 getApplicationInfo(pkg).flags：包可见性受限时它返回 null，
        // flags 与 sourceDir 全为空，系统应用会被误判成普通应用，写进 system_apps.list 后
        // daemon 的「系统应用默认豁免」就形同虚设。改为三路取并集：
        //   1) `pm list packages -s` 的直接输出（最可靠，不经过 PackageManager 可见性）
        //   2) ApplicationInfo.uid < 10000（等价 Cirno 的 FIRST_APPLICATION_UID 判定）
        //   3) FLAG_SYSTEM / 安装路径不在 /data 下（传统兜底）
        if (Shell.rootOk()) {
            val sysPkgs = Shell.sh("pm list packages -s 2>/dev/null")
                .lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toHashSet()
            for (line in Shell.sh("pm list packages 2>/dev/null").lineSequence()) {
                val pkg = line.removePrefix("package:").trim()
                if (pkg.isEmpty() || !seen.add(pkg)) continue
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val label = ai?.let { a ->
                    runCatching { pm.getApplicationLabel(a).toString() }.getOrNull()
                } ?: pkg
                val flags = ai?.flags ?: 0
                val path = ai?.sourceDir ?: ""
                val uid = ai?.uid ?: -1
                val sys = pkg in sysPkgs ||
                    (uid in 1 until 10000) ||
                    (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (path.isNotEmpty() && !path.startsWith("/data/"))
                val persist = (flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0
                out.add(AppEntry(pkg, label, sys, persist))
            }
        }
        if (out.isNotEmpty()) {
            val sorted = out.sortedWith(compareBy({ !it.system }, { it.label }))
            installedCache = System.currentTimeMillis() to sorted
            return sorted
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
        val sorted = out.sortedWith(compareBy({ !it.system }, { it.label }))
        installedCache = System.currentTimeMillis() to sorted
        return sorted
    }

    fun labelMap(ctx: Context): Map<String, String> {
        // 与 installed() 同路：HyperOS 4 的 getInstalledApplications 只返回本应用，
        // 直接用它做映射会让运行列表只剩包名尾巴。
        val m = HashMap<String, String>()
        for (e in installed(ctx)) {
            m[e.pkg] = e.label
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
