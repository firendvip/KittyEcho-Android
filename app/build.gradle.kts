/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlinx.kover)
}

// Release 签名凭据从 gitignored keystore.properties 读取（不再硬编码于本文件）。
// 缺该文件时 release 签名会失败（debug/单测不受影响）；备份见 CAT MAC/_keystore_backup/。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val projectMinSdk: String by project
val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project
val projectVersionNameSuffix = projectVersionName.substringAfter("-", "").let { suffix ->
    if (suffix.isNotEmpty()) {
        "-$suffix"
    } else {
        suffix
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-jvm-default=enable",
            "-Xwhen-guards",
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
            "-XXLanguage:+LocalTypeAliases",
        ))
    }
}

configure<ApplicationExtension> {
    namespace = "com.wordtaker.keyboard"
    compileSdk = projectCompileSdk.toInt()
    buildToolsVersion = tools.versions.buildTools.get()
    ndkVersion = tools.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "com.wordtaker.keyboard"
        minSdk = projectMinSdk.toInt()
        targetSdk = projectTargetSdk.toInt()
        versionCode = projectVersionCode.toInt()
        versionName = projectVersionName.substringBefore("-")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // WordTaker on-device ASR (sherpa-onnx) ships native .so only for arm64-v8a.
        // Restrict packaged ABIs to arm64-v8a so the AAR's JNI libs and FlorisBoard's
        // Rust libs are both present for that ABI (CMake still builds all ABIs; this
        // only filters what lands in the APK). Real devices are arm64 in practice.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${getGitCommitHash().get()}\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v~draft2\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")

        sourceSets {
            maybeCreate("main").apply {
                assets.srcDirs("src/main/assets")
            }
        }
    }

    bundle {
        language {
            // We disable language split because FlorisBoard does not use
            // runtime Google Play Service APIs and thus cannot dynamically
            // request to download the language resources for a specific locale.
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // The SenseVoice .onnx model is downloaded at runtime, never bundled, but keep
    // .onnx uncompressed in case a model is ever shipped as an asset.
    // dict_pinyin.dat must be stored uncompressed so the native decoder can mmap it via fd.
    // han.sqlite3 (郑码/嘸蝦米/倉頡/五笔/笔画 shape-code tables) stays uncompressed (Stored)
    // so the SQLite reader can open it directly from the APK without inflating.
    androidResources {
        // mmah.json (handwriting template DB) ships as mmah.hwr and stays uncompressed so it can
        // be read straight from the APK without inflating a ~800KB blob on every keyboard open.
        // (Using a dedicated extension avoids storing every layout .json uncompressed.)
        noCompress += listOf("onnx", "dat", "sqlite3", "hwr")
    }

    // sherpa-onnx AAR bundles several native libs; pick the first match so a single
    // copy of each lands in the APK and there are no duplicate-.so merge failures.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += listOf(
                "**/libonnxruntime.so",
                "**/libsherpa-onnx-jni.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
            )
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile") ?: "wordtaker-release.jks")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug+${getGitCommitHash(short = true).get()}"

            isDebuggable = true
            isJniDebuggable = false
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        named("release") {
            versionNameSuffix = projectVersionNameSuffix

            signingConfig = signingConfigs.getByName("release")

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        create("benchmark") {
            initWith(getByName("release"))

            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench+${getGitCommitHash(short = true).get()}"

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        baseline = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}


// After packaging, copy the APK to KittyEcho-<versionName>-<buildType>.apk in the same output dir.
afterEvaluate {
    listOf("debug", "beta", "release", "benchmark").forEach { buildType ->
        val taskName = "package${buildType.replaceFirstChar { it.uppercase() }}"
        tasks.findByName(taskName)?.doLast {
            val outDir = File(buildDir, "outputs/apk/$buildType")
            outDir.listFiles { f -> f.extension == "apk" }?.forEach { apk ->
                val dest = File(outDir, "KittyEcho-${projectVersionName}-${buildType}.apk")
                if (apk.name != dest.name) apk.renameTo(dest)
            }
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("src/main/config")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()
}

kover {
    useJacoco()
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    // testImplementation(composeBom)
    // androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    // sherpa-onnx (k2-fsa) on-device ASR. Official prebuilt AAR from GitHub releases
    // (no Maven Central artifact exists for com.k2-fsa). The AAR bundles the
    // com.k2fsa.sherpa.onnx Kotlin API classes + JNI .so libs.
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))
    implementation(libs.androidx.window.core)
    implementation(libs.cache4k)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)

    implementation(projects.lib.android)
    implementation(projects.lib.color)
    implementation(projects.lib.compose)
    implementation(projects.lib.kotlin)
    implementation(projects.lib.native)
    implementation(projects.lib.snygg)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json for JVM unit tests (the mockable android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

fun getGitCommitHash(short: Boolean = false): Provider<String> {
    if (!File(".git").exists()) {
        return providers.provider { "null" }
    }

    val execProvider = providers.exec {
        if (short) {
            commandLine("git", "rev-parse", "--short", "HEAD")
        } else {
            commandLine("git", "rev-parse", "HEAD")
        }
    }
    return execProvider.standardOutput.asText.map { it.trim() }
}
