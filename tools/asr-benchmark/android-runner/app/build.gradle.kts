import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlinx.kover")
}

val repositoryRoot = rootProject.projectDir.resolve("../../..").canonicalFile

fun requiredPrivateInput(propertyName: String, expectedName: String): File {
    val value = providers.gradleProperty(propertyName).orNull
        ?: throw GradleException("$propertyName is required")
    val declaredInput = File(value)
    if (!declaredInput.isAbsolute) {
        throw GradleException("$propertyName must be an absolute path")
    }
    val input = declaredInput.absoluteFile.toPath().normalize().toFile()
    val resolvedInput = input.canonicalFile
    if (
        input.name != expectedName ||
        resolvedInput.toPath().startsWith(repositoryRoot.toPath())
    ) {
        throw GradleException(
            "$propertyName must be the canonical repository-external $expectedName",
        )
    }
    return input
}

val modelReceipt = requiredPrivateInput(
    "kittyechoAsrModelReceipt",
    "model.int8.onnx.receipt.json",
)
val tokensReceipt = requiredPrivateInput(
    "kittyechoAsrTokensReceipt",
    "tokens.txt.receipt.json",
)
val runtimeReceipt = requiredPrivateInput(
    "kittyechoAsrRuntimeReceipt",
    "sherpa-onnx-1.13.3.aar.receipt.json",
)
val sherpaRuntimeAar = requiredPrivateInput(
    "kittyechoAsrRuntimeAar",
    "sherpa-onnx-1.13.3.aar",
)

val verifyParaformerBuildInputs = tasks.register<Exec>(
    "verifyParaformerBuildInputs",
) {
    group = "verification"
    description = "Revalidates the three canonical Paraformer receipts"
    workingDir(repositoryRoot)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    environment(
        "PYTHONPATH",
        repositoryRoot.resolve("tools/asr-benchmark/src").absolutePath,
    )
    commandLine(
        "python3",
        "-B",
        rootProject.projectDir
            .resolve("scripts/verify_paraformer_build_inputs.py")
            .absolutePath,
        "--repo-root",
        repositoryRoot.absolutePath,
        "--model-receipt",
        modelReceipt.absolutePath,
        "--tokens-receipt",
        tokensReceipt.absolutePath,
        "--runtime-receipt",
        runtimeReceipt.absolutePath,
        "--runtime-aar",
        sherpaRuntimeAar.absolutePath,
    )
    inputs.files(
        modelReceipt,
        tokensReceipt,
        runtimeReceipt,
        sherpaRuntimeAar,
    )
}

tasks.matching {
    it.name == "preBuild" ||
        it.name.startsWith("compile") ||
        it.name.startsWith("test") ||
        it.name.startsWith("assemble") ||
        it.name.startsWith("package") ||
        it.name.startsWith("kover")
}.configureEach {
    dependsOn(verifyParaformerBuildInputs)
}

android {
    namespace = "com.wordtaker.keyboard.asrbenchmark.runner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wordtaker.keyboard.asrbenchmark.runner"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "phase-b-development"
        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner =
            "com.wordtaker.keyboard.asrbenchmark.runner.SyntheticBenchmarkInstrumentation"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".development"
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files(sherpaRuntimeAar))
    testImplementation("junit:junit:4.13.2")
}
