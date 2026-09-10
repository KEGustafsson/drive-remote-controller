// The Android layer: Compose UI, NsdManager, OkHttp, lifecycle. Reads inputs,
// calls :core, writes outputs -- no decisions of its own. The analogue of the
// firmware's src/ and the plugin's index.cjs.
//
// The plugins applied below are declared `apply false` in the root build file;
// see the comment there for why all of them have to be, including AGP. This
// module is still only CONFIGURED when an Android SDK is present (see
// settings.gradle.kts), so a `:core:test` run on a machine without one applies
// no Android plugin and runs no SDK check.

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

/**
 * Output of a git command, or empty when git is unavailable -- a source
 * download rather than a clone, say.
 */
fun git(vararg args: String): String =
  try {
    providers
      .exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
      }
      .standardOutput
      .asText
      .get()
      .trim()
  } catch (_: Exception) {
    ""
  }

// The version comes from git, so nobody edits a number and no two builds claim
// to be the same thing. versionCode is the commit count, which is monotonic on
// main; versionName is 0.<count>, still deliberately below 1.0 because the
// commissioning checklists in docs/SAFETY.md have not been worked through.
//
// This replaced a hard-coded versionCode = 1. Any release built from this file
// therefore carries a far higher code than the old value, so an install over an
// earlier hand-numbered build is an upgrade rather than a refused downgrade.
val commitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val appVersion = "0.$commitCount"

// Release signing. The keystore is named by DRIVEREMOTE_KEYSTORE (the release
// workflow decodes it from a repository secret) or found at app/release.keystore
// locally, with its passwords from the environment and nowhere else -- there is
// no properties file to commit by accident.
//
// A keystore named but unusable is a mistake to STOP on, not a reason to fall
// back: a typo in the path or a forgotten password would otherwise silently
// produce an unsigned build that the release job would then have to catch.
val keystoreEnv: String? = System.getenv("DRIVEREMOTE_KEYSTORE")
val keystoreFile = file(keystoreEnv ?: "release.keystore")
val keystorePassword: String? = System.getenv("DRIVEREMOTE_KEYSTORE_PASSWORD")

if (keystoreEnv != null) {
  if (!keystoreFile.isFile) {
    throw GradleException("DRIVEREMOTE_KEYSTORE names $keystoreFile, which is not a file")
  }
  if (keystorePassword.isNullOrEmpty()) {
    throw GradleException("DRIVEREMOTE_KEYSTORE is set but DRIVEREMOTE_KEYSTORE_PASSWORD is empty")
  }
}

val hasReleaseKey = keystoreFile.isFile && !keystorePassword.isNullOrEmpty()

// One line naming the key, so nobody reads "BUILD SUCCESSFUL" as "signed".
logger.lifecycle(
  when {
    hasReleaseKey -> "Release signing: release key $keystoreFile"
    keystoreFile.isFile ->
      "Release signing: UNSIGNED ($keystoreFile found, but DRIVEREMOTE_KEYSTORE_PASSWORD is not set)"
    else -> "Release signing: UNSIGNED (no release keystore; see docs/BUILDING.md section 6)"
  }
)

// A shallow clone counts fewer commits, so a signed build from one could carry a
// lower versionCode than an existing release and be refused by the phone as a
// downgrade. Refuse first, with the fix in the message.
if (hasReleaseKey && git("rev-parse", "--is-shallow-repository") == "true") {
  throw GradleException(
    "Release-signed builds need the full git history: run `git fetch --unshallow` first"
  )
}

android {
  namespace = "io.github.kegustafsson.driveremote"
  compileSdk = 35

  defaultConfig {
    applicationId = "io.github.kegustafsson.driveremote"
    // API 26 (Android 8.0). Below this, NsdManager's discovery callbacks are
    // materially less reliable and there is no reason to carry the workarounds
    // for a boat-helm app that will run on a modern phone.
    minSdk = 26
    targetSdk = 35
    versionCode = commitCount
    versionName = appVersion
  }

  signingConfigs {
    create("release") {
      if (hasReleaseKey) {
        storeFile = keystoreFile
        storePassword = keystorePassword
        // takeUnless, not a bare elvis. CI injects an unset repository secret as
        // an EMPTY STRING rather than leaving the variable unset, so `?:` never
        // fires and the alias would silently become "" -- which fails deep
        // inside AGP's signing with an opaque keystore error rather than here.
        keyAlias = System.getenv("DRIVEREMOTE_KEY_ALIAS").takeUnless { it.isNullOrEmpty() }
          ?: "driveremote"
        keyPassword = System.getenv("DRIVEREMOTE_KEY_PASSWORD").takeUnless { it.isNullOrEmpty() }
          ?: keystorePassword
      }
    }
  }

  buildTypes {
    release {
      // R8 shrinks and obfuscates the release build: 8,239,654 -> 1,947,599
      // bytes, a 76% reduction. It was OFF here until 2026-09-10, on the
      // argument that shrinking an unverified codebase adds a failure mode that
      // only appears on a boat. What changed is that it stopped being
      // unverified: the shrunk APK was fresh-installed on a phone, granted a
      // new Signal K access request, armed against the live server and used to
      // command a drive and the thruster. See docs/BUILDING.md section 6.3.1.
      //
      // The specific risk that test cleared is in proguard-rules.pro: Tink,
      // behind EncryptedSharedPreferences, is where the stored token lives, and
      // a key manager stripped by R8 reads as a server-side auth failure rather
      // than as a build problem.
      //
      // Still not covered: :app:testDebugUnitTest measures the DEBUG variant, so
      // the layout floors -- a safety property on the control screen -- never see
      // the shrunk build. A control-layout change still needs that suite AND a
      // look at the shrunk APK on glass.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      // Without a keystore this stays null, which is what makes assembleRelease
      // produce app-release-unsigned.apk. The release workflow fails closed
      // rather than publishing that; a local build is free to produce it.
      if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // The compilerOptions DSL, not the kotlinOptions one :core already left
  // behind. `kotlinOptions { jvmTarget = "17" }` is an ERROR from Kotlin 2.2
  // on, not a warning, so the Gradle dependency group could not be bumped while
  // this module still used it. Same JVM target as before, and as :core.
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

  buildFeatures { compose = true }

  // Robolectric renders the real Compose layout against real resources; without
  // this it gets a stub resource table and every measurement is meaningless.
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  implementation(project(":core"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.security.crypto)
  implementation(libs.okhttp)

  val composeBom = platform(libs.compose.bom)
  implementation(composeBom)
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.okhttp.mockwebserver)

  // The layout floors are a safety property (see LayoutFloorsTest), so they are
  // measured here rather than only by carrying a phone to the boat. Robolectric
  // lays the real Compose tree out on the JVM; the vintage engine runs its
  // JUnit4 runner under this module's JUnit Platform.
  testImplementation(libs.junit4)
  testRuntimeOnly(libs.junit.vintage.engine)
  testImplementation(libs.robolectric)
  testImplementation(composeBom)
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

/** Hex SHA-256 of a file, for the SBOM below. */
fun sha256(f: File): String =
  MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * `gradlew :app:sbom` writes app/build/reports/bom.json: a CycloneDX 1.5
 * software bill of materials of everything on the release runtime classpath,
 * attached to every Release.
 *
 * Written here rather than by a plugin so the build pulls in nothing extra to
 * describe what the build contains. Each component carries the SHA-256 of its
 * artifacts, so a reader can check a jar or aar against Maven, and the
 * `dependencies` graph says who pulled in what.
 */
tasks.register("sbom") {
  val out = layout.buildDirectory.file("reports/bom.json")
  outputs.file(out)
  // Timestamped and classpath-dependent: never reuse a stale one.
  outputs.upToDateWhen { false }
  doLast {
    val cfg = configurations.getByName("releaseRuntimeClasspath")
    val appRef = "pkg:generic/DriveRemoteController@$appVersion"
    fun purl(id: ComponentIdentifier): String? =
      (id as? ModuleComponentIdentifier)?.let { "pkg:maven/${it.group}/${it.module}@${it.version}" }

    val dependsOn = sortedMapOf<String, List<String>>()
    val modules = sortedMapOf<String, ModuleComponentIdentifier>()
    fun walk(c: ResolvedComponentResult, ref: String) {
      if (dependsOn.containsKey(ref)) return
      val next =
        c.dependencies.filterIsInstance<ResolvedDependencyResult>().mapNotNull { d ->
          purl(d.selected.id)?.let { it to d.selected }
        }
      dependsOn[ref] = next.map { it.first }.distinct().sorted()
      next.forEach { (r, sel) ->
        modules[r] = sel.id as ModuleComponentIdentifier
        walk(sel, r)
      }
    }
    walk(cfg.incoming.resolutionResult.rootComponent.get(), appRef)

    // A module without an artifact -- a BOM, or a multiplatform umbrella
    // pointing at its -jvm variant -- is listed without hashes rather than
    // dropped, so the graph stays complete.
    val files =
      cfg.resolvedConfiguration.resolvedArtifacts.groupBy(
        { with(it.moduleVersion.id) { "pkg:maven/$group/$name@$version" } },
        { it.file },
      )
    val comps =
      modules.entries.joinToString(",\n") { (ref, id) ->
        val hashes =
          (files[ref] ?: emptyList()).distinct().sortedBy { it.name }.joinToString(", ") {
            """{"alg": "SHA-256", "content": "${sha256(it)}"}"""
          }
        """    {"type": "library", "group": "${id.group}", "name": "${id.module}", """ +
          """"version": "${id.version}", "purl": "$ref", "bom-ref": "$ref", "hashes": [$hashes]}"""
      }
    val deps =
      dependsOn.entries.joinToString(",\n") { (ref, on) ->
        """    {"ref": "$ref", "dependsOn": [${on.joinToString(", ") { "\"$it\"" }}]}"""
      }

    out.get().asFile.apply { parentFile.mkdirs() }.writeText(
      """{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "serialNumber": "urn:uuid:${UUID.randomUUID()}",
  "version": 1,
  "metadata": {
    "timestamp": "${Instant.now()}",
    "tools": {
      "components": [
        {"type": "application", "name": "Gradle", "version": "${gradle.gradleVersion}"},
        {"type": "application", "name": "drive-remote-controller sbom task", "version": "$appVersion"}
      ]
    },
    "component": {"type": "application", "name": "DriveRemoteController", "version": "$appVersion", "bom-ref": "$appRef"}
  },
  "components": [
$comps
  ],
  "dependencies": [
$deps
  ]
}
"""
    )
  }
}

/**
 * `gradlew :app:printVersion` writes the version to app/build/version.txt and
 * prints it. The release workflow reads the FILE, not Gradle's last stdout line:
 * anything else printed during a build would otherwise become the release tag.
 */
tasks.register("printVersion") {
  val out = layout.buildDirectory.file("version.txt")
  outputs.file(out)
  outputs.upToDateWhen { false }
  doLast {
    out.get().asFile.apply { parentFile.mkdirs() }.writeText("$appVersion\n")
    println(appVersion)
  }
}
