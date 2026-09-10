package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cost of getting this wrong is asymmetric, and both directions are bad:
 *
 *  - too eager, and the auth-scheme probe looks like a dead token, so a working
 *    station is thrown back to the access-request screen mid-manoeuvre;
 *  - too slow, and the operator keeps pressing buttons at a server that has
 *    stopped listening while the UI still shows a live station.
 */
class TokenHealthTest {

  @Test
  fun `starts healthy`() {
    val h = TokenHealth()
    assertFalse(h.isDead)
    assertEquals(0, h.consecutiveRejections)
  }

  @Test
  fun `survives the auth-scheme probe`() {
    // Every scheme before the right one is rejected BY DESIGN. A client that
    // gave up here would never reach the scheme the server actually wants.
    var h = TokenHealth()
    AuthScheme.TRY_ORDER.forEachIndexed { index, scheme ->
      h = h.rejected(scheme)
      assertFalse(h.isDead, "declared dead while still probing scheme ${index + 1}")
    }
  }

  @Test
  fun `declares death once the probe is exhausted and rejection continues`() {
    var h = TokenHealth()
    repeat(TokenHealth.REJECTIONS_BEFORE_DEAD) { h = h.rejected(AuthScheme.TRY_ORDER[it % AuthScheme.TRY_ORDER.size]) }
    assertTrue(h.isDead)
  }

  @Test
  fun `one acceptance clears the count`() {
    // The question is whether the token works NOW. A server that rejected
    // twice while restarting and then accepted is a working server.
    var h = TokenHealth()
    h = h.rejected(AuthScheme.BEARER).rejected(AuthScheme.JWT)
    assertFalse(h.isDead)
    h = h.accepted()
    assertEquals(0, h.consecutiveRejections)
    assertTrue(h.schemesRefused.isEmpty(), "acceptance must clear the refused schemes too")

    // And the full budget is available again afterwards.
    repeat(TokenHealth.REJECTIONS_BEFORE_DEAD - 1) {
      h = h.rejected(AuthScheme.TRY_ORDER[it % AuthScheme.TRY_ORDER.size])
    }
    assertFalse(h.isDead)
  }

  @Test
  fun `rejections must be consecutive to count`() {
    var h = TokenHealth()
    repeat(20) { h = h.rejected(AuthScheme.BEARER).accepted() }
    assertFalse(h.isDead)
  }

  @Test
  fun `stays dead once dead`() {
    var h = TokenHealth()
    repeat(TokenHealth.REJECTIONS_BEFORE_DEAD + 5) {
      h = h.rejected(AuthScheme.TRY_ORDER[it % AuthScheme.TRY_ORDER.size])
    }
    assertTrue(h.isDead)
  }

  @Test
  fun `the threshold is derived from the scheme list, not hard-coded`() {
    // If a third scheme is ever added, the threshold must move with it or the
    // probe becomes indistinguishable from a dead token.
    assertEquals(AuthScheme.TRY_ORDER.size + 1, TokenHealth.REJECTIONS_BEFORE_DEAD)
    assertTrue(TokenHealth.REJECTIONS_BEFORE_DEAD > AuthScheme.TRY_ORDER.size)
  }

  /**
   * The concurrency case, and the reason a rejection carries its scheme.
   *
   * STOP deliberately does not queue behind the heartbeat -- disarm is never
   * gated -- so on a `JWT`-only server the heartbeat, a hurried STOP and a
   * backgrounding release can all be built with the same unprobed `Bearer`
   * before any reply comes back to advance it. Counting replies would spend the
   * entire budget on ONE failed probe and take the controls away exactly when
   * the operator was reaching for STOP.
   */
  @Test
  fun `three concurrent sends refused on one unprobed scheme do not kill the token`() {
    var h = TokenHealth()
    repeat(TokenHealth.REJECTIONS_BEFORE_DEAD + 3) { h = h.rejected(AuthScheme.BEARER) }

    assertFalse(h.isDead, "a token was declared dead without JWT ever being tried")
    assertTrue(h.consecutiveRejections > TokenHealth.REJECTIONS_BEFORE_DEAD)
  }

  @Test
  fun `death still follows once every scheme has actually been refused`() {
    // The guard above must not become a way to never die: a genuinely dead
    // token gets refused on every scheme, and that must still be fatal.
    var h = TokenHealth()
    repeat(TokenHealth.REJECTIONS_BEFORE_DEAD) { h = h.rejected(AuthScheme.BEARER) }
    assertFalse(h.isDead)

    h = h.rejected(AuthScheme.JWT)
    assertTrue(h.isDead, "every scheme refused and the budget spent, but still not dead")
  }

  @Test
  fun `both conditions are required, neither alone is enough`() {
    // All schemes refused but inside the budget: not dead.
    var schemesOnly = TokenHealth()
    AuthScheme.TRY_ORDER.forEach { schemesOnly = schemesOnly.rejected(it) }
    assertEquals(AuthScheme.TRY_ORDER.size, schemesOnly.consecutiveRejections)
    assertFalse(schemesOnly.isDead, "died one rejection short of the budget")

    // Budget spent but a scheme never tried: not dead. Covered above for
    // BEARER; asserted here for every scheme, so adding a third to TRY_ORDER
    // cannot leave a hole.
    for (skipped in AuthScheme.TRY_ORDER) {
      var h = TokenHealth()
      repeat(TokenHealth.REJECTIONS_BEFORE_DEAD + 2) { i ->
        val others = AuthScheme.TRY_ORDER.filter { it != skipped }
        h = h.rejected(others[i % others.size])
      }
      assertFalse(h.isDead, "declared dead without ever trying $skipped")
    }
  }

  @Test
  fun `the verdict lands within a second at the heartbeat rate`() {
    // Sequencing lives in the ViewModel, but the constant that decides how long
    // an operator can keep commanding a server that has stopped listening is
    // this one, so it is pinned here.
    val worstCaseMs = TokenHealth.REJECTIONS_BEFORE_DEAD * SkContract.PERIODIC_REFRESH_MS
    assertTrue(worstCaseMs <= 1000, "token death takes ${worstCaseMs}ms to detect")
  }
}
