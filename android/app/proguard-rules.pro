# R8 keep rules for the release build. See docs/BUILDING.md section 6.3.1.
#
# Tink, reached through androidx.security:security-crypto, is the only
# dependency here that needs hand-written rules: neither the security-crypto AAR
# nor tink-android ships its own. SettingsStore keeps the Signal K token in
# EncryptedSharedPreferences, so a stripped key manager reads as a server-side
# auth failure -- in the release build only.
#
# This keep is deliberately blunt and costs most of the available shrinking
# (~96% of everything R8 keeps). Narrow it against
# app/build/outputs/mapping/release/usage.txt only with a phone to hand: the
# failure it guards against is silent at build time.
#
# THIS WHOLE BLOCK GOES WHEN LegacySecureStoreMigration.kt GOES. Tink reaches
# this build only through androidx.security-crypto, and the migration file is
# now its only caller -- SettingsStore moved to KeystoreEncryptedPreferences,
# which uses the platform's own AES-GCM and pulls in no Tink at all. Deleting
# the migration therefore removes the dependency, these rules, and with them
# the 96%: the release APK should shrink substantially. Do them together.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# Tink carries references R8 cannot resolve, and each unresolved reference is a
# hard ERROR, not a warning. There are three families of them and they surface
# one family per build, so fixing them individually is several rounds of
# whack-a-mole:
#
#   com.google.errorprone.annotations.*   compile-only annotations
#   com.google.api.client.http.*          google-api-client, from KeysDownloader
#   org.joda.time.Instant                 joda-time, from KeysDownloader
#
# KeysDownloader fetches keysets over HTTP. This app never uses it -- the token
# store is local -- but `-keep class com.google.crypto.tink.**` above keeps it,
# so its references have to resolve or be silenced.
#
# One rule covers all three: -dontwarn suppresses unresolved references
# ORIGINATING IN the named classes, and every one of these originates in Tink.
-dontwarn com.google.crypto.tink.**

# The narrower alternative, if you would rather name what is missing than who
# refers to it. Equivalent here, and AGP regenerates this list into
# app/build/outputs/mapping/release/missing_rules.txt whenever it changes:
#
#   -dontwarn com.google.errorprone.annotations.**
#   -dontwarn com.google.api.client.http.**
#   -dontwarn org.joda.time.Instant

# OkHttp, Compose, AndroidX and kotlinx.serialization all ship their own
# consumer rules; nothing is needed for them here. Add rules below only in
# response to an observed failure -- a rule written on a guess silently defeats
# the shrinking it was added for, and nothing reports that.
