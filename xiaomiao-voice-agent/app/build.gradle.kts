plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("jacoco")
}

import java.util.Properties
import org.gradle.testing.jacoco.tasks.JacocoReport

// API Key 只从 local.properties 读取（该文件已 gitignore），构建时写入 BuildConfig。
// 不留明文在 Kotlin 源码中，避免进入版本历史。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mossApiKey = (localProps.getProperty("moss.api.key") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.xiaomiao"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.xiaomiao"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "MOSS_API_KEY", "\"$mossApiKey\"")

        // 真机 instrumentation 测试入口（跑 WakeDetector/WakeService 本体，需设备在线）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        getByName("debug") {
            // AGP 插桩采集覆盖率：真机 instrumentation 运行时写出 coverage.ec，
            // connectedDebugAndroidTest 结束后 pull 到 build/outputs/code_coverage，
            // jacocoTestReport 的 executionData 已 include outputs/code_coverage/**/*.ec
            isTestCoverageEnabled = true
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*", "META-INF/versions/9/module-info.class")
        }
    }

    testOptions {
        // Robolectric 需要合并后的 assets/resources 才能读到 kws/ 下的模型文件
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
            // Robolectric 用本地 android-all 镜像：代理环境下远程仓库的 POM SHA-512 校验失败，
            // 故预下载 jar 到 .toolchain/robolectric-deps，走离线依赖解析绕过校验。
            it.systemProperty("robolectric.dependency.dir", "I:/voice-agent/.toolchain/robolectric-deps")
            it.systemProperty("robolectric.offline", "true")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    // 真机 instrumentation 测试（androidTest source set）
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// 本地 JVM 单元测试覆盖率报告（JaCoCo）。
// JVM 单测不覆盖依赖 Android Context/assets 的类（KwsFiles/WakeDetector），
// 那些需要 Robolectric 或真机 instrumentation，后续按需再接。
tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generate JaCoCo coverage report from local JVM unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }

    val kotlinDebugDir = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(kotlinDebugDir))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec", "outputs/code_coverage/**/*.ec")
        }
    )
}
