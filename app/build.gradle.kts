// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Live tests call a real provider over the network. They are excluded from the default
// device-test task so that `connectedDebugAndroidTest` is hermetic and can be green offline.
// Run them explicitly with:
//   ./gradlew :app:connectedDebugAndroidTest -PliveTests
// Credentials come from local.properties (git-ignored), never from source.
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { stream -> props.load(stream) }
}
val liveApiKey: String = localProps.getProperty("API_KEY")?.trim().orEmpty()
val liveEndpoint: String = localProps.getProperty("Endpoint")?.trim()
    ?: "https://api.mistral.ai/v1/chat/completions"
val liveModel: String = localProps.getProperty("Model")?.trim() ?: "mistral-small-latest"

android {
    namespace = "com.anchor.mine"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.anchor.mine"
        minSdk = 34
        targetSdk = 35
        versionCode = 10000 // per D-084: MAJOR*10000 + MINOR*100 + PATCH
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFile("proguard-rules.pro")

        if (!project.hasProperty("liveTests")) {
            testInstrumentationRunnerArguments["notAnnotation"] = "com.anchor.mine.smoke.LiveTest"
        }
    }

    buildTypes {
        // H1: LIVE_* credentials exist in DEBUG ONLY. Declaring them in defaultConfig put the
        // key in every variant's BuildConfig, including release. R8 happened to strip the
        // unreferenced field from the minified APK, but that is an accident of minification:
        // any release-reachable reference to BuildConfig.LIVE_API_KEY would have shipped the
        // key in cleartext (proven by experiment, see docs/agent/work/). The only reader is
        // LiveSmoke15Test, which is androidTest-scoped and runs against the debug variant.
        debug {
            buildConfigField("String", "LIVE_API_KEY", "\"${liveApiKey.replace("\"", "\\\"")}\"")
            buildConfigField("String", "LIVE_ENDPOINT", "\"${liveEndpoint.replace("\"", "\\\"")}\"")
            buildConfigField("String", "LIVE_MODEL", "\"${liveModel.replace("\"", "\\\"")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources {
            pickFirsts += listOf("com/k2fsa/sherpa/onnx/BuildConfig.class")
        }
    }
}

// D-123: `connectedDebugAndroidTest`'s own exit code is not trustworthy on AGP 9.4.0 / UTP 32.4.0
// (it reports failure for green runs and sometimes writes no exit-code file at all; see
// docs/agent/work/connected-red-build-rootcause.md). This task reads UTP's result artifacts
// instead and is the authoritative gate. It is NOT wired into `check`: a red connected run would
// then fail every offline build, and the AGP task still aborts the run before this one executes.
// Run it after any device test:
//   ./gradlew :app:connectedDebugAndroidTestVerify
//   ./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
val connectedResultsDir = layout.buildDirectory.dir("outputs/androidTest-results/connected/debug")
val connectedGateReport = layout.buildDirectory.file("reports/connected-gate/gate.json")

tasks.register("connectedDebugAndroidTestVerify") {
    group = "verification"
    description = "D-123: fail on real connected-test failures, reading UTP's XML/proto instead of its exit code."
    val resultsDir = connectedResultsDir
    val reportFile = connectedGateReport
    val gateScript = rootProject.layout.projectDirectory.file("gradle/connected-results-gate.py")
    // Resolved at configuration time so the task body captures no project/script references
    // (the configuration cache rejects those).
    val python = pythonExecutable()
    // The results directory is this task's INPUT. Without it Gradle sees a task with no inputs
    // and a single output file, so it is UP-TO-DATE forever after the first run: a later run
    // that leaves a failing TEST-*.xml on disk still reports the stale PASS (observed
    // 2026-11-07: UP-TO-DATE / BUILD SUCCESSFUL / exit 0 while the same script run by hand on
    // the same directory reported FAIL - 5 test(s), 1 failed). Declaring the input makes any
    // change to the results re-run the gate.
    inputs.dir(resultsDir).withPropertyName("connectedResults")
    outputs.file(reportFile)
    // G1 added inputs.dir(); the results are written by the connected task, so Gradle now requires
    // an explicit ordering. mustRunAfter (not dependsOn) keeps the documented `--continue` form
    // working: a red connected run must still let this gate read and report its results.
    mustRunAfter("connectedDebugAndroidTest")
    doLast {
        val dir = resultsDir.get().asFile
        val report = reportFile.get().asFile
        val script = gateScript.asFile
        if (!script.exists()) throw GradleException("D-123 gate script missing: $script")
        val command = listOf(python, script.absolutePath, dir.absolutePath, report.absolutePath)
        // ProcessBuilder, not providers.exec: an exec inside the action would capture the build
        // script and the configuration cache rejects that. The child's output is echoed to the
        // build log so the verdict is visible, not just the exit code.
        val exit = try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line -> logger.lifecycle(line) }
            }
            process.waitFor()
        } catch (e: Exception) {
            throw GradleException("D-123 gate could not run ${command.joinToString(" ")}: ${e.message}", e)
        }
        if (exit != 0) {
            val detail = if (report.exists()) report.readText() else "(no report written)"
            throw GradleException("Connected-test gate FAILED (exit $exit). Report: $report\n$detail")
        }
    }
}

// G2: the gate's failure-message path, pinned by fixtures. The gate is the only artifact a reader
// has after a red device run, so "FAILED <name>" with no assertion text made every failure
// undiagnosable. These fixtures build XML/proto result dirs and assert the message reaches both
// stdout and gate.json on the xml route, the proto route (D-123 fallback) and the empty-body case.
// Wired into `check` because a gate nobody tests is how the message got dropped in the first place.
tasks.register<Exec>("connectedGateFixtures") {
    group = "verification"
    description = "G2: assert connected-test failure messages survive into the gate's output."
    val fixtures = rootProject.layout.projectDirectory.file("gradle/test_connected_results_gate.py")
    commandLine(pythonExecutable(), fixtures.asFile.absolutePath)
}

tasks.named("check") { dependsOn("connectedGateFixtures") }

// Picks an interpreter that exists; CI images and Windows both differ. Fails loudly rather than
// silently skipping the gate, because a gate that cannot run must not look green.
fun pythonExecutable(): String {
    val candidates = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        listOf("python", "py", "python3")
    } else {
        listOf("python3", "python")
    }
    for (candidate in candidates) {
        val probe = providers.exec {
            commandLine(candidate, "-c", "import sys")
            isIgnoreExitValue = true
        }
        if (probe.result.get().exitValue == 0) return candidate
    }
    throw GradleException("D-123 gate needs Python 3 on PATH (tried ${candidates.joinToString()})")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Modern SQLCipher + AndroidX SQLite
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Bouncy Castle for Argon2id Export Encryption
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Sherpa-ONNX for Offline Speech (VAD + Zipformer STT + Kokoro TTS)
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    // LiteRT-LM for On-Device EmbeddingGemma 2
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2") // THIS FIXES THE JUNIT ERRORS
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.tracing:tracing:1.2.0")
    // O-2: ProviderScreen's field state is UI-local, so only a Compose test can prove a save
    // does not concatenate onto the previous value.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    debugImplementation("androidx.compose.ui:ui-tooling")
}