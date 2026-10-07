import java.net.URI
import java.util.Properties

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

// Room writes the schema of each version it builds here, and the file is committed. The migrations
// are hand-written SQL, so this is what a later change is diffed against to see what Room now
// expects of a table, index or view — the thing a migration must reproduce character for character.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Who to contact about this build's traffic, sent in the User-Agent Wikimedia asks every client
// for (CommonsApi). A personal address belongs to whoever built the APK, not to the repository, so
// it is read from the gitignored local.properties (`copiosa.contact=you@example.com`) and falls
// back to the project page: a fresh clone builds with nothing to set up. tools/cardtool/commons.py
// reads the same key.
val contact: String = Properties()
    .apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
    .getProperty("copiosa.contact")?.trim()?.takeIf { it.isNotEmpty() }
    ?: "https://github.com/RobertLitzke/Copiosa"

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

        buildConfigField("String", "CONTACT", "\"${contact.replace("\\", "").replace("\"", "")}\"")
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

    testOptions {
        unitTests {
            // Stub android.util.Log and friends instead of throwing, so JVM tests can cover code
            // that logs (e.g. MaintenanceRunner's per-task failure isolation).
            isReturnDefaultValues = true

            // Gradle does not forward -D from the command line into the forked test JVM, so the
            // authored-text dump (CourseTextDumpTest) would have no way to be told what to write
            // where. Everything else ignores these, and with courseDump unset the dump is a no-op.
            // `migrationDb` is the same arrangement for Migration42To43DryRunTest, which replays the
            // notes/cards split over a pulled copy of a real database.
            all { test ->
                listOf("courseDump", "courseId", "courseLang", "migrationDb").forEach { name ->
                    test.systemProperty(name, System.getProperty(name).orEmpty())
                }
            }
        }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
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

    // Background maintenance tasks; hilt-work supplies the @HiltWorker factory.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Book reader: paged paragraph rows so a whole novel is never resident at once.
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.mlkit.translate)
    implementation(libs.readability4j)
    // Declared explicitly even though readability4j drags it in: EpubParser and ArticleExtractor
    // both import org.jsoup directly, and a first-party compile dependency satisfied only
    // transitively moves whenever that library does. Newer than the 1.11.2 readability4j 1.0.8
    // asks for, deliberately: it parses untrusted markup. See libs.versions.toml.
    implementation(libs.jsoup)
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
