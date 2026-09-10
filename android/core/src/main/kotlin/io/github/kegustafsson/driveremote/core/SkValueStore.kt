package io.github.kegustafsson.driveremote.core

/**
 * Last-known value per path, plus **when each one last ARRIVED**.
 *
 * The arrival clock is the whole reason this class exists. Values alone cannot
 * express "this reading has stopped coming": Signal K retains a path's last
 * value forever, and this store keeps its copy across disconnects, so a unit
 * that is switched off leaves `rx.linkUp: true` standing indefinitely -- a dead
 * link that reads as a healthy one. Anything that displays or acts on unit
 * telemetry must consult the age, never just the value.
 *
 * Port of the snapshot/`getReceivedAt` half of `sk-plugin/src/skClient.ts`.
 *
 * Pure: the clock is passed in, never read here, so every staleness case is
 * testable without waiting. Not thread-safe by itself -- the platform layer
 * owns confinement (see :app).
 */
class SkValueStore(private val accept: Set<String> = SkContract.SUBSCRIBE_PATHS.toSet()) {

  private val values = mutableMapOf<String, Any?>()
  private val receivedAtMs = mutableMapOf<String, Long>()

  /** Last known value for [path], or null if never received (or received as null). */
  operator fun get(path: String): Any? = values[path]

  /** Has anything ever arrived for [path]? Distinguishes "null value" from "never seen". */
  fun hasEverReceived(path: String): Boolean = path in receivedAtMs

  /** When [path]'s last delta arrived, or null if never. */
  fun receivedAtMs(path: String): Long? = receivedAtMs[path]

  /** Age of [path]'s last delta at [nowMs], or null if never received. */
  fun ageMs(path: String, nowMs: Long): Long? = receivedAtMs[path]?.let { nowMs - it }

  /**
   * Apply parsed path/value pairs.
   *
   * Arrival is stamped for EVERY entry, including one whose value is unchanged.
   * In steady state that is the normal case -- the units republish identical
   * telemetry every 250 ms -- and stamping only on change would make a healthy,
   * quiet link look dead. This is the single most important line in the class.
   *
   * @return true if any *value* actually changed, so a caller can skip
   *   redundant UI work while still having had liveness refreshed.
   */
  fun apply(entries: List<Pair<String, Any?>>, nowMs: Long): Boolean {
    var changed = false
    for ((path, value) in entries) {
      if (path !in accept) continue
      receivedAtMs[path] = nowMs
      val had = path in values
      if (!had || values[path] != value) {
        values[path] = value
        changed = true
      }
    }
    return changed
  }

  /**
   * Read a path as a Boolean, or null when it is absent or not a boolean.
   *
   * Null means "no opinion" and callers must treat it as such rather than
   * defaulting it to false -- for `plugin.rxLive` in particular, defaulting a
   * not-yet-received path to false would lock the operator out of arming a
   * perfectly healthy system (see [evaluateLiveness]).
   */
  fun boolOrNull(path: String): Boolean? = values[path] as? Boolean

  /** Read a path as a String, or null when absent or not a string. */
  fun stringOrNull(path: String): String? = values[path] as? String

  /**
   * Values are deliberately NOT cleared on disconnect -- the browser client
   * does not clear them either. They stay readable as last-known so the UI can
   * keep showing what it last saw while greying it out, rather than blanking
   * the panel. The arrival clock is what stops last-known from being mistaken
   * for live.
   */
  fun snapshot(): Map<String, Any?> = values.toMap()
}
