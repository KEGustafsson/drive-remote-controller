// StatusPanel greys its derived lamps out whenever the socket isn't open, or
// whenever the unit that publishes a reading has stopped answering. Everything
// below the Link lamp is computed from the last Signal K snapshot (skClient
// never clears `values` on disconnect) and from a writeStatus that isn't
// re-checked until reconnect, so painting them live green/amber while
// "connecting…" would vouch for readings that aren't live. These tests pin
// that behaviour directly, since the App-level tests always run against an
// already-open connection.
//
// The panel renders traffic-light lamps rather than the original label/value
// rows; the guarantees asserted here are unchanged from that version.

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusPanel } from './StatusPanel';
import {
  SK_HH_MODE_PATH,
  SK_HH_SOURCE_PATH,
  SK_HH_THRUSTER_STATE_PATH,
  SK_RX_LINK_UP_PATH,
  SK_RX_MASTER_ENABLE_PATH,
  SK_RX_PORT_SOURCE_PATH,
  SK_RX_PORT_STATE_PATH,
  SK_RX_STBD_SOURCE_PATH,
  SK_RX_STBD_STATE_PATH,
} from '../config';
import type { ConnectionState } from '../skClient';
import type { RxLiveness } from '../pure/rxLiveness';

// A snapshot that would render every derived lamp in a confident (non-stale)
// colour if it were live: link up, master enable ON, both drives at a known
// position, thruster holding.
const LIVE_LOOKING_VALUES = {
  [SK_RX_LINK_UP_PATH]: true,
  [SK_RX_MASTER_ENABLE_PATH]: true,
  [SK_RX_PORT_STATE_PATH]: 'neutral',
  [SK_RX_STBD_STATE_PATH]: 'neutral',
  [SK_RX_PORT_SOURCE_PATH]: 'plugin',
  [SK_RX_STBD_SOURCE_PATH]: 'plugin',
  [SK_HH_THRUSTER_STATE_PATH]: 'OFF',
  [SK_HH_MODE_PATH]: 'hold',
  [SK_HH_SOURCE_PATH]: 'plugin',
};

function renderPanel(
  connectionState: ConnectionState,
  values: Record<string, unknown> = LIVE_LOOKING_VALUES,
  rxLiveness: RxLiveness = 'live',
  hhLiveness: RxLiveness = 'live',
) {
  return render(
    <StatusPanel
      connectionState={connectionState}
      writeStatus="writable"
      controlState="none"
      rxLiveness={rxLiveness}
      hhLiveness={hhLiveness}
      values={values}
    />,
  );
}

// title -> its lamp. Each Lamp is
// <div class="lamp lamp--X"><span light/><span title/><span value/></div>.
function lampFor(title: string): HTMLElement {
  const el = screen.getByText(title, { selector: '.lamp__title' });
  const lamp = el.closest('.lamp');
  if (!(lamp instanceof HTMLElement)) throw new Error(`no lamp for ${title}`);
  return lamp;
}

const DERIVED_LAMPS = [
  'Commands',
  'Control',
  'RX link',
  'RX enable',
  'Port',
  'Starboard',
  'Thruster',
];

describe('StatusPanel liveness', () => {
  it('paints derived lamps with their live status when the socket is open', () => {
    renderPanel('open');
    for (const title of DERIVED_LAMPS) {
      expect(lampFor(title).className).not.toContain('lamp--stale');
    }
    expect(lampFor('Commands').className).toContain('lamp--good');
    expect(screen.getByText('reaching boat')).toBeInTheDocument();
    expect(lampFor('Link').className).toContain('lamp--good');
  });

  it('greys every derived lamp out while connecting', () => {
    renderPanel('connecting', LIVE_LOOKING_VALUES, 'offline', 'offline');
    for (const title of DERIVED_LAMPS) {
      const cls = lampFor(title).className;
      expect(cls).toContain('lamp--stale');
      // No live verdict leaks through -- not green, not amber, not red.
      expect(cls).not.toMatch(/lamp--(good|warn|bad)\b/);
    }
    // Last-known text is retained (greyed, not erased) so it stays informative.
    expect(screen.getByText('reaching boat')).toBeInTheDocument();
    // The Link lamp keeps its OWN (bad) status -- it is the live signal.
    expect(lampFor('Link').className).toContain('lamp--bad');
    expect(screen.getByText('connecting…')).toBeInTheDocument();
  });

  it('greys derived lamps out while disconnected/reconnecting too', () => {
    renderPanel('closed', LIVE_LOOKING_VALUES, 'offline', 'offline');
    for (const title of DERIVED_LAMPS) {
      expect(lampFor(title).className).toContain('lamp--stale');
    }
  });

  it('does not raise the "another device" alert from stale data while offline', () => {
    render(
      <StatusPanel
        connectionState="connecting"
        writeStatus="writable"
        controlState="other"
        rxLiveness="offline"
        hhLiveness="offline"
        values={LIVE_LOOKING_VALUES}
      />,
    );
    // The take-over alert must not fire on a last-known 'other' verdict when
    // we can't confirm it's still true.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(
      screen.queryByText(/Another device holds the arm/),
    ).not.toBeInTheDocument();
  });

  it('does raise the "another device" alert when live and someone else holds it', () => {
    render(
      <StatusPanel
        connectionState="open"
        writeStatus="writable"
        controlState="other"
        rxLiveness="live"
        hhLiveness="live"
        values={LIVE_LOOKING_VALUES}
      />,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});

// The panel's ten lamps were most of a phone screen, so the readings now sit
// behind a <details> that starts closed and the space goes to the drive
// buttons. Which lamps are OUTSIDE that toggle is the safety-relevant half of
// the change, and is pinned here.
//
// Note the other assertions in this file keep working while collapsed because
// jsdom does not implement <details> hiding -- the lamps stay queryable. That
// is fine: those tests pin how a reading is DERIVED, not whether it is on
// screen. This is the one that pins what is visible without a tap.
describe('StatusPanel detail disclosure', () => {
  it('starts collapsed', () => {
    const { container } = renderPanel('open');
    const details = container.querySelector('details.status-panel__details');
    expect(details).toBeInstanceOf(HTMLDetailsElement);
    expect((details as HTMLDetailsElement).open).toBe(false);
  });

  it('keeps Link, Commands and Control out of the collapsible part', () => {
    const { container } = renderPanel('open');
    const details = container.querySelector('details.status-panel__details');
    // Can this app reach the boat, may it command, and who is authoritative --
    // none of these may cost a tap to find out.
    for (const title of ['Link', 'Commands', 'Control']) {
      expect(details?.contains(lampFor(title))).toBe(false);
    }
  });

  it('puts the per-unit readings behind the toggle', () => {
    const { container } = renderPanel('open');
    const details = container.querySelector('details.status-panel__details');
    for (const title of [
      'Drive unit',
      'Thruster unit',
      'RX link',
      'RX enable',
      'Port',
      'Starboard',
      'Thruster',
    ]) {
      expect(details?.contains(lampFor(title))).toBe(true);
    }
  });

  // The reason no "unit not responding" line was added here: the kill switch
  // already carries one, it is never collapsed, and a second copy was caught
  // by two App-level tests as the duplicate it was.
  it('does not duplicate the kill switch’s "not responding" wording', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'stale', 'stale');
    expect(screen.queryByText(/not responding —/i)).not.toBeInTheDocument();
  });
});

// The "RX link" lamp is a pure link-health light: green whenever a remote
// source is LIVE at RX (rx.linkUp), armed or not. Armed state is carried by
// the ARM button / "Control" lamp.
describe('StatusPanel RX link lamp', () => {
  it('is green when the link is up, even while nothing is armed', () => {
    renderPanel('open', { ...LIVE_LOOKING_VALUES, [SK_RX_LINK_UP_PATH]: true });
    expect(lampFor('RX link').className).toContain('lamp--good');
    expect(screen.getByText('up')).toBeInTheDocument();
  });

  it('warns when no remote is seen at RX', () => {
    renderPanel('open', { ...LIVE_LOOKING_VALUES, [SK_RX_LINK_UP_PATH]: false });
    expect(lampFor('RX link').className).toContain('lamp--warn');
    expect(screen.getByText('no remote')).toBeInTheDocument();
  });
});

// The trap these tests exist for: rx.linkUp is published BY RX, Signal K
// retains a path's last value forever, and this client keeps its last-known
// copy -- so `linkUp: true` just stands there after RX loses power, and every
// lamp derived from RX would happily vouch for readings that had stopped
// arriving. LIVE_LOOKING_VALUES is exactly that trap: perfectly healthy-looking
// values with no live unit behind them.
describe('StatusPanel when RX has stopped publishing', () => {
  it('does NOT report the RX link as up from a frozen linkUp:true', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'stale');
    expect(lampFor('RX link').className).not.toContain('lamp--good');
    expect(lampFor('RX link').className).toContain('lamp--stale');
    expect(screen.queryByText('up')).not.toBeInTheDocument();
  });

  it('calls out the dead RX unit explicitly, in red', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'stale');
    expect(lampFor('Drive unit').className).toContain('lamp--bad');
    expect(
      screen.getByText('NOT RESPONDING', { selector: '.lamp__value' }),
    ).toBeInTheDocument();
  });

  it('greys out every RX-derived lamp, not just the link one', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'stale');
    for (const title of ['RX link', 'RX enable', 'Port', 'Starboard']) {
      expect(lampFor(title).className).toContain('lamp--stale');
    }
  });

  it('distinguishes an RX never seen at all from one that went away', () => {
    renderPanel('open', {}, 'never-seen');
    expect(screen.getByText(/NOT SEEN/)).toBeInTheDocument();
  });

  it('reports the RX unit as responding, in green, when it is live', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'live');
    expect(lampFor('Drive unit').className).toContain('lamp--good');
  });

  it('blames the socket, not the unit, while disconnected', () => {
    renderPanel('closed', LIVE_LOOKING_VALUES, 'offline', 'offline');
    expect(lampFor('Drive unit').className).toContain('lamp--stale');
    expect(screen.queryByText(/NOT RESPONDING/)).not.toBeInTheDocument();
  });
});

// The thruster unit gets exactly the same treatment as RX -- it must not end
// up with a more forgiving notion of "present", which would reintroduce the
// same false-confidence bug on the other machine.
describe('StatusPanel when the thruster unit has stopped publishing', () => {
  it('calls out the dead thruster unit in red, independently of RX', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'live', 'stale');
    expect(lampFor('Thruster unit').className).toContain('lamp--bad');
    // RX is unaffected: one unit going away must not blank the other.
    expect(lampFor('Drive unit').className).toContain('lamp--good');
    expect(lampFor('Port').className).toContain('lamp--good');
  });

  it('greys the thruster reading out rather than showing a frozen state', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'live', 'stale');
    expect(lampFor('Thruster').className).toContain('lamp--stale');
    expect(screen.getByText('no data')).toBeInTheDocument();
  });

  it('shows the thruster state, mode and controlling station when live', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'live', 'live');
    expect(lampFor('Thruster').className).toContain('lamp--good');
    expect(screen.getByText(/off · hold/)).toBeInTheDocument();
  });

  it('reports a dead RX without blanking a live thruster', () => {
    renderPanel('open', LIVE_LOOKING_VALUES, 'stale', 'live');
    expect(lampFor('Drive unit').className).toContain('lamp--bad');
    expect(lampFor('Thruster unit').className).toContain('lamp--good');
    expect(lampFor('Thruster').className).toContain('lamp--good');
  });
});
