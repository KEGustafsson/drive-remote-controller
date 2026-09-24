// Renders the REAL App tree (real hooks, real components) against a REAL
// in-process WebSocket server that runs the REAL arbiter (arbiter.cjs) --
// never a mocked WebSocket, skClient, or arming logic. These tests exercise
// the actual production loop end to end: a pointer/click event -> the intent
// bytes a Signal K server would receive -> the arbiter's decision -> the
// activeClient echo -> what the operator sees.

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WebSocket as NodeWebSocket } from 'ws';
import { App } from './App';
import { IntentPostError, type PostIntent } from './clientIntent';
import { KILL_SWITCH_STOP_HOLDOVER_MS, PERIODIC_REFRESH_MS } from './config';
import { SkClientContext } from './hooks/useSkConnection';
import { createSkClient, type SkClient } from './skClient';
import { startArbiterServer, type ArbiterHarness } from '../test/arbiterServer';

let harness: ArbiterHarness;
let client: SkClient;

async function renderApp(
  appProps: { fetchLoginStatus?: () => Promise<unknown>; postIntent?: PostIntent } = {},
) {
  client = createSkClient(harness.url, NodeWebSocket as unknown as typeof WebSocket);
  await waitFor(() =>
    expect(client.getSnapshot().connectionState).toBe('open'),
  );
  const utils = render(
    <SkClientContext.Provider value={client}>
      {/* Intents go to the REAL arbiter in-process, standing in for the
          plugin's HTTP route; its resulting state comes back over the WS as
          the plugin's activeClient publish would. */}
      <App
        postIntent={async (intent) => {
          harness.feedIntent(intent as unknown as Record<string, unknown>);
        }}
        {...appProps}
      />
    </SkClientContext.Provider>,
  );
  // The mount-time intent POST resolves in a microtask and reports its outcome
  // into state (the Commands lamp). Flush that inside act() so every test
  // starts from a settled tree instead of logging an un-act-wrapped update.
  await act(async () => {});
  return utils;
}

function pointerDown(el: Element, pointerId: number) {
  fireEvent.pointerDown(el, { pointerId });
}
function pointerUp(el: Element, pointerId: number) {
  fireEvent.pointerUp(el, { pointerId });
}

// Arm this instance and wait for the arbiter's echo. The drive/thruster
// widgets are inert until commandable (armed + a live unit), so any test that
// drives the command buttons must arm first -- the harness pulses RX/HH
// telemetry from connect, so arming is enough to make the drives commandable.
async function arm() {
  fireEvent.click(screen.getByText('DISARMED'));
  await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
}

beforeEach(async () => {
  harness = await startArbiterServer();
});

afterEach(async () => {
  client?.close();
  await harness.close();
});

describe('App', () => {
  it('starts DISARMED with both drives NEUTRAL', async () => {
    await renderApp();
    expect(screen.getByText('DISARMED')).toBeInTheDocument();
    const port = screen.getByLabelText('Port drive control');
    const stbd = screen.getByLabelText('Starboard drive control');
    expect(within(port).getByText('NEUTRAL')).toBeInTheDocument();
    expect(within(stbd).getByText('NEUTRAL')).toBeInTheDocument();
  });

  it('publishes its intent on mount: no arm request, both neutral, a client id', async () => {
    const intentPromise = harness.nextIntent();
    await renderApp();
    const intent = await intentPromise;
    expect(intent.armReq).toBe(0);
    expect(intent.disarmReq).toBe(0);
    expect(intent.port).toBe('neutral');
    expect(intent.stbd).toBe('neutral');
    expect(typeof intent.clientId).toBe('string');
    expect((intent.clientId as string).length).toBeGreaterThan(0);
  });

  it('writes NOTHING to the Signal K data model -- it only subscribes', async () => {
    // The whole point of routing intents over HTTP: no per-tab
    // clients.<uuid> nodes (or any other delta) from the UI polluting the
    // data tree. The UI may only ever SUBSCRIBE on the WebSocket.
    await renderApp();
    fireEvent.click(screen.getByText('DISARMED')); // arm
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
    const port = screen.getByLabelText('Port drive control');
    pointerDown(within(port).getByLabelText('Port forward'), 1);
    // Let several heartbeats elapse -- any delta-publishing would show here.
    // act-wrapped like every other wait: the liveness poll and the arbiter's
    // echoes both re-render while we sit here.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 600));
    });

    const msgs = harness.clientMessages() as Array<Record<string, unknown>>;
    expect(msgs.length).toBeGreaterThan(0); // it did talk (the subscribe)
    for (const m of msgs) {
      expect(m).not.toHaveProperty('updates'); // never a delta write
      expect(m).toHaveProperty('subscribe');
    }
    // And it still genuinely commanded, via the arbiter, off-tree.
    expect(harness.state()).toMatchObject({ enabled: true, port: 'forward' });
  });

  it('tapping the kill switch requests arm; the arbiter grants and the UI shows ARMED', async () => {
    await renderApp();
    fireEvent.click(screen.getByText('DISARMED'));
    // ARMED only appears once the arbiter echoes activeClient === us.
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
    expect(harness.state().enabled).toBe(true);
  });

  it('pressing Port forward publishes forward; releasing returns to neutral', async () => {
    await renderApp();
    await arm();

    const port = screen.getByLabelText('Port drive control');
    const fwdBtn = within(port).getByLabelText('Port forward');

    const forwardIntent = harness.nextIntent((i) => i.port === 'forward');
    pointerDown(fwdBtn, 1);
    expect(within(port).getByText('FORWARD')).toBeInTheDocument();
    await forwardIntent;

    const neutralIntent = harness.nextIntent((i) => i.port === 'neutral');
    pointerUp(fwdBtn, 1);
    expect(within(port).getByText('NEUTRAL')).toBeInTheDocument();
    await neutralIntent;
  });

  it('pressing both Port buttons at once resolves to NEUTRAL, never a direction', async () => {
    await renderApp();
    await arm();

    const port = screen.getByLabelText('Port drive control');
    const fwdBtn = within(port).getByLabelText('Port forward');
    const revBtn = within(port).getByLabelText('Port reverse');

    pointerDown(fwdBtn, 1);
    expect(within(port).getByText('FORWARD')).toBeInTheDocument();

    const bothIntent = harness.nextIntent(
      (i) => i.port === 'neutral' && (i.seq as number) > 1,
    );
    pointerDown(revBtn, 2); // a second, different finger
    expect(within(port).getByText('NEUTRAL')).toBeInTheDocument();
    await bothIntent;
  });

  it('THE key requirement: simultaneous Port forward + Starboard reverse both take effect independently', async () => {
    await renderApp();
    await arm();

    const port = screen.getByLabelText('Port drive control');
    const stbd = screen.getByLabelText('Starboard drive control');
    const portFwd = within(port).getByLabelText('Port forward');
    const stbdRev = within(stbd).getByLabelText('Starboard reverse');

    pointerDown(portFwd, 1);
    pointerDown(stbdRev, 2);
    expect(within(port).getByText('FORWARD')).toBeInTheDocument();
    expect(within(stbd).getByText('REVERSE')).toBeInTheDocument();

    // Releasing one must not affect the other.
    pointerUp(portFwd, 1);
    expect(within(port).getByText('NEUTRAL')).toBeInTheDocument();
    expect(within(stbd).getByText('REVERSE')).toBeInTheDocument();
  });

  it('disables the drive buttons while DISARMED, and a press commands nothing', async () => {
    // The drives are inert until commandable: disarmed, the buttons are greyed
    // and cannot be pressed, so nothing can be commanded and the arbiter is
    // never armed. (The kill switch is the always-live stop -- tested via the
    // OFFLINE and takeover cases below.)
    await renderApp();

    const port = screen.getByLabelText('Port drive control');
    const fwdBtn = within(port).getByLabelText('Port forward');
    expect(fwdBtn).toBeDisabled();

    pointerDown(fwdBtn, 1);
    expect(within(port).getByText('NEUTRAL')).toBeInTheDocument(); // no command
    expect(screen.getByText('DISARMED')).toBeInTheDocument();
    // Let a heartbeat elapse; the intent must stay neutral and unarmed.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 300));
    });
    const s = harness.state();
    expect(s.enabled).toBe(false);
    expect(s.port).toBe('neutral');
  });

  it('renders incoming RX telemetry in the status panel', async () => {
    await renderApp();
    harness.sendDelta([
      { path: 'control.remoteController.rx.linkUp', value: true },
      { path: 'control.remoteController.rx.port.state', value: 'forward' },
      { path: 'control.remoteController.rx.port.source', value: 'tx' },
    ]);
    await waitFor(() => {
      expect(screen.getByText('up')).toBeInTheDocument();
      expect(screen.getByText('forward · TX remote')).toBeInTheDocument();
    });
  });

  it('warns when the server would silently drop commands (secured, not logged in)', async () => {
    // A secured server refuses this browser's intent POSTs (401) -- the real
    // symptom -- and its loginStatus says the same. Keep the login-status
    // promise pending across render, then resolve it INSIDE act() so the
    // resulting setState is unambiguously act-wrapped.
    let resolveLogin!: (v: unknown) => void;
    const login = new Promise<unknown>((r) => {
      resolveLogin = r;
    });
    await renderApp({
      fetchLoginStatus: () => login,
      postIntent: async () => {
        throw new IntentPostError(401);
      },
    });
    await act(async () => {
      resolveLogin({ status: 'notLoggedIn', authenticationRequired: true });
      await login;
    });
    expect(
      screen.getByText('BLOCKED — log in'),
    ).toBeInTheDocument();
  });

  it('reports a refused intent POST as BLOCKED even when loginStatus looked fine', async () => {
    // A readwrite login on a server whose plugin route fell back to
    // admin-only: loginStatus says writable, every POST is 403, and before
    // this the lamp read "reaching boat" while a STOP tap did nothing.
    await renderApp({
      fetchLoginStatus: async () => ({
        status: 'loggedIn',
        authenticationRequired: true,
        userLevel: 'readwrite',
      }),
      postIntent: async () => {
        throw new IntentPostError(403);
      },
    });
    await waitFor(() =>
      expect(screen.getByText('BLOCKED — log in')).toBeInTheDocument(),
    );
  });

  it('says so when the plugin is not running (503 to every intent)', async () => {
    await renderApp({
      fetchLoginStatus: async () => ({ authenticationRequired: false }),
      postIntent: async () => {
        throw new IntentPostError(503);
      },
    });
    await waitFor(() =>
      expect(screen.getByText('BLOCKED — plugin not running')).toBeInTheDocument(),
    );
  });

  it('says so when intents are not reaching the server at all', async () => {
    await renderApp({
      fetchLoginStatus: async () => ({ authenticationRequired: false }),
      postIntent: async () => {
        throw new TypeError('Failed to fetch');
      },
    });
    await waitFor(() =>
      expect(screen.getByText('NOT REACHING BOAT')).toBeInTheDocument(),
    );
  });

  it('reports commands reaching the boat on an open/unsecured server', async () => {
    let resolveLogin!: (v: unknown) => void;
    const login = new Promise<unknown>((r) => {
      resolveLogin = r;
    });
    await renderApp({ fetchLoginStatus: () => login });
    await act(async () => {
      resolveLogin({ status: 'notLoggedIn', authenticationRequired: false });
      await login;
    });
    expect(screen.getByText('reaching boat')).toBeInTheDocument();
  });

  it('shows "controlled by TX remote" on a drive when armed and TX owns it', async () => {
    await renderApp();
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());

    harness.sendDelta([
      { path: 'control.remoteController.rx.port.source', value: 'tx' },
      { path: 'control.remoteController.rx.port.state', value: 'forward' },
    ]);

    const port = screen.getByLabelText('Port drive control');
    await waitFor(() =>
      expect(within(port).getByText(/controlled by TX remote/)).toBeInTheDocument(),
    );
  });

  it('shows IN USE when another device holds the arm token', async () => {
    await renderApp();
    // A different device arms first.
    harness.injectIntent({ clientId: 'other-device', armReq: 0, disarmReq: 0 });
    harness.injectIntent({ clientId: 'other-device', armReq: 1, disarmReq: 0 });

    await waitFor(() => {
      expect(screen.getByText('IN USE')).toBeInTheDocument();
      expect(
        screen.getByText('another device'),
      ).toBeInTheDocument();
    });
    // This instance is NOT armed while another holds it.
    expect(screen.queryByText('ARMED')).not.toBeInTheDocument();
  });

  it('two-tap takeover: disarm (stop the other) then arm (take control)', async () => {
    await renderApp();
    harness.injectIntent({ clientId: 'other-device', armReq: 0, disarmReq: 0 });
    harness.injectIntent({ clientId: 'other-device', armReq: 1, disarmReq: 0 });
    await waitFor(() => expect(screen.getByText('IN USE')).toBeInTheDocument());

    // Tap 1 -> disarm (global stop): the button reads as a STOP while foreign.
    fireEvent.click(screen.getByText('IN USE'));
    await waitFor(() => expect(screen.getByText('DISARMED')).toBeInTheDocument());
    expect(harness.state().enabled).toBe(false);

    // Tap 2 -> arm: we take control. Deliberately a beat later: a tap within
    // KILL_SWITCH_STOP_HOLDOVER_MS of the button meaning STOP is still a STOP
    // (see 'a tap decided as STOP stays a STOP' below).
    await act(async () => {
      await new Promise((r) => setTimeout(r, KILL_SWITCH_STOP_HOLDOVER_MS));
    });
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
    expect(harness.state().activeClient).not.toBe('other-device');
  });
});

// The failure these guard against, at the level an operator experiences it:
// a unit switched off, the phone still all green and still armable. Nothing in
// the arming path would know the unit existed, and rx.linkUp -- the one reading
// meant to represent link health -- is a value published BY the unit, so it
// stays `true` after the board loses power.
//
// stopRx()/stopHh() model exactly that: the deltas simply stop. Nothing
// announces it; the absence IS the signal.
//
// The rule these tests pin has one deliberate nuance: ONE arm covers both
// machines, and it is granted while EITHER unit is reachable, because
// refusing gear control just because the thruster board is off would remove
// the primary docking control at the worst moment. So "cannot arm" requires
// BOTH units to be gone, while a single missing unit must still be stated
// plainly rather than hidden.
describe('App when a unit is switched off', () => {
  it('refuses to arm once NEITHER unit is responding, and says why', async () => {
    await renderApp();
    // Healthy to begin with, so the assertion below is about losing the
    // units, not about a UI that never worked.
    expect(screen.getByText('tap to arm remote control')).toBeInTheDocument();

    harness.stopRx();
    harness.stopHh();

    await waitFor(
      () =>
        expect(screen.getByText(/no unit responding/i)).toBeInTheDocument(),
      { timeout: 4000 },
    );
    const btn = screen.getByText('DISARMED').closest('button');
    expect(btn).toBeDisabled();

    fireEvent.click(btn!);
    // Nothing armed, on either side of the wire. The liveness poll re-renders
    // four times a second, so the wait is act-wrapped like every other wait in
    // this block -- unwrapped, those renders land outside act() and the run
    // warns about something that has nothing to do with what is asserted.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 400));
    });
    expect(screen.queryByText('ARMED')).not.toBeInTheDocument();
    expect(harness.state().enabled).toBe(false);
  });

  it('still arms with only the thruster unit alive, and names the missing one', async () => {
    await renderApp();
    harness.stopRx();

    await waitFor(
      () =>
        expect(
          screen.getByText(/drive unit not responding/i),
        ).toBeInTheDocument(),
      { timeout: 4000 },
    );
    const btn = screen.getByText('DISARMED').closest('button');
    expect(btn).not.toBeDisabled();
    fireEvent.click(btn!);
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
  });

  it('greys out the drive controls but not the thruster when RX goes offline mid-session', async () => {
    // The two machines gate independently: losing the RX (drive) board must
    // disable the gear controls while the still-live thruster board keeps its
    // own controls commandable -- the whole point of per-machine gating.
    await renderApp();
    await arm();
    const port = screen.getByLabelText('Port drive control');
    const portFwd = within(port).getByLabelText('Port forward');
    expect(portFwd).not.toBeDisabled();
    expect(screen.getByLabelText('Thrust bow to port')).not.toBeDisabled();

    harness.stopRx();

    await waitFor(() => expect(portFwd).toBeDisabled(), { timeout: 4000 });
    // HH is untouched, so the bow-thruster controls stay live.
    expect(screen.getByLabelText('Thrust bow to port')).not.toBeDisabled();
  });

  it('stops showing the RX rows as healthy instead of freezing them green', async () => {
    await renderApp();
    await waitFor(() => expect(screen.getByText('up')).toBeInTheDocument());

    harness.stopRx();

    await waitFor(
      () => expect(screen.getByText(/NOT RESPONDING/)).toBeInTheDocument(),
      { timeout: 4000 },
    );
    // The stale `linkUp: true` must no longer be presented as a live reading.
    expect(screen.queryByText('up')).not.toBeInTheDocument();
  });

  it('reports the two units independently -- one dying does not blank the other', async () => {
    await renderApp();
    harness.stopHh();

    await waitFor(
      () =>
        expect(
          screen.getByText(/thruster unit not responding/i),
        ).toBeInTheDocument(),
      { timeout: 4000 },
    );
    // RX is untouched: its link reading is still live and green.
    expect(screen.getByText('up')).toBeInTheDocument();
  });

  it('recovers on its own when the units come back', async () => {
    await renderApp();
    harness.stopRx();
    harness.stopHh();
    await waitFor(
      () => expect(screen.getByText(/no unit responding/i)).toBeInTheDocument(),
      { timeout: 4000 },
    );

    harness.startRx();
    harness.startHh();
    await waitFor(() =>
      expect(screen.getByText('tap to arm remote control')).toBeInTheDocument(),
    );
    // And arming works again -- no page reload needed.
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
  });
});

// The heading label is HH's statement, not this app's. Being armed with HOLD
// selected says only that the hold was REQUESTED: HH may be faulted, its
// heading may not be good yet, or its own ENGAGE input may have taken the
// thruster -- and hh.setpointDeg reads as a live number in all of those,
// because HH mirrors it to the fused heading whenever it is not holding
// (ARCHITECTURE.md §9). So "holding" may only come from hh.armed + hh.mode.
describe('App: "holding" comes from the HH unit, never from being armed', () => {
  async function armIntoHold() {
    await renderApp();
    await arm();
    fireEvent.click(screen.getByText('HOLD'));
  }

  it('does not claim a hold while HH reports it is not holding', async () => {
    await armIntoHold();
    harness.sendDelta([
      { path: 'control.remoteController.hh.armed', value: false },
      { path: 'control.remoteController.hh.mode', value: 'hold' },
      { path: 'control.remoteController.hh.setpointDeg', value: 40 },
    ]);

    await waitFor(() =>
      expect(screen.getByText('hold requested')).toBeInTheDocument(),
    );
    expect(screen.queryByText('holding')).not.toBeInTheDocument();
    // ...and no alarm, because this is the state every arm passes through and
    // the request has had no time to fail yet. The window that separates a
    // transition from a fault is pure/holdPhase.ts, exercised there and in
    // hooks/useHoldPhase.test.tsx rather than by making this test wait 2 s.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // The heading itself is still shown -- the INDICATION degrades, the data
    // does not disappear.
    expect(screen.getByText('040°')).toBeInTheDocument();
  });

  /**
   * ARMED_IDLE publishes hh.armed exactly as HOLDING does: HH asserts ENABLE in
   * both. Without its FSM state a hold that never started -- no trustworthy
   * heading -- reads as a running one, which is the false confidence SAFETY.md
   * rule 6 exists to prevent.
   */
  it('does not claim a hold when HH is armed but idle', async () => {
    await armIntoHold();
    harness.sendDelta([
      { path: 'control.remoteController.hh.armed', value: true },
      { path: 'control.remoteController.hh.mode', value: 'hold' },
      { path: 'sensors.headingHold.fsmState', value: 'ARMED_IDLE' },
      { path: 'control.remoteController.hh.setpointDeg', value: 40 },
    ]);

    await waitFor(() =>
      expect(screen.getByText('hold requested')).toBeInTheDocument(),
    );
    expect(screen.queryByText('holding')).not.toBeInTheDocument();
  });

  it('says holding once HH reports armed and in HOLD', async () => {
    await armIntoHold();
    harness.sendDelta([
      { path: 'control.remoteController.hh.armed', value: true },
      { path: 'control.remoteController.hh.mode', value: 'hold' },
      { path: 'control.remoteController.hh.setpointDeg', value: 40 },
    ]);

    await waitFor(() => expect(screen.getByText('holding')).toBeInTheDocument());
    expect(screen.getByText(/trim with the arrows/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /** And with HH reporting its FSM in HOLDING, which is the whole test. */
  it('says holding when HH reports its own FSM holding', async () => {
    await armIntoHold();
    harness.sendDelta([
      { path: 'control.remoteController.hh.armed', value: true },
      { path: 'control.remoteController.hh.mode', value: 'hold' },
      { path: 'sensors.headingHold.fsmState', value: 'HOLDING' },
      { path: 'control.remoteController.hh.setpointDeg', value: 40 },
    ]);

    await waitFor(() => expect(screen.getByText('holding')).toBeInTheDocument());
  });
});

describe('App: the intent heartbeat', () => {
  it('never stacks up POSTs -- a heartbeat waits for the one in flight', async () => {
    // Every 250 ms tick used to fire a fetch regardless, so a server answering
    // slowly (or not at all, until the 2 s abort) put up to eight requests on
    // the wire -- past the browser's per-host connection limit, with an urgent
    // STOP queued behind heartbeats whose content was already obsolete.
    let calls = 0;
    await renderApp({
      postIntent: () => {
        calls += 1;
        return new Promise<void>(() => {}); // never resolves
      },
    });
    expect(calls).toBe(1); // the mount send, still in flight

    await act(async () => {
      await new Promise((r) => setTimeout(r, PERIODIC_REFRESH_MS * 5));
    });
    expect(calls).toBe(1); // ...and every heartbeat since has coalesced into it

    // A body change is an operator action, not a heartbeat: it goes out at
    // once, whatever is still unanswered.
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(calls).toBe(2));
  });

  it('keeps waiting while an OLDER request is still unanswered', async () => {
    // Remembering only the newest request would let an operator action that
    // settles quickly clear the marker while the mount request still hangs --
    // and the heartbeat would then rebuild the very backlog it exists to
    // prevent. Every unsettled request counts, not just the last one sent.
    let calls = 0;
    await renderApp({
      postIntent: () => {
        calls += 1;
        return calls === 1
          ? new Promise<void>(() => {}) // the mount send never settles...
          : Promise.resolve(); // ...every later one settles at once
      },
    });
    expect(calls).toBe(1);

    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(calls).toBe(2)); // the operator action, settled

    await act(async () => {
      await new Promise((r) => setTimeout(r, PERIODIC_REFRESH_MS * 5));
    });
    expect(calls).toBe(2); // no heartbeat: the mount request is still pending
  });
});

// A tap's meaning is decided by the operator when they reach for the button,
// but the click lands on whatever the button shows by then. The arbiter's
// answer to a STOP comes back within milliseconds, so the second half of a
// double-tapped STOP -- or a second person's STOP decided against IN USE just
// before the holder disarmed -- used to land on DISARMED and ARM this station.
// In HOLD that engages a hold on the boat.
describe('App: a tap decided as STOP stays a STOP', () => {
  it('a double-tapped STOP does not re-arm; a tap after the hold-over arms normally', async () => {
    await renderApp();
    await arm();

    fireEvent.click(screen.getByText('ARMED')); // tap 1: STOP
    await waitFor(() => expect(screen.getByText('DISARMED')).toBeInTheDocument());
    fireEvent.click(screen.getByText('DISARMED')); // tap 2, a moment later
    await act(async () => {
      await new Promise((r) => setTimeout(r, 400));
    });
    expect(harness.state().enabled).toBe(false);
    expect(harness.state().activeClient).toBe('');
    expect(screen.queryByText('ARMED')).not.toBeInTheDocument();

    // A deliberate arm, once the hold-over has run out, still works.
    await act(async () => {
      await new Promise((r) => setTimeout(r, KILL_SWITCH_STOP_HOLDOVER_MS));
    });
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
    expect(harness.state().enabled).toBe(true);
  });

  it('a STOP decided against IN USE stays a STOP when the holder disarms first', async () => {
    await renderApp();
    harness.injectIntent({ clientId: 'other-device', armReq: 0, disarmReq: 0 });
    harness.injectIntent({ clientId: 'other-device', armReq: 1, disarmReq: 0 });
    await waitFor(() => expect(screen.getByText('IN USE')).toBeInTheDocument());

    // The holder disarms between this operator's decision and their click.
    harness.injectIntent({ clientId: 'other-device', armReq: 1, disarmReq: 1 });
    await waitFor(() => expect(screen.getByText('DISARMED')).toBeInTheDocument());
    fireEvent.click(screen.getByText('DISARMED'));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 400));
    });
    expect(harness.state().enabled).toBe(false);
    expect(harness.state().activeClient).toBe('');
  });
});

// Signal K keeps the last activeClient forever and the SK client keeps its
// copy across drops, so neither the socket's state nor that value can say the
// server is still there. A half-open socket (no FIN) stays 'open' indefinitely.
// The plugin republishes activeClient every 250 ms; its ARRIVAL is the proof.
describe('App: the server stream going silent', () => {
  it('reads a silent-but-open socket as OFFLINE, and the tap still stops', async () => {
    await renderApp();
    await arm();
    const portFwd = within(screen.getByLabelText('Port drive control')).getByLabelText(
      'Port forward',
    );
    expect(portFwd).not.toBeDisabled();

    harness.stallConnections();

    await waitFor(() => expect(screen.getByText('OFFLINE')).toBeInTheDocument(), {
      timeout: 4000,
    });
    // Nothing on the socket itself ever said so.
    expect(client.getSnapshot().connectionState).toBe('open');
    expect(screen.queryByText('ARMED')).not.toBeInTheDocument();
    expect(portFwd).toBeDisabled();
    expect(screen.getByRole('status', { name: 'Link: no data from server' })).toHaveClass(
      'lamp--bad',
    );
    expect(screen.getByRole('status', { name: /^Control:/ })).toHaveClass('lamp--stale');

    // The arbiter still hears our intents over HTTP -- we are still armed
    // there -- and the OFFLINE tap is the stop.
    expect(harness.state().enabled).toBe(true);
    fireEvent.click(screen.getByText('OFFLINE'));
    await waitFor(() => expect(harness.state().enabled).toBe(false));
  });
});

// A heading or a reversal note from a unit that has stopped answering is a
// frozen reading, and "current heading" under it presents it as live.
describe('App: HH readings follow HH liveness', () => {
  it('stops showing the held heading once HH stops answering', async () => {
    await renderApp();
    fireEvent.click(screen.getByText('HOLD'));
    harness.sendDelta([{ path: 'control.remoteController.hh.setpointDeg', value: 40 }]);
    await waitFor(() => expect(screen.getByText('040°')).toBeInTheDocument());

    harness.stopHh();
    await waitFor(
      () => expect(screen.getByText(/thruster unit not responding/i)).toBeInTheDocument(),
      { timeout: 4000 },
    );
    expect(screen.queryByText('040°')).not.toBeInTheDocument();
    expect(screen.getByText('---°')).toBeInTheDocument();
  });

  it('drops a reversal-pending note once HH stops answering', async () => {
    await renderApp();
    harness.sendDelta([
      { path: 'control.remoteController.hh.reversalPending', value: true },
    ]);
    await waitFor(() =>
      expect(screen.getByText(/reversing — waiting for the thruster/)).toBeInTheDocument(),
    );

    harness.stopHh();
    await waitFor(
      () => expect(screen.getByText(/thruster unit not responding/i)).toBeInTheDocument(),
      { timeout: 4000 },
    );
    expect(
      screen.queryByText(/reversing — waiting for the thruster/),
    ).not.toBeInTheDocument();
  });
});
