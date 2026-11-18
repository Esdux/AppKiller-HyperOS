plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yg.appkiller"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.yg.appkiller"
        minSdk = 33
        targetSdk = 37
        // 与 module/module.prop 的 version/versionCode 保持一致，刷模块后能一眼对上
        versionCode = 15
        versionName = "1.5"

        // 只打 arm64-v8a：本模块只面向 HyperOS/MIUI 的 arm64 设备，
        // 带上 armeabi-v7a / x86 只会让包里多出几十 MB 用不到的 native 库。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            // R8 开起来：debug 81 MB 里九成是 material-icons-extended 与 compose
            // 的生成代码，裁剪后约 6 MB。保留规则见 proguard-rules.pro，
            // 少一条就会出现「界面不崩但某行不显示」这类静默失效。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 用 debug 签名，模块刷入后由 pm install 直接装，不需要额外配置 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint { abortOnError = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader:0.9.4")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
}
