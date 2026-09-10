// End-to-end scenario: a realistic full operator session driven through the
// REAL rendered App against a REAL in-process server running the REAL
// arbiter (arbiter.cjs) -- no mocked WebSocket, skClient, React hooks, or
// arming logic. This is the single file that answers "does the whole
// pipeline work together": connect -> arm (arbiter grants) -> simultaneous
// two-hand command -> a backgrounded phone fails safe -> a WiFi blip and
// reconnect (armed state survives) -> disarm. The atomic per-behavior
// assertions live in src/App.test.tsx, src/skClient.test.ts and
// test/arbiter.test.ts; this chains them into one continuous story.

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WebSocket as NodeWebSocket } from 'ws';
import { App } from '../src/App';
import { SkClientContext } from '../src/hooks/useSkConnection';
import { createSkClient, type SkClient } from '../src/skClient';
import { startArbiterServer, type ArbiterHarness } from './arbiterServer';

let harness: ArbiterHarness;
let client: SkClient;

function pointerDown(el: Element, pointerId: number) {
  fireEvent.pointerDown(el, { pointerId });
}
function pointerUp(el: Element, pointerId: number) {
  fireEvent.pointerUp(el, { pointerId });
}

beforeEach(async () => {
  harness = await startArbiterServer();
});

afterEach(async () => {
  client?.close();
  await harness.close();
});

describe('end-to-end: a full remote-control session', () => {
  it('connect, arm, two-handed maneuver, background-interrupt fail-safe, reconnect, disarm', async () => {
    // --- 1. Connect.
    client = createSkClient(harness.url, NodeWebSocket as unknown as typeof WebSocket);
    await waitFor(() =>
      expect(client.getSnapshot().connectionState).toBe('open'),
    );
    render(
      <SkClientContext.Provider value={client}>
        {/* Intents go to the REAL arbiter in-process (standing in for the
            plugin's HTTP route); its state comes back over the WS exactly as
            the plugin's activeClient/enabled publish would. */}
        <App
          postIntent={async (intent) => {
            harness.feedIntent(intent as unknown as Record<string, unknown>);
          }}
        />
      </SkClientContext.Provider>,
    );

    // --- 2. On mount: the safe default is disarmed, both neutral, and the
    // arbiter has nobody in control.
    expect(screen.getByText('DISARMED')).toBeInTheDocument();
    await waitFor(() => expect(harness.state().activeClient).toBe(''));

    // --- 3. Arm: the arbiter grants and echoes activeClient back.
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());
    expect(harness.state().enabled).toBe(true);

    // --- 4. The reason two independent buttons matter: a two-handed docking
    // maneuver, port forward while backing down on starboard -- two fingers,
    // two pointerIds, effectively simultaneous. The arbiter (this instance
    // being the holder) forwards both to the canonical command outputs.
    const port = screen.getByLabelText('Port drive control');
    const stbd = screen.getByLabelText('Starboard drive control');
    const portFwd = within(port).getByLabelText('Port forward');
    const stbdRev = within(stbd).getByLabelText('Starboard reverse');

    pointerDown(portFwd, 101);
    pointerDown(stbdRev, 202);
    expect(within(port).getByText('FORWARD')).toBeInTheDocument();
    expect(within(stbd).getByText('REVERSE')).toBeInTheDocument();
    await waitFor(() => {
      const s = harness.state();
      expect(s.port).toBe('forward');
      expect(s.stbd).toBe('reverse');
    });

    // --- 5. Mid-maneuver the phone gets backgrounded -- neither button gets
    // a clean pointerup. Both must release to NEUTRAL on their own (like
    // TX's spring-return switches), without the operator touching anything,
    // and WITHOUT disarming (a lost touch is not a decision to disarm).
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    fireEvent(document, new Event('visibilitychange'));
    expect(within(port).getByText('NEUTRAL')).toBeInTheDocument();
    expect(within(stbd).getByText('NEUTRAL')).toBeInTheDocument();
    await waitFor(() => {
      const s = harness.state();
      expect(s.port).toBe('neutral');
      expect(s.stbd).toBe('neutral');
      expect(s.enabled).toBe(true); // still armed
    });
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    fireEvent(document, new Event('visibilitychange'));

    // --- 6. A WiFi blip: the connection drops and comes back. Armed state
    // must survive a mere reconnect -- the arbiter kept this instance as the
    // holder (it never went stale), and on reconnect the server re-sends the
    // current state so the UI re-syncs to ARMED.
    await act(async () => {
      harness.dropConnections();
      await waitFor(() =>
        expect(client.getSnapshot().connectionState).not.toBe('open'),
      );
      await waitFor(() =>
        expect(client.getSnapshot().connectionState).toBe('open'),
      );
    });
    await waitFor(() => expect(screen.getByText('ARMED')).toBeInTheDocument());

    // Still fully functional on the new connection.
    pointerDown(portFwd, 303);
    await waitFor(() => expect(harness.state().port).toBe('forward'));
    pointerUp(portFwd, 303);
    await waitFor(() => expect(harness.state().port).toBe('neutral'));

    // --- 7. End of session: disarm. The arbiter releases the token.
    fireEvent.click(screen.getByText('ARMED'));
    await waitFor(() => expect(screen.getByText('DISARMED')).toBeInTheDocument());
    expect(harness.state().enabled).toBe(false);
    expect(harness.state().activeClient).toBe('');
  });
});

describe('end-to-end: choosing the thruster mode before arming', () => {
  it('arms into the mode selected while disarmed, and commands nothing until then', async () => {
    // The operator decides which gate the arm opens BEFORE arming -- that is
    // the whole point of the chooser being live while disarmed. Proven against
    // the real arbiter, and proven both ways round: the same sequence has to
    // arm into MANUAL when MANUAL is showing, or 'hold' would just be the
    // fail-safe default being read back rather than the selection carrying.
    client = createSkClient(harness.url, NodeWebSocket as unknown as typeof WebSocket);
    await waitFor(() =>
      expect(client.getSnapshot().connectionState).toBe('open'),
    );
    render(
      <SkClientContext.Provider value={client}>
        <App
          postIntent={async (intent) => {
            harness.feedIntent(intent as unknown as Record<string, unknown>);
          }}
        />
      </SkClientContext.Provider>,
    );

    // Opens on MANUAL: arming from there opens the manual gate.
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => {
      const s = harness.state();
      expect(s.enabled).toBe(true);
      expect(s.thrusterMode).toBe('manual');
    });

    // Back to disarmed, and now pick HOLD with nothing armed.
    fireEvent.click(screen.getByText('ARMED'));
    await waitFor(() => expect(harness.state().enabled).toBe(false));
    const hold = screen.getByText('HOLD');
    expect(hold).not.toBeDisabled();
    fireEvent.click(hold);

    // Selecting is not commanding: still disarmed, still nothing at the
    // thruster -- and the panel says arming is what will engage the hold.
    expect(
      screen.getByText(/HOLD selected — starts holding when you arm/),
    ).toBeInTheDocument();
    await waitFor(() => {
      const s = harness.state();
      expect(s.enabled).toBe(false);
      expect(s.thruster).toBe('off');
    });

    // Arm: HOLD is the gate that opens, off the selection made while
    // disarmed, with no thruster press anywhere in the sequence.
    fireEvent.click(screen.getByText('DISARMED'));
    await waitFor(() => {
      const s = harness.state();
      expect(s.enabled).toBe(true);
      expect(s.thrusterMode).toBe('hold');
      expect(s.thruster).toBe('off');
      expect(s.trimDeg).toBe(0);
    });
  });
});
