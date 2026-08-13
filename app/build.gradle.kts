import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read version from properties or environment
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull()
val versionProps = Properties()
versionProps.load(rootProject.file("version.properties").inputStream())
val versionMajor = System.getenv("VERSION_MAJOR")?.toIntOrNull()
    ?: (versionProps["VERSION_MAJOR"] as String).toInt()
val versionMinor = System.getenv("VERSION_MINOR")?.toIntOrNull()
    ?: (versionProps["VERSION_MINOR"] as String).toInt()
val versionPatch = buildNumber
    ?: (versionProps["VERSION_PATCH"] as String).toInt()

// Base versionCode = MAJOR*10000 + MINOR*100 + PATCH.
// Per-ABI split APKs get base*10 + abiCode, which is what F-Droid's
// `VercodeOperation: 10 * %c + N` reproduces. The two must agree exactly or
// F-Droid will look for a versionCode our APKs do not carry.
val baseVersionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch

// Guard the scheme: MINOR or PATCH reaching 100 would collide with the next
// place value and could emit a versionCode lower than an already-published one,
// which Android refuses to install over.
require(versionMinor < 100 && versionPatch < 100) {
    "versionCode scheme collision: MINOR ($versionMinor) and PATCH ($versionPatch) " +
        "must stay below 100 — see the versionCode formula in app/build.gradle.kts"
}

/** ABI -> versionCode suffix. Must match VercodeOperation in the F-Droid recipe. */
val abiVersionCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86_64" to 3,
)

/** ABIs shipped in a normal release build. */
val RELEASE_ABIS = arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")

/**
 * Build a single ABI (`-PabiFilter=arm64-v8a`), used by F-Droid.
 * Validated eagerly so a typo fails the build instead of silently producing
 * an APK with no native libraries.
 */
val abiFilter: String? = (project.findProperty("abiFilter") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also {
        require(it in abiVersionCodes) {
            "Unknown -PabiFilter=$it — expected one of ${abiVersionCodes.keys}"
        }
    }

/**
 * A locally built ffmpeg-kit AAR, or null to use the Maven coordinate.
 *
 * Not used by the F-Droid recipe: reproducible builds require F-Droid and CI to
 * consume the *identical* artifact, and a cross-compiled FFmpeg is not
 * bit-reproducible across machines (see fdroid/README.md). This stays as a
 * tested escape hatch for anyone who wants to build FFmpeg themselves — drop the
 * AAR at app/libs/ffmpeg-kit.aar (gitignored) and it is picked up automatically.
 */
val localFfmpegAar: File? = file("libs/ffmpeg-kit.aar").takeIf { it.isFile }
    ?.also { logger.lifecycle("ffmpeg-kit: using local AAR at ${it.relativeTo(rootDir)}") }

android {
    namespace = "app.embeddy"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.embeddy"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = baseVersionCode
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falling back to the debug key produces an APK that cannot be
            // installed over a previous release: the debug keystore is generated
            // per machine, so every CI run signs with a different key and users
            // hit INSTALL_FAILED_UPDATE_INCOMPATIBLE. Warned about below, once
            // the task graph confirms a release is actually being built.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            // -PsingleApk collapses everything into one all-ABI APK.
            isEnable = !project.hasProperty("singleApk")
            // reset() is required — include() ADDS to the default set (every known
            // ABI), so without it AGP also emits empty mips/mips64/riscv64/armeabi/x86
            // APKs that contain no FFmpeg .so files and crash on first use.
            reset()
            // -PabiFilter=<abi> builds exactly one ABI. F-Droid uses this: it
            // publishes one APK per build entry and has to reproduce ours
            // byte-for-byte, so it builds each ABI separately with no universal.
            include(*(abiFilter?.let { arrayOf(it) } ?: RELEASE_ABIS))
            isUniversalApk = abiFilter == null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // FFmpeg-kit dlopen()s its native libraries by path, so they must be
            // extracted to the filesystem at install time. Declared here rather than
            // via android:extractNativeLibs in the manifest, which AGP deprecates.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/version-control-info.textproto",
                "META-INF/com/android/build/gradle/app-metadata.properties",
            )
        }
    }
}

// Per-ABI versionCodes and final filenames.
//
// F-Droid publishes our own signed APK after reproducing it from source, so the
// names and versionCodes here are what the recipe references — they are part of
// the contract, not cosmetic. Naming the outputs in Gradle rather than renaming
// in CI keeps a locally built APK identical to a released one.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.filters?.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach

            val name = variant.name
            if (abi != null) {
                abiVersionCodes[abi]?.let { impl.versionCode.set(baseVersionCode * 10 + it) }
                impl.outputFileName.set("Embeddy-v${versionMajor}.${versionMinor}.${versionPatch}-$abi.apk")
            } else if (name.contains("release", ignoreCase = true) || name.contains("debug", ignoreCase = true)) {
                // Universal (or non-split) output keeps the base versionCode
                impl.outputFileName.set(
                    "Embeddy-v${versionMajor}.${versionMinor}.${versionPatch}-universal.apk"
                )
            }
        }
    }
}

// Warn only when a release artifact is genuinely being produced. The buildTypes
// block above is configured on every invocation, so checking there would fire
// this on debug builds too.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.name.contains("Release") && it.project == project }
    if (buildingRelease && android.signingConfigs.findByName("release") == null) {
        logger.warn(
            "\n" + "!".repeat(78) +
                "\nRELEASE BUILD IS DEBUG-SIGNED — KEYSTORE_PATH is not set." +
                "\nThe debug keystore is generated per machine, so this APK cannot be" +
                "\ninstalled as an update over any other build." +
                "\nSee fdroid/README.md > Signing for the fix." +
                "\n" + "!".repeat(78)
        )
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jsoup)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Media processing.
    //
    // FFmpeg normally comes from Maven Central, but F-Droid builds it from
    // source and drops the result at app/libs/ffmpeg-kit.aar. Detecting that
    // file keeps the F-Droid recipe free of sed-patching this build script —
    // a patch that silently stops applying whenever these lines are reformatted.
    //
    // The AAR carries no POM, so ffmpeg-kit's transitive smart-exception-java
    // has to be declared by hand on that path.
    if (localFfmpegAar != null) {
        implementation(files(localFfmpegAar))
        implementation(libs.smart.exception.java)
    } else {
        implementation(libs.ffmpeg.kit)
    }
    implementation(libs.avif.coder)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Image loading (thumbnails, previews, animated WebP playback)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)

    // Logging
    implementation(libs.timber)

    // Background work (periodic cache cleanup)
    implementation(libs.work.runtime)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
