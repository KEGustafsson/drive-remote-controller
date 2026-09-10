// Rasterise the app mark to the PNG that Signal K's webapp list needs.
//
// Signal K reads `signalk.appIcon` from package.json and shows that image in
// the Admin UI's Webapps list; it wants a raster of at least 72x72, so the
// SVG favicon alone is not enough. Rather than add an image library to a
// project whose whole toolchain is deliberately small, this writes the PNG
// directly: the shapes are two flat-coloured polygons, which needs a
// point-in-polygon test and zlib -- both of which are a few lines and no
// dependencies.
//
// The output is COMMITTED (static/ is not gitignored, unlike the built
// public/), so nobody needs to run this to build or install the plugin. Rerun
// it only when the mark changes:
//
//   node scripts/make-icon.cjs
//
// Geometry and colours are duplicated from static/favicon.svg, which is itself
// the same drawing as the Android station's launcher icon
// (android/app/src/main/res/drawable/ic_launcher_foreground.xml). Three copies,
// no shared build step -- exactly like the project's other hand-synced
// contracts. Change them together.

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZE = 192; // >= the 72 Signal K asks for, and a standard icon size
const CANVAS = 108; // the Android adaptive-icon canvas the art is drawn on
const CORNER = 20; // matches the rx in favicon.svg

const BACKGROUND = [0x0b, 0x0b, 0x0b];
const FORWARD = [0x2a, 0x78, 0xd6];
const REVERSE = [0xeb, 0x68, 0x34];

// Same paths as favicon.svg, as point lists.
const FORWARD_CHEVRON = [
  [54, 30], [74, 50], [64, 50], [64, 52], [44, 52], [44, 50], [34, 50],
];
const REVERSE_CHEVRON = [
  [54, 78], [34, 58], [44, 58], [44, 56], [64, 56], [64, 58], [74, 58],
];

/** Ray-casting point-in-polygon. The chevrons are simple, non-self-intersecting. */
function inside(poly, x, y) {
  let hit = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i];
    const [xj, yj] = poly[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
      hit = !hit;
    }
  }
  return hit;
}

/** Rounded rectangle, in canvas units. */
function insideRoundedRect(x, y) {
  const r = CORNER;
  if (x < 0 || y < 0 || x > CANVAS || y > CANVAS) return false;
  const cx = x < r ? r : x > CANVAS - r ? CANVAS - r : x;
  const cy = y < r ? r : y > CANVAS - r ? CANVAS - r : y;
  if (cx === x && cy === y) return true; // in the straight-edged middle
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
}

// 4x4 supersampling: these are diagonal edges, and without it they stair-step
// badly at the sizes an icon is actually viewed at.
const SS = 4;

function render() {
  const px = Buffer.alloc(SIZE * SIZE * 4);
  const scale = CANVAS / SIZE;

  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let r = 0, g = 0, b = 0, a = 0;

      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const cx = (x + (sx + 0.5) / SS) * scale;
          const cy = (y + (sy + 0.5) / SS) * scale;

          if (!insideRoundedRect(cx, cy)) continue; // transparent outside

          let colour = BACKGROUND;
          if (inside(FORWARD_CHEVRON, cx, cy)) colour = FORWARD;
          else if (inside(REVERSE_CHEVRON, cx, cy)) colour = REVERSE;

          r += colour[0];
          g += colour[1];
          b += colour[2];
          a += 255;
        }
      }

      const n = SS * SS;
      const i = (y * SIZE + x) * 4;
      // Un-premultiply: the accumulated colour was only added for covered
      // samples, so divide by coverage, not by the sample count.
      const cov = a / 255;
      px[i] = cov ? Math.round(r / cov) : 0;
      px[i + 1] = cov ? Math.round(g / cov) : 0;
      px[i + 2] = cov ? Math.round(b / cov) : 0;
      px[i + 3] = Math.round(a / n);
    }
  }
  return px;
}

// ---- Minimal PNG writer -------------------------------------------------

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function toPng(px) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(SIZE, 0);
  ihdr.writeUInt32BE(SIZE, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  ihdr[10] = 0; // deflate
  ihdr[11] = 0; // adaptive filtering
  ihdr[12] = 0; // no interlace

  // One filter byte (0 = None) per scanline.
  const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1));
  for (let y = 0; y < SIZE; y++) {
    raw[y * (SIZE * 4 + 1)] = 0;
    px.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---- Write it -----------------------------------------------------------

const px = render();
const png = toPng(px);
const out = path.join(__dirname, '..', 'static', 'icons', `icon-${SIZE}x${SIZE}.png`);
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, png);

// A count per colour, so a botched run is obvious rather than silently shipping
// a blank square.
// Classified by which channel dominates, not by a brightness threshold:
// forward (#2A78D6) is blue-dominant with a RED channel of only 0x2A, so a
// naive `red > 0x80` test misses it entirely and files it under background.
let fwd = 0, rev = 0, bg = 0, clear = 0;
for (let i = 0; i < px.length; i += 4) {
  const [r, g, b, a] = [px[i], px[i + 1], px[i + 2], px[i + 3]];
  if (a < 128) clear++;
  else if (b > r + 40) fwd++;
  else if (r > b + 40) rev++;
  else bg++;
}
console.log(`wrote ${out} (${png.length} bytes, ${SIZE}x${SIZE})`);
console.log(`  forward ${fwd}px, reverse ${rev}px, background ${bg}px, transparent ${clear}px`);
if (!fwd || !rev) {
  console.error('ERROR: a chevron did not render');
  process.exit(1);
}
