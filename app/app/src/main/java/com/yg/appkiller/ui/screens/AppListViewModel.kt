package com.yg.appkiller.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.appkiller.AppRepo
import com.yg.appkiller.ModuleRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用列表的数据模型。
 *
 * [icon] 是**已解码的 ImageBitmap**，不是 Drawable。
 * 图标解码要读 APK 里的资源，一次几十毫秒；放在组合里做，列表滚动就会一卡一卡。
 * 在 IO 线程解好再发出去，UI 只负责画。
 *
 * [inWhite] / [inBlack] 是名单归属，直接来自模块的 protect.list / blacklist.list。
 * 它们是**列表数据的一部分**，不是筛选条件 —— 筛选条件只有 [Filter] 与两个勾选布尔。
 */
data class AppEntry(
    val packageName: String,
    val userId: Int,
    val appName: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val inWhite: Boolean,
    val inBlack: Boolean,
    val icon: ImageBitmap?,
) {
    /** LazyColumn 的 key：包名会重复（多用户），必须拼上 userId。 */
    val key: String get() = packageName + "#" + userId

    /**
     * 行右侧的标签：白名单优先于黑名单（一个包同时在两边是配置错误，按白名单显示）。
     *
     * **一个列表项最多一个 StatusTag。** 要表达两个状态就换成一个更准确的短语，
     * 不要并排两个标签 —— 并排会把行高撑开，也让「哪个才是主要状态」变得含糊。
     */
    val tag: TagSpec?
        get() = when {
            inWhite -> TagSpec("白名单", Color.Black, Color.White)
            inBlack -> TagSpec("黑名单", Color(0xFFD13636), Color.White)
            isSystem -> TagSpec("系统", Color(60, 179, 113), Color.White)
            else -> null
        }
}

/**
 * 应用列表的双流模型（照 Cirno 的 AppListViewModel）。
 *
 * - [allApps]：全量，只回答「有没有数据」。
 * - [visibleApps]：过滤后，真正渲染的。
 *
 * 合成一条流时，「搜不到」与「一个应用都没有」分不开，
 * 于是空状态只能写一句含糊的「暂无数据」，用户不知道该清搜索还是该去装应用。
 *
 * 过滤放在 ViewModel：几百个应用在组合里 filter，每次重组都重跑一遍。
 */
class AppListViewModel : ViewModel() {

    private val _allApps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(Filter.ALL)
    private val _onlyWhite = MutableStateFlow(false)
    private val _onlyBlack = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(false)
    private val _failed = MutableStateFlow(false)

    val query: StateFlow<String> = _query
    val filter: StateFlow<Filter> = _filter
    val onlyWhite: StateFlow<Boolean> = _onlyWhite
    val onlyBlack: StateFlow<Boolean> = _onlyBlack
    val loaded: StateFlow<Boolean> = _loaded
    val failed: StateFlow<Boolean> = _failed

    /** 全量，给空状态判断用 */
    val allApps: StateFlow<List<AppEntry>> = _allApps

    /**
     * 名单勾选是**并集**：两个都勾 = 白名单 + 黑名单。
     *
     * 用户原话（m03644）：「应用列表筛选加上白名单和黑名单，可同时勾选」。
     * 用交集的话，同时勾选永远是空集（一个包不该同时在两边），那就成了两个假开关。
     */
    val visibleApps: StateFlow<List<AppEntry>> =
        combine(_allApps, _query, _filter, _onlyWhite, _onlyBlack) { all, q, f, white, black ->
            all.asSequence()
                .filter { app ->
                    when (f) {
                        Filter.ALL -> true
                        Filter.USER -> !app.isSystem
                        Filter.SYSTEM -> app.isSystem
                        Filter.WHITE -> app.inWhite
                        Filter.BLACK -> app.inBlack
                    }
                }
                .filter { app ->
                    if (!white && !black) true
                    else (white && app.inWhite) || (black && app.inBlack)
                }
                .filter { app ->
                    if (q.isEmpty()) true
                    else app.appName.contains(q, ignoreCase = true) ||
                        app.packageName.contains(q, ignoreCase = true)
                }
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    /** 返回 Job：调用方 join() 之后才收下拉指示器，否则数据没回来指示器就没了。 */
    fun refresh(): Job = viewModelScope.launch {
        _failed.value = false
        val ctx = appContext
        if (ctx == null) {
            _failed.value = true
            _loaded.value = true
            return@launch
        }
        // 第一步：名单 + 应用清单，都快（root 一次 pm list packages）
        val base = withContext(Dispatchers.IO) {
            runCatching {
                val white = ModuleRepo.list("protect.list").toHashSet()
                val black = ModuleRepo.list("blacklist.list").toHashSet()
                AppRepo.installed(ctx).map { e ->
                    AppEntry(
                        packageName = e.pkg,
                        userId = 0,
                        appName = e.label,
                        isSystem = e.system,
                        enabled = true,
                        inWhite = white.contains(e.pkg),
                        inBlack = black.contains(e.pkg),
                        icon = null,
                    )
                }
            }.getOrNull()
        }
        if (base == null) {
            _failed.value = true
            _loaded.value = true
            return@launch
        }
        // 列表先上屏（无图标），图标随后逐个补 —— 300+ 个包全解完要好几秒，
        // 让用户先看到名字，比让他看一个转圈的进度条好。
        _allApps.value = base
        _loaded.value = true

        // 第二步：图标渐进填充，每攒够 24 个发一次
        withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val buffer = ArrayList<AppEntry>(24)
            val current = base.toMutableList()
            for (i in current.indices) {
                val e = current[i]
                val bmp = IconCache.get(e.packageName) ?: runCatching {
                    pm.getApplicationIcon(e.packageName).toBitmap(116, 116).asImageBitmap()
                }.getOrNull()?.also { IconCache.put(e.packageName, it) }
                if (bmp != null) {
                    current[i] = e.copy(icon = bmp)
                    buffer.add(current[i])
                }
                if (buffer.size >= 24) {
                    _allApps.value = current.toList()
                    buffer.clear()
                }
            }
            if (buffer.isNotEmpty() || current.isNotEmpty()) _allApps.value = current.toList()
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** 换分类保留搜索词与名单勾选：用户通常是在同一批词里换范围。 */
    fun onFilterChange(value: Filter) {
        if (_filter.value == value) return
        _filter.value = value
    }

    fun toggleWhite() {
        _onlyWhite.value = !_onlyWhite.value
    }

    fun toggleBlack() {
        _onlyBlack.value = !_onlyBlack.value
    }

    enum class Filter(val label: String) {
        ALL("全部"),
        USER("用户应用"),
        SYSTEM("系统应用"),
        WHITE("白名单"),
        BLACK("黑名单"),
    }

    /**
     * 图标缓存：同一个包在列表、二级页、运行列表里都要用。
     *
     * 用 accessOrder 的 LinkedHashMap 而不是 LruCache：这里要的是「最近用过的不重复解码」，
     * 400 条上限足够覆盖 300+ 个包的一轮遍历，同时不会把整机所有图标钉在内存里。
     */
    private object IconCache {
        private const val MAX = 400
        private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > MAX
        }

        @Synchronized
        fun get(pkg: String): ImageBitmap? = map[pkg]

        @Synchronized
        fun put(pkg: String, bmp: ImageBitmap) {
            map[pkg] = bmp
        }
    }

    companion object {
        /**
         * 应用上下文。ViewModel 拿不到 Context（它不该持有 Activity），
         * 由 Activity 在 onCreate 里塞一次。
         */
        var appContext: Context? = null
    }
}
