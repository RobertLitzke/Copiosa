import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The sherpa-onnx Android AAR (~57 MB, bundled native onnxruntime) is not published to a Maven repo,
// so it's vendored in app/libs and excluded from git. This task fetches it on a fresh checkout; if the
// file already exists it does nothing. Wired into preBuild so a clean clone builds without manual steps.
val sherpaOnnxVersion = "1.13.3"
val downloadSherpaOnnxAar by tasks.registering {
    val aar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile
    outputs.file(aar)
    doLast {
        if (aar.exists() && aar.length() > 0) return@doLast
        aar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        logger.lifecycle("Downloading sherpa-onnx AAR: $url")
        URI(url).toURL().openStream().use { input ->
            aar.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadSherpaOnnxAar) }

android {
    namespace = "com.copiosa"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.copiosa"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.mlkit.translate)
    implementation(libs.readability4j)
    implementation(libs.fsrs)

    // On-device TTS. The prebuilt AAR (native onnxruntime + Supertonic support) is vendored in
    // app/libs and fetched by the downloadSherpaOnnxAar task below; it is not committed to git.
    implementation(files("libs/sherpa-onnx-$sherpaOnnxVersion.aar"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.reorderable)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    // JVM twin of android.icu for NumberSpeller tests (same CLDR spell-out rules).
    testImplementation(libs.icu4j)
}
