import { useEffect, useState } from 'react';
import type { ConnectionState } from '../skClient';
import { resolveWriteStatus, type WriteStatus } from '../pure/writeAccess';

// signalk-server exposes the current connection's auth posture here
// (SERVERROUTESPREFIX '/skServer' + '/loginStatus', verified in the real
// server source). Relative + same-origin on purpose: the app is served BY
// the SK server, so this fetch carries the exact same session cookies the
// WebSocket connection does -- meaning its answer reflects whether that
// same connection may publish, not some unrelated context. In `npm run
// dev` (served from Vite, not the SK server) this simply fails and the
// status stays 'unknown', which is the correct "can't confirm" state
// rather than a false alarm.
const LOGIN_STATUS_URL = '/skServer/loginStatus';

/**
 * Reports whether this browser can actually publish commands to Signal K,
 * so the UI can warn when a secured server would silently drop them (see
 * pure/writeAccess.ts for why this matters). Re-checks whenever the socket
 * (re)connects, since a login could have happened in another tab between
 * connections. Injectable fetcher keeps it host-testable without a network.
 */
export function useWriteAccess(
  connectionState: ConnectionState,
  fetchLoginStatus: () => Promise<unknown> = defaultFetchLoginStatus,
): WriteStatus {
  const [writeStatus, setWriteStatus] = useState<WriteStatus>('unknown');

  useEffect(() => {
    // Only meaningful once the socket is actually up; before that we have
    // no connection whose write-capability to report.
    if (connectionState !== 'open') return;
    let cancelled = false;
    fetchLoginStatus()
      .then((body) => {
        if (!cancelled) setWriteStatus(resolveWriteStatus(body));
      })
      .catch(() => {
        // Endpoint missing/unreachable (e.g. dev server) -- stay 'unknown'
        // rather than guess. Never downgrade a working system to a scare.
        if (!cancelled) setWriteStatus('unknown');
      });
    return () => {
      cancelled = true;
    };
  }, [connectionState, fetchLoginStatus]);

  return writeStatus;
}

async function defaultFetchLoginStatus(): Promise<unknown> {
  const res = await fetch(LOGIN_STATUS_URL, { credentials: 'include' });
  if (!res.ok) throw new Error(`loginStatus ${res.status}`);
  return res.json();
}
