// "Is the server's stream still carrying live data?" -- pure, so it is
// testable without timers or sockets (same split as pure/rxLiveness.ts).
//
// Neither obvious signal can answer it. The socket's state cannot: a half-open
// socket (the far end gone, no FIN) stays 'open' indefinitely. The arbiter's
// published activeClient cannot either: Signal K retains a path's last value
// forever and the SK client keeps its copy across a reconnect, so ARMED /
// Control yours would stand on a value nobody is publishing. The plugin
// republishes activeClient every 250 ms, so its ARRIVAL -- on THIS socket,
// recently -- is the evidence.

import { SERVER_STREAM_STALE_MS } from '../config';
import type { ConnectionState } from '../skClient';

export interface ServerStreamInputs {
  connectionState: ConnectionState;
  /** When activeClient last arrived (runtime clock); undefined = never. */
  receivedAtMs: number | undefined;
  /** When the current socket opened (SkSnapshot.openedAt). */
  openedAtMs: number | undefined;
  nowMs: number;
  staleTimeoutMs?: number;
}

export function serverStreamLive({
  connectionState,
  receivedAtMs,
  openedAtMs,
  nowMs,
  staleTimeoutMs = SERVER_STREAM_STALE_MS,
}: ServerStreamInputs): boolean {
  if (connectionState !== 'open') return false;
  if (receivedAtMs === undefined) return false;
  // Heard on a socket that has since been replaced: says nothing about now,
  // however young it is.
  if (openedAtMs !== undefined && receivedAtMs < openedAtMs) return false;
  // `now` can lag an arrival by up to one poll; a negative age is fresh.
  return nowMs - receivedAtMs <= staleTimeoutMs;
}
