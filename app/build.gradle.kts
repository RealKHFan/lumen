import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.takahirom.roborazzi")
}

// Release signing comes from these environment variables; the key never goes into the repo.
val releaseKeystore: String? = System.getenv("LUMEN_KEYSTORE")

android {
    namespace = "io.github.realkhfan.lumen"
    compileSdk = 36

    defaultConfig {
        // Pixel Camera only opens its thumbnail in an app with Google Photos' package name.
        // Everything on screen says "Lumen"; it just can't be installed next to Google Photos.
        applicationId = "com.google.android.apps.photos"
        minSdk = 31
        targetSdk = 36
        // Above anything the real Google Photos uses, so the Play Store never offers it as an
        // "update". Format 20MMmmpp00: 1.0.7 = 2000010700.
        versionCode = 2000010700
        versionName = "1.0.7"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LUMEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUMEN_KEY_ALIAS") ?: "lumen"
                keyPassword = System.getenv("LUMEN_KEY_PASSWORD") ?: System.getenv("LUMEN_KEYSTORE_PASSWORD")
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Shrinks away the unused parts of the Kotlin library; names stay readable in crash reports.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key this build is signed with the debug key and can't update a release install.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Real rendering for screenshots, including shadows and rounded clipping.
        unitTests.all {
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // Robolectric's Android 16 runtime reaches into this JDK package.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    lint {
        abortOnError = true
        // The high versionCode is on purpose (see defaultConfig). Its scheme tops out at
        // 2,099,999,900, under Android's 2,100,000,000 limit.
        disable += "HighAppVersionCode"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/*.kotlin_builtins", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.74.0")
}

// A hanging test (for example an endless animation) fails the build instead of stalling it.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(15)) }
