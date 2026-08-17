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
 * The ABIs the Rust core is cross-compiled for, and the single source of truth for the APK
 * splits below. `arm64-v8a` covers every current phone; `x86_64` covers emulators, which is
 * what lets the instrumented suite run on CI's Linux runners, and x86 Chromebooks.
 *
 * Adding one here also needs `rustup target add <triple>` — cargo-ndk maps the Android ABI
 * name to the triple itself.
 */
val supportedAbis = listOf("arm64-v8a", "x86_64")

val ndkVersionUsed = "27.3.13750724"

/**
 * Release identity, overridable from the environment so the release workflow can derive both
 * from the git tag. The defaults are what a local build gets, so nothing about the documented
 * developer flow changes.
 */
val appVersionName: String = System.getenv("OMNISHIELD_VERSION_NAME") ?: "0.2.0"
val appVersionCode: Int = System.getenv("OMNISHIELD_VERSION_CODE")?.toIntOrNull() ?: 2

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
        versionCode = appVersionCode
        versionName = appVersionName

        ndk { abiFilters += supportedAbis }
    }

    /**
     * One APK per ABI plus a universal one. The Rust core is 5.5 MB per ABI, so a single
     * combined APK would make every user download a `.so` their device cannot execute. The
     * universal build stays because sideloading means there is no store to pick for you, and
     * "which one do I want" is a question a download page should be able to dodge.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        /**
         * Populated from the environment by the release workflow. Absent locally, and the
         * release build type falls back to the debug key when it is — see the comment there.
         */
        create("release") {
            val keystore = System.getenv("OMNISHIELD_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("OMNISHIELD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OMNISHIELD_KEY_ALIAS")
                keyPassword = System.getenv("OMNISHIELD_KEY_PASSWORD")
            }
        }
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
            // Published builds are signed with the release key from the environment. Without
            // it, fall back to the debug key rather than producing an unsigned APK: an
            // unsigned release cannot be installed, which would leave R8's output untestable
            // on a device — exactly the situation that lets a broken keep rule ship unnoticed.
            //
            // The fallback is a developer convenience only. A debug-signed APK must never be
            // published: the debug keystore is generated per machine, so two of them are
            // different apps to Android and neither can upgrade the other.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // Lint is not decoration here. It is what caught `BigInteger.TWO` being API 33 against
        // a minSdk of 29, which every unit test missed because they run on a desktop JVM where
        // the field exists. Without this block it only runs as lintVital during a release
        // build, so a debug-only workflow never sees it.
        abortOnError = true
        checkDependencies = true
        sarifReport = true
        warningsAsErrors = false

        // False positive: x86_64 *is* built, but it reaches the ABI list through
        // `supportedAbis` and lint reads this file statically, so it never sees the literal.
        // Keeping one source of truth for the ABI set is worth more than the check, which
        // would only ever fire on the two lines that already derive from it.
        disable += "ChromeOsAbiSupport"
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
 *
 * The Homebrew entries are where a macOS rustup install puts its shims. PATH is appended last
 * so a Linux or Windows contributor with a plain rustup install is found too, rather than
 * hitting the "cargo not found" failure below with a working toolchain installed.
 */
val rustToolDirs: List<File> = buildList {
    add(File(System.getProperty("user.home"), ".cargo/bin"))
    add(File("/opt/homebrew/opt/rustup/bin"))
    add(File("/opt/homebrew/bin"))
    add(File("/usr/local/bin"))
    System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(File(it)) }
}.filter { it.isDirectory }.distinct()

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Compile omnishield_core for ${supportedAbis.joinToString()} via cargo-ndk"

    val coreDir = rootProject.file("core")
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile

    workingDir = coreDir

    val cargoExe = rustToolDirs.map { File(it, "cargo") }.firstOrNull { it.canExecute() }
        ?: throw GradleException(
            "cargo not found on PATH or in ~/.cargo/bin — install rustup, then " +
                "`cargo install cargo-ndk` (see docs/development.md)"
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
    inputs.file(File(coreDir, "Cargo.lock"))
    inputs.file(File(coreDir, "rust-toolchain.toml"))
    // A changed ABI list has to rebuild, or jniLibs keeps whatever the last list produced.
    inputs.property("abis", supportedAbis)
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
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.foundation)
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
