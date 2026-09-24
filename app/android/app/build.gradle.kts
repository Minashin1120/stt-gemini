plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciRunNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

// versionCode はビルド時刻（2026-01-01 UTC からの経過分）。GITHUB_RUN_NUMBER は workflow ごとに
// 別カウントのため、debug（Android CI）と release（Release Build）の両方で単調増加させる。
val buildVersionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()

// release.yml が渡すタグ（vX.Y.Z）。指定時は versionName をタグと一致させる。
val releaseTag = System.getenv("RELEASE_TAG")?.trim()?.takeIf { it.isNotEmpty() }
val releaseVersionName = releaseTag?.let { tag ->
    Regex("""^v(\d+\.\d+\.\d+)$""").matchEntire(tag)?.groupValues?.get(1)
        ?: throw GradleException("RELEASE_TAG must be vX.Y.Z (got: $tag)")
}

android {
    namespace = "com.minashin1120.voxcribe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.minashin1120.voxcribe"
        minSdk = 29
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = releaseVersionName ?: "0.0.$ciRunNumber-debug"
    }

    signingConfigs {
        create("sharedDebug") {
            val ciKeystore = rootProject.file("ci/debug.keystore")

            if (!ciKeystore.exists()) {
                throw GradleException(
                    "ci/debug.keystore is missing. " +
                        "This repository requires the fixed shared keystore."
                )
            }

            storeFile = ciKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("de.sciss:jump3r:1.0.5")
}
