import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android plugin: AGP 9.0+ ships built-in Kotlin support and fails the build
    // if the standalone plugin is also applied.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Only arm64 is built. The dev machine is Apple Silicon, so the emulator system images and
 * any real test device share the `arm64-v8a` ABI — a second Rust target would be dead weight.
 * Add `x86_64` here (and via `rustup target add`) if an Intel host or x86 emulator appears.
 */
val supportedAbis = listOf("arm64-v8a")

val ndkVersionUsed = "27.3.13750724"

android {
    namespace = "io.omnishield"

    // compileSdk is deliberately ahead of targetSdk. Current AndroidX artifacts refuse to
    // compile against anything older, but targetSdk governs runtime behaviour and stays at
    // 34 per plan — the two are independent knobs.
    compileSdk = 37
    ndkVersion = ndkVersionUsed

    defaultConfig {
        applicationId = "io.omnishield"
        // 29 is a hard floor: VpnService.getConnectionOwnerUid (Phase 6) does not exist
        // below Android 10, and /proc/net scraping was blocked in the same release.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += supportedAbis }
    }

    buildTypes {
        release {
            // R8 was configured but switched off, which made the proguardFiles below inert —
            // the keep rules for the JNI seam existed but were never exercised. It is on now,
            // and the rules matter: breaking either of the first two couplings in CLAUDE.md
            // produces no build error, only a tunnel that silently does nothing.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key on purpose. This app is sideload-only (Play policy
            // forbids blocking ads in other apps), and an unsigned release APK cannot be
            // installed — which would leave R8's output untestable on a real device, i.e.
            // exactly the situation that lets a broken keep rule ship unnoticed.
            signingConfig = signingConfigs.getByName("debug")
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
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            // The Rust .so must stay uncompressed and page-aligned so it can be mapped
            // directly rather than extracted at install time.
            useLegacyPackaging = false
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// ---------------------------------------------------------------------------
// Rust core (../core) — built via cargo-ndk and dropped straight into jniLibs.
// ---------------------------------------------------------------------------

/**
 * Resolved from local.properties/env rather than from the AGP DSL: `android.ndkDirectory`
 * was removed in AGP 9, and deriving the path here keeps this task independent of AGP
 * internals that keep moving.
 */
val androidSdkDir: String = run {
    val local = rootProject.file("local.properties")
    val fromProps = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        null
    }
    fromProps
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException(
            "Android SDK not found — set sdk.dir in local.properties or ANDROID_HOME"
        )
}

/**
 * Gradle is usually launched from an environment with neither rustup's shims nor
 * `~/.cargo/bin` on PATH, so both are resolved explicitly. `cargo` itself comes from the
 * rustup shim directory, while the `cargo-ndk` subcommand binary lives in `~/.cargo/bin` —
 * both directories must be on the child PATH even though only `cargo` is invoked directly.
 */
val rustToolDirs: List<File> = listOf(
    File(System.getProperty("user.home"), ".cargo/bin"),
    File("/opt/homebrew/opt/rustup/bin"),
    File("/opt/homebrew/bin"),
    File("/usr/local/bin"),
).filter { it.isDirectory }

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Compile omnishield_core for ${supportedAbis.joinToString()} via cargo-ndk"

    val coreDir = rootProject.file("core")
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile

    workingDir = coreDir

    val cargoExe = rustToolDirs.map { File(it, "cargo") }.firstOrNull { it.canExecute() }
        ?: throw GradleException(
            "cargo not found in ${rustToolDirs.joinToString()} — install rustup, then " +
                "`cargo install cargo-ndk`"
        )

    environment(
        "PATH",
        (rustToolDirs.map { it.absolutePath } + listOfNotNull(System.getenv("PATH")))
            .joinToString(":"),
    )
    environment("ANDROID_NDK_HOME", File(androidSdkDir, "ndk/$ndkVersionUsed").absolutePath)

    commandLine(
        buildList {
            add(cargoExe.absolutePath)
            add("ndk")
            supportedAbis.forEach { add("-t"); add(it) }
            // -P is the API level. cargo-ndk 4.x repurposed lowercase -p to --package, where
            // it fails with "unknown package: 29". Matches minSdk.
            add("-P"); add("29")
            add("-o"); add(jniLibsDir.absolutePath)
            add("build")
            add("--release")
        }
    )

    inputs.dir(File(coreDir, "src"))
    inputs.file(File(coreDir, "Cargo.toml"))
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

val cleanRust = tasks.register<Delete>("cleanRust") {
    delete(rootProject.file("core/target"), layout.projectDirectory.dir("src/main/jniLibs"))
}
tasks.named("clean") { dependsOn(cleanRust) }

// Room schemas are exported so migration tests have a previous version to migrate *from*.
// Without this a schema change is only discovered when a user's database fails to open.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
