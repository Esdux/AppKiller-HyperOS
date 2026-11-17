package com.yg.appkiller.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.appkiller.Ctrl
import com.yg.appkiller.FrozenApp
import com.yg.appkiller.ModuleRepo
import com.yg.appkiller.Shell
import com.yg.appkiller.StateRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * 守护进程状态的一份快照。
 *
 * 全部 val：Compose 只靠 equals 判变化，var 字段改了不会触发重组，
 * 反而让人误以为「界面不刷新是协程没跑」。
 */
data class DaemonState(
    val running: Boolean = false,
    val pid: Int = -1,
    val uptimeSec: Long = 0L,
    val watching: List<FrozenApp> = emptyList(),
    val protectCount: Int = 0,
    val blackCount: Int = 0,
    val config: Map<String, String> = emptyMap(),
    val root: Boolean = false,
    /** 首轮还没读到，用来区分「确实没在跑」与「正在读」 */
    val loaded: Boolean = false,
) {
    val uptimeText: String
        get() = when {
            uptimeSec <= 0L -> "—"
            uptimeSec < 60 -> uptimeSec.toString() + " 秒"
            uptimeSec < 3600 -> (uptimeSec / 60).toString() + " 分钟"
            else -> (uptimeSec / 3600).toString() + " 小时 " + ((uptimeSec % 3600) / 60) + " 分"
        }

    val killMode: String get() = config["KILL_MODE"] ?: "sigkill"
    val timeoutMin: Int get() = config["TIMEOUT_MIN"]?.toIntOrNull() ?: 60
    val dryRun: Boolean get() = (config["DRY_RUN"] ?: "0") == "1"
}

/**
 * 守护状态的单一数据源。
 *
 * **用 flow + stateIn(WhileSubscribed)，不要用 while(true) 常驻协程。**
 * 后者在 App 切到后台后照样每秒打一次控制端口；前者在最后一个订阅者消失 5 秒后
 * 自动停，回到前台立刻重来。这也是「应用列表很卡」那次事故的教训之一：
 * 轮询协程不能由 tick 自增当 key 反复重建，也不能永远不停。
 *
 * 一次 load 只做四次本地 socket 调用（STATUS / CFG / LIST white / LIST black），
 * 全是 127.0.0.1 的毫秒级往返，不 fork su。
 */
class DaemonStateViewModel : ViewModel() {

    val state: StateFlow<DaemonState> = flow {
        // 首帧立刻给一次，避免页面空转一个间隔
        emit(load())
        while (true) {
            delay(REFRESH_MS)
            emit(load())
        }
        // flowOn(IO) 是必须的：viewModelScope 默认在 Main 上跑，而 load() 里是阻塞 socket。
        // 少了这一行，Android 会抛 NetworkOnMainThreadException，被 Ctrl.call 的 catch 吞成
        // lastError，界面表现是「守护进程明明在跑却显示未运行」—— 且不会崩，极难发现。
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DaemonState())

    /** 手动刷新：点状态卡、下拉刷新都走这里 */
    suspend fun refresh(): DaemonState = withContext(Dispatchers.IO) { load() }

    private fun load(): DaemonState {
        val root = Shell.rootOk()
        val running = Ctrl.ping()
        val snapshot = StateRepo.snapshot()
        val config = ModuleRepo.configMap()
        // 名单数量：控制端口优先，无守护时返回 0 而不是卡在 root 通道
        val protect = ModuleRepo.list("protect.list").size
        val black = ModuleRepo.list("blacklist.list").size
        return DaemonState(
            running = running,
            pid = snapshot.pid,
            uptimeSec = snapshot.uptimeSec,
            watching = snapshot.apps,
            protectCount = protect,
            blackCount = black,
            config = config,
            root = root,
            loaded = true,
        )
    }

    companion object {
        /**
         * 1 秒一次。用户明确要求「应该一秒一刷」——
         * 更慢会让「点了杀，界面还显示在冻结」看起来像没生效。
         */
        private const val REFRESH_MS = 1_000L
    }
}
