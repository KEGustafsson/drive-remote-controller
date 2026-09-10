// Capture the README screenshots from the REAL built app (public/) running
// against a mock Signal K server -- the same stand-in approach the test
// suite uses (a real ws server speaking the delta protocol, not a mock of
// our own client code). Two things are the REAL production code here:
//   * the arming authority (arbiter.cjs), reached over the same
//     POST /plugins/<id>/intent route the built app uses in production
//     (test/arbiterServer.ts does the identical substitution), and
//   * RX's telemetry heartbeat, republished at its 250 ms cadence -- both
//     the arm gate and the UI's freshness check judge RX by ARRIVAL, so a
//     one-shot delta would leave the shots showing a dead RX.
// The mock mirrors RX's arbitration for telemetry: armed plugin commands
// come back as rx.* state/source, and a scenario endpoint can simulate TX
// holding precedence over a drive.
//
// Usage (from sk-plugin/):
//   npm run build                       # screenshots show public/, not src/
//   npm i --no-save playwright          # deliberately NOT a devDependency --
//   npx playwright install chromium     # ~120 MB browser, docs-only tooling
//   node scripts/screenshots.cjs
//
// Output: docs/screenshots/*.png (committed -- referenced by README.md).

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');
const { chromium } = require('playwright');
const { ArmArbiter } = require('../arbiter.cjs');

const PUB = path.resolve(__dirname, '..', 'public');
const OUT = path.resolve(__dirname, '..', 'docs', 'screenshots');
fs.mkdirSync(OUT, { recursive: true });

const PLUGIN_ID = 'signalk-drive-remote-controller';
const INTENT_ROUTE = `/plugins/${PLUGIN_ID}/intent`;

// The real server-side arming authority, exactly as index.cjs shells it.
// The UI never writes a Signal K delta any more, so without this nothing
// would ever arm and the armed screenshots would time out.
const arbiter = new ArmArbiter();
let txOverridesPort = false; // scenario: TX live+enabled, commanding port FORWARD
let hhPowered = true;        // scenario: is the heading-hold unit answering?
let hhSetpointDeg = 41;      // the heading HH reports it is holding

const MIME = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  if (url === '/skServer/loginStatus') {
    // dummysecurity shape: security off, writes allowed -> the UI's
    // "Commands: reaching the boat" row goes green.
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ status: 'notLoggedIn', authenticationRequired: false }));
    return;
  }
  if (url === '/__scenario/tx-override') {
    txOverridesPort = req.url.includes('on=1');
    res.end('ok');
    return;
  }
  if (url === '/__scenario/hh-power') {
    hhPowered = req.url.includes('on=1');
    res.end('ok');
    return;
  }
  // The production intent route: signalk-server mounts each plugin's router
  // under /plugins/<id>, and the UI POSTs its {clientId, seq, armReq,
  // disarmReq, port, stbd} here every ~250 ms. Feeding it straight into the
  // real arbiter is what makes these screenshots show real arming behavior.
  if (url === INTENT_ROUTE && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      try {
        arbiter.onIntent(JSON.parse(body), Date.now());
      } catch {
        /* ignore malformed */
      }
      broadcast();
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ ok: true }));
    });
    return;
  }
  const fp = path.join(PUB, url === '/' ? 'index.html' : url);
  if (!fp.startsWith(PUB) || !fs.existsSync(fp) || fs.statSync(fp).isDirectory()) {
    res.statusCode = 404;
    res.end('not found');
    return;
  }
  res.setHeader('content-type', MIME[path.extname(fp)] || 'application/octet-stream');
  res.end(fs.readFileSync(fp));
});

const wss = new WebSocketServer({ server });
// The UI's socket is READ-ONLY (it only ever sends a `subscribe`), so there
// is deliberately no message handler mirroring deltas back: commands now
// arrive at the intent route above, not here.

// Mirror of RX's per-drive arbitration (master enable ON, local switches
// at neutral): TX outranks the plugin; a qualifying plugin owns the drive
// even while commanding neutral; nothing qualifying -> none/neutral. The
// plugin's side of it comes from the arbiter's published state -- the same
// values index.cjs writes to the plugin.* command paths for RX to read.
function drive(side, plugin) {
  if (side === 'port' && txOverridesPort) return { state: 'forward', source: 'tx' };
  if (plugin.enabled) return { state: plugin[side], source: 'plugin' };
  return { state: 'neutral', source: 'none' };
}

// Mirror of HH's arbitration for the thruster: an armed plugin owns it in the
// absence of TX, and the direction it drives is whatever the arbiter published.
function thrusterState(plugin) {
  if (!plugin.enabled) return 'OFF';
  if (plugin.thrusterMode !== 'manual') return 'OFF';
  return plugin.thruster === 'off' ? 'OFF' : plugin.thruster.toUpperCase();
}

function stateValues() {
  const now = Date.now();
  // RX is simulated as powered and publishing: tell the arbiter its
  // telemetry just arrived (this is the arm gate's only evidence RX exists),
  // then age out any client that has stopped heart-beating.
  arbiter.onRxTelemetry(now);
  // The heading-hold unit is a separate board that can be switched off on its
  // own -- when it is, its telemetry simply stops arriving, which is the only
  // evidence of its absence there is.
  if (hhPowered) arbiter.onHhTelemetry(now);
  arbiter.tick(now);
  const plugin = arbiter.state();
  const port = drive('port', plugin);
  const stbd = drive('stbd', plugin);
  const linkOk = plugin.enabled || txOverridesPort;
  // linkUp = a remote source is LIVE at RX, armed or not. In this mock the
  // plugin app is always connected and heart-beating, so the link reads "up"
  // whether or not anything is armed -- exactly what the phone UI's "RX
  // link" row now shows.
  return [
    { path: 'control.remoteController.rx.port.state', value: port.state },
    { path: 'control.remoteController.rx.stbd.state', value: stbd.state },
    { path: 'control.remoteController.rx.port.source', value: port.source },
    { path: 'control.remoteController.rx.stbd.source', value: stbd.source },
    { path: 'control.remoteController.rx.masterEnable', value: true },
    { path: 'control.remoteController.rx.linkOk', value: linkOk },
    { path: 'control.remoteController.rx.linkUp', value: true },
    // Written by the plugin authority, not RX: who holds the arm token, and
    // the server's own verdict on whether RX is alive. Published REPEATEDLY
    // at RX's cadence because the UI treats RX as present only while its
    // telemetry keeps ARRIVING -- a single delta at startup would leave the
    // shots showing a blocked ARM button after 1.5 s.
    { path: 'control.remoteController.plugin.activeClient', value: plugin.activeClient },
    { path: 'control.remoteController.plugin.enabled', value: plugin.enabled },
    { path: 'control.remoteController.plugin.rxLive', value: plugin.rxLive },
    { path: 'control.remoteController.plugin.hhLive', value: plugin.hhLive },
    // The heading-hold unit's own telemetry. Published only while it is
    // "powered", so switching it off in a scenario reproduces exactly what a
    // dead board looks like to the UI: nothing arrives.
    ...(hhPowered
      ? [
          { path: 'control.remoteController.hh.linkUp', value: true },
          { path: 'control.remoteController.hh.mode', value: plugin.thrusterMode },
          { path: 'control.remoteController.hh.thruster.state', value: thrusterState(plugin) },
          { path: 'control.remoteController.hh.source', value: plugin.enabled ? 'plugin' : 'none' },
          { path: 'control.remoteController.hh.setpointDeg', value: hhSetpointDeg },
          { path: 'control.remoteController.hh.armed', value: plugin.enabled },
          { path: 'control.remoteController.hh.reversalPending', value: false },
        ]
      : []),
  ];
}

function broadcast() {
  const delta = JSON.stringify({
    context: 'vessels.self',
    updates: [
      {
        $source: 'mock-rx',
        timestamp: new Date().toISOString(),
        values: stateValues(),
      },
    ],
  });
  for (const client of wss.clients) {
    if (client.readyState === client.OPEN) client.send(delta);
  }
}

setInterval(broadcast, 200);

async function main() {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch();

  async function openPage(viewport) {
    const context = await browser.newContext({ viewport, deviceScaleFactor: 2, hasTouch: true });
    const page = await context.newPage();
    await page.goto(url);
    await page.getByText('connected', { exact: true }).waitFor();
    return { context, page };
  }

  // `hasText: 'ARMED'` would match "DISARMED" too, so an unsuccessful arm
  // would sail through and be captured as if it had worked (it was, once).
  const arm = async (page) => {
    await page.locator('button.kill-switch').click();
    await page.locator('.kill-switch__state', { hasText: /^ARMED$/ }).waitFor();
  };

  // The arm token is exclusive and survives the browser context that holds
  // it: the arbiter only releases it once that client's heartbeat has been
  // silent for its staleness timeout. Without this wait, the next scenario's
  // page opens to an IN USE kill switch it cannot arm.
  const awaitTokenRelease = () => new Promise((r) => setTimeout(r, 1500));

  // The telemetry panel collapses by default, which is right for the helm and
  // wrong for a screenshot whose whole subject is a row inside it. Used only
  // by the two shots that document the detail itself; the rest are left as the
  // operator first meets them.
  const openDetail = async (page) => {
    await page.locator('.status-panel__toggle').click();
    await page.locator('.status-panel__lamps .lamp').last().waitFor({ state: 'visible' });
  };

  const press = (page, ariaLabel, pointerId) =>
    page.dispatchEvent(`button[aria-label="${ariaLabel}"]`, 'pointerdown', {
      pointerId,
      bubbles: true,
      cancelable: true,
      isPrimary: pointerId === 101,
      pointerType: 'touch',
    });

  // 1. Phone, freshly opened: disarmed, connected, everything neutral.
  {
    const { context, page } = await openPage({ width: 390, height: 844 });
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(OUT, 'phone-disarmed.png') });
    await context.close();
    console.log('captured phone-disarmed.png');
  }

  // 2. Phone, the flagship scenario: armed, two-handed docking maneuver --
  // port FORWARD and starboard REVERSE held by two different fingers.
  {
    const { context, page } = await openPage({ width: 390, height: 844 });
    await arm(page);
    await press(page, 'Port forward', 101);
    await press(page, 'Starboard reverse', 202);
    await page.waitForTimeout(800); // let telemetry mirror the commands
    await page.screenshot({ path: path.join(OUT, 'phone-two-handed.png') });
    await context.close();
    await awaitTokenRelease();
    console.log('captured phone-two-handed.png');
  }

  // 3. Desktop/tablet landscape: armed, but TX holds precedence on port --
  // the "controlled by TX remote" annotation + status panel attribution. The
  // attribution rows ARE the point here, and the sidebar has room for them, so
  // this is one of the two shots that opens the detail.
  {
    await fetch(`${url}__scenario/tx-override?on=1`);
    const { context, page } = await openPage({ width: 1280, height: 800 });
    await arm(page);
    await openDetail(page);
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(OUT, 'desktop-tx-override.png') });
    await context.close();
    await fetch(`${url}__scenario/tx-override?on=0`);
    await awaitTokenRelease();
    console.log('captured desktop-tx-override.png');
  }

  // 4. Phone, bow thruster in MANUAL: armed and pushing the bow to port.
  {
    const { context, page } = await openPage({ width: 390, height: 844 });
    await arm(page);
    await page.getByRole('button', { name: 'MANUAL' }).click();
    await press(page, 'Thrust bow to port', 303);
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(OUT, 'phone-thruster-manual.png') });
    await context.close();
    await awaitTokenRelease();
    console.log('captured phone-thruster-manual.png');
  }

  // 5. Phone, bow thruster in HOLD: the held heading and a +10 trim. The app
  // opens in MANUAL now, so switch to HOLD first -- the trim buttons only exist
  // in HOLD mode.
  {
    const { context, page } = await openPage({ width: 390, height: 844 });
    await arm(page);
    await page.getByRole('button', { name: 'HOLD' }).click();
    await page.getByRole('button', { name: 'Trim 10 degrees to starboard' }).click();
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(OUT, 'phone-heading-hold.png') });
    await context.close();
    await awaitTokenRelease();
    console.log('captured phone-heading-hold.png');
  }

  // 6. Phone with the heading-hold unit switched off: the drives still arm and
  // work, the thruster rows grey out, and the missing unit is named rather
  // than silently absent. Detail open -- the kill switch names the unit either
  // way, but the red NOT SEEN lamp is the evidence this shot exists to show.
  {
    await fetch(`${url}__scenario/hh-power?on=0`);
    const { context, page } = await openPage({ width: 390, height: 844 });
    await page.waitForTimeout(1800); // let the UI age out HH's telemetry
    await arm(page);
    await openDetail(page);
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(OUT, 'phone-unit-missing.png') });
    await context.close();
    await fetch(`${url}__scenario/hh-power?on=1`);
    await awaitTokenRelease();
    console.log('captured phone-unit-missing.png');
  }

  // 7. Phone, armed, with the telemetry detail expanded. Every other shot
  // shows the panel as the operator first meets it -- collapsed -- so without
  // this one the seven lamps behind "Detail" appear in no screenshot at all,
  // and the README would be describing rows a reader cannot see.
  {
    const { context, page } = await openPage({ width: 390, height: 844 });
    await arm(page);
    await openDetail(page);
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(OUT, 'phone-detail-open.png') });
    await context.close();
    await awaitTokenRelease();
    console.log('captured phone-detail-open.png');
  }

  await browser.close();
  server.close();
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
