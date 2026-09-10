// The intent one UI instance publishes to the plugin, plus this tab's
// stable identity. Kept out of src/pure/ because makeClientId() is
// deliberately non-deterministic (each browser tab must be distinguishable
// from every other) -- everything else here is a plain data shape.
//
// The wire counterpart lives in arbiter.cjs (server side); this is the
// TypeScript view of the exact object POSTed to the plugin's
// /plugins/<id>/intent route (not a Signal K path -- see config.ts).

import { SK_PLUGIN_INTENT_ENDPOINT } from './config';
import type { DrivePosition } from './pure/driveCommand';

/** Bow-thruster direction. 'off' (not 'neutral') -- a thruster coasts rather
 *  than sitting in a gear. Matches ThrusterCmdToSkString in the firmware. */
export type ThrusterDirection = 'port' | 'off' | 'stbd';

/** Which gate owns the thruster: the operator's buttons, or heading hold. */
export type ThrusterMode = 'manual' | 'hold';

export interface ClientIntent {
  /** Stable per-tab id; also the trailing path segment of this node. */
  clientId: string;
  /**
   * Increments on EVERY publish (including the 250 ms heartbeat), so no
   * change-detecting transport can ever dedupe a heartbeat and starve the
   * plugin's liveness view of this client.
   */
  seq: number;
  /** Increments once per operator "arm" tap. Edge-triggered by the arbiter. */
  armReq: number;
  /** Increments once per operator "disarm" tap. Edge-triggered; universal. */
  disarmReq: number;
  port: DrivePosition;
  stbd: DrivePosition;
  /** Bow thruster direction being asked for right now (manual mode only). */
  thruster: ThrusterDirection;
  /** Which thruster gate this client wants in force. */
  thrusterMode: ThrusterMode;
  /**
   * Commanded heading-hold TRIM in DEGREES, RELATIVE to the heading HH
   * captured when hold engaged. 0 = no trim (hold the captured heading). A
   * self-correcting level (the whole offset, not per-press events), clamped to
   * +/-MAX_TRIM_DEG, so a dropped or duplicated message can never desynchronise
   * this UI from the boat -- and, being relative, it needs no "not commanding"
   * sentinel (0 says it). See pure/trimOffset.ts and heading/heading_nudge.h.
   */
  trimDeg: number;
}

/**
 * A stable, unique-per-tab id. Two tabs (even on the same device) must get
 * different ids so the arbiter can tell them apart. If instances shared one
 * id (hence one $source), two disagreeing about ARMED would make the merged
 * value oscillate -- the separation is what prevents that. crypto.randomUUID()
 * where available, with a plain-random fallback for environments without it.
 */
export function makeClientId(): string {
  const c = (globalThis as { crypto?: Crypto }).crypto;
  if (c && typeof c.randomUUID === 'function') {
    return `ui-${c.randomUUID()}`;
  }
  return `ui-${Math.random().toString(36).slice(2)}-${Math.random()
    .toString(36)
    .slice(2)}`;
}

/** Sends one intent to the plugin. Injectable so tests can drive a real
 *  in-process arbiter without an HTTP server (mirrors how `fetchLoginStatus`
 *  is injected). Rejects on any non-2xx / network error so the caller can
 *  treat "commands not reaching the plugin" as a distinct state -- App.tsx
 *  turns the outcome into the Commands lamp via pure/intentStatus.ts. */
export type PostIntent = (intent: ClientIntent) => Promise<void>;

/**
 * A non-2xx answer to an intent POST. Carries the HTTP status so the caller
 * can tell "this browser is not allowed" (401/403) from "the plugin is not
 * running" (503) from a transport failure (no status at all).
 */
export class IntentPostError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`intent POST ${status}`);
    this.name = 'IntentPostError';
    this.status = status;
  }
}

/**
 * How long one intent POST may take. An intent is only relevant for the
 * 250 ms until the next one, so a request still open after this is already
 * stale -- and a server that has stopped answering must not be allowed to
 * queue an unbounded backlog of obsolete commands behind the browser's
 * per-origin connection limit, with a STOP somewhere at the back of it.
 */
export const INTENT_POST_TIMEOUT_MS = 2000;

/**
 * Production transport: POST the intent to the plugin's own route. Relative +
 * same-origin (the app is served BY the SK server), `credentials: 'include'`
 * so it carries the same session cookie the WebSocket does -- meaning a
 * secured server authorizes (or 401-rejects) it exactly as it would a delta
 * write. Nothing is written to the Signal K data tree.
 */
export const postIntent: PostIntent = async (intent) => {
  // AbortSignal.timeout is missing on some older WebViews; without it the
  // request simply has no client-side deadline, as before.
  const signal =
    typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function'
      ? AbortSignal.timeout(INTENT_POST_TIMEOUT_MS)
      : undefined;
  const res = await fetch(SK_PLUGIN_INTENT_ENDPOINT, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(intent),
    signal,
  });
  if (!res.ok) throw new IntentPostError(res.status);
};
