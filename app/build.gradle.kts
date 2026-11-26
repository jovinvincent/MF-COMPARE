plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.mfcompare.qzvktm"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  packaging {
    resources {
      excludes += listOf(
        "org/bouncycastle/pqc/**",
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "assets/com/tom_roush/fontbox/resources/cmap/Uni*",
        "assets/com/tom_roush/fontbox/resources/cmap/Adobe-CNS1*",
        "assets/com/tom_roush/fontbox/resources/cmap/Adobe-GB1*",
        "assets/com/tom_roush/fontbox/resources/cmap/Adobe-Japan1*",
        "assets/com/tom_roush/fontbox/resources/cmap/Adobe-Korea1*",
        "assets/com/tom_roush/fontbox/resources/cmap/GB*",
        "assets/com/tom_roush/fontbox/resources/cmap/KSC*",
        "assets/com/tom_roush/fontbox/resources/cmap/HKscs*",
        "assets/com/tom_roush/fontbox/resources/cmap/B5*",
        "assets/com/tom_roush/fontbox/resources/cmap/ETen*",
        "assets/com/tom_roush/fontbox/resources/cmap/90*",
        "assets/com/tom_roush/fontbox/resources/cmap/Add-*",
        "assets/com/tom_roush/fontbox/resources/cmap/Ext-*",
        "assets/com/tom_roush/fontbox/resources/cmap/EUC*",
        "assets/com/tom_roush/fontbox/resources/cmap/RKSJ*",
        "assets/com/tom_roush/fontbox/resources/cmap/NWP*",
        "assets/com/tom_roush/fontbox/resources/cmap/CNS*"
      )
    }
  }
  androidResources {
    ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:Uni*:Adobe-CNS1*:Adobe-GB1*:Adobe-Japan1*:Adobe-Korea1*:GB*:KSC*:HKscs*:B5*:ETen*:90*:Add-*:Ext-*:EUC*:RKSJ*:NWP*:CNS*"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all { testTask ->
        val localRobolectricDir = file("/home/appuser/.m2/repository/org/robolectric/android-all-instrumented/16-robolectric-13921718-i7")
        if (localRobolectricDir.exists()) {
          testTask.systemProperty("robolectric.offline", "true")
          testTask.systemProperty("robolectric.dependency.dir", localRobolectricDir.absolutePath)
        }
      }
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation("com.google.firebase:firebase-auth")
  // implementation("com.google.firebase:firebase-firestore")
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // Replaced by lean AppExtendedIcons.kt (saves 12,371 Compose vector classes)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.jsoup)
  // implementation(libs.pdfbox)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

// Replaced by zero-dependency streaming Kotlin ExcelParser.kt (saves 21,076 POI/XmlBeans classes + 2.86 MB schemas)
// dependencies {
//     implementation(libs.apache.poi)
//     implementation(libs.apache.poi.ooxml)
// }
