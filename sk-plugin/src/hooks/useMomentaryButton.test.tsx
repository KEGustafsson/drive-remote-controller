import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useMomentaryButton } from './useMomentaryButton';

function TestButton({
  testId = 'btn',
  disabled = false,
}: {
  testId?: string;
  disabled?: boolean;
}) {
  const { pressed, handlers } = useMomentaryButton(disabled);
  // Deliberately NOT forwarding the `disabled` DOM attribute here: these tests
  // exercise the hook's OWN inert/force-release contract directly (jsdom would
  // otherwise swallow the pointerdown before the hook ever saw it).
  return (
    <button data-testid={testId} data-pressed={pressed} {...handlers}>
      {pressed ? 'pressed' : 'released'}
    </button>
  );
}

function pointerDown(el: Element, pointerId = 1) {
  fireEvent.pointerDown(el, { pointerId });
}
function pointerUp(el: Element, pointerId = 1) {
  fireEvent.pointerUp(el, { pointerId });
}
function pointerCancel(el: Element, pointerId = 1) {
  fireEvent.pointerCancel(el, { pointerId });
}

afterEach(() => {
  Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  // Via fireEvent (not document.dispatchEvent directly) so React flushes
  // the resulting state update before the next test's render/assertions --
  // the same reason the assertion below needs it.
  fireEvent(document, new Event('visibilitychange'));
});

describe('useMomentaryButton', () => {
  it('starts released', () => {
    render(<TestButton />);
    expect(screen.getByTestId('btn')).toHaveTextContent('released');
  });

  it('press -> released text becomes pressed', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    expect(btn).toHaveTextContent('pressed');
  });

  it('release on pointerup', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    pointerUp(btn);
    expect(btn).toHaveTextContent('released');
  });

  it('release on pointercancel (e.g. an interrupted touch)', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    pointerCancel(btn);
    expect(btn).toHaveTextContent('released');
  });

  it('ignores an unrelated pointerId releasing -- only the pointer that pressed it can release it', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn, 1);
    pointerUp(btn, 2); // a different, unrelated pointer
    expect(btn).toHaveTextContent('pressed');
    pointerUp(btn, 1); // the real one
    expect(btn).toHaveTextContent('released');
  });

  it('a second pointer landing on an already-pressed button does not steal or extend it', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn, 1);
    pointerDown(btn, 2);
    pointerUp(btn, 2); // the second, ignored pointer releasing changes nothing
    expect(btn).toHaveTextContent('pressed');
    pointerUp(btn, 1);
    expect(btn).toHaveTextContent('released');
  });

  it('force-releases when the tab becomes hidden mid-press', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    expect(btn).toHaveTextContent('pressed');

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    fireEvent(document, new Event('visibilitychange'));

    expect(btn).toHaveTextContent('released');
  });

  it('force-releases on window blur mid-press', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    fireEvent(window, new Event('blur'));
    expect(btn).toHaveTextContent('released');
  });

  // The no-capture drag-off gap (2026-07-23 review): if setPointerCapture
  // was refused, a finger dragging off the button delivers its pointerup to
  // some OTHER element -- never here -- and no panic handler fires (tab
  // visible, window focused). Leaving the element without holding its
  // capture must therefore release. jsdom implements no pointer capture at
  // all, which is exactly the no-capture environment under test.
  it('force-releases when the pointer drags off the button without capture', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    expect(btn).toHaveTextContent('pressed');
    fireEvent.pointerLeave(btn, { pointerId: 1 });
    expect(btn).toHaveTextContent('released');
  });

  it('an unrelated pointer leaving does not release the pressing one', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn, 1);
    fireEvent.pointerLeave(btn, { pointerId: 2 });
    expect(btn).toHaveTextContent('pressed');
  });

  it('force-releases if the pointer capture is torn away mid-press', () => {
    render(<TestButton />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    fireEvent.lostPointerCapture(btn, { pointerId: 1 });
    expect(btn).toHaveTextContent('released');
  });

  it('ignores a press while disabled -- an inert control accepts no new command', () => {
    render(<TestButton disabled />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    expect(btn).toHaveTextContent('released');
  });

  it('force-releases when it becomes disabled mid-press -- authority withdrawn must not leave a latched command', () => {
    // The safety crux of gating the widgets: a finger held across the
    // disarm/offline edge must not keep reporting `pressed` (hence a command)
    // once the caller withdraws control -- fail to the safe value, exactly as
    // a lost pointer would.
    const { rerender } = render(<TestButton disabled={false} />);
    const btn = screen.getByTestId('btn');
    pointerDown(btn);
    expect(btn).toHaveTextContent('pressed');
    rerender(<TestButton disabled={true} />);
    expect(btn).toHaveTextContent('released');
  });

  it('two independent button instances track independently -- pressing one never affects the other', () => {
    render(
      <>
        <TestButton testId="a" />
        <TestButton testId="b" />
      </>,
    );
    pointerDown(screen.getByTestId('a'));
    expect(screen.getByTestId('a')).toHaveTextContent('pressed');
    expect(screen.getByTestId('b')).toHaveTextContent('released');
  });
});
