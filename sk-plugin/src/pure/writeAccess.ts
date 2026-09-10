// Pure, DOM/network-free logic for deciding whether THIS connection can
// actually publish command deltas to the Signal K server -- separated out
// so it's host-testable in isolation, same discipline as driveCommand.ts.
//
// Why this exists (safety): a Signal K server with security enabled
// silently DROPS delta writes from an unauthenticated/read-only connection
// -- verified against signalk-server-node's own source (ws.ts's
// processUpdates: when shouldAllowWrite() is false it logs a *server-side*
// provider error and returns, sending NOTHING back to the client). So the
// browser's socket.send() succeeds locally while the command never reaches
// RX, with zero feedback distinguishing that from "TX has priority." That
// is a silent failure of a safety-relevant control, so the app queries
// GET /skServer/loginStatus (same-origin, carries the same cookies the WS
// does) on connect and maps the answer to a write-capability the UI can
// surface. This function is that mapping.
//
// The mapping is deliberately conservative about crying wolf: it only
// returns 'readonly' when the server positively says writes require auth
// AND this connection doesn't have it. Anything ambiguous (old server, no
// endpoint, unparseable body) stays 'unknown' -- a withheld green check,
// never a false red alarm on a working system.

export type WriteStatus =
  // Not yet determined, or the server didn't give us a usable answer.
  | 'unknown'
  // This connection can publish command deltas (security off, or logged in
  // with write permission).
  | 'writable'
  // The server requires authentication to write and this connection lacks
  // it -- commands will be silently dropped. Surface this loudly.
  | 'readonly';

// The subset of signalk-server's /skServer/loginStatus response we rely on.
// Shapes cross-referenced against tokensecurity.ts's getLoginStatus() and
// dummysecurity.ts's (security-off) equivalent -- see this repo's
// JOURNAL.md plugin-API research entry.
interface LoginStatusLike {
  status?: unknown; // 'loggedIn' | 'notLoggedIn'
  authenticationRequired?: unknown; // false when server security is off
  userLevel?: unknown; // 'readonly' | 'readwrite' | 'admin' when logged in
}

export function resolveWriteStatus(loginStatus: unknown): WriteStatus {
  if (typeof loginStatus !== 'object' || loginStatus === null) return 'unknown';
  const l = loginStatus as LoginStatusLike;

  // Security disabled server-wide (dummysecurity: authenticationRequired
  // false, shouldAllowWrite unconditionally true) -- any client can write.
  if (l.authenticationRequired === false) return 'writable';

  // Security enabled: writes need an authenticated, non-read-only session.
  if (l.authenticationRequired === true) {
    if (l.status === 'loggedIn' && l.userLevel !== 'readonly') return 'writable';
    return 'readonly';
  }

  // authenticationRequired absent/garbage -- don't guess, don't alarm.
  return 'unknown';
}
