import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Does the published package actually contain everything the plugin loads?
 *
 * This exists because of a real outage. `suspendClock.cjs` was extracted out of
 * `index.cjs` and `require()`d from it, but `package.json`'s `files` list was not
 * updated — so every installed copy shipped an `index.cjs` whose first act was to
 * require a file that was not there. The plugin threw `MODULE_NOT_FOUND` at load,
 * registered no routes, and `POST /plugins/signalk-drive-remote-controller/intent`
 * fell through to signalk-server's admin-only `/plugins` gate. The station saw a
 * **401** and reported the operator's token as revoked or expired — a token that
 * was perfectly valid, on a server that was working.
 *
 * That is the nastiest shape a packaging bug can take: nothing local fails. The
 * whole suite passes, `tsc -b` is clean, the plugin runs fine from a checkout,
 * and the break appears only on a deployed install — as a misleading auth error
 * pointing at the wrong component entirely.
 *
 * So the requires are resolved transitively from `main` and checked against
 * `files`, rather than trusting anyone to remember. The next extraction cannot
 * repeat this.
 */
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

interface Manifest {
  main: string;
  files: string[];
}

const pkg: Manifest = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));

/** Local `require('./x')` targets in one file, as paths relative to the package. */
function localRequires(relFile: string): string[] {
  const src = readFileSync(path.join(root, relFile), 'utf8');
  const dir = path.posix.dirname(relFile.split(path.sep).join('/'));
  return [...src.matchAll(/require\(\s*['"](\.[^'"]+)['"]\s*\)/g)].map((m) =>
    path.posix.normalize(path.posix.join(dir, m[1])),
  );
}

/** Everything reachable from `main` by local requires, `main` included. */
function reachableFromMain(): string[] {
  const seen = new Set<string>();
  const queue = [pkg.main];
  while (queue.length > 0) {
    const file = queue.shift() as string;
    if (seen.has(file)) continue;
    seen.add(file);
    for (const dep of localRequires(file)) queue.push(dep);
  }
  return [...seen];
}

/** Would `files` ship this path — directly, or inside a listed directory? */
function isPackaged(relPath: string): boolean {
  return pkg.files.some(
    (entry) => entry === relPath || relPath.startsWith(`${entry.replace(/\/$/, '')}/`),
  );
}

describe('published package', () => {
  it('ships every file the plugin requires at load time', () => {
    const missing = reachableFromMain().filter((f) => !isPackaged(f));
    expect(
      missing,
      `these are require()d from ${pkg.main} but absent from package.json "files", so an ` +
        `installed copy would throw MODULE_NOT_FOUND on load and register no routes`,
    ).toEqual([]);
  });

  it('resolves more than just the entry point, so the check is not vacuous', () => {
    // If the require-scanning ever silently stopped working, the test above
    // would pass by finding nothing at all. Pin that it really walks the graph.
    const reachable = reachableFromMain();
    expect(reachable).toContain(pkg.main);
    expect(reachable).toContain('arbiter.cjs');
    expect(reachable).toContain('suspendClock.cjs');
  });
});
