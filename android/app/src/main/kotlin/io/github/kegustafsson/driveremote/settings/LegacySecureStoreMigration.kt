package io.github.kegustafsson.driveremote.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * One-time move of the secure store from `androidx.security:security-crypto`'s
 * EncryptedSharedPreferences to [KeystoreEncryptedPreferences].
 *
 * ## THIS FILE IS MEANT TO BE DELETED
 *
 * It is the only remaining caller of the deprecated `MasterKey` and
 * `EncryptedSharedPreferences`, and it is the reason this project still emits
 * deprecation warnings for them. That is deliberate and it is not suppressed:
 * the warnings are an accurate statement that a deprecated API is still
 * compiled in, and the honest way to clear them is to delete this file once
 * every station has run a build that contains it -- not to hide them.
 *
 * There is no way to avoid the deprecated call while still migrating. The old
 * values are encrypted with Tink keysets in that library's own format; reading
 * them without the library would mean reimplementing Tink.
 *
 * When it goes, any station that has not launched a migrating build simply
 * asks for a new access token, which is a one-tap recovery on the server.
 *
 * ## Failure is not fatal
 *
 * If the old store cannot be opened or read -- keyset invalidated, data
 * restored from another device, library gone -- the migration gives up and
 * leaves the new store empty. The station then behaves exactly as a fresh
 * install: it requests a token. Losing a token costs an access request;
 * crashing on a helm screen costs more.
 */
internal object LegacySecureStoreMigration {

  private const val TAG = "SecureStoreMigration"
  private const val LEGACY_FILE = "drive_remote_secure"
  private const val MIGRATED_FLAG = "migrated_from_security_crypto"

  /** Every key the legacy store held. */
  private val STRING_KEYS =
    listOf(
      SettingsStore.KEY_CLIENT_ID,
      SettingsStore.KEY_TOKEN,
      SettingsStore.KEY_TOKEN_SERVER,
    )
  private val LONG_KEYS =
    listOf(SettingsStore.KEY_SESSION_GENERATION, SettingsStore.KEY_TOKEN_EXPIRY)

  /**
   * Copies the legacy values into [target] if that has not happened yet.
   *
   * Synchronous and committed: [SettingsStore] reads the client id and session
   * generation immediately after construction, so the move has to be complete
   * before this returns.
   */
  fun migrateIfNeeded(context: Context, target: SharedPreferences) {
    if (target.getBoolean(MIGRATED_FLAG, false)) return

    val legacy = openLegacy(context)
    if (legacy != null) {
      val editor = target.edit()
      var moved = 0
      for (key in STRING_KEYS) {
        legacy.getString(key, null)?.let {
          editor.putString(key, it)
          moved++
        }
      }
      for (key in LONG_KEYS) {
        if (legacy.contains(key)) {
          editor.putLong(key, legacy.getLong(key, 0L))
          moved++
        }
      }
      editor.putBoolean(MIGRATED_FLAG, true)
      if (editor.commit()) {
        Log.i(TAG, "moved $moved value(s) out of the legacy secure store")
        // Only after the new store is durably written. Losing the old copy
        // before the new one is on disk would lose the token outright.
        runCatching { legacy.edit().clear().commit() }
      }
      return
    }

    // No legacy store, or it could not be opened. Either way there is nothing
    // to move and no reason to try again on every launch.
    target.edit().putBoolean(MIGRATED_FLAG, true).commit()
  }

  /**
   * The legacy store, or null if it does not exist or will not open.
   *
   * The deprecation warnings this produces are the point -- see the file
   * comment. Do not add @Suppress here.
   */
  private fun openLegacy(context: Context): SharedPreferences? {
    val app = context.applicationContext
    // getSharedPreferences would CREATE the file, so check first: a fresh
    // install has no legacy store and must not be given an empty one.
    val exists =
      runCatching {
          java.io.File(app.applicationInfo.dataDir, "shared_prefs/$LEGACY_FILE.xml").exists()
        }
        .getOrDefault(false)
    if (!exists) return null

    return runCatching {
        val masterKey =
          androidx.security.crypto.MasterKey.Builder(app)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
          app,
          LEGACY_FILE,
          masterKey,
          androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
      }
      .onFailure { Log.w(TAG, "legacy secure store will not open; starting fresh", it) }
      .getOrNull()
  }
}
