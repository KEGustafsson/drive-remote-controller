import { useEffect, useState } from 'react';
import {
  RX_TELEMETRY_POLL_MS,
  SK_HH_LINK_UP_PATH,
  SK_PLUGIN_ACTIVE_CLIENT_PATH,
  SK_PLUGIN_HH_LIVE_PATH,
  SK_PLUGIN_RX_LIVE_PATH,
  SK_RX_LINK_UP_PATH,
} from '../config';
import { evaluateRxLiveness, type RxLiveness } from '../pure/rxLiveness';
import { runtimeNowMs } from '../pure/runtimeClock';
import { serverStreamLive } from '../pure/serverStream';
import type { SubscribedPath } from '../skClient';
import type { SkConnection } from './useSkConnection';

/**
 * Watches whether RX telemetry is still arriving, and re-evaluates on a
 * timer.
 *
 * The timer is not optional polish: a link going dead produces NO event --
 * it is precisely the absence of events -- so React would never re-render to
 * discover it. Without a clock the UI keeps rendering the last delta it ever
 * received, which is exactly how a powered-down RX kept showing as "up —
 * ready" with a working ARM button.
 *
 * Any RX path would do as the heartbeat (RX publishes them all on one 250 ms
 * timer); rx.linkUp is used because it is the one the status panel displays,
 * so the row's freshness and its content come from the same delta.
 */
// The clock every arrival age here is measured against, re-read on a timer.
// Must be the SAME clock skClient stamps arrivals with, or every age is
// meaningless. runtimeNowMs() also counts time the device spent suspended,
// so a laptop closed with this open does not wake reporting stale telemetry
// as live (SAFETY.md invariant 6).
function useRuntimeNow(): number {
  const [now, setNow] = useState(() => runtimeNowMs());
  useEffect(() => {
    const id = setInterval(() => setNow(runtimeNowMs()), RX_TELEMETRY_POLL_MS);
    return () => clearInterval(id);
  }, []);
  return now;
}

export function useUnitLiveness(
  connection: SkConnection,
  telemetryPath: SubscribedPath,
  pluginLivePath: SubscribedPath,
): RxLiveness {
  const { connectionState, values, getReceivedAt } = connection;
  const now = useRuntimeNow();

  const receivedAt = getReceivedAt(telemetryPath);
  // `now` can lag a real arrival by up to one poll interval, and a
  // background tab's throttled timers can lag it much further; clamp so a
  // late clock can never report a negative age.
  const rxTelemetryAgeMs =
    receivedAt === undefined ? undefined : Math.max(0, now - receivedAt);

  return evaluateRxLiveness({
    connectionState,
    rxTelemetryAgeMs,
    pluginRxLive: values[pluginLivePath],
  });
}

/** The drive controller (RX). */
export function useRxLiveness(connection: SkConnection): RxLiveness {
  return useUnitLiveness(connection, SK_RX_LINK_UP_PATH, SK_PLUGIN_RX_LIVE_PATH);
}

/**
 * The heading-hold (bow thruster) unit. Deliberately the SAME code path as RX
 * -- the thruster must not end up with a subtly more forgiving notion of
 * "present" than the drives, and one implementation is the only way to be sure
 * of that.
 */
export function useHhLiveness(connection: SkConnection): RxLiveness {
  return useUnitLiveness(connection, SK_HH_LINK_UP_PATH, SK_PLUGIN_HH_LIVE_PATH);
}

/**
 * Is the server's stream still carrying live data -- the arbiter's
 * activeClient republish arriving on the current socket? The socket reading
 * 'open' is not enough (a half-open socket reads open forever) and the
 * retained activeClient value is not evidence at all. See pure/serverStream.ts.
 * Re-evaluated on the same timer as the unit checks, because going silent
 * produces no event.
 */
export function useServerStreamLive(connection: SkConnection): boolean {
  const { connectionState, openedAt, getReceivedAt } = connection;
  const now = useRuntimeNow();
  return serverStreamLive({
    connectionState,
    receivedAtMs: getReceivedAt(SK_PLUGIN_ACTIVE_CLIENT_PATH),
    openedAtMs: openedAt,
    nowMs: now,
  });
}
