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

val releaseCertificateSha256 =
    "D1650E0E1E0212B5879DB05B3B64692BEB1A1D25778D712F8EFB323E603DEECB"

fun String.escapeBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.antutu.ABenchMark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.antutu.ABenchMark"
        minSdk = 30
        targetSdk = 36
        versionCode = 15
        versionName = "v5.0"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.escapeBuildConfig()}\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${supabasePublishableKey.escapeBuildConfig()}\""
        )
        buildConfigField(
            "String",
            "RELEASE_CERT_SHA256",
            "\"$releaseCertificateSha256\""
        )
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("antutu") {
            dimension = "distribution"
            applicationId = "com.antutu.ABenchMark"
            manifestPlaceholders["appLabel"] = "QSpoof"
            manifestPlaceholders["engineLabel"] = "QEngine"
            manifestPlaceholders["engineTaskAffinity"] = "com.antutu.ABenchMark.engine"
        }
        create("antutuFull") {
            dimension = "distribution"
            applicationId = "com.antutu.benchmark.full"
            manifestPlaceholders["appLabel"] = "QSpoof"
            manifestPlaceholders["engineLabel"] = "QEngine"
            manifestPlaceholders["engineTaskAffinity"] = "com.antutu.benchmark.full.engine"
        }
        create("ludashi") {
            dimension = "distribution"
            applicationId = "com.ludashi.benchmark"
            manifestPlaceholders["appLabel"] = "Qspoof"
            manifestPlaceholders["engineLabel"] = "Qengine"
            manifestPlaceholders["engineTaskAffinity"] = "com.ludashi.benchmark.engine"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("qspoofRelease") {
            if (
                releaseStoreFile != null &&
                releaseStorePassword != null &&
                releaseKeyAlias != null &&
                releaseKeyPassword != null
            ) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (
                releaseStoreFile != null &&
                releaseStorePassword != null &&
                releaseKeyAlias != null &&
                releaseKeyPassword != null
            ) {
                signingConfig = signingConfigs.getByName("qspoofRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.inmobi.monetization:inmobi-ads-kotlin:10.8.3")
    implementation("com.startapp:inapp-sdk:5.4.0")
    testImplementation(libs.junit)
}
