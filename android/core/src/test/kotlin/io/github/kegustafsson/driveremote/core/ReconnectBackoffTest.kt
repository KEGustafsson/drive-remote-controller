package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReconnectBackoffTest {

  @Test
  fun `doubles from the initial delay up to the cap and stays there`() {
    val backoff = ReconnectBackoff()
    assertEquals(250L, backoff.nextDelay())
    assertEquals(500L, backoff.nextDelay())
    assertEquals(1000L, backoff.nextDelay())
    assertEquals(2000L, backoff.nextDelay())
    assertEquals(4000L, backoff.nextDelay())
    assertEquals(4000L, backoff.nextDelay(), "must saturate, not keep growing")
    assertEquals(4000L, backoff.nextDelay())
  }

  @Test
  fun `the first retry lands inside the staleness timeout RX will apply`() {
    assertEquals(
      SkContract.RECONNECT_INITIAL_DELAY_MS,
      ReconnectBackoff().nextDelay(),
    )
    assert(SkContract.RECONNECT_INITIAL_DELAY_MS < SkContract.SK_STALENESS_TIMEOUT_MS)
  }

  @Test
  fun `resets on a successful connection so a flapping link does not escalate`() {
    // Without the reset, a link that drops repeatedly would climb to the cap
    // and stay there, turning a brief blip into seconds of not seeing the boat.
    val backoff = ReconnectBackoff()
    repeat(5) { backoff.nextDelay() }
    backoff.reset()
    assertEquals(250L, backoff.nextDelay())
  }
}
