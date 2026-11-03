import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
}

// CP4-C (REPO_REVIEW §3.7): Firebase google-services/Crashlytics plugins are DISABLED.
// Telemetry is off fork-wide until an Editotsu-owned Firebase project exists.

val gitCommitHash = if (rootProject.file(".git").exists()) {
    try {
        providers.exec {
            commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        "nogit"
    }
} else {
    "nogit"
}

// Version: set once in gradle.properties (editotsu.version); versionCode:
//   1_000_000 + MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + S
//   S = alphaNN 1..19, betaNN 20+NN, rcNN 50+NN, stable 99.
val editotsuVersion = providers.gradleProperty("editotsu.version").get().trim()
val editotsuVersionCode = run {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d{2}))?$""").matchEntire(editotsuVersion)
        ?: error("editotsu.version '$editotsuVersion' must be MAJOR.MINOR.PATCH[-alphaNN|-betaNN|-rcNN]")
    val (major, minor, patch, stage, nn) = m.destructured
    require(minor.toInt() <= 99 && patch.toInt() <= 99) { "MINOR and PATCH must be <= 99" }
    val n = nn.toIntOrNull() ?: 0
    val stageCode = when (stage) {
        "alpha" -> n.also { require(it in 1..19) { "alphaNN must be 01..19" } }
        "beta" -> 20 + n.also { require(it in 1..29) { "betaNN must be 01..29" } }
        "rc" -> 50 + n.also { require(it in 1..48) { "rcNN must be 01..48" } }
        else -> 99
    }
    1_000_000 + major.toInt() * 10_000_000 + minor.toInt() * 100_000 + patch.toInt() * 1_000 + stageCode
}

// Build identity of non-official builds: "<label>.<commit8>[.dirty]", e.g. r22.e1065b6e.
// It goes into BuildConfig.EDITOTSU_BUILD_ID (shown in About) and after "+" in versionName.
// -Peditotsu.buildLabel=<label> marks a test build of release/beta (test-slot builds);
// debug and alpha always carry one ("dev" by default). Official builds pass no label.
val buildLabel = providers.gradleProperty("editotsu.buildLabel").orNull?.trim()?.takeIf { it.isNotEmpty() }
require(buildLabel == null || Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$").matches(buildLabel)) {
    "editotsu.buildLabel may only contain letters, digits, '-' and '_'"
}
val gitDirty = rootProject.file(".git").exists() && try {
    providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no") }
        .standardOutput.asText.get().isNotBlank()
} catch (e: Exception) {
    false
}
fun buildId(label: String) = "$label.$gitCommitHash" + if (gitDirty) ".dirty" else ""
val testBuildId = buildLabel?.let(::buildId) ?: ""
val devBuildId = buildId(buildLabel ?: "dev")
val torrentBench = providers.gradleProperty("editotsu.torrentBench").orNull == "true"

// arm64 libtorrent4j native. Default: the CP1.5-B "c2" build checked in at
// app/libs (built-in hashing, WebTorrent off; ELF build id d54b24f5...), which
// doesn't hit the Scudo abort the Maven 2.1.0-39 native hits when a torrent
// with files on disk is added again (A/B tested on a phone, 2026-11-01).
// Its pinned source + rebuild recipe live in native/libtorrent4j-arm64-c2/.
//   -Peditotsu.libtorrentArm64=maven             use the Maven artifact (A/B)
//   -Peditotsu.libtorrentArm64Jar=/abs/path.jar  another jar; provenance.json and
//                                                verify_artifact.py must sit next to it
// Every jar build runs the verifier in preBuild and fails unless the jar matches
// its recorded SHA-256, ELF build id and DT_NEEDED.
val libtorrentArm64UseMaven = providers.gradleProperty("editotsu.libtorrentArm64").orNull == "maven"
val libtorrentArm64JarOverride: File? = providers.gradleProperty("editotsu.libtorrentArm64Jar")
    .orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
val libtorrentArm64Jar: File? = when {
    libtorrentArm64UseMaven -> null
    libtorrentArm64JarOverride != null -> libtorrentArm64JarOverride
    else -> file("libs/libtorrent4j-android-arm64-2.1.0-39-cp1.5b-c2.jar")
}
if (libtorrentArm64Jar != null) {
    val inRepo = libtorrentArm64JarOverride == null
    val manifest = if (inRepo) rootProject.file("native/libtorrent4j-arm64-c2/provenance.json")
        else File(libtorrentArm64Jar.parentFile, "provenance.json")
    val verifier = if (inRepo) rootProject.file("native/libtorrent4j-arm64-c2/verify_artifact.py")
        else File(libtorrentArm64Jar.parentFile, "verify_artifact.py")
    require(libtorrentArm64Jar.isFile) { "arm64 libtorrent4j jar missing: $libtorrentArm64Jar" }
    require(manifest.isFile && verifier.isFile) {
        "arm64 libtorrent4j jar needs provenance.json and verify_artifact.py: $manifest, $verifier"
    }
    val verifyLibtorrentArm64 by tasks.registering(Exec::class) {
        group = "verification"
        description = "Fails unless the arm64 libtorrent4j jar matches its provenance."
        inputs.file(libtorrentArm64Jar)
        inputs.file(manifest)
        inputs.file(verifier)
        commandLine(
            "python3", "-I", verifier.absolutePath,
            "--manifest", manifest.absolutePath,
            "--jar", libtorrentArm64Jar.absolutePath
        )
    }
    tasks.configureEach {
        if (name == "preBuild") dependsOn(verifyLibtorrentArm64)
    }
}
val libtorrentNativeLabel = libtorrentArm64Jar?.nameWithoutExtension ?: "maven"

// libmpv: Maven mpv-android-lib by default. -Peditotsu.libmpvAar=/abs/path.aar swaps in a
// local build of the same lib with Vulkan (libplacebo vulkan backend, needed for compute
// shaders such as CuNNy; recipe: native/libmpv-vulkan). Its directory must hold
// provenance.json next to the aar. LIBMPV_VULKAN tells the player it may use gpu-api=vulkan.
val libmpvAar: File? = providers.gradleProperty("editotsu.libmpvAar").orNull?.let { File(it) }
if (libmpvAar != null) {
    require(libmpvAar.isFile) { "editotsu.libmpvAar not found: $libmpvAar" }
    require(File(libmpvAar.parentFile, "provenance.json").isFile) {
        "editotsu.libmpvAar needs provenance.json next to it: ${libmpvAar.parentFile}"
    }
}
val libmpvLabel = libmpvAar?.nameWithoutExtension ?: "maven"

val malClientId: String = (project.findProperty("malClientId") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("MAL_CLIENT_ID")?.takeIf { it.isNotBlank() }
    ?: run {
        val userHome = System.getProperty("user.home")
        val credFile = File(userHome, ".editotsu/credentials/mal.properties")
        if (credFile.exists()) {
            val props = Properties()
            credFile.inputStream().use { stream -> props.load(stream) }
            props.getProperty("mal.clientId")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
    ?: ""

android {
    namespace = "ani.dantotsu"
    compileSdk = 37

    defaultConfig {
        applicationId = "ani.editotsu"
        minSdk = 26
        targetSdk = 36

        versionName = editotsuVersion
        versionCode = editotsuVersionCode

        buildConfigField("String", "MAL_CLIENT_ID", "\"${malClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "EDITOTSU_BUILD_ID", "\"\"")
        resValue("string", "app_build_label", "Editotsu")
        // Torrent bench hook (task 7 rig): off unless -Peditotsu.torrentBench=true.
        buildConfigField("boolean", "TORRENT_BENCH", torrentBench.toString())
        buildConfigField("String", "LIBTORRENT_ARM64", "\"$libtorrentNativeLabel\"")
        buildConfigField("boolean", "LIBMPV_VULKAN", (libmpvAar != null).toString())
        buildConfigField("String", "LIBMPV", "\"$libmpvLabel\"")
    }

    // ------------------------------------------------------------------
    // CP4-A (REPO_REVIEW §3.5): stable fork-owned release signing.
    //
    // Keystore material is read from (first match wins):
    //   1. Gradle properties:  EDITOTSU_STORE_FILE / _STORE_PASSWORD /
    //      _KEY_ALIAS / _KEY_PASSWORD   (-P on CI, injected by beta.yml)
    //   2. Environment variables of the same names
    //   3. A local `keystore.properties` file in the repo root (git-ignored)
    //
    // When material IS present, alpha/release builds are signed with the
    // stable fork key. When it is ABSENT, builds fall back to an UNSIGNED
    // config and emit a loud warning — we NEVER silently sign releases with
    // the random debug keystore again.
    // ------------------------------------------------------------------
    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    fun editotsuSigningProp(name: String): String? {
        return (project.findProperty(name) as String?)
            ?: System.getenv(name)
            ?: keystoreProperties.getProperty(name)
    }

    val ksFile = editotsuSigningProp("EDITOTSU_STORE_FILE")
    val ksPass = editotsuSigningProp("EDITOTSU_STORE_PASSWORD")
    val aliasName = editotsuSigningProp("EDITOTSU_KEY_ALIAS")
    val keyPass = editotsuSigningProp("EDITOTSU_KEY_PASSWORD")

    // CP4v1-03: ALL FOUR fields are required for stable signing — a partial configuration
    // (e.g. missing alias/key password) must never be classified as "keystore available".
    val providedSigningFields = listOf(
        "EDITOTSU_STORE_FILE" to ksFile,
        "EDITOTSU_STORE_PASSWORD" to ksPass,
        "EDITOTSU_KEY_ALIAS" to aliasName,
        "EDITOTSU_KEY_PASSWORD" to keyPass
    )
    val haveReleaseKeystore = providedSigningFields.all { !it.second.isNullOrBlank() }

    // CP4v1-03: signing policy must NOT fail global configuration — a clean checkout stays
    // usable for help/tests/lint/debug/fdroid work without release credentials. The hard
    // failure happens ONLY when a publishable variant is actually PACKAGED (AGP refuses a
    // SigningConfig without storeFile), which keeps the tagged-CI gate intact.
    val devDebugSigningRequested =
        (project.findProperty("editotsu.devSigning") as String?) == "debug"

    signingConfigs {
        if (haveReleaseKeystore) {
            create("editotsuRelease") {
                storeFile = rootProject.file(ksFile!!)
                storePassword = ksPass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        } else {
            // Deliberately INCOMPLETE placeholder: assigning it makes packaging of publishable
            // variants fail deterministically ("missing required property storeFile") while
            // everything else keeps working.
            create("editotsu_RELEASE_KEYSTORE_MISSING") {}
        }
    }


    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    flavorDimensions += "store"

    productFlavors {
        // No versionNameSuffix on either flavor; the flavor distinction lives
        // in BuildConfig.FLAVOR, packaged features, and asset filenames.
        // The fdroid flavor is our internal degoogled build (built and
        // distributed by us, not official F-Droid infrastructure).
        create("fdroid") {
            dimension = "store"
        }
        create("google") {
            dimension = "store"
            isDefault = true
        }
    }

    val releaseSigningConfig = when {
        haveReleaseKeystore -> signingConfigs.getByName("editotsuRelease")
        devDebugSigningRequested -> {
            logger.warn(
                "EDITOTSU SIGNING: EXPLICIT dev-only fallback to the DEBUG keystore " +
                    "(-Peditotsu.devSigning=debug). These artifacts are NOT publishable."
            )
            signingConfigs.getByName("debug")
        }
        else -> {
            val missing = providedSigningFields.filter { it.second.isNullOrBlank() }.map { it.first }
            logger.error(
                "EDITOTSU SIGNING: release keystore material incomplete (missing: " +
                    missing.joinToString(", ") + "). Packaging alpha/release will FAIL.\n" +
                    "Provide all four EDITOTSU_* properties/env vars or a git-ignored keystore.properties. " +
                    "For THROWAWAY local dev builds only: -Peditotsu.devSigning=debug."
            )
            signingConfigs.getByName("editotsu_RELEASE_KEYSTORE_MISSING")
        }
    }

    buildTypes {
        create("alpha") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "+$devBuildId"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$devBuildId\"")
            resValue("string", "app_build_label", "Editotsu α")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher_alpha"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_alpha_round"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            isDefault = true
            signingConfig = releaseSigningConfig
        }

        // Public pre-releases: release-quality build with the beta application
        // identity (ani.editotsu.beta), so betas coexist with stable Editotsu.
        // Official beta builds pass no build label: plain versionName, no build ID.
        create("beta") {
            applicationIdSuffix = ".beta"
            if (testBuildId.isNotEmpty()) versionNameSuffix = "+$testBuildId"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$testBuildId\"")
            // Alpha versions (x.y.z-alphaNN) ship through this build type too: same
            // package, so they update betas in place, but with the alpha name and icon.
            val alphaVersion = editotsuVersion.contains("-alpha")
            resValue("string", "app_build_label", if (alphaVersion) "Editotsu α" else "Editotsu β")
            val channelIcon = if (alphaVersion) "ic_launcher_alpha" else "ic_launcher_beta"
            manifestPlaceholders["icon_placeholder"] = "@mipmap/$channelIcon"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/${channelIcon}_round"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("debug") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "+$devBuildId"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$devBuildId\"")
            resValue("string", "app_build_label", "Editotsu β")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher_beta"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_beta_round"
            isDebuggable = false
        }

        getByName("release") {
            // Stable package: no build ID unless -Peditotsu.buildLabel marks a test build.
            if (testBuildId.isNotEmpty()) versionNameSuffix = "+$testBuildId"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$testBuildId\"")
            resValue("string", "app_build_label", "Editotsu")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_round"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
        aidl = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("**/libavcodec.so")
            pickFirsts.add("**/libavdevice.so")
            pickFirsts.add("**/libavfilter.so")
            pickFirsts.add("**/libavformat.so")
            pickFirsts.add("**/libavutil.so")
            pickFirsts.add("**/libswresample.so")
            pickFirsts.add("**/libswscale.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-XXLanguage:+ContextParameters",
            "-Xmulti-platform"
        )
    }
}

// Upstream 3f366f69: exclude desktop org.json so R8 compiles strictly against
// Android platform definitions (prevents NoSuchFieldError: myArrayList on device).
configurations.all {
    exclude(group = "org.json", module = "json")
}

dependencies {
    // MPV Native Player (see editotsu.libmpvAar above)
    if (libmpvAar != null) implementation(files(libmpvAar)) else implementation(libs.mpv)

    // ffmpeg-kit
    implementation(libs.ffmpeg.kit)

    // Firebase

    // AndroidX
    implementation(libs.bundles.androidx)
    implementation(libs.androidx.webkit)

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)

    // Core libs
    implementation(libs.bundles.misc)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // Glide
    implementation(libs.bundles.glide)
    ksp(libs.glide.ksp)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.bundles.media3)
    implementation(libs.mediarouter)
    implementation(libs.androidx.compose.runtime)


    // UI
    implementation(libs.material)
    implementation(files("libs/AnimatedBottomBar-7fcb9af.aar"))
    implementation(libs.flexbox)
    implementation(libs.kenburns)
    implementation(libs.subsampling)
    implementation(libs.gesture)
    // Readium Kotlin Toolkit (MAX MERGE M4A, upstream bb7563ad)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.navigator.media.tts)
    implementation(libs.readium.adapter.pdfium.document)
    implementation(libs.readium.adapter.pdfium.navigator)
    implementation(libs.image.decoder)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text)
    implementation(libs.dialogs)
    implementation(libs.charts)

    implementation(libs.bundles.markwon)
    implementation(libs.bundles.groupie)
    implementation(libs.bundles.rx)
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)

    // Archive support (local source)
    implementation(libs.libarchive)
    implementation(libs.xmlutil.core)
    implementation(libs.xmlutil.serialization)

    // libtorrent
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm)
    if (libtorrentArm64Jar != null) {
        implementation(files(libtorrentArm64Jar))
    } else {
        implementation(libs.libtorrent4j.android.arm64)
    }
    implementation(libs.libtorrent4j.android.x86)
    implementation(libs.libtorrent4j.android.x86.x64)

    testImplementation(libs.junit)
}
