import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

// --- READ API KEY FROM local.properties ---
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val nvidiaApiKey = localProperties.getProperty("NVIDIA_API_KEY") ?: "\"\""

android {
    namespace = "com.mine.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    sourceSets {
        getByName("main") {
            java {
                srcDir("build/generated/source/proto/debug/java")
                srcDir("build/generated/source/proto/debug/grpc")
                srcDir("build/generated/java/generateDebugProto/java")
                srcDir("build/generated/java/generateDebugProto/grpc")
            }
        }
    }
    defaultConfig {
        applicationId = "com.mine.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- INJECT KEY INTO APP ---
        buildConfigField("String", "NVIDIA_API_KEY", nvidiaApiKey)
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // --- ENABLE BUILD CONFIG ---
    }
    androidResources {
        noCompress.add("onnx")
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
        create("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
                create("grpckt")
            }
            it.builtins {
                create("java")
            }
        }
    }
}

// NEW AGP 9.0+ WAY TO CONFIGURE KOTLIN
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.addAll("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // Core & Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.common)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room & SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.security.crypto)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // gRPC
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin)
    implementation(libs.proto.google.common.protos)
    compileOnly(libs.tomcat.annotations.api)

    implementation(files("libs/sherpa-onnx-1.13.3.aar"))
}

ksp {
    arg("room.generateKotlin", "true")
}
