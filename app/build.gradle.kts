import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Google's official AdMob test IDs. Safe to click; earn nothing.
val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerId = "ca-app-pub-3940256099942544/6300978111"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val testRewardedId = "ca-app-pub-3940256099942544/5224354917"

fun prop(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

// Release signing: keystore.properties (git-ignored) sits next to settings.gradle.kts.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hinterlay.app"

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hinterlay.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"

        buildConfigField("String", "FACEBOOK_APP_ID", "\"${prop("FACEBOOK_APP_ID", "")}\"")
        // Comma-separated AdMob test device ids (printed in logcat the first time ads load). Blank = none.
        buildConfigField("String", "ADMOB_TEST_DEVICE_IDS", "\"${prop("ADMOB_TEST_DEVICE_IDS", "")}\"")
        // Optional services. Leave blank to switch them off.
        buildConfigField("String", "CLARITY_PROJECT_ID", "\"${prop("CLARITY_PROJECT_ID", "")}\"")
        // Community sharing uploads a creation only when this is true (and Firebase is set up). Keep false until the privacy policy says so.
        buildConfigField("boolean", "COMMUNITY_SHARING", "${prop("COMMUNITY_SHARING", "false")}")
        buildConfigField("String", "PAYPAL_DONATE_URL", "\"${prop("PAYPAL_DONATE_URL", "")}\"")
        buildConfigField(
            "String", "PRIVACY_POLICY_URL",
            "\"${prop("PRIVACY_POLICY_URL", "https://example.com/hinterlay/privacy")}\""
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_package", "com.hinterlay.app.debug")
            manifestPlaceholders["quickExported"] = "true" // debug only: lets adb open the lock-screen camera for testing
            manifestPlaceholders["admobAppId"] = testAppId
            buildConfigField("String", "AD_BANNER_ID", "\"$testBannerId\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"$testInterstitialId\"")
            buildConfigField("String", "AD_REWARDED_ID", "\"$testRewardedId\"")
        }
        release {
            resValue("string", "app_package", "com.hinterlay.app")
            manifestPlaceholders["quickExported"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["admobAppId"] = prop("ADMOB_APP_ID", testAppId)
            buildConfigField("String", "AD_BANNER_ID", "\"${prop("ADMOB_BANNER_ID", testBannerId)}\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"${prop("ADMOB_INTERSTITIAL_ID", testInterstitialId)}\"")
            buildConfigField("String", "AD_REWARDED_ID", "\"${prop("ADMOB_REWARDED_ID", testRewardedId)}\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

if (prop("ADMOB_APP_ID", "").isEmpty()) {
    logger.warn("Hinterlay: ADMOB_APP_ID not set - release builds will use Google TEST ads (no revenue).")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.storage)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.androidx.palette)
    implementation(libs.clarity)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.face)
    implementation(libs.mlkit.face.mesh)
    implementation(libs.play.billing)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.kotlinx.coroutines.play.services)

    // AR real-world video (Part B). SceneView = ARCore + Filament + Compose ARScene.
    implementation(libs.arsceneview)
    implementation(libs.arcore)
}

// Firebase is optional: the app builds and runs without it, and cloud features switch on once
// app/google-services.json exists (see docs/SETUP_FIREBASE.md).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
