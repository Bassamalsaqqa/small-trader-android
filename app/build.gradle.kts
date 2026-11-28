import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.URI

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

fun escapeJavaStringLiteral(value: String): String {
  val sb = StringBuilder("\"")
  for (c in value) {
    when (c) {
      '\\' -> sb.append("\\\\")
      '"' -> sb.append("\\\"")
      '\b' -> sb.append("\\b")
      '\t' -> sb.append("\\t")
      '\n' -> sb.append("\\n")
      '\u000C' -> sb.append("\\f")
      '\r' -> sb.append("\\r")
      in '\u0000'..'\u001F', in '\u007F'..'\u009F' -> sb.append(String.format("\\u%04x", c.code))
      else -> sb.append(c)
    }
  }
  sb.append("\"")
  return sb.toString()
}

val releaseKeystore = file(
  System.getenv("KEYSTORE_PATH") ?: System.getenv("MYAPP_UPLOAD_STORE_FILE") ?: "${rootDir}/my-upload-key.jks"
)
val allowLocalDebugReleaseSigning = providers.gradleProperty("smalltrader.allowInsecureDebugSigningForRelease")
  .map(String::toBoolean)
  .orElse(false)
val hasReleaseSigning = releaseKeystore.isFile &&
  !System.getenv("STORE_PASSWORD").isNullOrBlank() && !System.getenv("KEY_PASSWORD").isNullOrBlank()

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.smalltrader.psdukx"
    minSdk = 24
    targetSdk = 36
    versionCode = 9
    versionName = "1.4.3"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val staffReadWorkspace = project.findProperty("smalltrader.staffReadWorkspace")?.toString()?.toBoolean() ?: false
    val configuredPropertyOrigin = project.findProperty("smalltrader.staffWorkspaceOrigin")?.toString()?.trim()
    val staffWorkspaceOrigin = if (configuredPropertyOrigin != null) {
      if (configuredPropertyOrigin.isBlank()) "unconfigured" else configuredPropertyOrigin
    } else {
      "https://account.palsync.net"
    }

    buildConfigField("boolean", "OFFLINE_OWNER_PILOT", (project.findProperty("smalltrader.offlineOwnerPilot")?.toString()?.toBooleanStrictOrNull() ?: false).toString())
    buildConfigField("String", "OFFLINE_CATALOG_MAX_PAGES", escapeJavaStringLiteral(project.findProperty("smalltrader.offlineCatalogMaxPages")?.toString() ?: ""))
    buildConfigField("String", "OFFLINE_CATALOG_MAX_PRODUCTS", escapeJavaStringLiteral(project.findProperty("smalltrader.offlineCatalogMaxProducts")?.toString() ?: ""))
    buildConfigField("boolean", "STAFF_READ_WORKSPACE", staffReadWorkspace.toString())
    buildConfigField("String", "STAFF_WORKSPACE_ORIGIN", escapeJavaStringLiteral(staffWorkspaceOrigin))
    buildConfigField("boolean", "STAFF_ALLOW_HTTP_LOOPBACK", "false")
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      storeFile = releaseKeystore
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
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Only an explicit local-development override can select debug signing.
      signingConfig = signingConfigs.getByName(if (allowLocalDebugReleaseSigning.get()) "debugConfig" else "release")
      buildConfigField("boolean", "STAFF_ALLOW_HTTP_LOOPBACK", "false")
    }
    create("benchmark") {
      initWith(getByName("release"))
      matchingFallbacks += listOf("release")
      signingConfig = signingConfigs.getByName("debugConfig")
      buildConfigField("boolean", "STAFF_ALLOW_HTTP_LOOPBACK", "false")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
      val staffAllowHttpLoopback = project.findProperty("smalltrader.staffAllowHttpLoopback")?.toString()?.toBoolean() ?: false
      buildConfigField("boolean", "STAFF_ALLOW_HTTP_LOOPBACK", staffAllowHttpLoopback.toString())
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  ksp(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

abstract class ValidateReleaseOriginTask : DefaultTask() {
  @get:Input
  abstract val configuredOrigin: Property<String>

  @TaskAction
  fun validate() {
    val origin = configuredOrigin.get().trim()
    if (origin.isBlank() || origin.equals("unconfigured", ignoreCase = true)) {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN is empty or unconfigured. A valid production HTTPS origin (e.g. https://account.palsync.net) is mandatory.")
    }
    val uri = try {
      URI(origin)
    } catch (e: Exception) {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN is not a valid URI: '$origin'. Error: ${e.message}")
    }
    if (uri.scheme != "https") {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must use secure HTTPS. Found: '$origin'")
    }
    if (uri.host.isNullOrBlank()) {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must have a valid host. Found: '$origin'")
    }
    if (!uri.rawUserInfo.isNullOrBlank()) {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain user credentials. Found: '$origin'")
    }
    if (!uri.rawPath.isNullOrBlank() && uri.rawPath != "/") {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain a path component. Found: '$origin'")
    }
    if (!uri.rawQuery.isNullOrBlank()) {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain a query string. Found: '$origin'")
    }
    if (!uri.rawFragment.isNullOrBlank()) {
      throw GradleException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain a fragment. Found: '$origin'")
    }
  }
}

val validateReleaseOrigin = tasks.register<ValidateReleaseOriginTask>("validateReleaseOrigin") {
  configuredOrigin.set(
    providers.gradleProperty("smalltrader.staffWorkspaceOrigin")
      .map { it.trim().ifBlank { "unconfigured" } }
      .orElse("https://account.palsync.net")
  )
}

tasks.matching {
  it.name.startsWith("packageRelease") ||
  it.name.startsWith("assembleRelease") ||
  it.name.startsWith("bundleRelease") ||
  it.name == "preReleaseBuild"
}.configureEach {
  dependsOn(validateReleaseOrigin)
}

abstract class RequireReleaseSigningTask : DefaultTask() {
  @get:Input
  abstract val signingConfigured: Property<Boolean>

  @TaskAction
  fun validate() {
    if (!signingConfigured.get()) {
      throw GradleException("Release packaging requires an existing release keystore and signing passwords. Use the debug build for local testing.")
    }
  }
}

val requireReleaseSigning = tasks.register<RequireReleaseSigningTask>("requireReleaseSigning") {
  signingConfigured.set(allowLocalDebugReleaseSigning.map { it || hasReleaseSigning })
}

tasks.matching {
  it.name.startsWith("packageRelease") ||
  it.name.startsWith("assembleRelease") ||
  it.name.startsWith("bundleRelease") ||
  it.name == "signReleaseBundle"
}.configureEach {
  dependsOn(requireReleaseSigning)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
  mustRunAfter(requireReleaseSigning)
}
