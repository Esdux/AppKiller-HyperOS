package com.yg.appkiller

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 与守护进程的本地控制通道（127.0.0.1:27183）。
 * 好处：普通应用权限即可用，不需要 su 授权，也不会像 su 子进程那样每次卡几秒。
 * 协议：单行 "AK1 appkiller <CMD> [args]"，daemon 应答后立即关闭连接。
 */
object Ctrl {
    const val PORT = 27183
    const val TOKEN_PREFIX = "AK1 appkiller "

    @Volatile
    var lastError: String? = null

    fun call(cmd: String, timeoutMs: Int = 2500): String? {
        var s: Socket? = null
        return try {
            s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", PORT), timeoutMs)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            out.write((TOKEN_PREFIX + cmd + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            val txt = s.getInputStream().bufferedReader().readText()
            if (txt.startsWith("ERR")) {
                lastError = txt.trim()
                null
            } else {
                lastError = null
                txt
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        } finally {
            try { s?.close() } catch (e: Exception) { }
        }
    }

    fun ping(): Boolean = (call("PING", 1200) ?: "").startsWith("PONG")

    /* ---- 每应用 / 每进程配置 ---- */

    /** PROCS <pkg> -> List<ProcEntry> */
    data class ProcEntry(val pid: Int, val cmdline: String, val adj: Int, val frozen: Boolean, val mode: String)

    fun procs(pkg: String): List<ProcEntry> {
        val raw = call("PROCS " + pkg, 3000) ?: return emptyList()
        return raw.trim().lines().mapNotNull { line ->
            val parts = line.split(" ", limit = 5)
            if (parts.size < 5) return@mapNotNull null
            ProcEntry(
                pid = parts[0].toIntOrNull() ?: return@mapNotNull null,
                cmdline = parts[1],
                adj = parts[2].toIntOrNull() ?: 0,
                frozen = parts[3] == "1",
                mode = parts[4],
            )
        }
    }

    /**
     * DECLPROCS <pkg> -> 该包在 AndroidManifest.xml 里声明过的全部进程名。
     *
     * 与 [procs] 的区别：procs 扫 /proc，只列此刻活着的；这里解 APK 清单，列「这个应用
     * 一共会有哪些进程」，应用没启动也有结果。二级页拿它当骨架，再把 procs 的实时状态
     * 叠上去，就不会因为应用没跑而显示「没有运行中的进程」。
     *
     * 第一行是主进程（= 包名）。读不到清单时返回空表（老设备 / 无 root）。
     */
    fun declProcs(pkg: String): List<String> {
        val raw = call("DECLPROCS " + pkg, 5000) ?: return emptyList()
        if (raw.startsWith("ERR")) return emptyList()
        return raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun killPid(pid: Int): Boolean = (call("KILLPID " + pid, 2000) ?: "").startsWith("OK")

    /** GETPERAPP <pkg> -> Pair(mode, scope)，"-" 表示未设置 */
    fun getPerApp(pkg: String): Pair<String, String> {
        val raw = call("GETPERAPP " + pkg, 2000) ?: return Pair("-", "-")
        val parts = raw.trim().removePrefix("OK").trim().split(" ")
        return Pair(parts.getOrElse(0) { "-" }, parts.getOrElse(1) { "-" })
    }

    fun setPerApp(pkg: String, mode: String, scope: String): Boolean =
        (call("SETPERAPP " + pkg + " " + mode + " " + scope, 2000) ?: "").startsWith("OK")

    /** GETPERPROC <procname> -> mode，"-" 表示未设置 */
    fun getPerProc(proc: String): String {
        val raw = call("GETPERPROC " + proc, 2000) ?: return "-"
        return raw.trim().removePrefix("OK").trim()
    }

    fun setPerProc(proc: String, mode: String): Boolean =
        (call("SETPERPROC " + proc + " " + mode, 2000) ?: "").startsWith("OK")

    fun killPkg(pkg: String, mode: String = ""): String =
        call("KILLPKG " + pkg + (if (mode.isEmpty()) "" else " " + mode), 3000) ?: "ERR"
}
