// Exhaustive host tests for the pure arming arbiter -- the server-side
// safety core (arbiter.cjs). No Signal K, no sockets: this is the direct
// analogue of the firmware's test_arbitration, and like it, every safety
// property is pinned by an explicit vector here rather than left to the
// integration tests to catch.

import { beforeEach, describe, expect, it } from 'vitest';
// @ts-expect-error -- plain CommonJS module, no .d.ts (pure JS by design,
// mirroring how index.cjs itself is untyped). Shape is exercised below.
import { ArmArbiter, MAX_TRIM_DEG, MAX_TRACKED_CLIENTS } from '../arbiter.cjs';

// Convenience: build a client intent object. seq auto-increments per call
// (unless a test overrides it) because the arbiter now ENFORCES per-client
// seq ordering -- each intent() is "a distinct send", exactly like the real
// UI's seqRef. The counter also absorbs any explicit seq a test passes, so
// a later default-seq intent always continues ABOVE it (mirroring a real
// client whose counter only moves forward). Tests that exercise the
// ordering gate itself pass seq explicitly.
let autoSeq = 0;
// Every test gets a fresh arbiter, so per-client seq baselines reset with it;
// the helper counter must reset too or a large explicit seq in one test would
// push later tests' small explicit seqs below the default counter.
beforeEach(() => {
  autoSeq = 0;
});
function intent(clientId: string, fields: Record<string, unknown> = {}) {
  const seq = typeof fields.seq === 'number' ? fields.seq : autoSeq + 1;
  autoSeq = Math.max(autoSeq, seq);
  return {
    clientId,
    armReq: 0,
    disarmReq: 0,
    port: 'neutral',
    stbd: 'neutral',
    ...fields,
    seq,
  };
}

// Every suite except "RX must be present to arm" is about arbitration BETWEEN
// CLIENTS, and arming now additionally requires a live RX (see arbiter.cjs).
// So these build an arbiter with BOTH units present and never timing out,
// isolating the property under test. Note each unit must still be announced
// once: an arbiter that has never seen a unit refuses to arm, which is itself
// pinned below. Announcing HH too is not cosmetic -- the arbiter neutralises
// commands aimed at a unit it cannot see, so a suite about arbitration
// BETWEEN CLIENTS would otherwise be measuring the absent-unit gate instead.
function makeArbiter(opts: Record<string, unknown> = {}) {
  const a = new ArmArbiter({
    rxStaleTimeoutMs: Number.POSITIVE_INFINITY,
    ...opts,
  });
  a.onRxTelemetry(0);
  a.onHhTelemetry(0);
  return a;
}

describe('ArmArbiter: fail-safe defaults', () => {
  it('starts disarmed, neutral, with no active client and neither unit seen', () => {
    const a = new ArmArbiter();
    expect(a.state()).toEqual({
      enabled: false,
      port: 'neutral',
      stbd: 'neutral',
      // The thruster fails to OFF exactly as the drives fail to NEUTRAL, and
      // the trim is 0 (hold whatever HH captures) -- a relative offset needs no
      // sentinel, 0 is the safe rest.
      thruster: 'off',
      thrusterMode: 'hold',
      trimDeg: 0,
      activeClient: '',
      rxLive: false,
      hhLive: false,
    });
  });

  it('a client merely appearing (baseline armReq) does NOT arm', () => {
    const a = makeArbiter();
    // A fresh tab mounts with armReq already at some value -- appearing must
    // never count as a press.
    a.onIntent(intent('A', { armReq: 5, disarmReq: 2 }), 0);
    expect(a.state().enabled).toBe(false);
    expect(a.state().activeClient).toBe('');
  });
});

describe('ArmArbiter: exclusive arm', () => {
  it('a fresh arm edge from the only client grants the token', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0); // baseline
    const changed = a.onIntent(intent('A', { armReq: 1 }), 100); // rising edge
    expect(changed).toBe(true);
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A' });
  });

  it('a second client cannot arm while another holds the token', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10); // A holds
    a.onIntent(intent('B', { armReq: 0 }), 20); // B baseline
    a.onIntent(intent('B', { armReq: 1 }), 30); // B tries to arm
    expect(a.state().activeClient).toBe('A'); // denied, A keeps it
  });

  it('two arm edges in the same instant resolve to exactly one holder (first wins)', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('B', { armReq: 0 }), 0);
    // Processed one after another by the single-threaded arbiter.
    a.onIntent(intent('A', { armReq: 1 }), 5);
    a.onIntent(intent('B', { armReq: 1 }), 5);
    expect(a.state().activeClient).toBe('A');
    expect(a.state().enabled).toBe(true);
  });
});

describe('ArmArbiter: universal disarm', () => {
  it('honours STOP even when it is the new client’s first accepted packet', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1, port: 'forward' }), 10);
    expect(a.state().enabled).toBe(true);

    // B's mount heartbeat may have been lost or may arrive after this packet.
    // Universal STOP must not depend on having observed a baseline first.
    const changed = a.onIntent(intent('B', { seq: 2, disarmReq: 1 }), 20);
    expect(changed).toBe(true);
    expect(a.state()).toMatchObject({
      enabled: false,
      activeClient: '',
      port: 'neutral',
      stbd: 'neutral',
      thruster: 'off',
    });

    // A delayed mount heartbeat is stale and cannot undo the stop.
    a.onIntent(intent('B', { seq: 1, disarmReq: 0 }), 30);
    expect(a.state().enabled).toBe(false);
  });

  // The counterpart to the test above. `disarmReq` is cumulative for the life
  // of a station's session, so "first packet we have seen" and "first packet
  // it has sent" are different things. Treating them as the same made every
  // return-from-eviction fire a phantom STOP -- and since backgrounding stops
  // the Android heartbeat, a phone that had pressed STOP once would kill
  // another station's arm every time the operator glanced at another app.
  it('does not fire a phantom STOP when an evicted station re-registers unchanged', () => {
    const a = makeArbiter();
    // B is a bystander station that pressed STOP earlier in its session.
    a.onIntent(intent('B', { seq: 1, disarmReq: 0 }), 0);
    a.onIntent(intent('B', { seq: 2, disarmReq: 1 }), 10);
    // A then takes the token and is manoeuvring.
    a.onIntent(intent('A', { seq: 3, armReq: 0 }), 20);
    a.onIntent(intent('A', { seq: 4, armReq: 1 }), 30);
    expect(a.state().enabled).toBe(true);

    // B backgrounds: its heartbeat stops and the arbiter evicts it. A keeps
    // its own heartbeat going and keeps the token.
    a.onIntent(intent('A', { seq: 5, armReq: 1 }), 1200);
    a.tick(1200);
    expect(a.state().enabled).toBe(true);

    // B resumes. Same clientId, same cumulative total, nobody touched STOP.
    a.onIntent(intent('B', { seq: 6, disarmReq: 1 }), 1210);
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A' });
  });

  it('still honours a STOP the station pressed while it was away', () => {
    const a = makeArbiter();
    a.onIntent(intent('B', { seq: 1, disarmReq: 0 }), 0);
    a.onIntent(intent('B', { seq: 2, disarmReq: 1 }), 10);
    a.onIntent(intent('A', { seq: 3, armReq: 0 }), 20);
    a.onIntent(intent('A', { seq: 4, armReq: 1 }), 30);
    a.onIntent(intent('A', { seq: 5, armReq: 1 }), 1200);
    a.tick(1200);
    expect(a.state().enabled).toBe(true);

    // The counter ADVANCED while B was gone -- a real press, on a station
    // whose POST could not get through at the time. It must still land.
    a.onIntent(intent('B', { seq: 6, disarmReq: 2 }), 1210);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });
  });

  // The phone persists clientId but keeps disarmReq in the ViewModel, so a
  // process restart reuses the id with a counter that starts again at 0.
  // Scored against the dead session's remembered total, every STOP the
  // restarted station sends until it climbs back past that total is silently
  // swallowed -- and a swallowed STOP is the one failure mode this project
  // has no answer for. A counter that moves BACKWARD means "restarted".
  it('honours a STOP from a station whose counter restarted', () => {
    const a = makeArbiter();
    // B runs a session in which it presses STOP three times.
    a.onIntent(intent('B', { seq: 1, disarmReq: 0 }), 0);
    a.onIntent(intent('B', { seq: 2, disarmReq: 3 }), 10);
    // A takes the token and is manoeuvring.
    a.onIntent(intent('A', { seq: 3, armReq: 0 }), 20);
    a.onIntent(intent('A', { seq: 4, armReq: 1, port: 'forward' }), 30);
    a.onIntent(intent('A', { seq: 5, armReq: 1, port: 'forward' }), 1200);
    a.tick(1200); // B is evicted; its counter (3) is remembered.
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });

    // B's app is killed and reopened: same persisted clientId, fresh counter.
    // The operator taps STOP, and because an urgent send skips the intent lane
    // it can be the FIRST packet of the new session to reach the arbiter --
    // disarmReq 1, below the remembered 3.
    a.onIntent(intent('B', { seq: 6, disarmReq: 1 }), 1210);
    expect(a.state()).toMatchObject({
      enabled: false,
      activeClient: '',
      port: 'neutral',
    });

    // ...and the reset is banked, so the new session's unchanged heartbeat is
    // not a second edge. Without that the restart would ratchet exactly as the
    // capacity path once did, disarming A again every 250 ms.
    a.onIntent(intent('A', { seq: 7, armReq: 2 }), 1220);
    expect(a.state().enabled).toBe(true);
    a.onIntent(intent('B', { seq: 8, disarmReq: 1 }), 1230);
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A' });
  });

  // Universal STOP outranks the client-table cap. Being unable to record a
  // new station is a bookkeeping limit; it must not become a way to make a
  // genuine stop a no-op while a machine is still under command.
  it('honours STOP from an untracked station when the client table is full', () => {
    const a = makeArbiter();
    a.onIntent(intent('holder', { seq: 1, armReq: 0 }), 0);
    a.onIntent(intent('holder', { seq: 2, armReq: 1, port: 'forward' }), 1);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });

    // Fill the table to capacity with other live stations.
    for (let i = 0; a.state().enabled && i < MAX_TRACKED_CLIENTS * 2; i += 1) {
      a.onIntent(intent(`filler-${i}`, { seq: 10 + i }), 2);
      if (a._clients.size >= MAX_TRACKED_CLIENTS) break;
    }
    expect(a._clients.size).toBe(MAX_TRACKED_CLIENTS);

    // A station that cannot be tracked at all still gets its STOP honoured.
    const changed = a.onIntent(intent('latecomer', { seq: 99, disarmReq: 1 }), 3);
    expect(changed).toBe(true);
    expect(a.state()).toMatchObject({
      enabled: false,
      activeClient: '',
      port: 'neutral',
      thruster: 'off',
    });
    // ...and is still not recorded, so the cap keeps doing its own job.
    expect(a._clients.has('latecomer')).toBe(false);
    expect(a._clients.size).toBe(MAX_TRACKED_CLIENTS);

    // The edge is spent exactly once. Consuming it without banking the
    // counter would make every later heartbeat from this untracked station
    // re-fire the same stop against a zero baseline -- a ratchet that keeps
    // anybody from re-arming for as long as the table stays full.
    a.onIntent(intent('holder', { seq: 100, armReq: 2 }), 4);
    expect(a.state().enabled).toBe(true);
    a.onIntent(intent('latecomer', { seq: 101, disarmReq: 1 }), 5);
    expect(a.state().enabled).toBe(true);

    // A genuinely new press from that same untracked station still lands.
    a.onIntent(intent('latecomer', { seq: 102, disarmReq: 2 }), 6);
    expect(a.state().enabled).toBe(false);
  });

  it("any client's disarm edge releases the holder (global kill)", () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, disarmReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10); // A holds
    a.onIntent(intent('B', { armReq: 0, disarmReq: 0 }), 20); // B baseline
    const changed = a.onIntent(intent('B', { disarmReq: 1 }), 30); // B disarms
    expect(changed).toBe(true);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });
  });

  it('the holder holding armReq across a disarm does NOT re-arm itself', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, disarmReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10); // A holds (armReq stays 1)
    a.onIntent(intent('B', { disarmReq: 0 }), 20); // baseline
    a.onIntent(intent('B', { disarmReq: 1 }), 30); // global disarm
    expect(a.state().enabled).toBe(false);
    // A keeps heart-beating armReq:1 (no NEW edge) -- must stay disarmed.
    a.onIntent(intent('A', { armReq: 1 }), 40);
    a.onIntent(intent('A', { armReq: 1 }), 50);
    expect(a.state().enabled).toBe(false);
    expect(a.state().activeClient).toBe('');
  });

  it('after a global disarm, a fresh arm edge re-arms normally', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, disarmReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10);
    a.onIntent(intent('A', { disarmReq: 1 }), 20); // A disarms itself
    expect(a.state().enabled).toBe(false);
    a.onIntent(intent('A', { armReq: 2, disarmReq: 1 }), 30); // fresh press
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A' });
  });
});

describe('ArmArbiter: two-tap handoff between devices', () => {
  it('B disarms (stops A) then arms (takes control) -- passes through disarmed', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, disarmReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10); // A holds
    a.onIntent(intent('B', { armReq: 0, disarmReq: 0 }), 20); // baseline

    // Tap 1: B disarms -> global stop.
    a.onIntent(intent('B', { disarmReq: 1 }), 30);
    expect(a.state().enabled).toBe(false);

    // Tap 2: B arms -> B now holds.
    a.onIntent(intent('B', { armReq: 1, disarmReq: 1 }), 40);
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'B' });

    // A, still heart-beating its old armReq:1, does not steal it back.
    a.onIntent(intent('A', { armReq: 1 }), 50);
    expect(a.state().activeClient).toBe('B');
  });
});

describe('ArmArbiter: liveness / fail to neutral', () => {
  it('a holder that stops heart-beating is evicted and the token auto-releases', () => {
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 100); // A holds
    // No further intents from A. Tick past the staleness window.
    const changed = a.tick(100 + 1001);
    expect(changed).toBe(true);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });
  });

  it('a holder that keeps heart-beating is NOT evicted', () => {
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 100);
    for (let t = 350; t <= 2000; t += 250) {
      a.onIntent(intent('A', { armReq: 1, seq: t }), t); // heartbeat
      a.tick(t);
    }
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A' });
  });

  it('a stale non-holder that had requested arm does NOT auto-arm when the holder leaves', () => {
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10); // A holds
    a.onIntent(intent('B', { armReq: 0 }), 20);
    a.onIntent(intent('B', { armReq: 1 }), 30); // B denied, locked out
    // A vanishes; only B remains, still holding its old armReq edge.
    a.onIntent(intent('B', { armReq: 1, seq: 500 }), 500);
    a.tick(10 + 1001); // evict A
    expect(a.state().enabled).toBe(false); // B does NOT auto-take control
    expect(a.state().activeClient).toBe('');
  });
});

describe('ArmArbiter: command forwarding', () => {
  it('only the holder\'s port/stbd reach the canonical command outputs', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1, port: 'forward', stbd: 'reverse' }), 10);
    // A non-holder pressing buttons must not command anything.
    a.onIntent(intent('B', { port: 'forward', stbd: 'forward' }), 20);
    expect(a.state()).toMatchObject({ port: 'forward', stbd: 'reverse' });
  });

  it('releasing the token forces both commands back to neutral', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1, port: 'forward', stbd: 'reverse' }), 10);
    a.onIntent(intent('A', { armReq: 1, disarmReq: 1, port: 'forward' }), 20);
    expect(a.state()).toMatchObject({
      enabled: false,
      port: 'neutral',
      stbd: 'neutral',
    });
  });

  it('garbage positions are coerced to neutral (never a spurious direction)', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1, port: 'sideways', stbd: 42 }), 10);
    expect(a.state()).toMatchObject({ port: 'neutral', stbd: 'neutral' });
  });
});

describe('ArmArbiter: malformed intents are ignored', () => {
  it('rejects intents with no/blank clientId without affecting state', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10); // A holds
    expect(a.onIntent({ armReq: 99 }, 20)).toBe(false); // no clientId
    expect(a.onIntent(intent('', { armReq: 99 }), 20)).toBe(false); // blank
    expect(a.onIntent(null, 20)).toBe(false);
    expect(a.state().activeClient).toBe('A');
  });

  // Client records are evicted on every tick, so this bounds only the window
  // BETWEEN ticks -- but without a cap, anything that can reach the intent
  // route could grow the Map without limit inside that window.
  it('stops tracking new clients past the cap, without disturbing the holder', () => {
    const a = makeArbiter();
    a.onIntent(intent('holder', { armReq: 0 }), 0);
    a.onIntent(intent('holder', { armReq: 1 }), 10); // holder arms
    expect(a.state().activeClient).toBe('holder');

    // Flood with distinct ids, all within one stale window (no tick).
    for (let i = 0; i < MAX_TRACKED_CLIENTS * 4; i++) {
      a.onIntent(intent(`flood-${i}`, { armReq: 1 }), 20);
    }

    // The holder is untouched: still armed, and its kill switch still works.
    expect(a.state().activeClient).toBe('holder');
    a.onIntent(intent('holder', { disarmReq: 1 }), 30);
    expect(a.state().enabled).toBe(false);
  });

  // An established client must keep being served after the cap is reached --
  // the cap refuses NEW records, it does not stop updating existing ones.
  it('keeps updating clients it already tracks once the table is full', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 10);
    for (let i = 0; i < MAX_TRACKED_CLIENTS * 2; i++) {
      a.onIntent(intent(`flood-${i}`), 20);
    }
    a.onIntent(intent('A', { armReq: 1, port: 'forward' }), 30);
    expect(a.state().port).toBe('forward');
  });
});

// The failure this guards against: the plugin being ARMED with the RX unit switched
// off entirely. Nothing in the arming path had any notion of RX at all -- the
// token was granted purely on client intents, so the phone showed a confident
// ARMED for a controller that was not powered. These vectors pin the gate.
//
// Note what the gate is and isn't: it is "RX telemetry is still ARRIVING"
// (onRxTelemetry), never the value of rx.linkUp or rx.linkOk. rx.linkUp is
// published by RX, so a dead RX leaves it frozen at its last reading -- the
// very thing that made this bug invisible. And rx.linkOk means "a remote
// source is live AND enabled", i.e. it is a consequence of something already
// being armed; gating arming on it would deadlock.
describe('ArmArbiter: RX must be present to arm', () => {
  const RX_STALE = 1500;
  function rxArbiter() {
    return new ArmArbiter({ staleTimeoutMs: 1000, rxStaleTimeoutMs: RX_STALE });
  }

  it('refuses to arm when RX telemetry has never been seen', () => {
    const a = rxArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 100); // a real arm press
    expect(a.state().enabled).toBe(false);
    expect(a.state().activeClient).toBe('');
    expect(a.state().rxLive).toBe(false);
  });

  it('refuses to arm when RX telemetry has gone stale', () => {
    const a = rxArbiter();
    a.onRxTelemetry(0);
    a.onIntent(intent('A', { armReq: 0 }), 0);
    // RX goes quiet; the operator presses ARM well after the timeout.
    a.onIntent(intent('A', { armReq: 1 }), RX_STALE + 100);
    expect(a.state().enabled).toBe(false);
  });

  it('arms normally once RX telemetry is arriving', () => {
    const a = rxArbiter();
    a.onRxTelemetry(0);
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onRxTelemetry(100);
    a.onIntent(intent('A', { armReq: 1 }), 100);
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A', rxLive: true });
  });

  it('an UNCHANGED telemetry value still counts as alive (arrival, not value)', () => {
    // RX republishes linkUp:true every 250 ms and it never changes while the
    // link is healthy. If liveness looked at the value changing, a perfectly
    // healthy RX would be judged dead.
    const a = rxArbiter();
    a.onIntent(intent('A', { armReq: 0 }), 0);
    for (let t = 0; t <= 3000; t += 250) {
      a.onRxTelemetry(t); // same value every time -- only arrival matters
      a.onIntent(intent('A', { armReq: 0, seq: t }), t); // client heartbeat
      a.tick(t);
    }
    a.onIntent(intent('A', { armReq: 1 }), 3000);
    expect(a.state().enabled).toBe(true);
  });

  it('releases an existing arm when RX goes away mid-session', () => {
    const a = rxArbiter();
    a.onRxTelemetry(0);
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 100);
    expect(a.state().enabled).toBe(true);

    // RX loses power. The client keeps heart-beating happily -- it has no
    // idea. Only the missing RX telemetry reveals it.
    for (let t = 350; t <= RX_STALE; t += 250) {
      a.onIntent(intent('A', { armReq: 1, seq: t }), t);
      a.tick(t);
    }
    expect(a.state().enabled).toBe(true); // still inside the window

    const changed = a.tick(100 + RX_STALE + 250);
    expect(changed).toBe(true);
    expect(a.state()).toMatchObject({
      enabled: false,
      port: 'neutral',
      stbd: 'neutral',
      activeClient: '',
      rxLive: false,
    });
  });

  it('does NOT silently re-arm when RX comes back -- a fresh press is required', () => {
    const a = rxArbiter();
    a.onRxTelemetry(0);
    a.onIntent(intent('A', { armReq: 0 }), 0);
    a.onIntent(intent('A', { armReq: 1 }), 100);
    a.tick(100 + RX_STALE + 250); // RX gone -> released
    expect(a.state().enabled).toBe(false);

    // RX returns; A is still heart-beating its old armReq:1 (no new edge).
    a.onRxTelemetry(5000);
    a.onIntent(intent('A', { armReq: 1, seq: 5000 }), 5000);
    expect(a.state().enabled).toBe(false);

    // Only a genuine new press re-arms.
    a.onRxTelemetry(5100);
    a.onIntent(intent('A', { armReq: 2, seq: 5100 }), 5100);
    expect(a.state()).toMatchObject({ enabled: true, activeClient: 'A' });
  });

  it('reports rxLive immediately on telemetry, before any tick', () => {
    // index.cjs publishes on the dead->alive edge from inside the telemetry
    // handler, so state() must already reflect the new verdict there.
    const a = rxArbiter();
    expect(a.state().rxLive).toBe(false);
    a.onRxTelemetry(0);
    expect(a.state().rxLive).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Bow thruster + the heading-hold unit (ARCHITECTURE.md §6). The thruster
// travels the same arbitration path as the drives -- one ARM, one holder, fail
// to a safe value -- so these pin that it really is the same path, not a
// parallel one with its own weaker rules.
// ---------------------------------------------------------------------------
describe('ArmArbiter: bow thruster commands', () => {
  it('passes the holder’s manual direction through', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(
      intent('A', {
        armReq: 1,
        seq: 3,
        thrusterMode: 'manual',
        thruster: 'stbd',
      }),
      3,
    );
    expect(a.state()).toMatchObject({ thruster: 'stbd', thrusterMode: 'manual' });
  });

  it('fails the thruster to OFF when nobody holds the token', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(
      intent('A', { armReq: 1, seq: 3, thrusterMode: 'manual', thruster: 'port' }),
      3,
    );
    expect(a.state().thruster).toBe('port');

    a.onIntent(intent('A', { armReq: 1, disarmReq: 1, seq: 4 }), 4);
    // Fail to OFF, not to the last commanded direction.
    expect(a.state()).toMatchObject({ enabled: false, thruster: 'off' });
  });

  it('never lets a manual direction survive into hold mode', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(
      intent('A', { armReq: 1, seq: 3, thrusterMode: 'hold', thruster: 'stbd' }),
      3,
    );
    expect(a.state().thruster).toBe('off');
  });

  it('never lets a commanded trim survive into manual mode', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(
      intent('A', { armReq: 1, seq: 3, thrusterMode: 'manual', trimDeg: 20 }),
      3,
    );
    expect(a.state().trimDeg).toBe(0);
  });

  it('forwards the commanded trim in hold mode; 0 (or none) means no trim', () => {
    // A relative offset needs no sentinel: 0 IS "hold the captured heading", so
    // going quiet or sending 0 or an unparseable value all mean the same safe
    // thing, and the last value retained by Signal K is never a stale command.
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(
      intent('A', { armReq: 1, seq: 3, thrusterMode: 'hold', trimDeg: 15 }),
      3,
    );
    expect(a.state().trimDeg).toBe(15);
    a.onIntent(
      intent('A', { armReq: 1, seq: 4, thrusterMode: 'hold', trimDeg: 0 }),
      4,
    );
    expect(a.state().trimDeg).toBe(0);
    a.onIntent(
      intent('A', { armReq: 1, seq: 5, thrusterMode: 'hold', trimDeg: null }),
      5,
    );
    expect(a.state().trimDeg).toBe(0);
  });

  it('clamps an out-of-range or garbage trim to 0..MAX rather than rejecting', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    let seq = 3;
    // Garbage / non-finite collapses to 0 (no trim); huge values saturate.
    for (const bad of [NaN, 'north', null]) {
      a.onIntent(
        intent('A', { armReq: 1, seq: seq++, thrusterMode: 'hold', trimDeg: bad }),
        seq,
      );
      expect(a.state().trimDeg).toBe(0);
    }
    a.onIntent(
      intent('A', { armReq: 1, seq: seq++, thrusterMode: 'hold', trimDeg: 999 }),
      seq,
    );
    expect(a.state().trimDeg).toBe(MAX_TRIM_DEG);
    a.onIntent(
      intent('A', { armReq: 1, seq: seq++, thrusterMode: 'hold', trimDeg: -80 }),
      seq,
    );
    expect(a.state().trimDeg).toBe(-MAX_TRIM_DEG);
  });

  it('treats an unrecognised direction or mode as the safe value', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(
      intent('A', { armReq: 1, seq: 3, thrusterMode: 'MANUAL', thruster: 'PORT' }),
      3,
    );
    // 'MANUAL' is not the contract's spelling, so it degrades to hold -- and a
    // thruster must never start pushing because of a malformed value.
    expect(a.state()).toMatchObject({ thrusterMode: 'hold', thruster: 'off' });
  });

  it('degrades the WHOLE tuple when the mode is unrecognised, trim included', () => {
    // Degrading the mode alone used to promote a mangled intent into a LIVE
    // hold: 'hold' plus whatever trim the packet happened to carry, i.e. a
    // commanded swing synthesised out of corruption. The safe value for the
    // thruster is the pair -- hold AND no trim.
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    for (const badMode of ['HOLD', 'Hold', 'holdd', 42, null]) {
      a.onIntent(
        intent('A', {
          armReq: 1,
          seq: autoSeq + 1,
          thrusterMode: badMode,
          trimDeg: 30,
        }),
        3,
      );
      expect(a.state()).toMatchObject({
        thrusterMode: 'hold',
        thruster: 'off',
        trimDeg: 0,
      });
    }
    // A station that actually asks for hold still commands its trim.
    a.onIntent(
      intent('A', {
        armReq: 1,
        seq: autoSeq + 1,
        thrusterMode: 'hold',
        trimDeg: 30,
      }),
      4,
    );
    expect(a.state().trimDeg).toBe(30);
  });
});

describe('ArmArbiter: two units, one ARM', () => {
  function bareArbiter(opts: Record<string, unknown> = {}) {
    return new ArmArbiter({ rxStaleTimeoutMs: 1500, ...opts });
  }

  it('cannot arm when NEITHER unit has been seen', () => {
    const a = bareArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    expect(a.state().enabled).toBe(false);
  });

  it('arms with only the drive unit live', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    expect(a.state()).toMatchObject({ enabled: true, rxLive: true, hhLive: false });
  });

  // The deliberate availability call: an absent thruster board must not take
  // away gear control, and vice versa.
  it('arms with only the thruster unit live', () => {
    const a = bareArbiter();
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    expect(a.state()).toMatchObject({ enabled: true, rxLive: false, hhLive: true });
  });

  it('holds the arm while at least one unit is still answering', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    expect(a.state().enabled).toBe(true);

    // The thruster board goes away; the drives are still there.
    a.onRxTelemetry(3000);
    a.onIntent(intent('A', { armReq: 1, seq: 3 }), 3000);
    a.tick(3000);
    expect(a.state()).toMatchObject({ enabled: true, hhLive: false });
  });

  // Client staleness is held off so this pins the UNIT-liveness rule alone.
  // With the default 1 s client timeout the holder would simply be evicted at
  // t=3000 and the token released, which is a different (already covered)
  // mechanism and would hide whether the command itself was erased.
  it('neutralises the missing drive unit and does not replay its old command on return', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2, port: 'forward' }), 2);
    expect(a.state().port).toBe('forward');

    // RX disappears while HH keeps the shared token alive.
    a.onHhTelemetry(3000);
    a.tick(3000);
    expect(a.state()).toMatchObject({ enabled: true, rxLive: false, port: 'neutral' });

    // Even a station that keeps asking for forward cannot plant a latent
    // command while RX is absent.
    a.onIntent(intent('A', { armReq: 1, seq: 3, port: 'forward' }), 3010);
    a.onRxTelemetry(3100);
    expect(a.state().port).toBe('neutral');

    // A STILL-HELD button is not a fresh command. seq advances on the 250 ms
    // heartbeat whether or not the finger moved, so it cannot be the evidence
    // of operator intent -- it orders transport, nothing more.
    a.onIntent(intent('A', { armReq: 1, seq: 4, port: 'forward' }), 3110);
    expect(a.state().port).toBe('neutral');

    // Releasing is the baseline the quarantine waits for...
    a.onIntent(intent('A', { armReq: 1, seq: 5, port: 'neutral' }), 3120);
    expect(a.state().port).toBe('neutral');

    // ...and the next forward after it is a genuinely new command.
    a.onIntent(intent('A', { armReq: 1, seq: 6, port: 'forward' }), 3130);
    expect(a.state().port).toBe('forward');
  });

  it('turns off the missing thruster and does not replay its old command on return', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(
      intent('A', {
        armReq: 1,
        seq: 2,
        thrusterMode: 'manual',
        thruster: 'port',
      }),
      2,
    );
    expect(a.state().thruster).toBe('port');

    a.onRxTelemetry(3000);
    a.tick(3000);
    // MANUAL, not HOLD, is the rest mode while armed: with enabled=true a
    // published 'hold' is itself a live engage request on HH (see
    // REST_MODE_WHILE_ARMED in arbiter.cjs).
    expect(a.state()).toMatchObject({
      enabled: true,
      hhLive: false,
      thruster: 'off',
      thrusterMode: 'manual',
      trimDeg: 0,
    });
    a.onIntent(
      intent('A', {
        armReq: 1,
        seq: 3,
        thrusterMode: 'manual',
        thruster: 'port',
      }),
      3010,
    );
    a.onHhTelemetry(3100);
    expect(a.state()).toMatchObject({ thruster: 'off', thrusterMode: 'manual' });
  });

  it('publishes MANUAL, never HOLD, as the rest mode while a holder exists but HH is not commandable', () => {
    // With enabled=true a published 'hold' is not a rest value: HH reads
    // {live, enabled, mode: hold} as an engage request and enters HOLDING the
    // moment its heading is good -- automatic thrust off a returning telemetry
    // frame, the very thing the quarantine exists to prevent, with the
    // operator's own UI showing MANUAL. 'manual' + 'off' is inert on HH
    // (ENABLE asserted, both direction lines low): an armed MANUAL operator
    // with no finger down. See REST_MODE_WHILE_ARMED in arbiter.cjs.
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0); // HH never seen: the arm goes through RX alone
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2, thrusterMode: 'hold', trimDeg: 5 }), 2);
    expect(a.state()).toMatchObject({
      enabled: true,
      hhLive: false,
      thrusterMode: 'manual',
      thruster: 'off',
      trimDeg: 0,
    });

    // HH appears. Quarantined until the holder is seen releasing: still MANUAL,
    // even though the holder keeps asking for HOLD with a trim.
    a.onHhTelemetry(100);
    expect(a.state()).toMatchObject({ hhLive: true, thrusterMode: 'manual' });
    a.onIntent(intent('A', { armReq: 1, seq: 3, thrusterMode: 'hold', trimDeg: 5 }), 110);
    expect(a.state()).toMatchObject({ thrusterMode: 'manual', trimDeg: 0 });

    // The release (off, no trim) lifts it; the next HOLD intent is honoured.
    a.onIntent(
      intent('A', { armReq: 1, seq: 4, thrusterMode: 'hold', thruster: 'off', trimDeg: 0 }),
      120,
    );
    a.onIntent(intent('A', { armReq: 1, seq: 5, thrusterMode: 'hold', trimDeg: 5 }), 130);
    expect(a.state()).toMatchObject({ thrusterMode: 'hold', trimDeg: 5 });

    // With nobody armed, enabled=false makes the tuple inert and the garbage
    // default 'hold' is the right rest again.
    a.onIntent(intent('A', { armReq: 1, disarmReq: 1, seq: 6 }), 140);
    expect(a.state()).toMatchObject({ enabled: false, thrusterMode: 'hold' });
  });

  // The erasure has to happen wherever the liveness verdict advances, not just
  // in tick(). onIntent() advances it too, and an intent that observes the
  // unit's death and is then DISCARDED at the ordering gate would otherwise
  // consume the falling edge: the next tick sees was-live already false and
  // erases nothing, leaving the old command latent behind the state() mask.
  it('erases the command even when a discarded intent consumed the falling edge', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 10 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 11, port: 'forward' }), 2);
    expect(a.state().port).toBe('forward');

    // RX is dead by now (rxStaleTimeoutMs 1500). This intent is the first
    // thing to notice, and is then rejected by the seq gate -- it changes no
    // command state itself, so only the edge handling can save us.
    a.onHhTelemetry(3000);
    expect(a.onIntent(intent('A', { armReq: 1, seq: 5, port: 'forward' }), 3000)).toBe(false);
    a.tick(3001);

    // RX returns. Nothing may resume without a fresh operator command.
    a.onRxTelemetry(3100);
    expect(a.state().port).toBe('neutral');
  });

  // A gap can open and close entirely between two ticks: cross the timeout at
  // 1500 and be answered at 1600, with the next tick not due until 1750. If
  // only tick() and onIntent() settle liveness, nothing ever observes that
  // dead interval and the returning frame republishes the pre-gap command.
  it('erases the command when a unit outage falls entirely between ticks', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2, port: 'forward' }), 2);
    expect(a.state().port).toBe('forward');

    // Still inside rxStaleTimeoutMs (1500), so this observes nothing.
    a.onHhTelemetry(1400);
    a.onIntent(intent('A', { armReq: 1, seq: 3, port: 'forward' }), 1400);
    expect(a.state().port).toBe('forward');

    // RX returns at 1600 having been out since 0 -- past the timeout, but no
    // tick fell in the interval. RX's own watchdog has neutralised by now, so
    // this must not put 'forward' back on the wire.
    a.onRxTelemetry(1600);
    expect(a.state()).toMatchObject({ rxLive: true, port: 'neutral' });

    // The held button still does not resume it -- release, then command.
    a.onIntent(intent('A', { armReq: 1, seq: 4, port: 'forward' }), 1610);
    expect(a.state().port).toBe('neutral');
    a.onIntent(intent('A', { armReq: 1, seq: 5, port: 'neutral' }), 1620);
    a.onIntent(intent('A', { armReq: 1, seq: 6, port: 'forward' }), 1630);
    expect(a.state().port).toBe('forward');
  });

  // A returning unit is quarantined until the operator lets go, because
  // erasing the stored command only clears what is STORED -- the holder's next
  // heartbeat carries whatever its buttons currently say, and a finger that
  // never moved would rewrite it within 250 ms. Mirrors the arm token, which
  // already requires a fresh press once RX is back.
  it('quarantines the thruster until the operator releases it', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    const held = { armReq: 1, thrusterMode: 'manual' as const, thruster: 'port' as const };
    a.onIntent(intent('A', { ...held, seq: 2 }), 2);
    expect(a.state().thruster).toBe('port');

    // HH goes away and comes back, RX holding the token throughout.
    a.onRxTelemetry(3000);
    a.tick(3000);
    a.onHhTelemetry(3100);
    expect(a.state()).toMatchObject({ hhLive: true, thruster: 'off' });

    // Still held: still off.
    a.onIntent(intent('A', { ...held, seq: 3 }), 3110);
    expect(a.state().thruster).toBe('off');

    // Released, then commanded again.
    a.onIntent(intent('A', { armReq: 1, seq: 4, thrusterMode: 'manual', thruster: 'off' }), 3120);
    a.onIntent(intent('A', { ...held, seq: 5 }), 3130);
    expect(a.state().thruster).toBe('port');
  });

  // The phone persists clientId -- it must, the access token is issued to that
  // device id -- while seq and the counters live in memory and restart at 0. A
  // process restart inside staleTimeoutMs therefore meets the record it left
  // behind, and its packets carry a LOWER seq: the ordering gate discards them
  // whole, so the new session's STOP returned 200 and did nothing while the
  // retained command stood. A per-launch session id says "new session" outright.
  it('honours STOP from a restarted process that still has a live record', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { session: 2, seq: 40, armReq: 0 }), 1);
    a.onIntent(intent('phone', { session: 2, seq: 41, armReq: 1, port: 'forward' }), 2);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });

    // Relaunch: same clientId, new session, seq and counters from scratch. The
    // operator's first act is STOP.
    a.onIntent(intent('phone', { session: 3, seq: 1, armReq: 0, disarmReq: 1 }), 300);
    expect(a.state()).toMatchObject({ enabled: false, port: 'neutral', activeClient: '' });
  });

  // Session ids are UNORDERED -- a uuid per launch -- so "different from the one
  // on record" cannot mean "newer". An old process's POST delayed past the new
  // process's first POST would otherwise read as yet another restart, wipe the
  // new session's record and republish the DEAD process's command. A heartbeat
  // from a process that has already exited must never move a machine.
  it('rejects a late packet from a session that has been replaced', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { session: 2, seq: 40, armReq: 0 }), 1);
    a.onIntent(intent('phone', { session: 2, seq: 41, armReq: 1, port: 'forward' }), 2);
    expect(a.state().port).toBe('forward');

    // Relaunch, and the operator is not touching anything.
    a.onIntent(intent('phone', { session: 3, seq: 1, armReq: 0 }), 300);
    a.onIntent(intent('phone', { session: 3, seq: 2, armReq: 0 }), 310);
    expect(a.state()).toMatchObject({ enabled: false, port: 'neutral' });

    // s1's last POST finally lands, still carrying 'forward'. It must be
    // discarded outright -- not treated as a third session.
    expect(
      a.onIntent(intent('phone', { session: 2, seq: 42, armReq: 1, port: 'forward' }), 320),
    ).toBe(false);
    expect(a.state()).toMatchObject({ enabled: false, port: 'neutral' });

    // And the live session keeps working afterwards.
    a.onIntent(intent('phone', { session: 3, seq: 3, armReq: 1 }), 330);
    expect(a.state().enabled).toBe(true);
  });

  // The kill switch must never GRANT the token. Statement order used to be relied
  // on for this -- disarm ran first, setting holder to null, and the arm gate is
  // `holder === null`, so the disarm handed the arm its precondition and the same
  // packet armed. Directly reachable once STOP bypassed the send lane: an urgent
  // STOP overtakes a not-yet-delivered ARM and its snapshot carries both counters
  // already incremented.
  it('never arms from an intent that also carries a new disarm', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { seq: 1, armReq: 0, disarmReq: 0 }), 1);
    expect(a.state().enabled).toBe(false);

    // The overtaking STOP: both counters up, in one packet, from an unarmed
    // station. Nobody holds the token, so the arm gate's precondition is met.
    a.onIntent(intent('phone', { seq: 2, armReq: 1, disarmReq: 1 }), 2);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });

    // The arm edge is CONSUMED, not queued: re-arming needs a fresh press.
    a.onIntent(intent('phone', { seq: 3, armReq: 1, disarmReq: 1 }), 3);
    expect(a.state().enabled).toBe(false);
    a.onIntent(intent('phone', { seq: 4, armReq: 2, disarmReq: 1 }), 4);
    expect(a.state().enabled).toBe(true);
  });

  // ...and the same packet must not be able to take the token FROM another
  // station either.
  it('does not let a disarm-plus-arm packet steal the token', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('other', { seq: 1, armReq: 0 }), 1);
    a.onIntent(intent('other', { seq: 2, armReq: 1 }), 2);
    expect(a.state().activeClient).toBe('other');

    a.onIntent(intent('phone', { seq: 3, armReq: 0, disarmReq: 0 }), 3);
    a.onIntent(intent('phone', { seq: 4, armReq: 1, disarmReq: 1 }), 4);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });
  });

  // The generation watermark has to outlive the record that carried it. The
  // record is evictable, and it held the only copy: after an eviction a delayed
  // packet from an older session met rec == null, was taken as a first sighting,
  // and a baseline followed by an ARM edge could re-arm and move a machine after
  // the live session had gone quiet.
  it('keeps an old session closed after the record is evicted', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { session: 7, seq: 1, armReq: 0 }), 1);
    a.onIntent(intent('phone', { session: 7, seq: 2, armReq: 1 }), 2);
    expect(a.state().enabled).toBe(true);

    // Silence past staleTimeoutMs: the record is evicted and the token released.
    a.onRxTelemetry(3000);
    a.onHhTelemetry(3000);
    a.tick(3000);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });

    // Two queued packets from the DEAD session 6 -- a baseline, then an arm edge
    // carrying an active command. Both must be refused outright.
    expect(a.onIntent(intent('phone', { session: 6, seq: 1, armReq: 0 }), 3010)).toBe(false);
    expect(
      a.onIntent(intent('phone', { session: 6, seq: 2, armReq: 1, port: 'forward' }), 3020),
    ).toBe(false);
    expect(a.state()).toMatchObject({ enabled: false, port: 'neutral' });

    // The live session re-registers with its own generation and works normally.
    //
    // Note what a re-registering station actually sends: its counters are
    // CUMULATIVE for the life of the session (ClientIntent: "increments once per
    // operator ARM tap"), and they restart only when the process does -- which
    // bumps the generation. So session 7 comes back still carrying the armReq: 1
    // it already spent, and arming again takes a FRESH tap (armReq: 2). A packet
    // rewinding session 7's counters to 0 would not be this station returning at
    // all; it would be a replay, and the re-arm it could then fire off the next
    // unchanged heartbeat is pinned as a defect in the replay suite below.
    a.onIntent(intent('phone', { session: 7, seq: 3, armReq: 1 }), 3030);
    expect(a.state().enabled).toBe(false); // just back -- not a new press
    a.onIntent(intent('phone', { session: 7, seq: 4, armReq: 2 }), 3040);
    expect(a.state().enabled).toBe(true);
  });

  // An ORDERED generation needs no memory of dead sessions, so there is nothing
  // to bound and nothing that can be forgotten.
  it('stays correct across many relaunches, with no memory to exhaust', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    let t = 1;
    for (let i = 1; i <= 40; i += 1) {
      a.onIntent(intent('phone', { session: i, seq: 1, armReq: 0 }), t);
      t += 10;
    }
    a.onIntent(intent('phone', { session: 40, seq: 2, armReq: 1, port: 'forward' }), t);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });

    // Generation 1, forty relaunches ago, is still closed. The unordered version
    // forgot ids past its bound, and a forgotten one arriving late cleared the
    // real holder and marked the RUNNING session superseded -- silently killing
    // that station's STOP.
    t += 10;
    expect(a.onIntent(intent('phone', { session: 1, seq: 99, disarmReq: 5 }), t)).toBe(false);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });
  });

  // The upgrade path: the record was created by a client sending no generation,
  // and the relaunched session-aware client sends one. That is a new process, so
  // without this the mixed-version relaunch keeps the original defect and STOP
  // is filtered by the seq gate.
  it('treats a legacy record as replaced by a session-aware client', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { seq: 40, armReq: 0 }), 1); // no session
    a.onIntent(intent('phone', { seq: 41, armReq: 1, port: 'forward' }), 2);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });

    a.onIntent(intent('phone', { session: 1, seq: 1, armReq: 0, disarmReq: 1 }), 300);
    expect(a.state()).toMatchObject({ enabled: false, port: 'neutral' });
  });

  // ...and the reverse: once a generation is on record, a packet carrying none is
  // from a process older than it.
  it('closes a sessionless packet once a generation is on record', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { session: 5, seq: 1, armReq: 0 }), 1);
    a.onIntent(intent('phone', { session: 5, seq: 2, armReq: 1, port: 'forward' }), 2);
    expect(a.state().port).toBe('forward');

    expect(a.onIntent(intent('phone', { seq: 3, disarmReq: 9 }), 3)).toBe(false);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });
  });

  // ...and the restart must not become a way to smuggle an ARM through. The new
  // session establishes baselines without firing an edge, exactly as a genuinely
  // new station does.
  it('does not let a restart re-arm without a fresh press', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { session: 2, seq: 10, armReq: 0 }), 1);
    a.onIntent(intent('phone', { session: 2, seq: 11, armReq: 1 }), 2);
    a.onIntent(intent('other', { seq: 500, disarmReq: 1 }), 3); // universal STOP
    expect(a.state().enabled).toBe(false);

    // Relaunch carrying armReq = 1 from the new session's own count. That is a
    // baseline, not a press.
    a.onIntent(intent('phone', { session: 3, seq: 1, armReq: 1 }), 300);
    expect(a.state().enabled).toBe(false);
    // A genuine press in the new session does arm.
    a.onIntent(intent('phone', { session: 3, seq: 2, armReq: 2 }), 310);
    expect(a.state().enabled).toBe(true);
  });

  // The nonce must not weaken the ordering gate it sits in front of: a delayed
  // packet from the SAME session is still a replay and still discarded.
  it('still discards a replayed packet from the same session', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('phone', { session: 2, seq: 10, armReq: 0 }), 1);
    a.onIntent(intent('phone', { session: 2, seq: 11, armReq: 1, port: 'forward' }), 2);
    a.onIntent(intent('phone', { session: 2, seq: 12, armReq: 1, port: 'neutral' }), 3);
    a.onIntent(intent('phone', { session: 2, seq: 13, armReq: 1, port: 'forward' }), 4);
    expect(a.state().port).toBe('forward');

    // A stale heartbeat from earlier in the SAME session arrives late.
    expect(a.onIntent(intent('phone', { session: 2, seq: 12, port: 'neutral' }), 5)).toBe(false);
    expect(a.state().port).toBe('forward');
  });

  // A unit never seen since this arbiter started has no live-to-dead edge to
  // trip the gate, so it must START quarantined rather than acquire the gate on
  // its first loss. Reachable at every plugin restart, because arming needs only
  // ONE live unit: arm through the thruster, hold a drive command while RX is
  // still absent, and without this it lands the moment RX powers up.
  it('quarantines a unit that has never been seen, not just one that has left', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    // HH only. RX has never existed as far as this arbiter is concerned.
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2, port: 'forward' }), 2);
    expect(a.state()).toMatchObject({ enabled: true, rxLive: false, port: 'neutral' });

    // RX powers up for the first time. The held press must not come alive with
    // it -- there has been no safe baseline for the drives at any point.
    a.onRxTelemetry(3000);
    a.onIntent(intent('A', { armReq: 1, seq: 3, port: 'forward' }), 3010);
    expect(a.state()).toMatchObject({ rxLive: true, port: 'neutral' });

    // Release once RX is live, then command.
    a.onIntent(intent('A', { armReq: 1, seq: 4, port: 'neutral' }), 3020);
    a.onIntent(intent('A', { armReq: 1, seq: 5, port: 'forward' }), 3030);
    expect(a.state().port).toBe('forward');
  });

  // A release that arrives DURING the outage is not the post-recovery edge the
  // gate requires: it happens before there is anything to recover from. If it
  // counted, the operator could let go and press again while the unit was still
  // absent -- masked, so the press looks inert -- and the first heartbeat after
  // the unit returned would republish it. Motion restarting off a press made
  // during the blackout.
  it('does not accept a release given while the unit is still absent', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2, port: 'forward' }), 2);
    expect(a.state().port).toBe('forward');

    // RX drops out; HH keeps the holder armed so the session survives.
    a.onHhTelemetry(3000);
    a.tick(3000);
    expect(a.state()).toMatchObject({ enabled: true, rxLive: false, port: 'neutral' });

    // Operator lets go, then presses again -- both while RX is absent.
    a.onIntent(intent('A', { armReq: 1, seq: 3, port: 'neutral' }), 3010);
    a.onIntent(intent('A', { armReq: 1, seq: 4, port: 'forward' }), 3020);
    expect(a.state().port).toBe('neutral');

    // RX returns. The press above must NOT come alive with it.
    a.onRxTelemetry(3100);
    a.onIntent(intent('A', { armReq: 1, seq: 5, port: 'forward' }), 3110);
    expect(a.state()).toMatchObject({ rxLive: true, port: 'neutral' });

    // Only a release seen while RX is live opens the way again.
    a.onIntent(intent('A', { armReq: 1, seq: 6, port: 'neutral' }), 3120);
    a.onIntent(intent('A', { armReq: 1, seq: 7, port: 'forward' }), 3130);
    expect(a.state().port).toBe('forward');
  });

  // In HOLD, `thruster` is ALWAYS 'off' -- HOLD commands through the trim, not
  // through a direction. So a release predicate that looked only at the
  // direction would be satisfied by every ordinary HOLD heartbeat, lifting the
  // quarantine while a non-zero trim was still being asked for, and the next
  // heartbeat would restart automatic thrust off an offset dialled in before the
  // outage. The erase path already zeroes trimDeg, so the release must require
  // it too.
  it('does not lift the thruster quarantine while a HOLD trim is still asked for', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    const holding = { armReq: 1, thrusterMode: 'hold' as const, thruster: 'off' as const, trimDeg: 10 };
    a.onIntent(intent('A', { ...holding, seq: 2 }), 2);
    expect(a.state().trimDeg).toBe(10);

    // HH drops out and returns, RX keeping the token alive throughout.
    for (let t = 250; t <= 3000; t += 250) a.onRxTelemetry(t);
    a.tick(3000);
    a.onHhTelemetry(3100);
    expect(a.state()).toMatchObject({ hhLive: true, trimDeg: 0 });

    // The same HOLD intent -- direction 'off', trim still 10 -- must NOT be
    // taken as the operator letting go.
    a.onIntent(intent('A', { ...holding, seq: 3 }), 3110);
    a.onIntent(intent('A', { ...holding, seq: 4 }), 3120);
    expect(a.state().trimDeg).toBe(0);

    // Trimming back to zero is the release; then a fresh offset applies.
    a.onIntent(
      intent('A', { armReq: 1, seq: 5, thrusterMode: 'hold', thruster: 'off', trimDeg: 0 }),
      3130,
    );
    a.onIntent(intent('A', { ...holding, seq: 6 }), 3140);
    expect(a.state().trimDeg).toBe(10);
  });

  // The quarantine must not become a way to lock the operator out. Each machine
  // is gated by its OWN unit, so a thruster outage may not cost the drives their
  // commands, and releasing one machine may not silently release the other.
  it('quarantines only the machine whose unit went away', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(
      intent('A', { armReq: 1, seq: 2, port: 'forward', thrusterMode: 'manual', thruster: 'port' }),
      2,
    );
    expect(a.state()).toMatchObject({ port: 'forward', thruster: 'port' });

    // HH alone drops out and returns. RX must be kept genuinely answering
    // throughout -- a gap in ITS telemetry would quarantine the drives too,
    // and then this test would pass for the wrong reason.
    for (let t = 250; t <= 3000; t += 250) a.onRxTelemetry(t);
    a.tick(3000);
    expect(a.state()).toMatchObject({ rxLive: true, hhLive: false });
    a.onHhTelemetry(3100);
    a.onRxTelemetry(3100);

    // The drives were never absent, so a held forward keeps working.
    a.onIntent(
      intent('A', { armReq: 1, seq: 3, port: 'forward', thrusterMode: 'manual', thruster: 'port' }),
      3110,
    );
    expect(a.state()).toMatchObject({ port: 'forward', thruster: 'off' });
  });

  // Nothing is published while disarmed, so a quarantine must not outlive the
  // session that caused it and greet the next operator with dead controls.
  it('does not strand a quarantine across a disarm', () => {
    const a = bareArbiter({ staleTimeoutMs: Number.POSITIVE_INFINITY });
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2, port: 'forward' }), 2);

    // RX drops, the operator disarms, RX returns.
    a.onHhTelemetry(3000);
    a.tick(3000);
    a.onIntent(intent('A', { armReq: 1, disarmReq: 1, seq: 3, port: 'neutral' }), 3010);
    expect(a.state().enabled).toBe(false);
    a.onRxTelemetry(3100);

    // Re-arming from neutral and then commanding works without an extra
    // release: the disarm already was the release.
    a.onIntent(intent('A', { armReq: 2, disarmReq: 1, seq: 4, port: 'neutral' }), 3110);
    a.onIntent(intent('A', { armReq: 2, disarmReq: 1, seq: 5, port: 'forward' }), 3120);
    expect(a.state()).toMatchObject({ enabled: true, port: 'forward' });
  });

  it('releases the arm once BOTH units have gone', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onHhTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    expect(a.state().enabled).toBe(true);

    a.onIntent(intent('A', { armReq: 1, seq: 3 }), 3000);
    a.tick(3000);
    expect(a.state()).toMatchObject({
      enabled: false,
      thruster: 'off',
      port: 'neutral',
      stbd: 'neutral',
    });
  });

  it('does not silently re-arm when a unit comes back', () => {
    const a = bareArbiter();
    a.onRxTelemetry(0);
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 1);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 2);
    a.onIntent(intent('A', { armReq: 1, seq: 3 }), 3000);
    a.tick(3000);
    expect(a.state().enabled).toBe(false);

    // Unit returns, client keeps heart-beating its OLD armReq: still disarmed.
    a.onHhTelemetry(4000);
    a.onIntent(intent('A', { armReq: 1, seq: 4 }), 4000);
    expect(a.state().enabled).toBe(false);
    // Only a fresh press re-arms.
    a.onHhTelemetry(4100);
    a.onIntent(intent('A', { armReq: 2, seq: 5 }), 4100);
    expect(a.state().enabled).toBe(true);
  });

  it('reports hhLive immediately on telemetry, before any tick', () => {
    const a = bareArbiter();
    expect(a.state().hhLive).toBe(false);
    a.onHhTelemetry(0);
    expect(a.state().hhLive).toBe(true);
  });
});

// The failure these guard against (found in the 2026-07-23 review): the edge
// baselines were assigned from whatever value arrived LAST, so a stale or
// malicious intent carrying a LOWER armReq re-opened an already-consumed
// counter value as a "fresh" edge -- a client could silently regain the token
// after a universal disarm no operator ever answered. The fix is two
// independent layers: per-client seq ordering discards stale intents whole,
// and the baselines themselves are clamped monotonic so they can never move
// backward even for clients that send no seq at all.
describe('ArmArbiter: replay / out-of-order intents cannot corrupt edges', () => {
  it('a replayed old armReq can NEVER silently re-arm after a universal disarm', () => {
    const a = makeArbiter();
    a.onIntent(intent('X', { armReq: 0, seq: 1 }), 0);
    a.onIntent(intent('X', { armReq: 1, seq: 2 }), 10); // real tap: X holds
    expect(a.state().activeClient).toBe('X');

    // A delayed heartbeat from before the tap arrives late (lower seq AND
    // lower armReq). It must be discarded -- and even if seq were missing,
    // the baseline must not move backward.
    a.onIntent(intent('X', { armReq: 0, seq: 1 }), 20);

    // Another station hits the universal disarm (baseline first -- a client
    // merely appearing never counts as a press).
    a.onIntent(intent('Y', { disarmReq: 0, seq: 1 }), 25);
    a.onIntent(intent('Y', { disarmReq: 1, seq: 2 }), 30);
    expect(a.state().enabled).toBe(false);

    // X's transport re-delivers the old armReq:1 intent. No operator has
    // touched anything -- this must NOT re-arm.
    a.onIntent(intent('X', { armReq: 1, seq: 4 }), 40);
    expect(a.state().enabled).toBe(false);
    expect(a.state().activeClient).toBe('');

    // A genuinely fresh tap (a NEW counter value) still works.
    a.onIntent(intent('X', { armReq: 2, seq: 5 }), 50);
    expect(a.state().activeClient).toBe('X');
  });

  it('the armReq baseline cannot be lowered even by a seq-less client', () => {
    const a = makeArbiter();
    // This client never sends seq (null skips the ordering gate) -- the
    // monotonic clamp alone must hold the line.
    a.onIntent({ clientId: 'X', armReq: 1, port: 'neutral', stbd: 'neutral' }, 0);
    a.onIntent({ clientId: 'X', armReq: 2, port: 'neutral', stbd: 'neutral' }, 10); // tap: holds
    expect(a.state().activeClient).toBe('X');
    a.onIntent({ clientId: 'X', armReq: 0, port: 'neutral', stbd: 'neutral' }, 20); // corrupt attempt
    a.onIntent({ clientId: 'Y', disarmReq: 0 }, 25); // baseline
    a.onIntent({ clientId: 'Y', disarmReq: 1 }, 30); // universal disarm
    expect(a.state().enabled).toBe(false);
    a.onIntent({ clientId: 'X', armReq: 2, port: 'neutral', stbd: 'neutral' }, 40); // replay
    expect(a.state().enabled).toBe(false);
  });

  it('an out-of-order intent does not revert the holder commands', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 0);
    a.onIntent(intent('A', { armReq: 1, seq: 2, port: 'forward' }), 10);
    expect(a.state().port).toBe('forward');
    // A stale heartbeat from before the press arrives late: discarded whole,
    // so the live command is not flickered back to neutral by old data.
    a.onIntent(intent('A', { armReq: 1, seq: 1, port: 'neutral' }), 20);
    expect(a.state().port).toBe('forward');
  });

  it('a stale intent does not refresh liveness (old packets are not proof of life)', () => {
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('A', { armReq: 0, seq: 10 }), 0);
    a.onIntent(intent('A', { armReq: 1, seq: 11 }), 100); // A holds
    // Only stale replays arrive from then on; they must not keep A "seen".
    a.onIntent(intent('A', { armReq: 1, seq: 5 }), 900);
    a.tick(100 + 1001);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });
  });

  it('the disarm baseline is also clamped monotonic', () => {
    const a = makeArbiter();
    a.onIntent(intent('A', { disarmReq: 3, seq: 1 }), 0); // baseline 3
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 10); // holds
    // Stale-looking lower disarmReq must not lower the baseline...
    a.onIntent(intent('A', { armReq: 1, disarmReq: 0, seq: 3 }), 20);
    expect(a.state().activeClient).toBe('A'); // and must not disarm by itself
    // ...so a replay of the ALREADY-CONSUMED disarm baseline value is inert,
    a.onIntent(intent('A', { armReq: 1, disarmReq: 3, seq: 4 }), 30);
    expect(a.state().activeClient).toBe('A');
    // while a genuinely fresh disarm tap still stops everything instantly.
    a.onIntent(intent('A', { armReq: 1, disarmReq: 4, seq: 5 }), 40);
    expect(a.state().enabled).toBe(false);
  });

  // THE EVICTION HOLE. Every other guard in this suite lives in the client
  // RECORD -- the seq gate and the monotonic clamps -- and eviction destroys the
  // record. Only the counters outlive it, so if the arm total is not among them
  // the baseline is rebuilt from whichever packet arrives first after the
  // station re-registers, and a delayed pre-tap heartbeat rebuilds it LOW.
  it('cannot be re-armed by an out-of-order pre-tap packet after eviction', () => {
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 100); // baseline
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 350); // the operator's tap
    expect(a.state().activeClient).toBe('A');
    a.onIntent(intent('A', { armReq: 1, seq: 3 }), 600); // steady heartbeat

    // WiFi blip: silent past the staleness budget, so the token auto-releases.
    a.tick(2000);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });

    // Connectivity returns and two queued POSTs are delivered OUT OF ORDER: the
    // delayed pre-tap heartbeat first, then the current one. NOBODY has touched
    // the kill switch, so nothing here may re-grant the token.
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 2100);
    a.onIntent(intent('A', { armReq: 1, seq: 4 }), 2200);
    expect(a.state()).toMatchObject({ enabled: false, activeClient: '' });

    // Steady heartbeats at the same value stay inert, however many arrive...
    a.onIntent(intent('A', { armReq: 1, seq: 5 }), 2300);
    a.onIntent(intent('A', { armReq: 1, seq: 6 }), 2550);
    expect(a.state().activeClient).toBe('');

    // ...and a genuinely fresh press still arms, immediately.
    a.onIntent(intent('A', { armReq: 2, seq: 7 }), 2800);
    expect(a.state().activeClient).toBe('A');
  });

  it('is not re-armed by an in-order heartbeat after eviction either', () => {
    // The same story with the packets arriving the RIGHT way round -- the case
    // the record baseline already covered. Pinned so the two orderings are
    // known to agree, rather than one of them holding by accident.
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('A', { armReq: 0, seq: 1 }), 100);
    a.onIntent(intent('A', { armReq: 1, seq: 2 }), 350);
    a.tick(2000);
    expect(a.state().activeClient).toBe('');
    a.onIntent(intent('A', { armReq: 1, seq: 3 }), 2100);
    a.onIntent(intent('A', { armReq: 1, seq: 4 }), 2350);
    expect(a.state().activeClient).toBe('');
  });

  it('lets a relaunched station arm on its FIRST fresh press', () => {
    // The other side of the same coin, and why the arm memory may only be
    // dropped on ORDERED evidence. A phone persists clientId (the SK token is
    // issued to it) while its counters live in the ViewModel and begin again at
    // 0, so after an eviction + relaunch the remembered total would otherwise
    // sit above every counter the new process can send, swallowing the
    // operator's taps. A generation above the watermark says "new process"
    // without guessing, so the dead session's totals go with it.
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('P', { session: 7, armReq: 0, seq: 1 }), 100);
    a.onIntent(intent('P', { session: 7, armReq: 1, seq: 2 }), 200);
    expect(a.state().activeClient).toBe('P');

    // Backgrounded past the budget -> evicted, arm total banked at 1.
    a.tick(2000);
    expect(a.state().activeClient).toBe('');

    // Killed and relaunched: same clientId, NEW generation, counters at 0.
    a.onIntent(intent('P', { session: 8, armReq: 0, seq: 1 }), 2100);
    a.onIntent(intent('P', { session: 8, armReq: 1, seq: 2 }), 2200);
    expect(a.state().activeClient).toBe('P');
  });

  it('cannot be re-armed by a packet from the generation the relaunch replaced', () => {
    // The seam between the two mechanisms, which is where a future change is
    // most likely to go wrong: a relaunch DROPS this station's remembered arm
    // total (so the new process can arm on its first real tap), and the only
    // thing still standing between the dead generation's spent counters and a
    // fresh arm edge is the session gate closing it. Neither guard is
    // sufficient alone -- the memory drop deliberately removes the other one --
    // so the interaction is pinned here rather than left implied by two tests
    // that each pass for their own reasons.
    const a = makeArbiter({ staleTimeoutMs: 1000 });
    a.onIntent(intent('P', { session: 7, armReq: 0, seq: 1 }), 100);
    a.onIntent(intent('P', { session: 7, armReq: 1, seq: 2 }), 200);
    expect(a.state().activeClient).toBe('P');

    a.tick(2000); // evicted; generation 7's arm total banked
    expect(a.state().activeClient).toBe('');

    // Relaunched. This is the one path that drops the remembered totals.
    a.onIntent(intent('P', { session: 8, armReq: 0, seq: 1 }), 2100);

    // Now generation 7's delayed packets turn up, carrying the arm total the
    // operator already spent -- and, in the second one, a higher total still.
    // Both are from a process that no longer exists and must be inert.
    a.onIntent(intent('P', { session: 7, armReq: 1, seq: 9 }), 2150);
    expect(a.state().activeClient).toBe('');
    a.onIntent(intent('P', { session: 7, armReq: 2, seq: 10 }), 2160);
    expect(a.state().activeClient).toBe('');

    // ...and the living process is unaffected: its own tap still arms.
    a.onIntent(intent('P', { session: 8, armReq: 1, seq: 2 }), 2200);
    expect(a.state().activeClient).toBe('P');
  });

  it('rejects an oversized clientId without creating any bookkeeping', () => {
    const a = makeArbiter();
    const huge = 'x'.repeat(65);
    expect(a.onIntent(intent(huge, { armReq: 1 }), 0)).toBe(false);
    a.onIntent(intent(huge, { armReq: 2 }), 10);
    expect(a.state().activeClient).toBe('');
  });
});
