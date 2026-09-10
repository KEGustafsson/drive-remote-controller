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
    await new Promise((r) => setTimeout(r, 600));

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
    await new Promise((r) => setTimeout(r, 300));
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

    // Tap 2 -> arm: we take control.
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
    // Nothing armed, on either side of the wire.
    await new Promise((r) => setTimeout(r, 400));
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
