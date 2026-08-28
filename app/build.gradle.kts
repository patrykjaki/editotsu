plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
}

if (gradle.startParameter.taskNames.any { it.contains("google", true) }) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

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

android {
    namespace = "ani.dantotsu"
    compileSdk = 37

    defaultConfig {
        applicationId = "ani.editotsu"
        minSdk = 26
        targetSdk = 36

        versionName = "0.2.1"
        versionCode = 1000002

        buildConfigField("String", "EDITOTSU_BUILD_ID", "\"\"")
        resValue("string", "app_build_label", "Editotsu")

        signingConfig = signingConfigs.getByName("debug")
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
        create("fdroid") {
            dimension = "store"
            versionNameSuffix = "-fdroid"
        }
        create("google") {
            dimension = "store"
            isDefault = true
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
        }

        getByName("debug") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta01"
            buildConfigField("String", "EDITOTSU_BUILD_ID", "\"$workstreamId\"")
            resValue("string", "app_build_label", "Editotsu [$shortWorkstreamId]")
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher_beta"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_beta_round"
            isDebuggable = false
        }

        getByName("release") {
            manifestPlaceholders["icon_placeholder"] = "@mipmap/ic_launcher"
            manifestPlaceholders["icon_placeholder_round"] = "@mipmap/ic_launcher_round"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
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
    add("googleImplementation", platform(libs.firebase.bom))
    add("googleImplementation", libs.bundles.firebase)

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
