plugins {
    alias(libs.plugins.android.application)
}

val supabaseUrl = providers.gradleProperty("supabaseUrl")
    .orElse("https://aearzrtzrtyvuywjcwni.supabase.co").get()
val supabasePublishableKey = providers.gradleProperty("supabasePublishableKey")
    .orElse("sb_publishable_hi9tDuZNDMr8vF0vAwF5wg_s-XsIn6J").get()
val releaseStoreFile = providers.gradleProperty("QSPOOF_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("QSPOOF_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("QSPOOF_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("QSPOOF_RELEASE_KEY_PASSWORD").orNull
val releaseCertificateSha256 = "D1650E0E1E0212B5879DB05B3B64692BEB1A1D25778D712F8EFB323E603DEECB"

fun String.escapeBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

android {
    // Reuse the main app's Kotlin package so the profile screen and API client
    // stay byte-for-byte shared; only the installed application ID differs.
    namespace = "com.antutu.ABenchMark"
    compileSdk = 36

    defaultConfig {
        applicationId = "qspoof.companion"
        minSdk = 34
        targetSdk = 36
        versionCode = 9
        versionName = "9.0"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.escapeBuildConfig()}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${supabasePublishableKey.escapeBuildConfig()}\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertificateSha256\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("qspoofRelease") {
            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                signingConfig = signingConfigs.getByName("qspoofRelease")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../app/proguard-rules.pro")
        }
    }
}

dependencies {
}
