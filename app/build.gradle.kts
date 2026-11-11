import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
  rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val ndkVer = "30.0.16248370"

android {
  namespace = "com.criapix.fm1droid"
  compileSdk = 36
  ndkVersion = ndkVer
  defaultConfig {
    applicationId = "com.criapix.fm1droid"
    minSdk = 26
    targetSdk = 36
    versionCode = 3
    versionName = "0.3.0"
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    val ks = localProps.getProperty("release.keystore")
    if (ks != null) {
      create("release") {
        storeFile = file(ks)
        storePassword = localProps.getProperty("release.storePassword")
        keyAlias = localProps.getProperty("release.keyAlias")
        keyPassword = localProps.getProperty("release.keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfigs.findByName("release")?.let { signingConfig = it }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
  }
}

kotlin { jvmToolchain(17) }

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}

// Rust -> libfm1droid.so. Needs the MSVC env for host build scripts: run Gradle after
// `. .\tools\vsenv.ps1` (see docs/NOTES.md).
val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""
val cargoNdk = tasks.register<Exec>("cargoNdk") {
  workingDir = rootProject.file("native/fm1droid")
  environment("ANDROID_NDK_HOME", File(sdkDir, "ndk/$ndkVer").absolutePath)
  inputs.dir(rootProject.file("native/fm1droid/src"))
  inputs.file(rootProject.file("native/fm1droid/Cargo.toml"))
  outputs.dir(rootProject.file("app/src/main/jniLibs"))
  commandLine(
    "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "26",
    "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
    "build", "--release",
  )
}
tasks.named("preBuild") { dependsOn(cargoNdk) }
