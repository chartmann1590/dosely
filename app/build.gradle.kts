plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dosely.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dosely.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
