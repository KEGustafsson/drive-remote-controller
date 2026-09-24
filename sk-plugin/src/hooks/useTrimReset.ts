import { useEffect, useState } from 'react';
import { RX_TELEMETRY_POLL_MS } from '../config';
import type { ThrusterMode } from '../clientIntent';
import { runtimeNowMs } from '../pure/runtimeClock';
import { trimMustReset } from '../pure/trimReset';

/**
 * Must the heading trim be forced back to 0 right now? The rule is
 * pure/trimReset.ts; this only supplies the one input React does not have to
 * hand -- how long HH has read not-live on a connected stream -- and
 * re-evaluates it on a timer, because the window running out produces no
 * event.
 *
 * The clock is `runtimeNowMs`, the same one every arrival age is measured on.
 */
export function useTrimReset(
  mode: ThrusterMode,
  connected: boolean,
  armed: boolean,
  hhLive: boolean,
): boolean {
  const [now, setNow] = useState(() => runtimeNowMs());
  const [hhGoneSince, setHhGoneSince] = useState<number | null>(null);

  useEffect(() => {
    const id = setInterval(() => setNow(runtimeNowMs()), RX_TELEMETRY_POLL_MS);
    return () => clearInterval(id);
  }, []);

  // Stamped on the transition, one render late -- always in the direction of
  // patience: until the stamp lands the count reads 0. The stream dropping
  // clears it, so an outage always restarts the count from nothing.
  const hhGone = connected && !hhLive;
  useEffect(() => {
    setHhGoneSince(hhGone ? runtimeNowMs() : null);
  }, [hhGone]);

  const hhGoneForMs =
    hhGone && hhGoneSince !== null ? Math.max(0, now - hhGoneSince) : 0;

  return trimMustReset({ mode, connected, armed, hhLive, hhGoneForMs });
}
