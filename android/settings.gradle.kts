// The Android command station, split the same way the rest of this project is:
//
//   core/  pure Kotlin, no Android imports  -- the analogue of lib/control_core/
//                                              (firmware) and arbiter.cjs (plugin)
//   app/   Android glue: Compose, NsdManager, OkHttp, lifecycle
//          -- the analogue of src/ (firmware) and index.cjs (plugin)
//
// AGENTS.md's rule is that anything which MAKES A DECISION lives in the pure,
// host-tested layer, and the platform layer only reads inputs, calls the core
// and writes outputs. That rule is what makes the safety logic testable without
// a boat, and it applies here for the same reason.

pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "drive-remote-controller-android"

include(":core")

// `app` is included only when an Android SDK is actually present.
//
// Gradle configures EVERY included project even for a build that only targets
// :core, so an unconditional include(":app") makes `./gradlew :core:test` fail
// on a machine without the SDK -- which would put the pure safety logic's test
// suite behind an Android toolchain it does not use or need. The core module is
// plain Kotlin/JVM and must stay runnable anywhere, exactly as `pio test -e
// native` runs the firmware's core without an ESP32 attached.
val androidSdkPresent =
  sequenceOf(
      System.getenv("ANDROID_HOME"),
      System.getenv("ANDROID_SDK_ROOT"),
      file("local.properties")
        .takeIf { it.exists() }
        ?.let { java.util.Properties().apply { it.inputStream().use(::load) } }
        ?.getProperty("sdk.dir"),
    )
    .filterNotNull()
    .any { it.isNotBlank() && file(it).isDirectory }

/**
 * Does this task name actually target the :app module?
 *
 * Matched on task-path structure, NOT as a substring: `wrapper` contains "app"
 * (wr-app-er), so a naive `contains("app")` made `gradle wrapper` fail with a
 * missing-SDK error on a machine that was never asking for :app.
 */
fun targetsAppModule(taskName: String): Boolean {
  val path = taskName.removePrefix(":")
  return path == "app" || path.startsWith("app:")
}

if (androidSdkPresent) {
  include(":app")
} else {
  if (gradle.startParameter.taskNames.any(::targetsAppModule)) {
    throw GradleException(
      "No Android SDK found -- set ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in " +
        "local.properties to build :app. The pure core still builds and tests " +
        "without it: ./gradlew :core:test"
    )
  }
  logger.lifecycle(
    "No Android SDK found -- configuring :core only. ./gradlew :core:test works; " +
      ":app needs ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in local.properties."
  )
}
