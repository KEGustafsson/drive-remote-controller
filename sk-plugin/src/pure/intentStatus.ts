// Pure mapping from the outcome of an intent POST to what the Commands lamp
// should say. DOM/network-free, same discipline as writeAccess.ts.
//
// Why this exists: the Commands lamp used to be driven ONLY by
// /skServer/loginStatus -- a statement about this browser's session, made
// before a single command was sent. That is a proxy, and it was wrong in
// exactly the cases that matter: a readwrite login on a server whose plugin
// route fell back to admin-only (every POST 403), a plugin that is disabled
// (every POST 503), or a session cookie that expired mid-session (401). In all
// three the lamp read "reaching boat" while nothing reached anything, and a
// STOP tap failed with no indication. The intent POST is the real effect --
// AGENTS.md: verify the real effect, not a proxy -- so its outcome is what the
// lamp reports, with the loginStatus answer only filling in until one exists.

import type { WriteStatus } from './writeAccess';

export type IntentStatus =
  // Nothing sent yet (or no verdict yet): fall back to the loginStatus proxy.
  | 'unknown'
  // The plugin accepted the last intent (2xx). Whatever loginStatus said,
  // commands are demonstrably reaching the plugin.
  | 'ok'
  // 401 / 403: the server refused this browser. Log in, or the route needs a
  // higher permission level than this session has.
  | 'auth'
  // 503: the plugin's route answered, but the plugin is not running.
  | 'unavailable'
  // Any other failure -- a network error, a timeout, a 5xx: the server or the
  // route is not answering at all.
  | 'network';

/** Classify a rejected PostIntent promise. */
export function classifyIntentFailure(err: unknown): IntentStatus {
  const status =
    typeof err === 'object' && err !== null && 'status' in err
      ? (err as { status?: unknown }).status
      : undefined;
  if (status === 401 || status === 403) return 'auth';
  if (status === 503) return 'unavailable';
  return 'network';
}

export type CommandsIndication = {
  value: string;
  status: 'good' | 'warn' | 'bad';
};

/**
 * What the Commands lamp shows. A real POST outcome outranks the loginStatus
 * proxy in both directions: a 2xx means commands are reaching the plugin even
 * if loginStatus looked read-only, and a 401/403/503/network failure is a
 * blocked command path even if loginStatus looked fine.
 */
export function commandsIndication(
  writeStatus: WriteStatus,
  intentStatus: IntentStatus,
): CommandsIndication {
  switch (intentStatus) {
    case 'ok':
      return { value: 'reaching boat', status: 'good' };
    case 'auth':
      return { value: 'BLOCKED — log in', status: 'bad' };
    case 'unavailable':
      return { value: 'BLOCKED — plugin not running', status: 'bad' };
    case 'network':
      return { value: 'NOT REACHING BOAT', status: 'bad' };
    case 'unknown':
      break;
  }
  switch (writeStatus) {
    case 'writable':
      return { value: 'reaching boat', status: 'good' };
    case 'readonly':
      return { value: 'BLOCKED — log in', status: 'bad' };
    default:
      return { value: 'unconfirmed', status: 'warn' };
  }
}
