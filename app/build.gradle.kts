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

// Workstream build identity (modernized port of infra/build-identity).
// Explicit -Peditotsu.workstreamId=<workstream>-<rev>@<base8>+<diff8>
// marks development builds; official beta/release builds leave it blank.
// The require() fails configuration fast on malformed IDs (never silently).
val workstreamId = providers.gradleProperty("editotsu.workstreamId")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "local-debug"
val workstreamIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*-[A-Za-z0-9][A-Za-z0-9._-]*@[0-9a-fA-F]{8}\\+[0-9a-fA-F]{8}$")
require(workstreamId == "local-debug" || workstreamIdPattern.matches(workstreamId)) {
    "editotsu.workstreamId must match <workstream>-<revision>@<base8>+<diff8>"
}
val shortWorkstreamId = workstreamId.substringBefore("@")

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

        versionName = "0.5.0-beta01"
        versionCode = 1000510

        buildConfigField("String", "MAL_CLIENT_ID", "\"${malClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        // Workstream identity: blank in defaultConfig; alpha/debug expose
        // the explicit workstream ID, official beta/release stay blank.
        buildConfigField("String", "EDITOTSU_BUILD_ID", "\"\"")
        resValue("string", "app_build_label", "Editotsu")
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
        // 0.5 release: NO versionNameSuffix on either flavor. Both release
        // APKs badge versionName 0.5.0-beta01; the flavor distinction lives
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
            versionNameSuffix = "-alpha01-$gitCommitHash"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$workstreamId\"")
            resValue("string", "app_build_label", "Editotsu [$shortWorkstreamId]")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher_alpha"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_alpha_round"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            isDefault = true
            signingConfig = releaseSigningConfig
        }

        // 0.5 prerelease line: release-quality behavior with the beta
        // application identity (ani.editotsu.beta), so betas coexist with
        // stable Editotsu. Chosen over suffixing `release` (which would
        // corrupt future stable semantics) and over `alpha` (debuggable,
        // unoptimized). Stable `release` is untouched for 0.5.0+.
        // No versionNameSuffix: inherits the exact public version.
        // Official beta: blank build ID, normal label (provenance via
        // version/package/signer, never a workstream tag).
        create("beta") {
            applicationIdSuffix = ".beta"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"\"")
            resValue("string", "app_build_label", "Editotsu")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher_beta"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_beta_round"
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
            // Friend-test betaNN suffixes retired for the 0.5 release line:
            // dev builds carry the commit hash so no betaNN identity can
            // leak into the public release.
            versionNameSuffix = "-dev-$gitCommitHash"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$workstreamId\"")
            resValue("string", "app_build_label", "Editotsu [$shortWorkstreamId]")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher_beta"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_beta_round"
            isDebuggable = false
        }

        getByName("release") {
            // Official stable: blank build ID, normal label.
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"\"")
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

dependencies {
    // MPV Native Player
    implementation(libs.mpv)

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

    implementation(libs.bundles.media3)
    implementation(libs.mediarouter)


    // UI
    implementation(libs.material)
    implementation(files("libs/AnimatedBottomBar-7fcb9af.aar"))
    implementation(libs.flexbox)
    implementation(libs.kenburns)
    implementation(libs.subsampling)
    implementation(libs.gesture)
    implementation(libs.ebook)
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
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.x86)
    implementation(libs.libtorrent4j.android.x86.x64)

    testImplementation(libs.junit)
}
