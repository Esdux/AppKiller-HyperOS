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
}
