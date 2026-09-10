package io.github.kegustafsson.driveremote.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.kegustafsson.driveremote.core.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "drive_remote_settings")

/**
 * Persisted settings: which server to talk to, this station's identity, and its
 * access token.
 *
 * The token is kept in [EncryptedSharedPreferences] rather than DataStore
 * because it authorises commanding machinery -- it is the one stored value
 * whose disclosure matters. Everything else is ordinary preferences.
 */
class SettingsStore(context: Context) {

  private val appContext = context.applicationContext

  // AES-256-GCM under a key held in the Android Keystore. This replaced
  // androidx.security-crypto's EncryptedSharedPreferences, which 1.1.0
  // deprecated without a replacement; KeystoreEncryptedPreferences presents the
  // same SharedPreferences interface so nothing below this line changed --
  // including the commit()/apply() distinction nextSessionGeneration depends on.
  //
  // There was a one-time migration here, off androidx.security-crypto's
  // EncryptedSharedPreferences. It has been deleted: every station ran it, and
  // keeping it meant keeping that library, Tink, and R8 keep rules for both.
  //
  // A station that somehow never ran a migrating build loses its stored token
  // and asks for a new one -- the same recovery as any unreadable token.
  private val secure: SharedPreferences by lazy {
    KeystoreEncryptedPreferences(appContext, SECURE_FILE, SECURE_KEY_ALIAS)
  }

  /** The configured server, or null until one has been chosen. */
  val serverAddress: Flow<ServerAddress?> =
    appContext.dataStore.data.map { prefs ->
      val host = prefs[KEY_HOST] ?: return@map null
      val port = prefs[KEY_PORT] ?: ServerAddress.DEFAULT_PORT
      val tls = prefs[KEY_TLS] ?: false
      runCatching { ServerAddress(host, port, tls) }.getOrNull()
    }

  suspend fun setServerAddress(address: ServerAddress) {
    appContext.dataStore.edit { prefs ->
      prefs[KEY_HOST] = address.host
      prefs[KEY_PORT] = address.port
      prefs[KEY_TLS] = address.useTls
    }
  }

  /**
   * This station's stable identity, generated once and kept.
   *
   * Two stations must never share one: the arbiter keys its per-client
   * bookkeeping on it, and two clients presenting the same id would be treated
   * as one -- which is exactly the state in which two devices could disagree
   * about being armed. A v4 UUID, as the access-request spec requires.
   *
   * It survives reinstall only as far as the app's data does; a fresh id simply
   * means a fresh access request, which is the correct outcome.
   */
  fun clientId(): String =
    secure.getString(KEY_CLIENT_ID, null)
      ?: UUID.randomUUID().toString().also { secure.edit().putString(KEY_CLIENT_ID, it).apply() }

  /**
   * Claim the next session generation: a strictly increasing integer, bumped
   * once per process start and persisted.
   *
   * ORDERED on purpose, where [clientId] is merely unique. The arbiter needs to
   * compare two sessions, not just tell them apart: `clientId` survives a
   * relaunch (the access token is issued to that device id) while `seq` and the
   * arm/disarm counters restart at 0, so a restart inside the arbiter's stale
   * timeout looks exactly like a replayed packet. A generation says which of two
   * sessions is newer, so the older one can be closed permanently -- a uuid
   * would only say "different", and the arbiter would have to remember dead ids
   * and eventually forget one.
   *
   * Written synchronously (`commit`, not `apply`): the value must be on disk
   * before the first intent carries it, or a crash-and-relaunch could reuse a
   * generation the server has already seen -- which is the one thing an order
   * cannot survive.
   *
   * Clearing app data resets this to 1, and also clears [clientId] -- so the
   * station returns as a different device needing a fresh token, and the server
   * has no record of the old generations under that id.
   */
  @Suppress("ApplySharedPref") // see below: ordering beats latency here
  fun nextSessionGeneration(): Long {
    // Floored by the wall clock, so the value is strictly increasing across
    // launches EVEN IF it cannot be stored.
    //
    // Two failures have to be avoided at once and they pull in opposite
    // directions. Reusing a generation makes the arbiter read the relaunch as
    // the SAME session and filter its packets -- STOP included -- behind the
    // retained sequence baseline. But sending NO generation is worse than it
    // sounds: the arbiter closes a null session whenever a record or watermark
    // holds a numbered one for that client, and the watermark outlives eviction,
    // so an established station would be rejected indefinitely rather than
    // falling back to legacy behaviour. That is a bricked helm station, and it
    // is what this method did before -- I reasoned about the record and forgot
    // the watermark.
    //
    // `max(stored + 1, now)` needs no successful write to keep increasing: the
    // clock has moved on by the next launch. The stored value is a FLOOR, which
    // is what protects against the clock going backwards. Persisting is still
    // attempted, and still worth doing -- it just is not load-bearing.
    val stored = secure.getLong(KEY_SESSION_GENERATION, 0L)
    val next = maxOf(stored + 1L, System.currentTimeMillis())
    secure.edit().putLong(KEY_SESSION_GENERATION, next).commit()
    return next
  }

  /**
   * The Signal K access token, or null if we do not hold one.
   *
   * Pass the server it is wanted for. A token is issued BY one server and is
   * meaningless at another, so it is stored with the address it belongs to and
   * withheld if the caller is asking on behalf of a different one. Without
   * that check, pointing the app at a second server would send it straight to
   * the control screen holding the first server's token, and the operator
   * would watch it fail as a 401 rather than simply being asked to authorise
   * the new server.
   */
  fun token(forServer: ServerAddress? = null): String? {
    val token = secure.getString(KEY_TOKEN, null) ?: return null
    if (forServer == null) return token
    val issuedBy = secure.getString(KEY_TOKEN_SERVER, null)
    // A token stored before this binding existed has no recorded server. Treat
    // it as belonging to the configured one rather than forcing a
    // re-authorisation on upgrade.
    if (issuedBy != null && issuedBy != forServer.toString()) return null
    return token
  }

  /** Which server issued the stored token, if one is held. */
  fun tokenServer(): String? = secure.getString(KEY_TOKEN_SERVER, null)

  fun setToken(token: String?, expiresAtMs: Long?, server: ServerAddress? = null) {
    secure
      .edit()
      .apply {
        if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        if (expiresAtMs == null) remove(KEY_TOKEN_EXPIRY) else putLong(KEY_TOKEN_EXPIRY, expiresAtMs)
        if (token == null || server == null) remove(KEY_TOKEN_SERVER)
        else putString(KEY_TOKEN_SERVER, server.toString())
      }
      .apply()
  }

  /**
   * Has the stored token passed its stated expiry?
   *
   * A token with no stated expiry is never considered expired: the server did
   * not say, so the app uses it until the server rejects it. Guessing an expiry
   * would disarm a working station for no reason.
   */
  fun tokenExpired(nowMs: Long): Boolean {
    if (!secure.contains(KEY_TOKEN_EXPIRY)) return false
    return secure.getLong(KEY_TOKEN_EXPIRY, Long.MAX_VALUE) <= nowMs
  }

  internal companion object {
    /** The encrypted file, and the Keystore alias protecting it. */
    const val SECURE_FILE = "drive_remote_secure_v2"
    const val SECURE_KEY_ALIAS = "drive_remote_secure"

    val KEY_HOST = stringPreferencesKey("server_host")
    val KEY_PORT = intPreferencesKey("server_port")
    val KEY_TLS = booleanPreferencesKey("server_tls")

    const val KEY_CLIENT_ID = "client_id"
    const val KEY_SESSION_GENERATION = "session_generation"
    const val KEY_TOKEN = "sk_token"
    const val KEY_TOKEN_EXPIRY = "sk_token_expiry"
    const val KEY_TOKEN_SERVER = "sk_token_server"
  }
}
