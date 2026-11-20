plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yg.appkiller"

    // 正式包用本机统一发布密钥（tools/README.md「Android 应用签名」）。
    // 密码只从环境变量取，绝不写进这个文件；缺密码时回退 debug 签名，
    // 这样没有密钥的机器也能编译，只是装出来不能覆盖正式版。
    signingConfigs {
        create("release") {
            val ks = System.getenv("ANDROID_KS")
                ?: System.getProperty("user.home") + "/my-release.keystore"
            val pass = System.getenv("ANDROID_KS_PASS")
            if (pass != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = pass
                keyAlias = System.getenv("ANDROID_KS_ALIAS") ?: "mykey"
                keyPassword = pass
                // 规范固定 v2 + v3（v1 关、v4 不开）。注意：minSdk 33 时
                // apksigner 认为 v2 用不上，只写 v3 块——所以交付包一律再走
                // tools/sign_apk.sh 重签一遍，那个脚本会强制把 v2 也写进去。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.yg.appkiller"
        minSdk = 33
        targetSdk = 37
        // 与 module/module.prop 的 version/versionCode 保持一致，刷模块后能一眼对上
        versionCode = 2
        versionName = "1.1"

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
            // 有发布密钥就签它，没有就退回 debug（见上面 signingConfigs 的说明）
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
