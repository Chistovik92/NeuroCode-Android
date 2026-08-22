import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) FileInputStream(file).use(::load)
}
fun signingProperty(name: String): String? =
    keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name.uppercase().replace('.', '_'))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.secrethero.neurocode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.secrethero.neurocode"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin.jvmToolchain(17)

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += setOf(
        "META-INF/AL2.0",
        "META-INF/LGPL2.1",
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
    )
}

dependencies {
    implementation(project(":llama"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    implementation(libs.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.sora.editor)
    implementation(libs.jgit)
    implementation(libs.slf4j.android)
    coreLibraryDesugaring(libs.desugar)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(platform(libs.compose.bom))
}
