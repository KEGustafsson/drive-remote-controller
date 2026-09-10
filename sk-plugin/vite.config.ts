// Deliberately NOT using vite's or vitest's defineConfig() helper here --
// vitest bundles its own transitive copy of vite, so importing
// defineConfig from 'vitest/config' pulls in that copy's Plugin<> type,
// which TypeScript then treats as structurally incompatible with
// @vitejs/plugin-react's Plugin<> (from the top-level vite) -- a
// well-known dual-package-version tooling artifact, not a real type
// error. defineConfig is purely an identity function used for editor
// type-inference convenience; Vite reads the default export at runtime
// either way, so exporting a plain object with our own minimal type here
// sidesteps the clash entirely rather than fighting overload resolution.
import react from '@vitejs/plugin-react';

interface PluginConfig {
  plugins: unknown[];
  base: string;
  publicDir: string | false;
  build: { outDir: string; emptyOutDir: boolean };
  test: {
    environment: string;
    globals: boolean;
    setupFiles: string[];
  };
}

// Signal K's webapps.ts mounts a plugin's `public/` directory as static
// assets at `/<package.json name>/` (verified against the real
// signalk-server-node source, not guessed -- see JOURNAL.md). `base: './'`
// makes the built asset URLs relative, so the app works correctly no
// matter which sub-path the server mounts it under.
const config: PluginConfig = {
  plugins: [react()],
  base: './',
  // Vite's static-passthrough directory defaults to `public/`, which is also
  // our build OUTPUT directory (what signalk-server serves) -- that overlap
  // made Vite warn on every build, so it used to be disabled outright.
  //
  // It is now pointed at `static/` instead, which resolves the clash and gives
  // us verbatim copying with STABLE filenames. That last part is the whole
  // reason: importing the icon as a module would have Vite emit it hashed
  // (icon-a1b2c3.png), and both the `signalk.appIcon` path in package.json and
  // the favicon <link> in index.html need a name that does not change on every
  // build.
  publicDir: 'static',
  build: {
    outDir: 'public',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./test/setup.ts'],
  },
};

export default config;
