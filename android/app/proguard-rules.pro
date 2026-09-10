# R8 keep rules for the release build. See docs/BUILDING.md section 6.3.1.
#
# This file is deliberately almost empty, and that is the end state rather than
# an omission.
#
# It used to carry a blunt `-keep class com.google.crypto.tink.** { *; }` plus a
# `-dontwarn` for three families of unresolved reference Tink drags in. Tink
# reached this build only through androidx.security-crypto, whose only caller was
# LegacySecureStoreMigration.kt -- a one-time move off that library's
# EncryptedSharedPreferences. The migration has run on every station, so the file,
# the dependency and these rules all went together.
#
# That keep was expensive: R8's seeds.txt showed 20,682 of 21,562 retained
# entries were Tink, 96% of everything the shrinker was forbidden to touch.
#
# Everything else the app depends on ships its own consumer rules, verified by
# unzipping the artifacts rather than assumed:
#
#   okhttp 4.12               META-INF/proguard/okhttp3.pro
#   kotlinx.serialization     META-INF/com.android.tools/r8/kotlinx-serialization-r8.pro
#   Compose, AGP, AndroidX    consumer rules inside the AARs
#
# The token store now uses the platform's own AES-256-GCM under an Android
# Keystore key (KeystoreEncryptedPreferences), which needs no keep rules: it
# reflects on nothing.
#
# Add rules here only in response to an observed failure. A rule written on a
# guess silently defeats the shrinking it was added for, and nothing reports it.
