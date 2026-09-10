package io.github.kegustafsson.driveremote.core

/**
 * Capped exponential reconnect backoff. Port of the reconnect scheduling in
 * `sk-plugin/src/skClient.ts`.
 *
 * The first retry lands well inside [SkContract.SK_STALENESS_TIMEOUT_MS], but
 * the cap is deliberately ABOVE it. While this station is disconnected it
 * publishes nothing, so RX's per-source watchdog has already failed this source
 * to NEUTRAL -- backing off further can only delay *recovery* (worst case one
 * cap interval), it cannot prolong a stale command. A tighter cap would just
 * hammer a server that is genuinely down.
 *
 * Pure and deterministic: no timers, no jitter, so the schedule is pinned by
 * tests rather than observed.
 */
class ReconnectBackoff(
  private val initialDelayMs: Long = SkContract.RECONNECT_INITIAL_DELAY_MS,
  private val maxDelayMs: Long = SkContract.RECONNECT_MAX_DELAY_MS,
) {
  private var nextDelayMs = initialDelayMs

  /** The delay to wait before the next attempt, then double it up to the cap. */
  fun nextDelay(): Long {
    val delay = nextDelayMs
    nextDelayMs = (nextDelayMs * 2).coerceAtMost(maxDelayMs)
    return delay
  }

  /**
   * Call on a successful connection. Without this a link that flaps would keep
   * escalating until every reconnect took the full cap, turning a brief blip
   * into seconds of being unable to see the boat.
   */
  fun reset() {
    nextDelayMs = initialDelayMs
  }
}
