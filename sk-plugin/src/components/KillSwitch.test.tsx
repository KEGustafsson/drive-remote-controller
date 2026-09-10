// The kill switch derives armed/foreignControl from the arbiter's last-known
// activeClient, which the SK client never clears on disconnect. So the moment
// the socket drops it must STOP asserting a confident ARMED/IN USE. But the
// socket is only the READ side -- the intent heartbeat keeps POSTing over
// HTTP, so we may still genuinely hold the token while offline. These tests
// pin both halves: offline overrides every displayed state, AND the tap
// remains a live, ungated STOP (SAFETY.md: disarm is never gated on
// anything -- least of all on the health of a different transport).

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { KillSwitch } from './KillSwitch';

function renderSwitch(props: Partial<Parameters<typeof KillSwitch>[0]> = {}) {
  const onArm = vi.fn();
  const onDisarm = vi.fn();
  render(
    <KillSwitch
      armed={false}
      foreignControl={false}
      connected={true}
      rxLiveness="live"
      hhLiveness="live"
      canArm={true}
      onArm={onArm}
      onDisarm={onDisarm}
      {...props}
    />,
  );
  return { onArm, onDisarm };
}

function killButton(): HTMLButtonElement {
  const btn = screen.getByRole('button');
  if (!(btn instanceof HTMLButtonElement)) throw new Error('no kill button');
  return btn;
}

describe('KillSwitch when connected', () => {
  it('shows ARMED and disarms on tap when we hold the token', () => {
    const { onArm, onDisarm } = renderSwitch({ armed: true });
    expect(screen.getByText('ARMED')).toBeInTheDocument();
    expect(killButton().className).toContain('kill-switch--armed');
    fireEvent.click(killButton());
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).not.toHaveBeenCalled();
  });

  it('shows IN USE and stops (disarms) on tap when another device holds it', () => {
    const { onArm, onDisarm } = renderSwitch({ foreignControl: true });
    expect(screen.getByText('IN USE')).toBeInTheDocument();
    fireEvent.click(killButton());
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).not.toHaveBeenCalled();
  });

  it('shows DISARMED and arms on tap when nobody holds it', () => {
    const { onArm, onDisarm } = renderSwitch();
    expect(screen.getByText('DISARMED')).toBeInTheDocument();
    fireEvent.click(killButton());
    expect(onArm).toHaveBeenCalledTimes(1);
    expect(onDisarm).not.toHaveBeenCalled();
  });
});

describe('KillSwitch when offline (socket down)', () => {
  it('does NOT show a confident ARMED even though armed is still true (stale)', () => {
    renderSwitch({ armed: true, connected: false });
    // Guards against a red ARMED persisting after the connection dropped --
    // and equally against a false "DISARMED": with the heartbeat still
    // POSTing, we may in truth still be armed. OFFLINE claims neither.
    expect(screen.queryByText('ARMED')).not.toBeInTheDocument();
    expect(screen.queryByText('DISARMED')).not.toBeInTheDocument();
    expect(killButton().className).not.toContain('kill-switch--armed');
    expect(killButton().className).toContain('kill-switch--offline');
    expect(screen.getByText('OFFLINE')).toBeInTheDocument();
    expect(screen.getByText(/connection lost/i)).toBeInTheDocument();
  });

  it('does NOT show IN USE from a stale foreign holder either', () => {
    renderSwitch({ foreignControl: true, connected: false });
    expect(screen.queryByText('IN USE')).not.toBeInTheDocument();
    expect(killButton().className).toContain('kill-switch--offline');
  });

  // THE critical offline property: the read socket dying must not take the
  // stop control with it. Disarm travels a separate HTTP POST that may well
  // still work -- and if it doesn't, firing it costs nothing. A disabled
  // kill switch during a network wobble is the exact wrong failure mode.
  it('the tap stays live while offline and always means STOP (never arm)', () => {
    const { onArm, onDisarm } = renderSwitch({ armed: true, connected: false });
    const btn = killButton();
    expect(btn.disabled).toBe(false);
    expect(btn).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(btn);
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).not.toHaveBeenCalled();
  });

  it('offline tap disarms even from the (stale) nobody-holds-it state', () => {
    // Even when the last-known state says nothing is armed, the truth is
    // unknowable while offline -- the tap must still be a stop, never an arm.
    const { onArm, onDisarm } = renderSwitch({ connected: false });
    fireEvent.click(killButton());
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).not.toHaveBeenCalled();
  });
});

// With a unit switched off, arming must be impossible until it is verified
// present -- otherwise the UI shows an all-green, armable state for a board
// with no power. Stopping must never be blocked by the same condition, since a
// stop is exactly what you want when things are wrong.
describe('KillSwitch when no unit is responding', () => {
  it('refuses to arm and names the reason', () => {
    const { onArm } = renderSwitch({
      canArm: false,
      rxLiveness: 'stale',
      hhLiveness: 'stale',
    });
    const btn = killButton();
    expect(btn.disabled).toBe(true);
    expect(btn.className).toContain('kill-switch--blocked');
    expect(screen.getByText(/no unit responding/i)).toBeInTheDocument();
    fireEvent.click(btn);
    expect(onArm).not.toHaveBeenCalled();
  });

  it('says "check they are powered on" when no unit has ever been seen', () => {
    renderSwitch({
      canArm: false,
      rxLiveness: 'never-seen',
      hhLiveness: 'never-seen',
    });
    expect(screen.getByText(/no units seen/i)).toBeInTheDocument();
    expect(screen.getByText(/powered on/i)).toBeInTheDocument();
  });

  // One ARM covers both machines, and one absent unit must NOT block it --
  // refusing to arm the drives because the thruster board is off would take
  // away the primary docking control at the worst possible moment. The
  // partial state is named on the button instead of being hidden.
  it('still arms with only the drive unit live, and names the missing one', () => {
    const { onArm } = renderSwitch({
      canArm: true,
      rxLiveness: 'live',
      hhLiveness: 'stale',
    });
    const btn = killButton();
    expect(btn.disabled).toBe(false);
    expect(screen.getByText(/thruster unit not responding/i)).toBeInTheDocument();
    fireEvent.click(btn);
    expect(onArm).toHaveBeenCalled();
  });

  it('still arms with only the thruster unit live, and names the missing one', () => {
    const { onArm } = renderSwitch({
      canArm: true,
      rxLiveness: 'stale',
      hhLiveness: 'live',
    });
    expect(screen.getByText(/drive unit not responding/i)).toBeInTheDocument();
    fireEvent.click(killButton());
    expect(onArm).toHaveBeenCalled();
  });

  it('still lets a held arm be released -- a stop is never gated on RX', () => {
    const { onDisarm } = renderSwitch({
      armed: true,
      canArm: false,
      rxLiveness: 'stale',
    });
    const btn = killButton();
    expect(btn.disabled).toBe(false);
    fireEvent.click(btn);
    expect(onDisarm).toHaveBeenCalledTimes(1);
  });

  it('still lets another device be stopped while RX is unreachable', () => {
    const { onDisarm } = renderSwitch({
      foreignControl: true,
      canArm: false,
      rxLiveness: 'stale',
    });
    fireEvent.click(killButton());
    expect(onDisarm).toHaveBeenCalledTimes(1);
  });
});
