plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreFilePath = System.getenv("KEYSTORE_FILE")
    ?: System.getenv("RELEASE_KEYSTORE_PATH")
    ?: project.findProperty("keystore.file") as? String
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
    ?: project.findProperty("keystore.password") as? String
val keystoreKeyAlias = System.getenv("KEY_ALIAS")
    ?: System.getenv("ANDROID_KEY_ALIAS")
    ?: project.findProperty("key.alias") as? String
val keystoreKeyPassword = System.getenv("KEY_PASSWORD")
    ?: System.getenv("ANDROID_KEY_PASSWORD")
    ?: project.findProperty("key.password") as? String
val hasReleaseKeystore = keystoreFilePath != null && file(keystoreFilePath).exists()

val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.1.1"

android {
    namespace = "com.dosely.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.charles.dosely"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AdMob IDs are injected from environment/CI secrets and are NEVER
        // committed to this repository. Local and debug builds fall back to
        // Google's published test IDs (safe for development and testing).
        // For a local release build, export ADMOB_APPLICATION_ID,
        // ADMOB_BANNER_UNIT_ID and ADMOB_INTERSTITIAL_UNIT_ID first.
        buildConfigField(
            "String",
            "ADMOB_BANNER_UNIT_ID",
            "\"${System.getenv("ADMOB_BANNER_UNIT_ID") ?: "ca-app-pub-3940256099942544/9214589741"}\"",
        )
        buildConfigField(
            "String",
            "ADMOB_INTERSTITIAL_UNIT_ID",
            "\"${System.getenv("ADMOB_INTERSTITIAL_UNIT_ID") ?: "ca-app-pub-3940256099942544/1033173712"}\"",
        )
        manifestPlaceholders["admobApplicationId"] =
            System.getenv("ADMOB_APPLICATION_ID") ?: "ca-app-pub-3940256099942544~3347511713"

        // Cloudflare feedback worker endpoint (NOT a secret — safe to commit).
        // Overridable via -Pfeedback.worker.url or the FEEDBACK_WORKER_URL env var.
        buildConfigField(
            "String",
            "FEEDBACK_WORKER_URL",
            "\"${project.findProperty("feedback.worker.url")
                ?: System.getenv("FEEDBACK_WORKER_URL")
                ?: "https://dosely-feedback-api.charles-h-hartmann1.workers.dev"}\"",
        )

        // Shared capability key for the feedback worker (defense in depth — the
        // worker enforces it only when its own secret is configured). Not a
        // user secret; rotate by redeploying the worker + app. Overridable via
        // -Pfeedback.api.key or the FEEDBACK_API_KEY env var; empty disables.
        buildConfigField(
            "String",
            "FEEDBACK_API_KEY",
            "\"${project.findProperty("feedback.api.key")
                ?: System.getenv("FEEDBACK_API_KEY")
                ?: ""}\"",
        )
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreFilePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            manifestPlaceholders["admobApplicationId"] = "ca-app-pub-3940256099942544~3347511713"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":sync"))
    implementation(libs.play.services.wearable)
    implementation(libs.play.billing)
    implementation(libs.health.connect)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    implementation(project(":hartmann-crosspromo"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.workmanager)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.mlkit.translate)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.litertlm.android)

    implementation(libs.materialkolor)

    debugImplementation(libs.androidx.ui.tooling)
}
