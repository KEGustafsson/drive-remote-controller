package io.github.kegustafsson.driveremote.core

/**
 * "Is the server's stream still carrying live data?" Port of
 * `sk-plugin/src/pure/serverStream.ts`, and the same rule.
 *
 * Neither obvious signal can answer it. The socket's state cannot: a half-open
 * socket (the far end gone, no FIN -- the Signal K host losing power, the Wi-Fi
 * path breaking) stays OPEN until OkHttp's ping fails, 20 s and more later. The
 * arbiter's published `activeClient` cannot either: Signal K retains a path's
 * last value forever and [SkValueStore] keeps its copy across a reconnect, so
 * ARMED / control yours would stand on a value nobody is publishing. The plugin
 * republishes `activeClient` every [SkContract.PERIODIC_REFRESH_MS], so its
 * ARRIVAL -- on THIS socket, recently -- is the evidence.
 *
 * @param receivedAtMs when `activeClient` last arrived, or null if never.
 * @param openedAtMs when the current socket opened, on the same clock; null if
 *   that is not tracked, in which case the socket check is skipped.
 */
fun serverStreamLive(
  connectionState: ConnectionState,
  receivedAtMs: Long?,
  openedAtMs: Long?,
  nowMs: Long,
  staleTimeoutMs: Long = SkContract.SERVER_STREAM_STALE_MS,
): Boolean {
  if (connectionState != ConnectionState.OPEN) return false
  if (receivedAtMs == null) return false
  // Heard on a socket that has since been replaced: says nothing about now,
  // however young it is.
  if (openedAtMs != null && receivedAtMs < openedAtMs) return false
  // `now` can lag an arrival by up to one poll; a negative age is fresh.
  return nowMs - receivedAtMs <= staleTimeoutMs
}

/**
 * The start of a condition that must hold CONTINUOUSLY: [sinceMs] carried on
 * while [holds], [nowMs] on the first moment it does, null the moment it does
 * not -- so any break restarts the count from nothing.
 *
 * What the browser's `useTrimReset` keeps in React state, as a pure step the
 * caller threads from one derivation to the next.
 */
fun continuousSince(holds: Boolean, sinceMs: Long?, nowMs: Long): Long? =
  if (holds) sinceMs ?: nowMs else null
