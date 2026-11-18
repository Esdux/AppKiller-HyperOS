package com.yg.appkiller

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 正式版检查：只问 GitHub Releases 的最新 tag，不下载、不自动安装、不上报任何东西。
 *
 * 为什么不用某个「更新库」：这里只需要一次 GET + 一次版本号比较，
 * 引一个库要多背一套生命周期、一个前台服务和一个同意弹窗，收益是负的。
 */
object Update {
    const val REPO_URL = "https://github.com/Esdux/AppKiller-HyperOS"
    const val RELEASES_URL = REPO_URL + "/releases/latest"
    private const val API = "https://api.github.com/repos/Esdux/AppKiller-HyperOS/releases/latest"

    /** 最新正式版；拿不到返回 null（离线、被墙、限流都走这里） */
    data class Info(val tag: String, val url: String, val name: String)

    /**
     * 一次查询的三种结局，调用方要分开显示：
     *  - NotFound：仓库还没有发布过正式版（GitHub 回 404），不是网络问题；
     *  - Failed：网络 / 限流 / 解析失败；
     *  - Ok：拿到 tag。
     * 合成一个 null 的话，用户看到「检查失败」会去反复试网络，而实际是还没有正式版。
     */
    sealed class Result {
        data class Ok(val info: Info) : Result()
        object NotFound : Result()
        object Failed : Result()
    }

    fun fetchLatest(timeoutMs: Int = 8000): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                // GitHub API 强制要求 User-Agent，缺了直接 403
                setRequestProperty("User-Agent", "AppKiller-HyperOS")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code == 404) return Result.NotFound
            if (code != 200) return Result.Failed
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            Result.Ok(
                Info(
                    tag = o.optString("tag_name").trim(),
                    url = o.optString("html_url").ifBlank { RELEASES_URL },
                    name = o.optString("name").trim(),
                ),
            )
        } catch (e: Exception) {
            Result.Failed
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /** 去掉 v 前缀，方便比较与显示 */
    fun normalize(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    /**
     * 版本号比较：按 . 分段逐段比数字，非数字段按字符串比。
     * 返回 >0 表示 a 更新，0 相等，<0 表示 a 更旧。
     * 不用字符串比较：那会让 1.10 小于 1.9。
     */
    fun compare(a: String, b: String): Int {
        val x = normalize(a).split(".", "-")
        val y = normalize(b).split(".", "-")
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)?.toIntOrNull()
            val q = y.getOrNull(i)?.toIntOrNull()
            if (p != null && q != null) {
                if (p != q) return if (p > q) 1 else -1
            } else {
                val s = x.getOrNull(i) ?: ""
                val t = y.getOrNull(i) ?: ""
                val c = s.compareTo(t)
                if (c != 0) return if (c > 0) 1 else -1
            }
        }
        return 0
    }
}
