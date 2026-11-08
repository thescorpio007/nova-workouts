import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build number comes from GitHub (run number); shown as "1.0 · build 01".
val buildNo: Int = (System.getenv("NOVA_BUILD") ?: "1").toInt()
val buildLabel: String = String.format("%02d", buildNo)
val appVersion = "1.0"

// Signing key: decrypted by the GitHub workflow from keystore/nova-workouts.jks.enc using the
// NOVA_WORKOUTS_KEY secret. The key and its password are never stored in the public code.
val signProps = Properties().apply {
    val f = rootProject.file("keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.nova.workouts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.workouts"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNo
        versionName = "$appVersion · build $buildLabel"
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/nova-workouts.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = signProps.getProperty("password")
                keyAlias = "novaworkouts"
                keyPassword = signProps.getProperty("password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ks = rootProject.file("keystore/nova-workouts.jks")
            signingConfig = if (ks.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "NovaWorkouts-$appVersion-build-$buildLabel.apk"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
