import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.pulsedrop.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.pulsedrop.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 7
    versionName = "1.5"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Release is signed only when an upload key is actually present, so a fresh clone with no
  // keystore and no signing.properties still produces a buildable unsigned release.
  signingConfigs {
    create("release") {
      val propsFile = rootProject.file("signing.properties")
      val props = Properties().apply {
        if (propsFile.exists()) propsFile.inputStream().use { load(it) }
      }
      val lookup: (String) -> String? = { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: props.getProperty(key)?.takeIf { it.isNotBlank() }
      }
      val keystorePath = lookup("KEYSTORE_PATH") ?: "my-upload-key.jks"
      val keystoreFile =
        file(if (File(keystorePath).isAbsolute) keystorePath else rootProject.file(keystorePath))
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
        storePassword = lookup("STORE_PASSWORD")
        keyAlias = lookup("KEY_ALIAS") ?: "upload"
        keyPassword = lookup("KEY_PASSWORD")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfigs.findByName("release")
        ?.takeIf { it.storeFile?.exists() == true }
        ?.let { signingConfig = it }
    }
    // Debug intentionally uses AGP's built-in signing config, which generates
    // ~/.android/debug.keystore on demand. A repo-committed debug keystore would not survive
    // a fresh clone.
    debug { }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.nanohttpd)
  // Self-signed LAN TLS: browsers only hand over mic/camera/screen on a secure origin,
  // and the embedded server is the only way a PC reaches this phone.
  implementation(libs.bcpkix)
  implementation(libs.okhttp)
  implementation(libs.zxing.android.embedded)
  implementation(libs.zxing.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
