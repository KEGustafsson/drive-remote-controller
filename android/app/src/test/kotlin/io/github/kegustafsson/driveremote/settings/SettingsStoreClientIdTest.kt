package io.github.kegustafsson.driveremote.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The station's identity must survive a moment in which it cannot be read.
 *
 * Robolectric has no Android Keystore, so [SettingsStore] here is exactly a
 * phone whose Keystore failed at launch: the stored id is on disk and cannot be
 * decrypted. `clientId()` used to take that as "no id", mint a new one and write
 * it -- and a write without a key REMOVES the entry, so the stored identity was
 * deleted. One hiccup, and the station came back as a new device needing an
 * admin's approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreClientIdTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun rawSecure() =
    context.getSharedPreferences(SettingsStore.SECURE_FILE, Context.MODE_PRIVATE)

  @Test
  fun `an unreadable stored id is left on disk, not replaced`() {
    // Ciphertext from a previous launch, sealed under the (absent) Keystore key.
    val sealed = "AAECAwQFBgcICQoLc3RvcmVkLWlkLWNpcGhlcnRleHQ="
    rawSecure().edit().putString(SettingsStore.KEY_CLIENT_ID, sealed).commit()

    val settings = SettingsStore(context)
    val id = settings.clientId()

    assertEquals(
      "the stored identity was deleted by a launch that could not read it",
      sealed,
      rawSecure().getString(SettingsStore.KEY_CLIENT_ID, null),
    )
    assertNotEquals(sealed, id)
    assertEquals("one process, one id", id, settings.clientId())
  }

  @Test
  fun `with no key and nothing stored, nothing is written`() {
    rawSecure().edit().clear().commit()
    val settings = SettingsStore(context)
    val id = settings.clientId()
    assertEquals(id, settings.clientId())
    assertEquals(null, rawSecure().getString(SettingsStore.KEY_CLIENT_ID, null))
  }
}
