import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Read through `providers` so the configuration cache tracks local.properties as an
// input instead of us reading it behind Gradle's back. Falls back to an env var for CI,
// then to "" so a missing key is a clear in-app error rather than a build failure.
fun apiKey(name: String): String =
    providers.fileContents(layout.settingsDirectory.file("local.properties"))
        .asText
        .map { Properties().apply { load(it.reader()) }.getProperty(name).orEmpty() }
        .orElse(providers.environmentVariable(name))
        .getOrElse("")

val apiKeyNames = listOf("GEMINI_API_KEY", "MISTRAL_API_KEY", "GROQ_API_KEY", "NVIDIA_API_KEY")

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.sqldelight.androidDriver)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.disheveled.bills"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.disheveled.bills"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        apiKeyNames.forEach { buildConfigField("String", it, "\"${apiKey(it)}\"") }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            // R8 shrinks and optimises Compose, Ktor and the rest (35 MB -> 5.5 MB APK). The
            // libraries ship their own keep rules; proguard-rules.pro is for what R8 can't see.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}