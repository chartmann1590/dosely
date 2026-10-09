plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.dosely.wear"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.charles.dosely"
        minSdk = 30
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("VERSION_NAME") ?: "1.1.1"
    }
    val keyPath = System.getenv("KEYSTORE_FILE") ?: System.getenv("RELEASE_KEYSTORE_PATH")
    if (keyPath != null && file(keyPath).exists()) {
        signingConfigs.create("release") {
            storeFile = file(keyPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("ANDROID_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":sync"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.wear.compose.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)
}
