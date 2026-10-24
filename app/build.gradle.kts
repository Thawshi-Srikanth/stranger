import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.0.21"
    id("com.google.gms.google-services")

}

android {
    namespace = "com.tk.stranger"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    val propVersionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 11
    val propVersionName = (project.findProperty("versionName") as? String) ?: "1.0.$propVersionCode"

    defaultConfig {
        applicationId = "com.tk.stranger"
        minSdk = 26
        targetSdk = 36
        versionCode = propVersionCode
        versionName = propVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile = rootProject.file("local.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("signing.store.file")
                ?: System.getenv("SIGNING_STORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
            }
            storePassword = (keystoreProperties.getProperty("signing.store.password")
                ?: System.getenv("SIGNING_STORE_PASSWORD"))?.takeIf { it.isNotBlank() }
            keyAlias = (keystoreProperties.getProperty("signing.key.alias")
                ?: System.getenv("SIGNING_KEY_ALIAS"))?.takeIf { it.isNotBlank() }
            keyPassword = (keystoreProperties.getProperty("signing.key.password")
                ?: System.getenv("SIGNING_KEY_PASSWORD"))?.takeIf { it.isNotBlank() }
        }
    }

    val releaseSigning = signingConfigs.getByName("release")
    val hasValidReleaseSigning = releaseSigning.storeFile?.exists() == true &&
        !releaseSigning.storePassword.isNullOrBlank() &&
        !releaseSigning.keyAlias.isNullOrBlank() &&
        !releaseSigning.keyPassword.isNullOrBlank()

    buildTypes {
        debug {
            if (hasValidReleaseSigning) {
                signingConfig = releaseSigning
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasValidReleaseSigning) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.tablerIcons)
    implementation(libs.cupertino)
    implementation(libs.cupertino.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aws.sdk.s3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.lottie.compose)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.cloudy)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

}
