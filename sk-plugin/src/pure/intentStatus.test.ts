import { describe, expect, it } from 'vitest';
import { classifyIntentFailure, commandsIndication } from './intentStatus';

describe('classifyIntentFailure', () => {
  it('reads 401 and 403 as an authorisation problem', () => {
    expect(classifyIntentFailure({ status: 401 })).toBe('auth');
    expect(classifyIntentFailure({ status: 403 })).toBe('auth');
  });

  it('reads 503 as the plugin not running', () => {
    expect(classifyIntentFailure({ status: 503 })).toBe('unavailable');
  });

  it('reads anything else -- a network error, a timeout, a 5xx -- as not reaching the boat', () => {
    expect(classifyIntentFailure(new TypeError('Failed to fetch'))).toBe('network');
    expect(classifyIntentFailure({ status: 500 })).toBe('network');
    expect(classifyIntentFailure(undefined)).toBe('network');
  });
});

describe('commandsIndication', () => {
  it('lets a real POST outcome outrank the loginStatus proxy in both directions', () => {
    // Accepted intents prove commands are landing, whatever loginStatus said.
    expect(commandsIndication('readonly', 'ok')).toEqual({
      value: 'reaching boat',
      status: 'good',
    });
    // And a refused intent is a blocked path even on a "writable" session --
    // the readwrite login on a server whose plugin route fell back to
    // admin-only is exactly this case.
    expect(commandsIndication('writable', 'auth').status).toBe('bad');
    expect(commandsIndication('writable', 'unavailable')).toEqual({
      value: 'BLOCKED — plugin not running',
      status: 'bad',
    });
    expect(commandsIndication('writable', 'network')).toEqual({
      value: 'NOT REACHING BOAT',
      status: 'bad',
    });
  });

  it('falls back to loginStatus until an intent has been answered', () => {
    expect(commandsIndication('writable', 'unknown').status).toBe('good');
    expect(commandsIndication('readonly', 'unknown')).toEqual({
      value: 'BLOCKED — log in',
      status: 'bad',
    });
    expect(commandsIndication('unknown', 'unknown')).toEqual({
      value: 'unconfirmed',
      status: 'warn',
    });
  });
});
