plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kooo.evcam"
    compileSdk = 36

    // 签名配置。
    // 默认沿用仓库内的公开测试密钥（AOSP 测试签名，口令公开），方便任何人自行构建、
    // 并保证不同版本之间可以覆盖安装。正式发布可用环境变量覆盖为自己的密钥：
    //   ZEEKR_KEYSTORE / ZEEKR_KEYSTORE_PASSWORD / ZEEKR_KEY_ALIAS / ZEEKR_KEY_PASSWORD
    signingConfigs {
        create("release") {
            val customStore = System.getenv("ZEEKR_KEYSTORE")
            if (!customStore.isNullOrBlank() && file(customStore).exists()) {
                storeFile = file(customStore)
                storePassword = System.getenv("ZEEKR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZEEKR_KEY_ALIAS")
                keyPassword = System.getenv("ZEEKR_KEY_PASSWORD")
            } else {
                storeFile = file("../keystore/release.jks")
                storePassword = "android"
                keyAlias = "apkeasytool"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.dts88.zeekrshortcut"
        minSdk = 28
        targetSdk = 36
        // 每次发布 tag 前同步更新这两个值：versionCode 决定能否覆盖安装，
        // versionName 会成为 Release 名称与 APK 文件名
        //
        // 后缀默认就是 -alpha。只有项目拥有者明确说「这一版发 beta」时才用
        // -beta，而且<b>不会延续到下一版</b>——上一版是 beta 不是下一版也是 beta
        // 的理由。检查更新默认只推 beta 和正式版（alpha 要车主自己打开「接收 Alpha 版」），
        // 标错等于把一个没验证过的版本推给一台正在用的车机。
        // fork 构建：版本号永远排在上游之上，后缀带 -fab 标记（update.VersionName 认这个标记，
        // 打开「更新从自己的 fork 取」时只认带标记的版本，上游的版本号再大也不会被当成更新）。
        versionCode = 410
        // 版本号从 0.1.0 重新起算；代码基座为 EVCam 1.6.6 (0876b97)
        // 每次同步上游后把第二段抬到上游之上：上游 4.12.1 → fork 4.13.0-fab-beta
        versionName = "4.19.1-fab-beta"
    }

    buildTypes {
        release {
            // 使用签名配置
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

}

// ---- Robolectric 的 android-all 包：构建期解析，测试期不联网 ----
// 所有 Robolectric 测试都写着 @Config(sdk = 34)。默认情况下 Robolectric 在跑测试的时候
// 才去 Maven Central 下载对应的 android-all 包，下载失败就让整个类报错
// （CI 上见过：org.robolectric.internal.dependency.MavenArtifactFetcher.java:129，
// 同一个 commit 上 tag 构建绿、main 推送红）。所以把这个包改成普通的构建期依赖：
// Gradle 负责下载、缓存、重试，然后复制到 build/robolectric-deps，测试只从那里取。
//
// 版本号是量出来的，不是猜的：本机 ~/.m2 里 Robolectric 4.16 为 sdk 34 实际下载的就是
// org.robolectric:android-all-instrumented:14-robolectric-10818077-i7；
// 与 robolectric-4.16.jar 内 DefaultSdkProvider 的映射一致
// （Android 14 = API 34 = org.robolectric.versioning.AndroidVersions$U，内部版本 10818077；
// -i7 后缀表示预插桩包，Robolectric 4.16 默认用它）。
// 若测试换了 sdk，这里不会静默放过：下面 doLast 的 check 会直接报错。
val robolectricAndroidAllVersion = "14-robolectric-10818077-i7"
val robolectricAndroidAllJar = "android-all-instrumented-$robolectricAndroidAllVersion.jar"

// 独立的可解析配置：只用来拿这一个 jar，不进测试 classpath
val robolectricDeps by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")

val prepareRobolectricDeps = tasks.register<Sync>("prepareRobolectricDeps") {
    group = "verification"
    description = "把 Robolectric 的 android-all 包复制到 build/robolectric-deps，供离线测试使用"
    from(robolectricDeps)
    into(robolectricDepsDir)
    doLast {
        val jar = robolectricDepsDir.get().asFile.resolve(robolectricAndroidAllJar)
        check(jar.isFile) {
            "离线测试用的 android-all 包不存在: $jar —— 这是构建问题，不是测试失败，不要跳过"
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricDeps)
    // robolectric.offline + robolectric.dependency.dir 是 Robolectric 4.16 的
    // LegacyDependencyResolver 认的两个系统属性：离线时只从目录里取 <artifactId>-<version>[-分类].jar
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.cardview)

    // JSON 解析
    implementation("com.google.code.gson:gson:2.10.1")

    // Glide 图片加载库（用于缓存和优化缩略图加载）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // WorkManager 定时任务（用于保活）
    implementation("androidx.work:work-runtime:2.9.0")

    // 「发送到手机」：局域网内起一个 HTTP 服务 + 生成二维码。
    // 两个都是上游 EVCam 用过的，许可证与 GPL-3.0 兼容：
    // NanoHTTPD 是 BSD-3-Clause，ZXing 是 Apache-2.0。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.1")

    // 录像回放的播放器（Media3 ExoPlayer，Apache-2.0，与 GPL-3.0 兼容）：画面按系统时钟走，
    // 不跟着车机报的声音位置等（见 ManagedVideoPlayer）
    implementation(libs.media3.exoplayer)

    // 设置界面（PreferenceScreen）
    implementation("androidx.preference:preference:1.2.1")
    // 两栏设置的左右分栏（androidx 自己的 PreferenceHeaderFragmentCompat 也用它）
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    robolectricDeps("org.robolectric:android-all-instrumented:$robolectricAndroidAllVersion") {
        // 只需要那一个 jar，不拉传递依赖
        isTransitive = false
    }
}
