import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stocksdetective.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stocksdetective.app"
        // Gemini Nano (on-device AI) needs Android 14+ on supported phones; older phones
        // get the rule-based analyst, so the app itself runs on Android 8+.
        minSdk = 26
        targetSdk = 37
        // Every upload to Google Play needs a higher versionCode. CI passes BUILD_NUMBER;
        // locally pass -PversionCode=N (and optionally -PversionName=1.0.1).
        versionCode = (project.findProperty("versionCode") as String? ?: System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = project.findProperty("versionName") as String? ?: "1.0"
    }

    signingConfigs {
        // Release builds are signed with the Google Play upload key when one is configured:
        // locally from keystore.properties (git-ignored, see README), in CI from environment
        // variables. Otherwise the release build is left unsigned.
        val localProps = rootProject.file("keystore.properties")
        val props = Properties().apply { if (localProps.exists()) localProps.inputStream().use { load(it) } }
        val keystore = props.getProperty("storeFile") ?: System.getenv("ANDROID_KEYSTORE_PATH")
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore)
                storePassword = props.getProperty("storePassword") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("keyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = props.getProperty("keyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Screenshots of every screen (Robolectric, no emulator): ./gradlew :app:testDebugUnitTest -Pscreenshots
            it.systemProperty("screenshots", project.hasProperty("screenshots"))
            it.systemProperty("roborazzi.test.record", project.hasProperty("screenshots"))
            it.systemProperty("screenshotDir", rootProject.file("docs/screenshots").absolutePath)
            it.systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2")
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":detectivekit"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.mlkit.genai.prompt)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
