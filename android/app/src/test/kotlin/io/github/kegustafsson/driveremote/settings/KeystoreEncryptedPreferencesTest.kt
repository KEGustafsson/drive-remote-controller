package io.github.kegustafsson.driveremote.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store that holds the Signal K access token.
 *
 * Robolectric does NOT provide the Android Keystore -- `KeyStore.getInstance
 * ("AndroidKeyStore")` throws "AndroidKeyStore not found" there, which is how
 * the first version of these tests failed. So the key is injected: an ordinary
 * JCE AES-256 key stands in, and everything else runs for real -- the sealing,
 * the per-write IV, the storage format, the typed getters, and the behaviour
 * when a value will not decrypt.
 *
 * What that leaves unproven is exactly one thing: that the Android Keystore
 * hands back a usable key on a real device, and what happens when it
 * invalidates one. Those need a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreEncryptedPreferencesTest {

  /** A fixed AES-256 key, standing in for the one the Keystore would hold. */
  private val testKey: javax.crypto.SecretKey =
    javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

  private fun store(file: String = "test_secure") =
    KeystoreEncryptedPreferences(
      ApplicationProvider.getApplicationContext(),
      file,
      "test_alias_$file",
      keySource = { testKey },
    )

  /** A store that cannot get a key, as on a device whose key was invalidated. */
  private fun keylessStore(file: String) =
    KeystoreEncryptedPreferences(
      ApplicationProvider.getApplicationContext(),
      file,
      "test_alias_$file",
      keySource = { null },
    )

  @Test
  fun `a string survives a write and a read`() {
    val prefs = store()
    prefs.edit().putString("sk_token", "a-real-looking-token-value").apply()
    assertEquals("a-real-looking-token-value", prefs.getString("sk_token", null))
  }

  @Test
  fun `a long survives, which is what the session generation depends on`() {
    val prefs = store("gen")
    prefs.edit().putLong("session_generation", 1_762_000_000_000L).commit()
    assertEquals(1_762_000_000_000L, prefs.getLong("session_generation", 0L))
  }

  @Test
  fun `commit reports success, so nextSessionGeneration can rely on it`() {
    val prefs = store("commit")
    assertTrue(prefs.edit().putLong("k", 7L).commit())
  }

  @Test
  fun `what lands on disk is not the plaintext`() {
    val prefs = store("ciphertext")
    prefs.edit().putString("sk_token", "SUPER-SECRET-TOKEN").commit()
    val raw =
      ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("ciphertext", android.content.Context.MODE_PRIVATE)
        .getString("sk_token", null)
    assertNotEquals("SUPER-SECRET-TOKEN", raw)
    assertFalse("plaintext must not appear in the stored blob", raw!!.contains("SUPER-SECRET"))
  }

  @Test
  fun `the same plaintext encrypts differently each time`() {
    // setRandomizedEncryptionRequired: a repeated IV under GCM is catastrophic,
    // so identical writes must not produce identical ciphertext.
    val prefs = store("iv")
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    prefs.edit().putString("k", "same").commit()
    val first = ctx.getSharedPreferences("iv", android.content.Context.MODE_PRIVATE).getString("k", null)
    prefs.edit().putString("k", "same").commit()
    val second = ctx.getSharedPreferences("iv", android.content.Context.MODE_PRIVATE).getString("k", null)
    assertNotEquals(first, second)
  }

  @Test
  fun `an absent key returns the default`() {
    val prefs = store("absent")
    assertNull(prefs.getString("nothing", null))
    assertEquals(-1L, prefs.getLong("nothing", -1L))
    assertFalse(prefs.contains("nothing"))
  }

  @Test
  fun `removing a key removes it`() {
    val prefs = store("remove")
    prefs.edit().putString("k", "v").commit()
    assertTrue(prefs.contains("k"))
    prefs.edit().remove("k").commit()
    assertFalse(prefs.contains("k"))
    assertNull(prefs.getString("k", null))
  }

  @Test
  fun `putString null removes rather than storing the word null`() {
    val prefs = store("nullput")
    prefs.edit().putString("k", "v").commit()
    prefs.edit().putString("k", null).commit()
    assertNull(prefs.getString("k", null))
  }

  @Test
  fun readingAnUndecryptableValueLooksLikeAbsence() {
    // A key invalidated on the phone, or app data restored from another device,
    // leaves ciphertext that will not decrypt. The station must then behave as
    // though it holds no token and ask for one -- not crash on the helm screen.
    val prefs = store("tampered")
    prefs.edit().putString("sk_token", "value").commit()
    ApplicationProvider.getApplicationContext<android.content.Context>()
      .getSharedPreferences("tampered", android.content.Context.MODE_PRIVATE)
      .edit()
      .putString("sk_token", "bm90LWEtdmFsaWQtY2lwaGVydGV4dC1hdC1hbGw=")
      .commit()
    assertNull(prefs.getString("sk_token", null))
    assertEquals("fallback", prefs.getString("sk_token", "fallback"))
  }

  @Test
  fun `with no key, nothing is written and nothing is written in the clear`() {
    // The path the first run of these tests actually took, before the key was
    // injectable. It must fail closed and it must be observable.
    val prefs = keylessStore("nokey")
    prefs.edit().putString("sk_token", "SUPER-SECRET-TOKEN").commit()

    assertFalse("no key means no persistence", prefs.keyAvailable)
    assertEquals(1, prefs.writesDroppedWithoutKey)
    assertNull(prefs.getString("sk_token", null))

    val raw =
      ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("nokey", android.content.Context.MODE_PRIVATE)
        .getString("sk_token", null)
    assertNull("must never fall back to storing plaintext", raw)
  }

  @Test
  fun `with a key, nothing is reported as dropped`() {
    val prefs = store("counters")
    prefs.edit().putString("k", "v").commit()
    assertTrue(prefs.keyAvailable)
    assertEquals(0, prefs.writesDroppedWithoutKey)
  }

  @Test
  fun `a batched edit writes every value in it`() {
    // setToken() stages three changes and applies them together.
    val prefs = store("batch")
    prefs
      .edit()
      .apply {
        putString("sk_token", "t")
        putLong("sk_token_expiry", 99L)
        putString("sk_token_server", "http://boat:3000")
      }
      .commit()
    assertEquals("t", prefs.getString("sk_token", null))
    assertEquals(99L, prefs.getLong("sk_token_expiry", 0L))
    assertEquals("http://boat:3000", prefs.getString("sk_token_server", null))
  }
}
