package io.github.kegustafsson.driveremote.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SharedPreferences] whose VALUES are encrypted with a key held in the
 * Android Keystore.
 *
 * This replaces `androidx.security:security-crypto`'s EncryptedSharedPreferences,
 * which that library deprecated in 1.1.0 without shipping a replacement -- the
 * library is finished, and AndroidX's guidance is to do this yourself.
 *
 * ## Why it implements SharedPreferences rather than a smaller interface
 *
 * It stores the Signal K access token, the client id and the session
 * generation. The session generation in particular has an ordering requirement
 * that is easy to break: it must be on disk BEFORE the intent carrying it goes
 * out, which is why [SettingsStore] calls `commit()` there and `apply()`
 * everywhere else. Presenting the same interface means the calling code -- and
 * that distinction -- does not change at all in this rewrite. A smaller custom
 * interface would have meant re-deriving those semantics by hand, in the one
 * store whose contents authorise commanding a clutch and a thruster.
 *
 * ## What is and is not protected
 *
 * Values are encrypted; KEY NAMES ARE NOT. The old library encrypted key names
 * too (AES256-SIV). That is a deliberate reduction: the names here are
 * `sk_token`, `client_id` and so on. They reveal that a token exists, never its
 * value, and the file is already inside app-private storage. If a key name ever
 * becomes sensitive, this is the decision to revisit.
 *
 * AES-256-GCM, a fresh random IV per write (enforced by
 * `setRandomizedEncryptionRequired`), the IV stored alongside the ciphertext.
 * GCM authenticates, so a tampered value fails to decrypt rather than
 * decrypting to something else.
 *
 * ## Failing to read is treated as absent, never as a crash
 *
 * A Keystore key can become unusable -- app data restored to another device,
 * the key invalidated, the store corrupted. Every read catches that and returns
 * the default, so the station behaves as though it holds no token: it asks for
 * a new one through the access-request flow. The alternative, throwing on a
 * helm screen, is worse. [decryptFailures] counts these so a caller can tell
 * "no token yet" from "a token that will not decrypt" if it ever matters.
 */
class KeystoreEncryptedPreferences(
  context: Context,
  fileName: String,
  private val keyAlias: String,
  /**
   * Where the AES key comes from. Defaults to the Android Keystore.
   *
   * Injectable because the Keystore does NOT exist off-device: under
   * Robolectric `KeyStore.getInstance("AndroidKeyStore")` throws
   * "AndroidKeyStore not found". Without this seam the whole class would be
   * untestable on the JVM, which for the store holding the token that
   * authorises commanding a clutch is not an acceptable place to have no
   * tests. Tests inject an ordinary JCE AES key; everything else -- the
   * sealing, the IV handling, the storage format, the failure behaviour -- is
   * then exercised for real. What stays untested is only where the key lives.
   */
  private val keySource: () -> SecretKey? = { null },
) : SharedPreferences {

  constructor(
    context: Context,
    fileName: String,
    keyAlias: String,
  ) : this(context, fileName, keyAlias, keySource = { null }) {
    useAndroidKeystore = true
  }

  private var useAndroidKeystore = false

  private val delegate: SharedPreferences =
    context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

  /** Reads that failed to decrypt since construction. Diagnostic only. */
  @Volatile
  var decryptFailures: Int = 0
    private set

  /**
   * Writes dropped because no key was available.
   *
   * Non-zero means this store is not persisting anything: the value is thrown
   * away rather than written in the clear, and reads therefore look like
   * "nothing stored". A station in that state asks for a new access token on
   * every launch. That is the safe direction, but it is silent, so it is
   * counted here rather than left to be inferred.
   */
  @Volatile
  var writesDroppedWithoutKey: Int = 0
    private set

  /** False when no key could be obtained -- see [writesDroppedWithoutKey]. */
  val keyAvailable: Boolean
    get() = secretKey != null

  // ---------------------------------------------------------------- crypto --

  private val secretKey: SecretKey? by lazy {
    if (useAndroidKeystore) loadOrCreateKey() else keySource()
  }

  private fun loadOrCreateKey(): SecretKey? =
    runCatching {
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        ?: generateKey()
    }.getOrNull()

  private fun generateKey(): SecretKey =
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              keyAlias,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // A fresh IV per encryption, generated by the platform. Reusing an
            // IV with GCM is catastrophic rather than merely weak, so this is
            // left to the system instead of to this file.
            .setRandomizedEncryptionRequired(true)
            // No user authentication requirement: the station must be able to
            // read its token to reconnect while the phone is locked in a
            // pocket. Confidentiality here is against another app or an offline
            // reader of app storage, not against someone holding the unlocked
            // phone -- who can drive the helm screen anyway.
            .setUserAuthenticationRequired(false)
            .build()
        )
      }
      .generateKey()

  private fun encrypt(plaintext: String): String? =
    runCatching {
      val key = secretKey ?: return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val iv = cipher.iv
      require(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
      val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
      Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrNull()

  private fun decrypt(stored: String): String? =
    runCatching {
      val key = secretKey ?: return null
      val blob = Base64.decode(stored, Base64.NO_WRAP)
      if (blob.size <= IV_BYTES) return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        key,
        GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
      )
      String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }.getOrElse {
      decryptFailures++
      null
    }

  private fun readString(key: String): String? =
    delegate.getString(key, null)?.let { decrypt(it) }

  // ------------------------------------------------------- SharedPreferences --

  /**
   * Decrypted values, for the keys that hold strings. Provided for interface
   * completeness; [SettingsStore] does not use it.
   */
  override fun getAll(): MutableMap<String, Any?> =
    delegate.all.keys.associateWith { readString(it) }.toMutableMap()

  override fun getString(key: String, defValue: String?): String? = readString(key) ?: defValue

  override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
    readString(key)?.split(SET_SEPARATOR)?.filter { it.isNotEmpty() }?.toMutableSet() ?: defValues

  override fun getInt(key: String, defValue: Int): Int = readString(key)?.toIntOrNull() ?: defValue

  override fun getLong(key: String, defValue: Long): Long =
    readString(key)?.toLongOrNull() ?: defValue

  override fun getFloat(key: String, defValue: Float): Float =
    readString(key)?.toFloatOrNull() ?: defValue

  override fun getBoolean(key: String, defValue: Boolean): Boolean =
    readString(key)?.toBooleanStrictOrNull() ?: defValue

  override fun contains(key: String): Boolean = delegate.contains(key)

  override fun edit(): SharedPreferences.Editor = Editor()

  override fun registerOnSharedPreferenceChangeListener(
    listener: SharedPreferences.OnSharedPreferenceChangeListener
  ) = delegate.registerOnSharedPreferenceChangeListener(listener)

  override fun unregisterOnSharedPreferenceChangeListener(
    listener: SharedPreferences.OnSharedPreferenceChangeListener
  ) = delegate.unregisterOnSharedPreferenceChangeListener(listener)

  /**
   * Values are encrypted as they are staged, so `commit()` and `apply()` keep
   * the delegate's own semantics exactly: commit writes synchronously and
   * reports success, apply is asynchronous. [SettingsStore.nextSessionGeneration]
   * depends on that difference.
   */
  private inner class Editor : SharedPreferences.Editor {
    private val staged = delegate.edit()

    private fun put(key: String, value: String): SharedPreferences.Editor {
      val sealed = encrypt(value)
      // Encryption failing must not leave a STALE value readable under this key,
      // and must never fall back to storing the plaintext. Remove it: absent is
      // a state every caller handles, stale is not, and clear is a security
      // regression. Counted, because otherwise the store would quietly stop
      // persisting anything and nothing would say so.
      if (sealed == null) {
        writesDroppedWithoutKey++
        staged.remove(key)
      } else {
        staged.putString(key, sealed)
      }
      return this
    }

    override fun putString(key: String, value: String?): SharedPreferences.Editor =
      if (value == null) remove(key) else put(key, value)

    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
      if (values == null) remove(key) else put(key, values.joinToString(SET_SEPARATOR))

    override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value.toString())

    override fun putLong(key: String, value: Long): SharedPreferences.Editor =
      put(key, value.toString())

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
      put(key, value.toString())

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
      put(key, value.toString())

    override fun remove(key: String): SharedPreferences.Editor {
      staged.remove(key)
      return this
    }

    override fun clear(): SharedPreferences.Editor {
      staged.clear()
      return this
    }

    override fun commit(): Boolean = staged.commit()

    override fun apply() = staged.apply()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128

    /** ASCII unit separator (0x1F): not valid in the values this app stores. */
    const val SET_SEPARATOR = "\u001F"
  }
}
