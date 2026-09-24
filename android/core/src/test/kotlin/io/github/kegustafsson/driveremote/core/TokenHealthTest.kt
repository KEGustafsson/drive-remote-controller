package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

  /**
   * A read-only token is a DIFFERENT failure from a revoked one, and the notice
   * is the only place the operator can learn which they have. Told to request a
   * new token, the operator of a read-only one approves the new request exactly
   * as they approved the last, and arrives back at the same 403.
   */
  @Test
  fun `a 403 says the token lacks write permission, not that it was revoked`() {
    val notice = tokenRefusedNotice("boat.local:3000", 403)

    assertTrue(notice.contains("boat.local:3000"), "the operator must be told which server")
    assertTrue(notice.contains("403"))
    assertTrue(
      notice.contains("READ/WRITE"),
      "the remedy is the PERMISSION on the next approval; without it the operator loops",
    )
    assertFalse(
      notice.contains("revoked"),
      "a read-only token was not revoked, and saying so sends the operator round again",
    )
  }

  @Test
  fun `a 401 still reads as a revoked or expired token`() {
    val notice = tokenRefusedNotice("boat.local:3000", 401)

    assertTrue(notice.contains("401"))
    assertTrue(notice.contains("revoked or expired"))
    assertFalse(notice.contains("READ/WRITE"))
  }

  @Test
  fun `the verdict lands within a second at the heartbeat rate`() {
    // Sequencing lives in the ViewModel, but the constant that decides how long
    // an operator can keep commanding a server that has stopped listening is
    // this one, so it is pinned here.
    val worstCaseMs = TokenHealth.REJECTIONS_BEFORE_DEAD * SkContract.PERIODIC_REFRESH_MS
    assertTrue(worstCaseMs <= 1000, "token death takes ${worstCaseMs}ms to detect")
  }

  /**
   * The probe and the verdict together, as the intent poster drives them: each
   * refusal is recorded against the scheme the request carried, and moves the
   * probe with [AuthScheme.nextAfterRefusal]. Returns how many requests it took
   * to declare the token dead, or null if it never was.
   */
  private fun sendsUntilDead(
    startAt: AuthScheme,
    budget: Int,
    accepts: (AuthScheme) -> Boolean,
  ): Int? {
    var scheme = startAt
    var h = TokenHealth()
    repeat(budget) { i ->
      if (accepts(scheme)) {
        h = h.accepted()
      } else {
        h = h.rejected(scheme)
        scheme = AuthScheme.nextAfterRefusal(scheme, scheme)
      }
      if (h.isDead) return i + 1
    }
    return null
  }

  /**
   * The defect. The probe used to only climb, so once it had settled on the
   * last scheme a revoked token was refused with that scheme alone -- forever.
   * [TokenHealth] needs EVERY scheme refused, so the token was never declared
   * dead and the station kept presenting as able to command.
   */
  @Test
  fun `a revoked token dies even when the probe had settled on the last scheme`() {
    for (settled in AuthScheme.TRY_ORDER) {
      val sends = sendsUntilDead(startAt = settled, budget = 20) { false }
      assertEquals(
        TokenHealth.REJECTIONS_BEFORE_DEAD,
        sends,
        "probe settled on $settled: a revoked token must be declared dead within the budget",
      )
    }
  }

  /**
   * The other half. On a Bearer-only server one stray refusal (a restart, a
   * proxy blip) moved a climb-only probe to JWT for good, and the good token was
   * then refused on every request until it was declared dead.
   */
  @Test
  fun `one stray refusal on a Bearer-only server does not strand the probe`() {
    var scheme = AuthScheme.BEARER
    var h = TokenHealth().rejected(scheme)
    scheme = AuthScheme.nextAfterRefusal(scheme, AuthScheme.BEARER)
    repeat(50) {
      if (scheme == AuthScheme.BEARER) {
        h = h.accepted()
      } else {
        h = h.rejected(scheme)
        scheme = AuthScheme.nextAfterRefusal(scheme, scheme)
      }
      assertFalse(h.isDead, "a working Bearer token was declared dead")
    }
    assertEquals(AuthScheme.BEARER, scheme)
    assertEquals(TokenHealth.HEALTHY, h)
  }

  @Test
  fun `a working token on either scheme is found from any starting point`() {
    for (good in AuthScheme.TRY_ORDER) {
      for (start in AuthScheme.TRY_ORDER) {
        assertNull(sendsUntilDead(startAt = start, budget = 50) { it == good })
      }
    }
  }

  /**
   * Several sends are in flight at once, each built with the scheme that stood
   * when it left. A late refusal of a scheme the probe has already moved off
   * must not move it again -- with wrapping, that would carry it straight back
   * to the scheme that was just refused.
   */
  @Test
  fun `a refusal of a scheme already moved off does not move the probe`() {
    val first = AuthScheme.TRY_ORDER.first()
    val second = AuthScheme.nextAfterRefusal(first, first)
    assertEquals(AuthScheme.TRY_ORDER[1], second)
    assertEquals(second, AuthScheme.nextAfterRefusal(second, first))
    // ...while a refusal of the current last scheme wraps to the first.
    assertEquals(first, AuthScheme.nextAfterRefusal(AuthScheme.TRY_ORDER.last(), AuthScheme.TRY_ORDER.last()))
  }
}
