package io.github.kegustafsson.driveremote.settings

import android.content.Context
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
 * The token is kept in [KeystoreEncryptedPreferences] rather than DataStore
 * because it authorises commanding machinery -- it is the one stored value
 * whose disclosure matters. Everything else is ordinary preferences.
 */
class SettingsStore
internal constructor(
  context: Context,
  // Test seam only: the Keystore does not exist under Robolectric, and the
  // key-recovery paths below cannot be exercised without a key source that
  // fails and then recovers.
  private val secureFactory: (Context) -> KeystoreEncryptedPreferences,
) {

  constructor(context: Context) : this(context, { ctx ->
    KeystoreEncryptedPreferences(ctx, SECURE_FILE, SECURE_KEY_ALIAS)
  })

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
  private val secure: KeystoreEncryptedPreferences by lazy { secureFactory(appContext) }

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
   *
   * **A stored id that cannot be read right now is not an absent one.** Minting
   * and writing a new id in that state used to DELETE the stored one -- the
   * write fails without a key and the store removes the entry rather than leave
   * a stale value -- so a single launch-time Keystore hiccup made this station a
   * new device, needing an admin to approve it again. So a new id is persisted
   * only when there is genuinely none and the key works. Otherwise this process
   * runs on an in-memory id and leaves the stored one alone for the next launch.
   * That is protocol-safe: a fresh id per process is exactly how the browser UI
   * already presents itself, and the arbiter keys nothing on it but bookkeeping.
   */
  fun clientId(): String {
    synchronized(cacheLock) { ephemeralClientId?.let { return it } }
    secure.getString(KEY_CLIENT_ID, null)?.let { return it }
    // Asking for the key retries a failed load. If that brings it back, read
    // again: the first read may have missed a stored id only because the key
    // was not there yet, and the decision below must rest on the same key
    // state as the read it follows.
    if (secure.keyAvailable) {
      secure.getString(KEY_CLIENT_ID, null)?.let { return it }
      if (!secure.contains(KEY_CLIENT_ID)) {
        return UUID.randomUUID().toString().also {
          secure.edit().putString(KEY_CLIENT_ID, it).apply()
        }
      }
    }
    synchronized(cacheLock) {
      return ephemeralClientId ?: UUID.randomUUID().toString().also { ephemeralClientId = it }
    }
  }

  /** This process's id when the stored one cannot be read; see [clientId]. */
  private var ephemeralClientId: String? = null

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

  // The token, the server that issued it and its stated expiry, held in memory
  // after the first read.
  //
  // The encrypted file is still the persistence layer and still the only place
  // any of this is stored; this is a cache in front of it, and it exists because
  // of how often it is read. Every token() and tokenExpired() is an AES-GCM
  // decrypt against a key in the Android Keystore, and both are called on the
  // main thread on every 250 ms heartbeat tick -- four Keystore round trips a
  // second, for values that change only when setToken() is called.
  //
  // Filled on first read and replaced by setToken(), which is the ONLY writer of
  // these three keys, so the cache cannot silently diverge from the file. The
  // lock is there because the readers are not all on one dispatcher -- the same
  // reasoning as IntentPoster's authLock, and just as cheap: no I/O inside it.
  private val cacheLock = Any()
  private var cacheLoaded = false
  private var cachedToken: String? = null
  private var cachedTokenServer: String? = null
  /** Null means the server stated no expiry, which is not the same as "expired". */
  private var cachedExpiryMs: Long? = null

  /** Caller must hold [cacheLock]. */
  private fun loadCache() {
    if (cacheLoaded) return
    // Not cached when no key can be had: a read then sees "nothing stored" only
    // because nothing COULD be read, and caching it would keep a launch-time
    // Keystore hiccup as a missing token for the whole process. The key is
    // asked for FIRST, because asking retries the load -- a read made before
    // it could miss a token that the same call then went on to find the key for.
    if (!secure.keyAvailable) {
      cachedToken = null
      cachedTokenServer = null
      cachedExpiryMs = null
      return
    }
    cachedToken = secure.getString(KEY_TOKEN, null)
    cachedTokenServer = secure.getString(KEY_TOKEN_SERVER, null)
    cachedExpiryMs =
      if (secure.contains(KEY_TOKEN_EXPIRY)) secure.getLong(KEY_TOKEN_EXPIRY, Long.MAX_VALUE)
      else null
    cacheLoaded = true
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
   *
   * The binding is by `host:port` and does NOT include the scheme, so the same
   * host reached over https and over http counts as the same issuer. That is
   * the key the token was stored under before this was noticed, and changing it
   * now would invalidate every stored token on upgrade for no safety gain --
   * the cleartext gate ([io.github.kegustafsson.driveremote.core.isPrivateHost])
   * is what decides whether the token may travel in the clear, not this.
   */
  fun token(forServer: ServerAddress? = null): String? {
    synchronized(cacheLock) {
      loadCache()
      val token = cachedToken ?: return null
      if (forServer == null) return token
      // A token stored before this binding existed has no recorded server. Treat
      // it as belonging to the configured one rather than forcing a
      // re-authorisation on upgrade.
      val issuedBy = cachedTokenServer
      if (issuedBy != null && issuedBy != forServer.toString()) return null
      return token
    }
  }

  /** Which server issued the stored token, if one is held. */
  fun tokenServer(): String? =
    synchronized(cacheLock) {
      loadCache()
      cachedTokenServer
    }

  fun setToken(token: String?, expiresAtMs: Long?, server: ServerAddress? = null) {
    synchronized(cacheLock) {
      secure
        .edit()
        .apply {
          if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
          if (expiresAtMs == null) remove(KEY_TOKEN_EXPIRY)
          else putLong(KEY_TOKEN_EXPIRY, expiresAtMs)
          if (token == null || server == null) remove(KEY_TOKEN_SERVER)
          else putString(KEY_TOKEN_SERVER, server.toString())
        }
        .apply()
      // Refreshed here rather than invalidated: this is the whole of what was
      // written, so the next read has no reason to go back to the Keystore.
      cachedToken = token
      cachedExpiryMs = expiresAtMs
      cachedTokenServer = if (token == null) null else server?.toString()
      cacheLoaded = true
    }
  }

  /**
   * Has the stored token passed its stated expiry?
   *
   * A token with no stated expiry is never considered expired: the server did
   * not say, so the app uses it until the server rejects it. Guessing an expiry
   * would disarm a working station for no reason.
   */
  fun tokenExpired(nowMs: Long): Boolean {
    synchronized(cacheLock) {
      loadCache()
      val expiry = cachedExpiryMs ?: return false
      return expiry <= nowMs
    }
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
