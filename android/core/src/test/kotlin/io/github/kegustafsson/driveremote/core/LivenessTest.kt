package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported one-for-one from `sk-plugin/src/pure/rxLiveness.test.ts`.
 *
 * The rule that decides whether the UI may present a unit as present -- and
 * therefore whether it offers an ARM at all. Pure, so every case is pinned here
 * rather than inferred from a rendered screen.
 */
class LivenessTest {

  private val fresh = SkContract.TELEMETRY_STALE_MS - 1
  private val stale = SkContract.TELEMETRY_STALE_MS + 1

  @Test
  fun `is live when telemetry is fresh and the server agrees`() {
    assertEquals(
      UnitLiveness.LIVE,
      evaluateLiveness(ConnectionState.OPEN, telemetryAgeMs = fresh, pluginUnitLive = true),
    )
  }

  @Test
  fun `is stale once telemetry ages past the timeout`() {
    // THE BUG THIS EXISTS FOR: rx.linkUp is still `true` here -- it always will
    // be, forever, because the board that publishes it is switched off. Only
    // the age can reveal it.
    assertEquals(
      UnitLiveness.STALE,
      evaluateLiveness(ConnectionState.OPEN, telemetryAgeMs = stale, pluginUnitLive = true),
    )
  }

  @Test
  fun `treats the timeout boundary itself as still live`() {
    assertEquals(
      UnitLiveness.LIVE,
      evaluateLiveness(
        ConnectionState.OPEN,
        telemetryAgeMs = SkContract.TELEMETRY_STALE_MS,
        pluginUnitLive = true,
      ),
    )
  }

  @Test
  fun `distinguishes never-seen from went-away`() {
    assertEquals(
      UnitLiveness.NEVER_SEEN,
      evaluateLiveness(ConnectionState.OPEN, telemetryAgeMs = null, pluginUnitLive = null),
    )
  }

  @Test
  fun `believes the server when it says the unit is gone, even with fresh deltas`() {
    assertEquals(
      UnitLiveness.STALE,
      evaluateLiveness(
        ConnectionState.OPEN,
        telemetryAgeMs = fresh,
        pluginUnitLive = false,
        pluginVerdictAgeMs = fresh,
      ),
    )
  }

  @Test
  fun `ignores a retained stale plugin verdict when unit telemetry is fresh`() {
    assertEquals(
      UnitLiveness.LIVE,
      evaluateLiveness(ConnectionState.OPEN, fresh, false, pluginVerdictAgeMs = stale),
    )
  }

  // The age is what decides whether a `false` verdict is news or a leftover.
  // An unknown age must therefore fall back to believing the server, not to
  // discarding its gate -- otherwise a caller who simply omits the argument
  // silently loses the check.
  @Test
  fun `believes a plugin verdict whose age is unknown`() {
    assertEquals(
      UnitLiveness.STALE,
      evaluateLiveness(ConnectionState.OPEN, fresh, false, pluginVerdictAgeMs = null),
    )
  }

  @Test
  fun `falls back to the age check when the server has no opinion yet`() {
    // An older plugin build, or the rxLive path simply not received yet. Must
    // not lock the operator out of arming a healthy system -- the server
    // enforces its own gate regardless of what this app believes.
    assertEquals(
      UnitLiveness.LIVE,
      evaluateLiveness(ConnectionState.OPEN, telemetryAgeMs = fresh, pluginUnitLive = null),
    )
  }

  @Test
  fun `reports offline rather than stale while the socket is down`() {
    // The unit is not to blame for our own connection being down, and we hold
    // no evidence either way while no delta can arrive.
    for (state in listOf(ConnectionState.CONNECTING, ConnectionState.CLOSED)) {
      assertEquals(
        UnitLiveness.OFFLINE,
        evaluateLiveness(state, telemetryAgeMs = stale, pluginUnitLive = true),
        "connection state $state",
      )
    }
  }

  @Test
  fun `permits arming ONLY when the unit is live`() {
    assertTrue(readyToArm(UnitLiveness.LIVE))
    for (s in listOf(UnitLiveness.OFFLINE, UnitLiveness.NEVER_SEEN, UnitLiveness.STALE)) {
      assertFalse(readyToArm(s), "liveness $s must not permit arming")
    }
  }
}
