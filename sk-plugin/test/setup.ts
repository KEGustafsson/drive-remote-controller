import '@testing-library/jest-dom/vitest';

// Explicit rather than relying on RTL to infer it: some tests wrap a real
// async socket-close/reconnect sequence in an explicit act(async () => {...})
// (see test/endToEnd.test.tsx) so React flushes state updates that
// originate from genuine external I/O events rather than from a
// synchronous fireEvent call. Without this flag React logs "the current
// testing environment is not configured to support act(...)" even though
// the usage is correct.
declare global {
  // eslint-disable-next-line no-var
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

// jsdom does not implement PointerEvent at all (a long-standing, known gap
// -- jsdom/jsdom#2751). useMomentaryButton.ts depends on real pointerId
// values to correctly ignore unrelated/extra pointers (the "two fingers,
// two different buttons" and "a stray second finger on one button" cases),
// and MouseEvent doesn't carry pointerId -- fireEvent.pointerDown/Up would
// otherwise silently produce events with no usable pointerId, making every
// pointer look the same to the code under test. This polyfill is the
// standard, minimal workaround: enough of the real PointerEvent shape for
// the app code (and RTL's fireEvent) to behave like a real browser.
if (typeof globalThis.PointerEvent === 'undefined') {
  class PointerEventPolyfill extends MouseEvent {
    public pointerId: number;
    public pointerType: string;
    public isPrimary: boolean;

    constructor(type: string, params: PointerEventInit = {}) {
      super(type, params);
      this.pointerId = params.pointerId ?? 0;
      this.pointerType = params.pointerType ?? 'mouse';
      this.isPrimary = params.isPrimary ?? true;
    }
  }
  // @ts-expect-error -- polyfilling a DOM global jsdom doesn't provide.
  globalThis.PointerEvent = PointerEventPolyfill;
}

// jsdom also has no pointer-capture implementation at all -- stub it
// globally here (rather than per test file) so every component under
// test can call setPointerCapture()/releasePointerCapture() without
// throwing, matching real browser behavior closely enough for these
// tests' purposes (they don't assert on capture semantics themselves).
if (!('setPointerCapture' in HTMLElement.prototype)) {
  Object.defineProperties(HTMLElement.prototype, {
    setPointerCapture: { value: () => {}, writable: true, configurable: true },
    releasePointerCapture: { value: () => {}, writable: true, configurable: true },
    hasPointerCapture: { value: () => false, writable: true, configurable: true },
  });
}

// useWriteAccess (via App) fetches /skServer/loginStatus -- a relative URL
// Node's fetch can't resolve in jsdom, and there's no SK server here. A
// never-settling default means component tests that don't exercise the
// write-access path simply leave writeStatus at 'unknown' with no async
// state update to flush (so no stray act() warnings), while tests that DO
// care inject their own resolving fetcher via App's fetchLoginStatus prop
// and await it with waitFor.
globalThis.fetch = (() =>
  new Promise(() => {
    /* never settles */
  })) as unknown as typeof fetch;
