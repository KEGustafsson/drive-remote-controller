// The kill switch derives armed/foreignControl from the arbiter's last-known
// activeClient, which the SK client never clears on disconnect. So the moment
// the socket drops it must STOP asserting a confident ARMED/IN USE. But the
// socket is only the READ side -- the intent heartbeat keeps POSTing over
// HTTP, so we may still genuinely hold the token while offline. These tests
// pin both halves: offline overrides every displayed state, AND the tap
// remains a live, ungated STOP (SAFETY.md: disarm is never gated on
// anything -- least of all on the health of a different transport).

import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { KILL_SWITCH_STOP_HOLDOVER_MS } from '../config';
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

// The button's meaning can change between the operator's decision and the
// click landing -- the arbiter answers a STOP in milliseconds. A tap within
// KILL_SWITCH_STOP_HOLDOVER_MS of the button last meaning STOP is a STOP.
describe('KillSwitch: a tap aimed at STOP stays a STOP', () => {
  const base = {
    connected: true,
    rxLiveness: 'live' as const,
    hhLiveness: 'live' as const,
    canArm: true,
  };

  afterEach(() => {
    vi.restoreAllMocks();
  });

  for (const [what, from] of [
    ['ARMED', { armed: true, foreignControl: false, connected: true }],
    ['IN USE', { armed: false, foreignControl: true, connected: true }],
    ['OFFLINE', { armed: false, foreignControl: false, connected: false }],
  ] as const) {
    it(`a tap just after ${what} flips to DISARMED still stops, never arms`, () => {
      const onArm = vi.fn();
      const onDisarm = vi.fn();
      const { rerender } = render(
        <KillSwitch {...base} {...from} onArm={onArm} onDisarm={onDisarm} />,
      );
      rerender(
        <KillSwitch
          {...base}
          armed={false}
          foreignControl={false}
          onArm={onArm}
          onDisarm={onDisarm}
        />,
      );
      expect(screen.getByText('DISARMED')).toBeInTheDocument();
      fireEvent.click(killButton());
      expect(onDisarm).toHaveBeenCalledTimes(1);
      expect(onArm).not.toHaveBeenCalled();
    });
  }

  // A tap held over as STOP restarts the window: hammering STOP at a pace just
  // under the hold-over must never reach ARM, however long it goes on.
  it('hammered STOP taps never reach ARM', () => {
    let now = 10_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const onArm = vi.fn();
    const onDisarm = vi.fn();
    const { rerender } = render(
      <KillSwitch {...base} armed foreignControl={false} onArm={onArm} onDisarm={onDisarm} />,
    );
    rerender(
      <KillSwitch
        {...base}
        armed={false}
        foreignControl={false}
        onArm={onArm}
        onDisarm={onDisarm}
      />,
    );
    for (let i = 0; i < 6; i++) {
      now += KILL_SWITCH_STOP_HOLDOVER_MS - 100;
      fireEvent.click(killButton());
    }
    expect(onArm).not.toHaveBeenCalled();
    expect(onDisarm).toHaveBeenCalledTimes(6);
  });

  it('arms normally once the hold-over has passed', () => {
    let now = 10_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const onArm = vi.fn();
    const onDisarm = vi.fn();
    const { rerender } = render(
      <KillSwitch {...base} armed foreignControl={false} onArm={onArm} onDisarm={onDisarm} />,
    );
    rerender(
      <KillSwitch
        {...base}
        armed={false}
        foreignControl={false}
        onArm={onArm}
        onDisarm={onDisarm}
      />,
    );
    now += KILL_SWITCH_STOP_HOLDOVER_MS + 1;
    fireEvent.click(killButton());
    expect(onArm).toHaveBeenCalledTimes(1);
    expect(onDisarm).not.toHaveBeenCalled();
  });
});

// Owner decision: a gesture's meaning is fixed at touch-down. A click fires on
// LIFT, and only when the pointer comes up inside the button -- so a hurried
// STOP whose finger slid off used to send nothing. STOP now acts on the way
// down and its gesture can never arm; ARM still waits for the lift, so sliding
// off cancels an arm nobody meant. Keyboard and assistive activation (a click
// with detail 0 and no pointer down) is decided at the click, as before.
// fireEvent.click defaults to detail 0 -- a pointer's click passes {detail: 1}.
describe('KillSwitch: the gesture is decided at touch-down', () => {
  const base = {
    rxLiveness: 'live' as const,
    hhLiveness: 'live' as const,
    canArm: true,
  };

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function setup(initial: { armed: boolean; foreignControl: boolean; connected: boolean }) {
    const onArm = vi.fn();
    const onDisarm = vi.fn();
    const utils = render(
      <KillSwitch {...base} {...initial} onArm={onArm} onDisarm={onDisarm} />,
    );
    const show = (next: { armed: boolean; foreignControl: boolean; connected: boolean }) =>
      utils.rerender(<KillSwitch {...base} {...next} onArm={onArm} onDisarm={onDisarm} />);
    return { onArm, onDisarm, show };
  }

  const ARMED = { armed: true, foreignControl: false, connected: true };
  const IN_USE = { armed: false, foreignControl: true, connected: true };
  const OFFLINE = { armed: false, foreignControl: false, connected: false };
  const DISARMED = { armed: false, foreignControl: false, connected: true };

  for (const [what, from] of [
    ['ARMED', ARMED],
    ['IN USE', IN_USE],
    ['OFFLINE', OFFLINE],
  ] as const) {
    it(`STOP fires on pointer down from ${what}, with no click at all`, () => {
      const { onArm, onDisarm } = setup(from);
      fireEvent.pointerDown(killButton(), { pointerId: 1 });
      // The finger slides off and lifts elsewhere: no click ever reaches it.
      fireEvent.pointerLeave(killButton(), { pointerId: 1 });
      expect(onDisarm).toHaveBeenCalledTimes(1);
      expect(onArm).not.toHaveBeenCalled();
    });

    it(`the click that ends a STOP gesture from ${what} never arms, however long it was held`, () => {
      let now = 10_000;
      vi.spyOn(performance, 'now').mockImplementation(() => now);
      const { onArm, onDisarm, show } = setup(from);
      fireEvent.pointerDown(killButton(), { pointerId: 1 });
      expect(onDisarm).toHaveBeenCalledTimes(1);
      // The STOP lands and the button flips to DISARMED under the finger...
      show(DISARMED);
      // ...which stays down well past the holdover, then lifts on the button.
      now += KILL_SWITCH_STOP_HOLDOVER_MS * 3;
      fireEvent.click(killButton(), { detail: 1 });
      expect(onArm).not.toHaveBeenCalled();
      expect(onDisarm).toHaveBeenCalledTimes(1);
    });
  }

  it('ARM does nothing on pointer down, and arms on the click', () => {
    const { onArm, onDisarm } = setup(DISARMED);
    fireEvent.pointerDown(killButton(), { pointerId: 1 });
    expect(onArm).not.toHaveBeenCalled();
    fireEvent.click(killButton(), { detail: 1 });
    expect(onArm).toHaveBeenCalledTimes(1);
    expect(onDisarm).not.toHaveBeenCalled();
  });

  it('an ARM gesture that slides off sends nothing', () => {
    const { onArm, onDisarm } = setup(DISARMED);
    fireEvent.pointerDown(killButton(), { pointerId: 1 });
    fireEvent.pointerLeave(killButton(), { pointerId: 1 });
    fireEvent.pointerUp(document.body, { pointerId: 1 });
    expect(onArm).not.toHaveBeenCalled();
    expect(onDisarm).not.toHaveBeenCalled();
  });

  it('an ARM gesture whose button comes to mean STOP before the lift is a STOP', () => {
    const { onArm, onDisarm, show } = setup(DISARMED);
    fireEvent.pointerDown(killButton(), { pointerId: 1 });
    show(IN_USE); // another station armed during the press
    fireEvent.click(killButton(), { detail: 1 });
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).not.toHaveBeenCalled();
  });

  it('a keyboard click still arms or stops by the policy', () => {
    const disarmed = setup(DISARMED);
    fireEvent.click(killButton(), { detail: 0 });
    expect(disarmed.onArm).toHaveBeenCalledTimes(1);
    disarmed.show(ARMED);
    fireEvent.click(killButton(), { detail: 0 });
    expect(disarmed.onDisarm).toHaveBeenCalledTimes(1);
  });

  // A swallow left standing by a gesture that never produced its click must
  // not eat the next legitimate activation.
  it('a STOP that slid off cannot swallow a later keyboard arm', () => {
    let now = 10_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { onArm, onDisarm, show } = setup(ARMED);
    fireEvent.pointerDown(killButton(), { pointerId: 1 });
    fireEvent.pointerLeave(killButton(), { pointerId: 1 });
    show(DISARMED);
    now += KILL_SWITCH_STOP_HOLDOVER_MS + 1;
    fireEvent.click(killButton(), { detail: 0 });
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).toHaveBeenCalledTimes(1);
  });

  it('a cancelled STOP gesture cannot swallow a later click', () => {
    let now = 10_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { onArm, onDisarm, show } = setup(ARMED);
    fireEvent.pointerDown(killButton(), { pointerId: 1 });
    fireEvent.pointerCancel(killButton(), { pointerId: 1 });
    show(DISARMED);
    now += KILL_SWITCH_STOP_HOLDOVER_MS + 1;
    fireEvent.click(killButton(), { detail: 1 });
    expect(onDisarm).toHaveBeenCalledTimes(1);
    expect(onArm).toHaveBeenCalledTimes(1);
  });

  it('the next pointer gesture decides afresh after a STOP that slid off', () => {
    let now = 10_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { onArm, show } = setup(ARMED);
    fireEvent.pointerDown(killButton(), { pointerId: 1 });
    fireEvent.pointerLeave(killButton(), { pointerId: 1 });
    show(DISARMED);
    now += KILL_SWITCH_STOP_HOLDOVER_MS + 1;
    fireEvent.pointerDown(killButton(), { pointerId: 2 });
    expect(onArm).not.toHaveBeenCalled(); // ARM waits for the lift
    fireEvent.click(killButton(), { detail: 1 });
    expect(onArm).toHaveBeenCalledTimes(1);
  });

  // The holdover still restarts on a STOP decided at touch-down, so hammered
  // STOP gestures at any pace under it never reach ARM.
  it('hammered STOP gestures never reach ARM', () => {
    let now = 10_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { onArm, onDisarm, show } = setup(ARMED);
    show(DISARMED);
    for (let i = 0; i < 6; i++) {
      now += KILL_SWITCH_STOP_HOLDOVER_MS - 100;
      fireEvent.pointerDown(killButton(), { pointerId: i + 1 });
      fireEvent.click(killButton(), { detail: 1 });
    }
    expect(onArm).not.toHaveBeenCalled();
    expect(onDisarm).toHaveBeenCalledTimes(6);
  });
});
