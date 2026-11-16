package com.yg.appkiller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 对话框宿主：状态由设置页写入，UI 在 AppContent 顶层渲染。
 * 放在外壳顶层是为了让对话框 z 序高于悬浮导航栏（画在页内会被导航栏盖住）。
 */
object DialogHost {
    var cirnoShow by mutableStateOf(false)
    var cirnoLoading by mutableStateOf(false)
    var cirnoWhite by mutableStateOf<List<String>>(emptyList())
    var cirnoBlack by mutableStateOf<List<String>>(emptyList())
    var cirnoSelW by mutableStateOf<Set<String>>(emptySet())
    var cirnoSelB by mutableStateOf<Set<String>>(emptySet())
    var cirnoMerge by mutableStateOf(true)
    var cirnoTab by mutableIntStateOf(0)

    var logShow by mutableStateOf(false)
    var logText by mutableStateOf("")
}
