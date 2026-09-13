package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arrival clock is the whole point of this class, so most of these cases
 * are about age rather than value. This is the same bug the plugin's
 * `rxLiveness` work was written for, defended one layer lower.
 */
class SkValueStoreTest {

  @Test
  fun `records a value and its arrival time`() {
    val store = SkValueStore()
    store.apply(listOf(SkContract.RX_LINK_UP to true), nowMs = 1_000)

    assertEquals(true, store[SkContract.RX_LINK_UP])
    assertEquals(1_000L, store.receivedAtMs(SkContract.RX_LINK_UP))
    assertEquals(500L, store.ageMs(SkContract.RX_LINK_UP, nowMs = 1_500))
  }

  @Test
  fun `stamps arrival even when the value is unchanged`() {
    // THE CRITICAL CASE. In steady state the units republish identical
    // telemetry every 250 ms, so "unchanged" is the NORMAL case -- stamping
    // only on change would make a healthy, quiet link look dead.
    val store = SkValueStore()
    store.apply(listOf(SkContract.RX_LINK_UP to true), nowMs = 1_000)
    val changed = store.apply(listOf(SkContract.RX_LINK_UP to true), nowMs = 1_250)

    assertFalse(changed, "value did not change")
    assertEquals(1_250L, store.receivedAtMs(SkContract.RX_LINK_UP), "but arrival must have moved")
    assertEquals(0L, store.ageMs(SkContract.RX_LINK_UP, nowMs = 1_250))
  }

  @Test
  fun `a switched-off unit leaves a true value standing but an ageing clock`() {
    // The end-to-end shape of the bug: the VALUE says the link is up forever,
    // and only the age can reveal that nobody is publishing it any more.
    val store = SkValueStore()
    store.apply(listOf(SkContract.RX_LINK_UP to true), nowMs = 1_000)

    val muchLater = 1_000 + SkContract.TELEMETRY_STALE_MS + 1
    assertEquals(true, store[SkContract.RX_LINK_UP], "the value never withdraws itself")
    assertEquals(
      UnitLiveness.STALE,
      evaluateLiveness(
        ConnectionState.OPEN,
        telemetryAgeMs = store.ageMs(SkContract.RX_LINK_UP, muchLater),
        pluginUnitLive = store.boolOrNull(SkContract.PLUGIN_RX_LIVE),
      ),
    )
  }

  @Test
  fun `reports a change only when the value actually differs`() {
    val store = SkValueStore()
    assertTrue(store.apply(listOf(SkContract.RX_PORT_STATE to "neutral"), 0))
    assertFalse(store.apply(listOf(SkContract.RX_PORT_STATE to "neutral"), 1))
    assertTrue(store.apply(listOf(SkContract.RX_PORT_STATE to "forward"), 2))
  }

  @Test
  fun `distinguishes a null value from never having been seen`() {
    val store = SkValueStore()
    assertFalse(store.hasEverReceived(SkContract.PLUGIN_ACTIVE_CLIENT))
    assertNull(store.ageMs(SkContract.PLUGIN_ACTIVE_CLIENT, nowMs = 0))

    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to null), nowMs = 10)
    assertTrue(store.hasEverReceived(SkContract.PLUGIN_ACTIVE_CLIENT))
    assertNull(store[SkContract.PLUGIN_ACTIVE_CLIENT])
    assertEquals(0L, store.ageMs(SkContract.PLUGIN_ACTIVE_CLIENT, nowMs = 10))
  }

  @Test
  fun `ignores paths outside the accepted set`() {
    val store = SkValueStore(accept = setOf(SkContract.RX_LINK_UP))
    assertFalse(store.apply(listOf("navigation.speedOverGround" to 3.4), nowMs = 0))
    assertFalse(store.hasEverReceived("navigation.speedOverGround"))
  }

  @Test
  fun `boolOrNull returns null for absent or non-boolean rather than false`() {
    // Defaulting a not-yet-received plugin.rxLive to false would lock the
    // operator out of arming a perfectly healthy system.
    val store = SkValueStore()
    assertNull(store.boolOrNull(SkContract.PLUGIN_RX_LIVE))

    store.apply(listOf(SkContract.PLUGIN_RX_LIVE to "yes"), nowMs = 0)
    assertNull(store.boolOrNull(SkContract.PLUGIN_RX_LIVE))

    store.apply(listOf(SkContract.PLUGIN_RX_LIVE to false), nowMs = 1)
    assertEquals(false, store.boolOrNull(SkContract.PLUGIN_RX_LIVE))
  }

  @Test
  fun `clear forgets both the values and their arrival stamps`() {
    val store = SkValueStore()
    store.apply(
      listOf(SkContract.PLUGIN_ACTIVE_CLIENT to "me", SkContract.RX_PORT_STATE to "forward"),
      nowMs = 1_000,
    )
    store.clear()

    assertNull(store[SkContract.PLUGIN_ACTIVE_CLIENT])
    assertFalse(store.hasEverReceived(SkContract.PLUGIN_ACTIVE_CLIENT))
    assertNull(store.ageMs(SkContract.RX_PORT_STATE, nowMs = 1_000), "the stamp must go too")
    assertEquals(emptyMap<String, Any?>(), store.snapshot())

    // And it is empty, not broken: the next session fills it normally.
    store.apply(listOf(SkContract.RX_PORT_STATE to "neutral"), nowMs = 2_000)
    assertEquals(0L, store.ageMs(SkContract.RX_PORT_STATE, nowMs = 2_000))
  }

  @Test
  fun `a cleared store no longer paints this station as armed`() {
    // The reason clear() exists. A revoked token ends the session with our own
    // clientId standing in activeClient, and the value never withdraws itself --
    // so a station that reaches a control screen again reads as ARMED with no
    // session behind it, and a server whose plugin is stopped sends nothing that
    // would correct it.
    val store = SkValueStore()
    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to "me"), nowMs = 1_000)
    assertEquals(ControlState.YOU, controlStateOf(store[SkContract.PLUGIN_ACTIVE_CLIENT], "me"))

    store.clear()
    assertEquals(ControlState.NONE, controlStateOf(store[SkContract.PLUGIN_ACTIVE_CLIENT], "me"))
  }

  @Test
  fun `keeps last-known values across a disconnect`() {
    // Not cleared on purpose: the UI keeps showing what it last saw, greyed
    // out. The arrival clock is what stops last-known being read as live.
    val store = SkValueStore()
    store.apply(listOf(SkContract.RX_PORT_STATE to "forward"), nowMs = 0)
    assertEquals("forward", store.stringOrNull(SkContract.RX_PORT_STATE))
    assertEquals(mapOf<String, Any?>(SkContract.RX_PORT_STATE to "forward"), store.snapshot())
  }
}
