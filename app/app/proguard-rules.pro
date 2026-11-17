# AppKiller 的 release 保留规则（基于 miuix-compose-ui skill 的模板改写）。
#
# 为什么开 R8：material-icons-extended 与 compose runtime 生成代码体量极大，
# debug APK 81 MB，其中九成是这类生成物与未裁剪的资源。
# 但开了 R8 之后反射与 AGSL 路径会静默失效（界面不崩，只是某些行不显示、模糊变黑），
# 所以下面几组必须显式保留。

# Compose runtime 通过反射查找 @Composable 的 lambda 与 state 类
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-keep class androidx.compose.runtime.** { *; }

# Miuix 图标是扩展属性，名字被 R8 改掉后 MiuixIcons.X 找不到
-keep class top.yukonga.miuix.kmp.icon.** { *; }

# RuntimeShader 的 AGSL 通过字符串索引 uniform，字段名不能混淆
-keepclassmembers class top.yukonga.miuix.kmp.blur.** {
    <fields>;
}
-keepclassmembers class top.yukonga.miuix.kmp.shader.** {
    <fields>;
}

# 本工程的入口：Activity 由 manifest 与 Intent 反射拉起，
# ViewModel 由 viewModel() 按类名实例化，混淆后都会变成「点了没反应」
-keep class com.yg.appkiller.ui.AppShellActivity { *; }
-keep class com.yg.appkiller.ui.screens.**Activity { *; }
-keep class com.yg.appkiller.ui.data.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# 只保留行号，方便看崩溃栈；不保留源文件名
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
