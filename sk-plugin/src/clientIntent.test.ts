// postIntent's client-side deadline. App.tsx skips every heartbeat while a
// POST is in flight, so a POST with no deadline that never settles would stop
// the heartbeat for the life of the page. AbortSignal.timeout is missing on
// older Safari/WebViews; the deadline must not depend on it.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { INTENT_POST_TIMEOUT_MS, postIntent, type ClientIntent } from './clientIntent';

const INTENT: ClientIntent = {
  clientId: 'ui-test',
  seq: 1,
  armReq: 0,
  disarmReq: 0,
  port: 'neutral',
  stbd: 'neutral',
  thruster: 'off',
  thrusterMode: 'manual',
  trimDeg: 0,
};

// A fetch that never answers, but honours its abort signal like a real one.
function hangingFetch(): typeof fetch {
  return ((_url: unknown, init?: RequestInit) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () =>
        reject(new DOMException('aborted', 'AbortError')),
      );
    })) as unknown as typeof fetch;
}

const realFetch = globalThis.fetch;
const realTimeout = AbortSignal.timeout;

beforeEach(() => {
  vi.useFakeTimers();
  // The platform this is about: no AbortSignal.timeout at all.
  (AbortSignal as unknown as { timeout?: unknown }).timeout = undefined;
});

afterEach(() => {
  vi.useRealTimers();
  globalThis.fetch = realFetch;
  (AbortSignal as unknown as { timeout: typeof realTimeout }).timeout = realTimeout;
});

describe('postIntent without AbortSignal.timeout', () => {
  it('still gives up after INTENT_POST_TIMEOUT_MS instead of hanging forever', async () => {
    globalThis.fetch = hangingFetch();
    const outcome = postIntent(INTENT).then(
      () => 'resolved',
      (err: unknown) => (err as Error).name,
    );
    await vi.advanceTimersByTimeAsync(INTENT_POST_TIMEOUT_MS - 1);
    let settled = false;
    void outcome.then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(outcome).resolves.toBe('AbortError');
  });

  it('clears its deadline once the POST settles, leaving no timer behind', async () => {
    globalThis.fetch = (async () => ({ ok: true, status: 200 })) as unknown as typeof fetch;
    await postIntent(INTENT);
    expect(vi.getTimerCount()).toBe(0);
  });
});
