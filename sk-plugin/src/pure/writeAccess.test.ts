import { describe, expect, it } from 'vitest';
import { resolveWriteStatus } from './writeAccess';

describe('resolveWriteStatus', () => {
  it('security off (authenticationRequired false) -> writable', () => {
    expect(
      resolveWriteStatus({ status: 'notLoggedIn', authenticationRequired: false }),
    ).toBe('writable');
  });

  it('security on + logged in with write permission -> writable', () => {
    expect(
      resolveWriteStatus({
        status: 'loggedIn',
        authenticationRequired: true,
        userLevel: 'readwrite',
      }),
    ).toBe('writable');
  });

  it('security on + logged in as admin -> writable', () => {
    expect(
      resolveWriteStatus({
        status: 'loggedIn',
        authenticationRequired: true,
        userLevel: 'admin',
      }),
    ).toBe('writable');
  });

  it('security on + NOT logged in -> readonly (commands would be dropped)', () => {
    expect(
      resolveWriteStatus({ status: 'notLoggedIn', authenticationRequired: true }),
    ).toBe('readonly');
  });

  it('security on + logged in but read-only user -> readonly', () => {
    expect(
      resolveWriteStatus({
        status: 'loggedIn',
        authenticationRequired: true,
        userLevel: 'readonly',
      }),
    ).toBe('readonly');
  });

  it.each([undefined, null, 42, 'nope', {}, { authenticationRequired: 'yes' }])(
    'ambiguous/garbage input %p -> unknown, never a false alarm',
    (value) => {
      expect(resolveWriteStatus(value)).toBe('unknown');
    },
  );
});
