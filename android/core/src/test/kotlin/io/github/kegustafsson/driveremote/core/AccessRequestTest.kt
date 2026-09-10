package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The token flow, pinned branch by branch.
 *
 * These are exactly the cases that are awkward to produce against a real
 * server -- DENIED needs an admin to click deny, a tokenless approval needs a
 * broken server -- which is why the parsing is pure and lives here rather than
 * being discovered on the boat.
 */
class AccessRequestTest {

  @Test
  fun `request body carries the clientId and description the spec requires`() {
    val body = AccessRequest.buildRequestBody("uuid-here", "Drive Remote Control (Pixel)")
    assertTrue(body.contains("\"clientId\":\"uuid-here\""))
    assertTrue(body.contains("\"description\":\"Drive Remote Control (Pixel)\""))
  }

  @Test
  fun `202 with a pending state yields the href to poll`() {
    val body =
      """{"state":"PENDING","href":"/signalk/v1/access/requests/358b5f32-76bf-4b33-8b23-10a330827185"}"""
    assertEquals(
      AccessState.Pending("/signalk/v1/access/requests/358b5f32-76bf-4b33-8b23-10a330827185"),
      AccessRequest.parseRequestResponse(body),
    )
  }

  @Test
  fun `a pending response with no href fails rather than polling nothing`() {
    // With no href there is nothing to check; pretending otherwise leaves the
    // app waiting forever on a request it can never resolve.
    val state = AccessRequest.parseRequestResponse("""{"state":"PENDING"}""")
    assertTrue(state is AccessState.Failed, "expected Failed, got $state")
  }

  @Test
  fun `an approved poll yields the token`() {
    val body =
      """
      {"state":"COMPLETED","statusCode":200,
       "accessRequest":{"permission":"APPROVED","token":"eyJhbGciOiJIUzI1NiIs",
                        "expirationTime":"2027-09-20T16:51:31.350Z"}}
      """
    val state = AccessRequest.parsePollResponse(body)
    assertTrue(state is AccessState.Approved, "expected Approved, got $state")
    assertEquals("eyJhbGciOiJIUzI1NiIs", (state as AccessState.Approved).token)
    assertEquals(
      java.time.Instant.parse("2027-09-20T16:51:31.350Z").toEpochMilli(),
      state.expiresAtMs,
    )
  }

  @Test
  fun `a denied poll is terminal, not a retry`() {
    // Silently retrying would spam the admin's approval queue forever, and the
    // operator would be left watching a spinner with no explanation.
    val body = """{"state":"COMPLETED","statusCode":200,"accessRequest":{"permission":"DENIED"}}"""
    assertEquals(AccessState.Denied, AccessRequest.parsePollResponse(body))
  }

  @Test
  fun `approved but tokenless is a failure, never a success`() {
    // Otherwise the app believes it can command and is 401'd on every intent,
    // with nothing on screen explaining why.
    val body =
      """{"state":"COMPLETED","accessRequest":{"permission":"APPROVED","token":""}}"""
    assertTrue(AccessRequest.parsePollResponse(body) is AccessState.Failed)
  }

  @Test
  fun `a still-pending poll stays pending`() {
    assertTrue(AccessRequest.parsePollResponse("""{"state":"PENDING"}""") is AccessState.Pending)
  }

  @Test
  fun `a server that approves immediately is handled on the first response`() {
    val body =
      """{"state":"COMPLETED","accessRequest":{"permission":"APPROVED","token":"tok"}}"""
    val state = AccessRequest.parseRequestResponse(body)
    assertTrue(state is AccessState.Approved, "expected Approved, got $state")
    assertEquals("tok", (state as AccessState.Approved).token)
  }

  @Test
  fun `garbage yields Failed with a reason rather than throwing`() {
    val garbage = listOf("", "not json", "[]", "{}", """{"state":"WAT"}""", """{"state":"COMPLETED"}""")
    for (body in garbage) {
      assertTrue(
        AccessRequest.parsePollResponse(body) is AccessState.Failed,
        "should have failed cleanly: $body",
      )
    }
  }

  @Test
  fun `expiry accepts an ISO instant, a duration, or neither`() {
    assertEquals(
      java.time.Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(),
      AccessRequest.parseExpiry("2027-01-01T00:00:00Z", nowMs = 0),
    )
    assertEquals(86_400_000L, AccessRequest.parseExpiry("1d", nowMs = 0))
    assertEquals(3_600_000L + 500, AccessRequest.parseExpiry("1h", nowMs = 500))

    // No stated expiry means "use it until the server rejects it", never
    // "already expired" -- guessing would disarm a working station for nothing.
    assertNull(AccessRequest.parseExpiry(null, nowMs = 0))
    assertNull(AccessRequest.parseExpiry("", nowMs = 0))
    assertNull(AccessRequest.parseExpiry("whenever", nowMs = 0))
  }

  @Test
  fun `an immediate approval with a duration expiry is measured from now, not from 1970`() {
    // The poll path already took nowMs; the request path defaulted it to 0, so
    // a server answering the POST with COMPLETED + "1d" yielded a token that
    // had "expired" in January 1970, and the session ended on its first
    // heartbeat -- every time.
    val body =
      """{"state":"COMPLETED","accessRequest":{"permission":"APPROVED","token":"tok","expirationTime":"1d"}}"""
    val now = 1_700_000_000_000L
    assertEquals(
      AccessState.Approved("tok", now + 86_400_000L),
      AccessRequest.parseRequestResponse(body, now),
    )
  }

  @Test
  fun `both auth schemes are offered, Bearer first`() {
    // signalk-server has historically accepted only "JWT <token>" on some
    // versions (issue #715), so the app tries both rather than shipping a
    // guess that fails on half the fleet.
    assertEquals(listOf(AuthScheme.BEARER, AuthScheme.JWT), AuthScheme.TRY_ORDER)
    assertEquals("Bearer abc", AuthScheme.BEARER.headerValue("abc"))
    assertEquals("JWT abc", AuthScheme.JWT.headerValue("abc"))
  }
}
