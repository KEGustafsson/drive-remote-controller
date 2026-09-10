import { createContext, useContext, useSyncExternalStore } from 'react';
import type { SkClient, SkSnapshot, SubscribedPath } from '../skClient';

// A real client is created once (see main.tsx) and provided via context --
// deliberately NOT created inside a hook/component, so its connection
// lifecycle is independent of React's render cycle (immune to
// StrictMode's dev-mode double-invoke, which would otherwise risk opening
// two competing WebSocket connections). Tests provide their own client
// instance (backed by a real in-process ws server, not a mock) through the
// same context, so component tests exercise the exact same code path
// production does.
export const SkClientContext = createContext<SkClient | null>(null);

// NOTE: deliberately no publish/write capability here. The Signal K socket
// is read-only by construction (see skClient.ts) -- everything a component
// wants to CHANGE goes out as an intent POST via clientIntent.ts instead.
export interface SkConnection extends SkSnapshot {
  /**
   * Arrival time of a path's last delta -- how a caller tells "still being
   * published" from "last published before the device died". `values` cannot
   * express that difference; see SkClient.getReceivedAt.
   */
  getReceivedAt: (path: SubscribedPath) => number | undefined;
}

export function useSkConnection(): SkConnection {
  const client = useContext(SkClientContext);
  if (client === null) {
    throw new Error(
      'useSkConnection() called outside <SkClientContext.Provider> -- ' +
        'every component that talks to Signal K must be rendered under ' +
        'the provider set up in main.tsx (or a test-specific one).',
    );
  }
  const snapshot = useSyncExternalStore(client.subscribe, client.getSnapshot);
  return {
    ...snapshot,
    getReceivedAt: client.getReceivedAt,
  };
}
