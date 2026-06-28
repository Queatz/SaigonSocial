import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.app.shared)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "social.saigon"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "social.saigon"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildFeatures {
        // Generate BuildConfig so the API base URL can be baked in per build type.
        buildConfig = true
    }
    // Where the client talks to the API, baked into BuildConfig.API_BASE_URL per build type so no
    // source editing is needed: debug -> local dev server (10.0.2.2 is the host loopback as seen
    // from an emulator), release -> production. Override either build with -PapiBaseUrl=<url>.
    val apiBaseUrlOverride = project.findProperty("apiBaseUrl") as String?
    buildTypes {
        getByName("debug") {
            val url = apiBaseUrlOverride ?: "http://10.0.2.2:9090"
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            // Sign the release build with the debug keystore so it can be installed straight onto a
            // device via adb for testing against production, without a dedicated release keystore.
            signingConfig = signingConfigs.getByName("debug")
            val url = apiBaseUrlOverride ?: "https://api.saigon.social"
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
