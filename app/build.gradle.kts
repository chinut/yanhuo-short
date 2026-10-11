plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.chinut.yanhuo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chinut.yanhuo"

        // 手机端，但保持和电视端一样的下限 —— 老机器也装得上
        minSdk = 23
        targetSdk = 36

        versionCode = 4
        versionName = "1.1.0"

        // 只保留中文资源。
        // 用 androidResources.localeFilters 而不是已弃用的 resourceConfigurations
        // （AGP 9 起后者会报 deprecation，而且语言过滤已独立成 androidResources 的职责）
        androidResources {
            localeFilters += listOf("zh")
        }
    }

    signingConfigs {
        create("release") {
            // 自用/学习项目：固定签名，便于覆盖安装升级
            storeFile = file("yanhuo.jks")
            storePassword = "yanhuo123"
            keyAlias = "yanhuo"
            keyPassword = "yanhuo123"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 连载更新检查（后台定期跑，用来发更新提示）
    implementation(libs.androidx.work.runtime.ktx)

    // 播放器
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)

    // 网络 / 协程 / 图片
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 单元测试。
    // 只测**纯逻辑**（滑动阈值 / 版本清单解析这类判定），不测 Compose ——
    // 手势与联网必须在真机上验，但"位移多少算切集""清单怎么写算有效"
    // 是纯函数，可以也应该用测试锁住。
    testImplementation(libs.junit)
    // ⚠️ 必须显式引入 org.json 的实现。
    // Android 单元测试跑在 JVM 上，android.jar 里的 org.json 是**打桩的空实现**，
    // 调用会抛 "Method not mocked"，导致所有解析类测试失败。
    // 这个包提供了同 API 的真实实现，补上才能真跑。
    testImplementation(libs.json)
}
