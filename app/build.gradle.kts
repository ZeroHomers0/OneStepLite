import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
}

// version.json stays the single source of truth: build.ps1 reads it, and the
// runtime marker asserted by both build paths lives there too.
val versionFile = rootProject.file("version.json")
val versionInfo = JsonSlurper().parse(versionFile) as Map<*, *>
val appVersionName = versionInfo["name"] as String
val appVersionCode = (versionInfo["code"] as Number).toInt()

android {
    namespace = "io.github.zerohomers0.onesteplite"
    compileSdk = 37

    // Pinned so a clean machine and CI resolve the same toolchain the release
    // notes name; the NDK version also drives the packaged ARM64 library.
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.zerohomers0.onesteplite"
        minSdk = 34
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Lint runs in CI. The baseline records the findings that are inherent to
    // an LSPosed module; anything new still fails the build.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }

    // The release key never lives in the repository. Provide it through the
    // environment; without it the release build stays unsigned.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("ONESTEPLITE_KEYSTORE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("ONESTEPLITE_STORE_PASSWORD")
                keyAlias = System.getenv("ONESTEPLITE_KEY_ALIAS")
                keyPassword = System.getenv("ONESTEPLITE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("ONESTEPLITE_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Provided by the Xposed framework at runtime; never packaged into the APK.
    compileOnly(files("../libs/xposed-api-82.jar"))
}

// Checks the packaged APK the way the module loader will read it: entry class,
// Xposed assets, licence bundle and the uncompressed ARM64 native library.
// The verifier is plain Python so the same check runs on Windows and on CI.
val verifyApk by tasks.registering(Exec::class) {
    dependsOn("assembleRelease")
    val python = if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"
    val output = layout.buildDirectory.dir("outputs/apk/release")
    commandLine(
        python,
        rootProject.file("tools/assert_module_apk.py").absolutePath,
        output.get().asFile.absolutePath,
        "--require-native",
    )
}

// Copies the verified APK to dist/ with the checksum manifest used by releases.
val stageRelease by tasks.registering {
    dependsOn(verifyApk)
    val output = layout.buildDirectory.dir("outputs/apk/release")
    val dist = rootProject.layout.projectDirectory.dir("dist")
    val versionName = android.defaultConfig.versionName
    val signed = System.getenv("ONESTEPLITE_KEYSTORE") != null
    doLast {
        val source = output.get().asFile.resolve(if (signed) "app-release.apk" else "app-release-unsigned.apk")
        val name = "OneStepLite-$versionName" + if (signed) ".apk" else "-unsigned.apk"
        val target = dist.file(name).asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        dist.file("SHA256SUMS.txt").asFile.writeText("$digest  ${target.name}\n")
        logger.lifecycle("Staged ${target.name} ($digest)")
    }
}
