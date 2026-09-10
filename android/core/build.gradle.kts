// Pure Kotlin/JVM. NOTHING Android-specific may enter this module -- no
// android.* imports, no Compose, no OkHttp, no clock reads of its own. Every
// function here is a decision the safety rules depend on, and every one of them
// is pinned by a vector test ported one-for-one from the plugin's vitest suite.
//
// The same rule as lib/control_core/ in the firmware: if it decides something,
// it lives here and is testable on a laptop.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// Emit Java 17 bytecode -- what the Android Gradle Plugin's desugaring and
// D8/R8 expect -- without pinning a *specific* JDK to build with. A
// `jvmToolchain(17)` would demand a JDK 17 installation and fail on a machine
// that has only 21, which buys nothing here: the constraint is the bytecode
// level :app consumes, not the compiler that produced it. Any JDK >= 17 works.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  // kotlinx.serialization is pure Kotlin -- no Android dependency -- so parsing
  // and building the wire formats stays in the tested core rather than drifting
  // into the platform layer where it could only be exercised on a device.
  //
  // `implementation`, not `api`: the core's public surface deals in Kotlin
  // types (data classes, maps, enums) and never hands a JsonElement to a
  // caller. Hiding the wire format is the point of having this module -- :app
  // should not be able to reach past it and parse a delta itself.
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
  testLogging { events("failed") }

  // Print the totals. `pio test -e native` and `npm test` both end with a count,
  // and the JOURNAL records those counts as the evidence a change was verified;
  // a silent green Gradle build cannot be quoted the same way.
  addTestListener(
    object : TestListener {
      override fun beforeSuite(suite: TestDescriptor) = Unit
      override fun beforeTest(test: TestDescriptor) = Unit
      override fun afterTest(test: TestDescriptor, result: TestResult) = Unit
      override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        if (suite.parent == null) {
          logger.lifecycle(
            "core: ${result.testCount} tests, ${result.successfulTestCount} passed, " +
              "${result.failedTestCount} failed, ${result.skippedTestCount} skipped"
          )
        }
      }
    }
  )
}
