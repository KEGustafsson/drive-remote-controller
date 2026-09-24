package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Port of `sk-plugin/src/pure/intentStatus.test.ts`, plus the kill-switch line
 * this station adds. The classification and the lamp wording are the browser's.
 */
class IntentStatusTest {

  @Test
  fun `reads 401 and 403 as an authorisation problem`() {
    assertEquals(IntentStatus.AUTH, classifyIntentOutcome(401))
    assertEquals(IntentStatus.AUTH, classifyIntentOutcome(403))
  }

  @Test
  fun `reads 503 as the plugin not running`() {
    assertEquals(IntentStatus.UNAVAILABLE, classifyIntentOutcome(503))
  }

  @Test
  fun `reads anything else -- no response, a timeout, a 5xx, a 400 -- as not reaching the boat`() {
    assertEquals(IntentStatus.NETWORK, classifyIntentOutcome(null))
    assertEquals(IntentStatus.NETWORK, classifyIntentOutcome(500))
    assertEquals(IntentStatus.NETWORK, classifyIntentOutcome(502))
    assertEquals(IntentStatus.NETWORK, classifyIntentOutcome(400))
    assertEquals(IntentStatus.NETWORK, classifyIntentOutcome(404))
  }

  @Test
  fun `reads any 2xx as reaching the boat`() {
    assertEquals(IntentStatus.OK, classifyIntentOutcome(200))
    assertEquals(IntentStatus.OK, classifyIntentOutcome(204))
    assertEquals(IntentStatus.OK, classifyIntentOutcome(299))
  }

  @Test
  fun `the lamp wording mirrors the browser's`() {
    assertEquals(CommandsIndication("reaching boat", CommandsTone.GOOD), commandsIndication(IntentStatus.OK))
    assertEquals(
      CommandsIndication("BLOCKED — plugin not running", CommandsTone.BAD),
      commandsIndication(IntentStatus.UNAVAILABLE),
    )
    assertEquals(
      CommandsIndication("NOT REACHING BOAT", CommandsTone.BAD),
      commandsIndication(IntentStatus.NETWORK),
    )
    // "log in" is the browser's remedy; this station has none by that name.
    assertEquals(CommandsTone.BAD, commandsIndication(IntentStatus.AUTH).tone)
    assertEquals(
      CommandsIndication("unconfirmed", CommandsTone.NEUTRAL),
      commandsIndication(IntentStatus.UNKNOWN),
    )
  }

  @Test
  fun `the kill switch names why commands are not reaching the boat`() {
    assertEquals(
      "commands not reaching boat — plugin stopped",
      commandsNotReachingLine(IntentStatus.UNAVAILABLE),
    )
    assertEquals("commands not reaching boat — login refused", commandsNotReachingLine(IntentStatus.AUTH))
    assertEquals("commands not reaching boat — no network", commandsNotReachingLine(IntentStatus.NETWORK))
  }

  /** Healthy, or not yet answered: nothing to warn about, and no warning at launch. */
  @Test
  fun `the kill switch says nothing extra while commands are landing or unanswered`() {
    assertNull(commandsNotReachingLine(IntentStatus.OK))
    assertNull(commandsNotReachingLine(IntentStatus.UNKNOWN))
  }

  /** End to end through the view: a 503 reaches the kill switch's line. */
  @Test
  fun `a 503 answer reaches the view as a degraded kill-switch line`() {
    val view =
      deriveStationView(
        SkValueStore(),
        ConnectionState.OPEN,
        "ui-me",
        nowMs = 1_000,
        intentStatus = classifyIntentOutcome(503),
      )
    assertEquals("commands not reaching boat — plugin stopped", view.commandsNotReaching)
    assertEquals("BLOCKED — plugin not running", view.commands.value)
  }
}
