// Root build file. Every plugin either module uses is declared here with
// `apply false`, and each module applies the ones it needs. This is the layout
// the Kotlin and Android Gradle plugins support; the two tempting alternatives
// both fail, and both fail in ways that read as version problems when they are
// not:
//
//   1. Declare only `kotlin.jvm` here. `kotlin.jvm` (:core) and
//      `kotlin.android` (:app) ship in the SAME artifact --
//      org.jetbrains.kotlin:kotlin-gradle-plugin -- so this puts that artifact
//      on a classloader :app inherits. :app's own request for `kotlin.android`
//      then finds the class already loaded, cannot tell which version it came
//      from, and fails with "already on the classpath with an unknown version".
//
//   2. Declare the Kotlin plugins here but leave AGP in :app. Resolution now
//      succeeds, but the Kotlin plugin sits in the PARENT classloader while AGP
//      sits in :app's child one, and a parent cannot see its child. Applying
//      `kotlin.android` dies on NoClassDefFoundError:
//      com/android/build/gradle/api/BaseVariant.
//
//   3. Declare nothing here and let each module carry its own versioned
//      plugins. :app then gets the Kotlin plugin and AGP in one classloader and
//      the build runs -- but the Kotlin plugin warns that it "was loaded
//      multiple times in different subprojects, which is not supported and may
//      break the build". Not something to ship under SAFETY.md.
//
// So AGP is named here too. The cost is that `./gradlew :core:test` now
// RESOLVES AGP even on a machine with no Android SDK. It still passes there --
// settings.gradle.kts keeps :app out of the build, and an unapplied plugin
// never runs its SDK check -- so the guarantee that matters (the pure safety
// logic is testable without an Android toolchain) holds. What it costs is the
// download, not the outcome.
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.android.application) apply false
}
